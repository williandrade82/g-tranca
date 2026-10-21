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
import java.util.concurrent.ConcurrentHashMap

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
    val scope: CoroutineScope,
    val hostTransport: MemoryTransport,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.openTable(
    mode: GameMode,
    seed: Long,
    guestSeats: List<Seat>,
    targetScore: Int = 1000,
    network: MemoryNetwork = MemoryNetwork(),
    stake: Int = 0,
): TestTable {
    val config = GameConfig(mode, Difficulty.FACIL, targetScore = targetScore, stake = stake)
    val rules = TableRules("Mesa de teste", mode, config.rules, targetScore, stake = stake, difficulty = Difficulty.FACIL.id)
    val dispatcher = StandardTestDispatcher(testScheduler)
    // Sessões no relógio virtual, fora do `backgroundScope` (que o `advanceUntilIdle` não roda); os coletores só suspendem.
    val scope = CoroutineScope(dispatcher + Job())
    val hostTransport = network.transport()
    val hostSession = HostSession(hostTransport, "mesa-1", rules, "Anfitrião")
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
    return TestTable(network, hostSession, host, guests, endpoints, scope, hostTransport)
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
        // §16.5 depois de um reingresso, a vista (igual à de antes) volta a pedir a jogada: reenvia a mesma escolha (sem
        // sortear de novo; se já tinha chegado, a repetida é recusada).
        var last: Pair<Long, com.gtranca.engine.Action>? = null
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            var was: com.gtranca.net.GuestStatus? = null
            guest.status.collect { status ->
                val view = guest.view.value
                val choice = last
                if (was == com.gtranca.net.GuestStatus.Reconnecting && status == com.gtranca.net.GuestStatus.Playing &&
                    view != null && choice != null && choice.first == view.requestId) {
                    guest.propose(choice.second, choice.first)
                }
                was = status
            }
        }
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            var answered = 0L
            guest.view.collect { view ->
                if (view == null) return@collect
                if (view.legal.isNotEmpty() && view.requestId > answered) {
                    answered = view.requestId
                    val rules = guest.rules.value!!.rules
                    val action = driver.chooseAction(view.toPlayerView(rules), view.legal)
                    last = view.requestId to action
                    guest.propose(action, view.requestId)
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

/** Um anfitrião qualquer (inclusive o sucessor que assumiu) joga o próprio assento como o bot desse assento jogaria. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.driveHostOf(host: MultiplayerHost, seed: Long) {
    val seat = host.hostSeat
    val driver = testBot(seed, seat)
    val controller = host.controller
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
        var continued = 0
        controller.stateOf(seat).collect { snapshot ->
            if (snapshot.stage == Stage.ROUND_OVER && continued < snapshot.roundNumber) {
                continued = snapshot.roundNumber
                host.continueRound()
            }
            if (snapshot.humanLegal.isNotEmpty()) {
                controller.submit(seat, driver.chooseAction(snapshot.view, snapshot.humanLegal), snapshot.humanRequestId)
            }
        }
    }
}

/**
 * §16.4 varredura de vazamento instalável em qualquer teste: cada mensagem **serializada** a um convidado registrado é
 * conferida contra o estado real do anfitrião naquele instante (ver `scanGuestMessage`); o estado completo só pode ir a
 * um sucessor. Compõe com outro `tap` ([next]).
 */
internal class LeakScanner(private val seed: Long) {
    private val endpoints = ConcurrentHashMap<String, Pair<Seat, GuestSession>>()
    val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val successorMessages: MutableSet<Seat> = ConcurrentHashMap.newKeySet()
    private val fromDiscard = ConcurrentHashMap<Int, MutableSet<com.gtranca.engine.model.Card>>()
    val types: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var host: MultiplayerHost? = null

    @Volatile
    var checked = 0

    fun register(endpoint: com.gtranca.net.EndpointId, seat: Seat, session: GuestSession) {
        endpoints[endpoint.value] = seat to session
    }

    fun registerTable(table: TestTable) {
        host = table.host
        table.endpoints.forEach { (seat, transport) -> register(transport.id, seat, table.guests.getValue(seat)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(scope: TestScope, host: MultiplayerHost) {
        this.host = host
        scope.backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler)) {
            host.controller.events.collect { event ->
                if (event is com.gtranca.game.GameEvent.ActionApplied) {
                    fromDiscard.getOrPut(event.roundNumber) { ConcurrentHashMap.newKeySet() } += event.event.takenFromDiscard
                }
            }
        }
    }

    fun tap(next: (com.gtranca.net.Delivery) -> List<com.gtranca.net.Delivery> = { listOf(it) }): (com.gtranca.net.Delivery) -> List<com.gtranca.net.Delivery> = { delivery ->
        scan(delivery)
        next(delivery)
    }

    private fun scan(delivery: com.gtranca.net.Delivery) {
        val (seat, session) = endpoints[delivery.to.value] ?: return
        val current = host ?: return
        val type = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(delivery.bytes.decodeToString())
                .let { (it as kotlinx.serialization.json.JsonObject)["p"] as? kotlinx.serialization.json.JsonObject }
                ?.get("t")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrNull() ?: return
        types += type
        if (type == "successorState") {
            val id = current.sessionPlayerAt(seat)
            if (id == null || id !in current.sessionSuccessors()) failures += "assento ${seat.index}: estado completo a quem não é sucessor"
            successorMessages += seat
            return
        }
        val match = current.controller.currentMatch
        val tokens = endpoints.filterKeys { it != delivery.to.value }.values.mapNotNull { it.second.token }.filter { it != session.token }
        runCatching { scanGuestMessage(delivery, seat, match, fromDiscard[match.roundNumber].orEmpty(), tokens, seed) }
            .onSuccess { if (it) checked++ }
            .onFailure { failures += "assento ${seat.index} ($type): ${it.message}" }
    }
}
