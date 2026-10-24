package com.gtranca.multiplayer

import android.content.Context
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.net.LocalTransport
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.TableAdvert
import com.gtranca.net.TableRules
import com.gtranca.net.invitationFor
import com.gtranca.net.nearby.MultiplayerAccess
import com.gtranca.net.nearby.NearbyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O modo multijogador do app (§16): um transporte (Nearby) e, no máximo, uma mesa por vez. Vive no processo, fora das
 * telas, porque a mesa atravessa várias telas (mesas disponíveis → sala → jogo → resultado) e a troca de papel na
 * sucessão. Só é criado depois de [MultiplayerAccess.check] (as telas usam o `MultiplayerAccessGate`).
 */
object MultiplayerHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: NearbyTransport? = null

    private val _table = MutableStateFlow<MultiplayerTable?>(null)

    /** A mesa atual deste aparelho, se houver. */
    val table: StateFlow<MultiplayerTable?> = _table.asStateFlow()

    @Synchronized
    private fun transport(context: Context): LocalTransport =
        transport ?: MultiplayerAccess.transport(context.applicationContext, scope).also { transport = it }

    /** Mesas anunciadas por perto (§16.2); a busca para sozinha quando ninguém mais observa. */
    fun discover(context: Context): Flow<List<TableAdvert>> = transport(context).discover()

    /** §16.2 jogadores próximos (presença) achados por perto. */
    fun discoverPresence(context: Context): Flow<List<PresenceAdvert>> = transport(context).discoverPresence()

    /** §16.2 chave de presença deste aparelho: nova a cada vez que o Wi-Fi do jogo liga. */
    @Volatile
    private var presenceKey: String? = null

    private val _presencePhotos = MutableStateFlow<Map<String, String>>(emptyMap())

    /** §16.2 fotos dos jogadores próximos por chave de presença (Base64; "" = sem foto). Só em memória. */
    val presencePhotos: StateFlow<Map<String, String>> = _presencePhotos.asStateFlow()

    /** §16.2 o Wi-Fi do jogo desligou: a próxima presença leva uma chave nova e as fotos recebidas são descartadas. */
    fun resetPresence() {
        presenceKey = null
        _presencePhotos.value = emptyMap()
    }

    /**
     * §16.2 com o Wi-Fi do jogo ligado e fora de uma mesa: anuncia a presença ([name]) e procura convites com a própria
     * chave; achado um, entra na mesa com a chave (o convite chega pela sessão e a tela mostra o modal *Convite*). Roda até
     * ser cancelado (Wi-Fi desligado, mesa aberta ou jogo); a presença para ao sair.
     */
    suspend fun runPresence(context: Context, data: GameData, name: String, photo: String?) {
        if (_table.value != null) return
        val t = transport(context)
        val key = presenceKey ?: com.gtranca.net.newPresenceKey().also { presenceKey = it }
        try {
            t.advertisePresence(PresenceAdvert(key, name))
            // §16.2 enquanto fora de mesa: atende os pedidos de foto e pede a dos jogadores próximos novos.
            val advert = kotlinx.coroutines.coroutineScope {
                val photos = com.gtranca.net.PresencePhotos(t, photo, _presencePhotos)
                val serving = launch { runCatching { photos.serve() } }
                val fetching = launch { runCatching { photos.fetchAll(t.discoverPresence().catch { emit(emptyList()) }) } }
                try {
                    t.discover().catch { emit(emptyList()) }.first { list -> list.invitationFor(key) != null && _table.value == null }
                        .invitationFor(key)
                } finally {
                    // Pára de ler os eventos antes de a sessão da mesa começar a ler.
                    serving.cancel()
                    fetching.cancel()
                }
            } ?: return
            newTable(context, data, photo).joinAsync(advert.tableId, name, inviteKey = key)
        } finally {
            withContext(NonCancellable) { runCatching { t.stopPresence() } }
        }
    }

    /**
     * Uma mesa nova. A anterior, se houver, é deixada antes de a nova entrar ou publicar (um leitor por transporte); uma
     * entrega de resultado ainda aberta nela (§16.11) acaba aqui.
     */
    fun newTable(context: Context, data: GameData, photo: String? = null): MultiplayerTable {
        val leaving = _table.value?.let { old -> scope.launch { old.leave() } }
        return MultiplayerTable(transport(context), scope, DataMpCoins(data, scope), after = leaving).also {
            it.myPhoto = photo
            _table.value = it
        }
    }

    /** O jogador saiu das telas da mesa: fecha o que não precisa mais ficar aberto (§16.11 o anfitrião segue entregando). */
    fun exit() {
        val table = _table.value ?: return
        scope.launch {
            table.exitScreens()
            if (table.role.value == null) _table.compareAndSet(table, null)
        }
    }

    /** Sai da sala de espera (ou desiste de entrar, ou recusa o convite). */
    fun leave() {
        val table = _table.value ?: return
        _table.value = null
        scope.launch { table.leave() }
    }
}

/** §16.13, §16.14 moedas e estatísticas locais do multijogador, no mesmo arquivo da carteira. */
private class DataMpCoins(private val data: GameData, scope: CoroutineScope) : MpCoins {
    /** §14.6, §16.2 espera a leitura do saldo antes de responder ao convite ou à entrada (falha de leitura: não bloqueia). */
    override suspend fun canAfford(stake: Int): Boolean =
        runCatching { com.gtranca.engine.Coins.canJoin(data.wallet.wallet.first().balance, stake) }.getOrDefault(true)

    override suspend fun debit(stake: Int): Boolean = runCatching { data.wallet.debit(stake) }.getOrDefault(false)

    override suspend fun settle(gameId: String, rules: TableRules, result: GameResult, stake: Int, received: Int) {
        runCatching { data.stats.recordBet(gameId, rules.mode, rules.difficulty, result, stake, received) }
    }
}
