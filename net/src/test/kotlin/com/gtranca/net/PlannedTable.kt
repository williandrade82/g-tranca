package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

internal val TEST_PERSONAS = listOf("bolt", "domino", "turbo", "zen")

internal fun testRules(mode: GameMode = GameMode.DUPLAS, stake: Int = 0) =
    TableRules("Mesa", mode, RuleSet.DEFAULT, 1000, stake, "facil", rulesTitle = "Clássica")

/** Mesa montada em memória: o anfitrião (lugar 0), os recrutados nos lugares pedidos e robôs nos demais (§16.2). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlannedTable(
    val network: MemoryNetwork,
    val host: HostSession,
    val guests: Map<Int, GuestSession>,
    val transports: Map<Int, MemoryTransport>,
    val plan: TablePlan,
)

/** Plano com os [humans] (lugar → nome) como recrutados, chave de presença "k<lugar>", e robôs nos outros lugares. */
internal fun planFor(mode: GameMode, humans: Map<Int, String>, seed: Long = 1, waitMillis: Long = ENTRY_WAIT_MILLIS): TablePlan {
    var layout = TableLayout.initial(mode)
    humans.forEach { (seat, name) -> layout = layout.call(seat, "k$seat", name)!!.accept("k$seat")!! }
    mode.seats.map { it.index }.filter { layout.slots[it] == Slot.Vacant }.forEachIndexed { i, seat ->
        layout = layout.placeRobot(seat, TEST_PERSONAS[i])!!
    }
    return TablePlan(layout, TEST_PERSONAS, seed, waitMillis = waitMillis)
}

/**
 * Abre a mesa e liga os recrutados. [start] = o "Prepare-se!" já passou e o jogo começou (`advanceUntilIdle` roda o relógio
 * virtual até o fim).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.plannedTable(
    humans: Map<Int, String> = emptyMap(),
    mode: GameMode = GameMode.DUPLAS,
    rules: TableRules = testRules(mode),
    network: MemoryNetwork = MemoryNetwork(),
    newToken: () -> String = ::randomToken,
    start: Boolean = true,
    guestCodec: ProtocolCodec = ProtocolCodec(),
    hostCodec: ProtocolCodec = ProtocolCodec(),
    plan: TablePlan = planFor(mode, humans),
    connect: Boolean = true,
): PlannedTable {
    val host = HostSession(network.transport(), "m", rules, "Anfitrião", codec = hostCodec, newToken = newToken, plan = plan)
    host.open(sessionScope)
    val transports = mutableMapOf<Int, MemoryTransport>()
    val guests = if (!connect) emptyMap() else humans.mapValues { (seat, name) ->
        val t = network.transport().also { transports[seat] = it }
        GuestSession(t, name, codec = guestCodec).also { it.join(sessionScope, "m", inviteKey = "k$seat") }
    }
    if (start) advanceUntilIdle() else runCurrent()
    return PlannedTable(network, host, guests, transports, plan)
}

internal fun Seat.key() = "k$index"
