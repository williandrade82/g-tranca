package com.gtranca.multiplayer

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.Stage
import com.gtranca.game.botSeed
import com.gtranca.net.GuestSession
import com.gtranca.net.HostSession
import com.gtranca.net.MemoryNetwork
import com.gtranca.net.MemoryTransport
import com.gtranca.net.TableRules
import com.gtranca.net.TableStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random

/** Bot determinístico (Fácil) do assento: o mesmo nos jogos de referência e nos "humanos" simulados. */
internal fun testBot(seed: Long, seat: Seat): BotPlayer = createBot(Difficulty.FACIL, Random(botSeed(seed, seat.index)))

/** Mesa multijogador em memória: anfitrião (assento 0), convidados nos assentos pedidos e robôs nos demais. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestTable(
    val network: MemoryNetwork,
    val hostSession: HostSession,
    val host: MultiplayerHost,
    val guests: Map<Seat, GuestSession>,
    val endpoints: Map<Seat, MemoryTransport>,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.openTable(
    mode: GameMode,
    seed: Long,
    guestSeats: List<Seat>,
    targetScore: Int = 1000,
    network: MemoryNetwork = MemoryNetwork(),
): TestTable {
    val config = GameConfig(mode, Difficulty.FACIL, targetScore = targetScore)
    val rules = TableRules("Mesa de teste", mode, config.rules, targetScore, stake = 0, difficulty = Difficulty.FACIL.id)
    val dispatcher = StandardTestDispatcher(testScheduler)
    // Sessões no relógio virtual, fora do `backgroundScope` (que o `advanceUntilIdle` não roda); os coletores só suspendem.
    val scope = CoroutineScope(dispatcher + Job())
    val hostSession = HostSession(network.transport(), "mesa-1", rules, "Anfitrião")
    hostSession.open(scope)
    val endpoints = mutableMapOf<Seat, MemoryTransport>()
    val guests = guestSeats.associateWith { seat ->
        val transport = network.transport().also { endpoints[seat] = it }
        GuestSession(transport, "Convidado ${seat.index}").also { it.join(scope, "mesa-1") }
    }
    advanceUntilIdle()
    guests.forEach { (seat, guest) -> guest.chooseSeat(seat); guest.confirmSeat() }
    advanceUntilIdle()
    val seats = hostSession.startGame().keys
    advanceUntilIdle()
    val host = MultiplayerHost(
        hostSession, config, seed, seats,
        botFactory = { seat, _ -> testBot(seed, seat) },
        computeDispatcher = dispatcher,
    )
    return TestTable(network, hostSession, host, guests, endpoints)
}

/**
 * Cada convidado joga o que o bot do assento jogaria (mesma semente) e avança as próprias telas de resultado quando recebe
 * o pacote de fim de partida (§16.11); [advanceDelay] simula quem demora nessas telas (o jogo não espera por ele).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.driveGuests(
    table: TestTable,
    seed: Long,
    advanceDelay: (Seat) -> Long = { 0L },
    guests: Map<Seat, GuestSession> = table.guests,
) {
    guests.forEach { (seat, guest) ->
        val driver = testBot(seed, seat)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            guest.roundOver.collect { over ->
                if (over != null) {
                    advanceDelay(seat).takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it) }
                    guest.advance()
                }
            }
        }
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            var answered = 0L
            guest.view.collect { view ->
                if (view == null) return@collect
                if (view.legal.isNotEmpty() && view.requestId > answered) {
                    answered = view.requestId
                    val rules = guest.rules.value!!.rules
                    guest.propose(driver.chooseAction(view.toPlayerView(rules), view.legal), view.requestId)
                }
            }
        }
    }
}

/** O anfitrião joga o próprio assento (pela vista dele) como o bot desse assento jogaria. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.driveHost(table: TestTable, seed: Long) {
    val seat = table.host.hostSeat
    val driver = testBot(seed, seat)
    val controller = table.host.controller
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
        var continued = 0
        controller.stateOf(seat).collect { snapshot ->
            if (snapshot.stage == Stage.ROUND_OVER && continued < snapshot.roundNumber) {
                continued = snapshot.roundNumber
                table.host.continueRound()
            }
            if (snapshot.humanLegal.isNotEmpty()) {
                controller.submit(seat, driver.chooseAction(snapshot.view, snapshot.humanLegal), snapshot.humanRequestId)
            }
        }
    }
}
