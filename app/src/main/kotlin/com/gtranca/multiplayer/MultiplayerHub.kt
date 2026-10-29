package com.gtranca.multiplayer

import android.content.Context
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.net.LocalTransport
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.TableAdvert
import com.gtranca.net.TableRules
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

    private val _recruitment = MutableStateFlow<com.gtranca.net.Recruitment?>(null)

    /** §16.2 o recrutamento imediato deste aparelho (anfitrião montando a mesa e/ou recrutado), enquanto fora de mesa. */
    val recruitment: StateFlow<com.gtranca.net.Recruitment?> = _recruitment.asStateFlow()

    private val _recruitEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.gtranca.net.RecruitEvent>(extraBufferCapacity = 16)

    /** §16.2 avisos ao recrutado (tirado da mesa, mesa encerrada, saldo insuficiente); o início vai direto à mesa. */
    val recruitEvents: kotlinx.coroutines.flow.SharedFlow<com.gtranca.net.RecruitEvent> = _recruitEvents

    /** Leitura dos eventos do transporte pelo recrutamento (parada antes de uma mesa ler, um leitor por transporte). */
    @Volatile
    private var recruiting: kotlinx.coroutines.Job? = null

    /** §16.2 para o recrutamento (o anfitrião tocou em Escolher posições) antes de a mesa começar a ler o transporte. */
    suspend fun stopRecruiting() {
        recruiting?.let { it.cancel(); it.join() }
    }

    /** Diagnóstico (só no build debug): eventos do recrutamento e da presença, sem nomes, fotos nem bytes. */
    private val debugLog: (String) -> Unit =
        if (com.gtranca.BuildConfig.DEBUG) { message -> android.util.Log.d("GTrancaNearby", message) } else { _ -> }

    /** Registra um evento de diagnóstico (só no build debug; sem nomes, fotos nem bytes). */
    fun debug(message: String) = debugLog(message)

    @Volatile
    private var presenceName: String = ""

    /** §16.2 o nome do perfil mudou: o anúncio acompanha, sem reiniciar a presença nem perder o recrutamento. */
    fun renamePresence(name: String) {
        if (name == presenceName) return
        presenceName = name
        val rec = _recruitment.value ?: return
        scope.launch { runCatching { rec.rename(name) } }
    }

    /** Para onde o aparelho vai ao sair da presença: a mesa [tableId], com a chave [inviteKey] (chamada de §16.7 = [viaCall]). */
    private class Entry(val tableId: String, val inviteKey: String, val viaCall: Boolean)

    private val _joining = MutableStateFlow(false)

    /**
     * §16.2 o recrutador começou a partida e este aparelho (recrutado) está passando do recrutamento para a mesa do jogo
     * (a camada "Prepare-se!" fica na tela nesse intervalo, mesmo sem recrutamento nem mesa ainda).
     */
    val joining: StateFlow<Boolean> = _joining.asStateFlow()

    /** §16.2 encerra a mesa de lugares do recrutador (voltou à tela 1, saiu ou desligou o Wi-Fi): chamados cancelados, recrutados avisados. */
    fun closeRecruiting() {
        val rec = _recruitment.value ?: return
        scope.launch { runCatching { rec.closeHosting() } }
    }

    /** Chave de presença atual deste aparelho (`null` com o Wi-Fi do jogo desligado). */
    val myPresenceKey: String? get() = presenceKey

    /**
     * §16.2 com o Wi-Fi do jogo ligado e fora de uma mesa: anuncia a presença ([name]), troca fotos e atende o
     * recrutamento imediato ([com.gtranca.net.Recruitment]); recrutado que recebe o início entra na mesa com a própria
     * chave. Roda até ser cancelado (Wi-Fi desligado, mesa aberta ou jogo); a presença para ao sair.
     */
    suspend fun runPresence(context: Context, data: GameData, name: String, photo: String?) {
        if (_table.value != null) return
        val t = transport(context)
        val key = presenceKey ?: com.gtranca.net.newPresenceKey().also { presenceKey = it }
        presenceName = name
        try {
            // §16.2 o início da partida montada (Start) ou §16.7 a chamada para uma partida em andamento (Called).
            val start = kotlinx.coroutines.coroutineScope {
                var recRef: com.gtranca.net.Recruitment? = null
                // §16.2 as fotos dos jogadores próximos vão pela conexão de espera do recrutamento (nunca por uma avulsa).
                val photos = com.gtranca.net.PresencePhotos(t, key, photo, _presencePhotos, reserved = { recRef?.ownsEndpoint(it) == true }, log = debugLog)
                // O estado do recrutamento vive no escopo do hub (não no desta função), então não se perde se a tela recompor.
                val rec = com.gtranca.net.Recruitment(
                    t, scope, key, name, photos,
                    canAfford = { stake -> runCatching { com.gtranca.engine.Coins.canJoin(data.wallet.wallet.first().balance, stake) }.getOrDefault(true) },
                    log = debugLog,
                    clock = { System.nanoTime() / 1_000_000 },
                )
                recRef = rec
                val running = launch { runCatching { rec.run() } }
                recruiting = running
                _recruitment.value = rec
                try {
                    val events = launch {
                        rec.events.collect {
                            if (it !is com.gtranca.net.RecruitEvent.Start && it !is com.gtranca.net.RecruitEvent.Called) _recruitEvents.emit(it)
                        }
                    }
                    val entry = rec.events.first { it is com.gtranca.net.RecruitEvent.Start || it is com.gtranca.net.RecruitEvent.Called }
                    // Só o início da partida montada mostra "Prepare-se!"; a chamada mostra o convite antes de entrar.
                    val start = when (entry) {
                        is com.gtranca.net.RecruitEvent.Start -> Entry(entry.tableId, entry.inviteKey, viaCall = false).also { _joining.value = true }
                        is com.gtranca.net.RecruitEvent.Called -> Entry(entry.tableId, entry.inviteKey, viaCall = true)
                        else -> error("evento inesperado")
                    }
                    events.cancel()
                    start
                } finally {
                    _recruitment.value = null
                    withContext(NonCancellable) {
                        // Encerra as conexões de recrutamento (aviso de saída, até 0,5 s) e só então pára de ler os eventos,
                        // antes de a sessão da mesa começar a ler.
                        runCatching { rec.shutdown() }
                        running.cancel()
                        running.join()
                        recruiting = null
                    }
                }
            }
            // §16.2 o anfitrião tocou em Escolher posições: entra direto, com a própria chave (sem novo convite).
            // §16.15 o anúncio de presença pára (sem "saindo") ANTES de procurar a mesa: trocar o anúncio no meio do pedido de conexão fazia falhar.
            withContext(NonCancellable) { runCatching { t.stopPresence(announceLeaving = false) } }
            newTable(context, data, photo).joinAsync(start.tableId, name, inviteKey = start.inviteKey, viaCall = start.viaCall)
            _joining.value = false
        } finally {
            _joining.value = false
            withContext(NonCancellable) { runCatching { t.stopPresence() } }
        }
    }

    /**
     * Uma mesa nova. A anterior, se houver, é deixada antes de a nova entrar ou publicar (um leitor por transporte); uma
     * entrega de resultado ainda aberta nela (§16.11) acaba aqui.
     */
    fun newTable(context: Context, data: GameData, photo: String? = null): MultiplayerTable {
        val leaving = _table.value?.let { old -> scope.launch { old.leave() } }
        return MultiplayerTable(transport(context), scope, DataMpCoins(data, scope), after = leaving, sessions = DataMpSessions(data)).also {
            it.myPhoto = photo
            it.log = debugLog
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

    /** Solta a mesa **sem** apagar a sessão guardada (§16.12): cancelar o "Voltar à mesa" não perde o direito de tentar de novo. */
    fun leaveKeepingSession() {
        val table = _table.value ?: return
        _table.value = null
        scope.launch { table.leave() }
    }

    /** Sai da sala de espera (ou desiste de entrar, ou recusa o convite): a sessão guardada (§16.12) é apagada. */
    fun leave() {
        val table = _table.value ?: return
        _table.value = null
        table.forgetSession()
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

/** §16.12 a sessão do jogador comum no `:data` (arquivo próprio, separado do jogo salvo contra robôs). */
private class DataMpSessions(private val data: GameData) : MpSessions {
    override suspend fun save(session: com.gtranca.data.MultiplayerSession) = data.multiplayerSession.save(session)

    override suspend fun clear() = data.multiplayerSession.clear()
}
