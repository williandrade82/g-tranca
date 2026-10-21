package com.gtranca.multiplayer

import android.content.Context
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.net.LocalTransport
import com.gtranca.net.TableAdvert
import com.gtranca.net.TableRules
import com.gtranca.net.nearby.MultiplayerAccess
import com.gtranca.net.nearby.NearbyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** Uma mesa nova (a anterior, se houver, é deixada). */
    fun newTable(context: Context, data: GameData): MultiplayerTable {
        _table.value?.let { old -> scope.launch { old.leave() } }
        return MultiplayerTable(transport(context), scope, DataMpCoins(data)).also { _table.value = it }
    }

    /** O jogador saiu das telas da mesa: fecha o que não precisa mais ficar aberto (§16.11 o anfitrião segue entregando). */
    fun exit() {
        val table = _table.value ?: return
        scope.launch {
            table.exitScreens()
            if (table.role.value == null) _table.compareAndSet(table, null)
        }
    }

    /** Sai da sala de espera (ou desiste de entrar). */
    fun leave() {
        val table = _table.value ?: return
        _table.value = null
        scope.launch { table.leave() }
    }
}

/** §16.13, §16.14 moedas e estatísticas locais do multijogador, no mesmo arquivo da carteira. */
private class DataMpCoins(private val data: GameData) : MpCoins {
    override suspend fun debit(stake: Int): Boolean = runCatching { data.wallet.debit(stake) }.getOrDefault(false)

    override suspend fun settle(gameId: String, rules: TableRules, result: GameResult, stake: Int, received: Int) {
        runCatching { data.stats.recordBet(gameId, rules.mode, rules.difficulty, result, stake, received) }
    }
}
