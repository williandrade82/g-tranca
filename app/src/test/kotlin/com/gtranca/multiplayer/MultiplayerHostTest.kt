package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Seat
import com.gtranca.engine.viewFor
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameEvent
import com.gtranca.game.Stage
import com.gtranca.net.ActionRejected
import com.gtranca.net.Delivery
import com.gtranca.net.GameFinished
import com.gtranca.net.GuestStatus
import com.gtranca.net.RoundOver
import com.gtranca.net.GuestSession
import com.gtranca.net.NoticeKind
import com.gtranca.net.PauseReason
import com.gtranca.net.TableNotice
import com.gtranca.net.MemoryNetwork
import com.gtranca.net.ProtocolCodec
import com.gtranca.net.RejectReason
import com.gtranca.net.TableStage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.ConcurrentHashMap

/**
 * §16 (plano, etapa 3): anfitrião autoritativo + convidados pelo transporte em memória. O anfitrião valida e aplica;
 * cada convidado só recebe a vista do seu assento e as jogadas públicas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerHostTest {

    private fun reference(mode: GameMode, seed: Long, targetScore: Int, dispatcher: kotlinx.coroutines.CoroutineDispatcher): GameController {
        val config = GameConfig(mode, Difficulty.FACIL, targetScore = targetScore)
        return GameController(config, seed, mode.seats.map { BotSeatPlayer(testBot(seed, it)) }, computeDispatcher = dispatcher, botDelayMillis = 0)
    }

    /** §16.1, §16.6 humanos simulados (anfitrião e convidados) + robôs: o mesmo jogo da partida local de mesma semente. */
    @ParameterizedTest(name = "{0} semente {1}, convidados {2}")
    @CsvSource(
        "DUPLAS, 31, '1 2 3'",
        "DUPLAS, 32, '2'",
        "DUPLAS, 33, '1 3'",
        "INDIVIDUAL, 34, '1'",
    )
    fun `§16_1 partida completa pela rede da o mesmo resultado da partida local`(mode: GameMode, seed: Long, guestSeats: String) = runTest {
        val local = reference(mode, seed, 1000, StandardTestDispatcher(testScheduler))
        local.run()

        val table = openTable(mode, seed, guestSeats.split(' ').map { Seat(it.toInt()) })
        val finished = ConcurrentHashMap<Seat, GameFinished>()
        table.guests.forEach { (seat, guest) ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                guest.gameEvents.collect { if (it is GameFinished) finished[seat] = it }
            }
        }
        driveGuests(table, seed)
        driveHost(table, seed)
        table.host.run()
        advanceUntilIdle()

        val match = table.host.controller.currentMatch
        match.history shouldBe local.currentMatch.history
        match.totals shouldBe local.currentMatch.totals
        match.winner shouldBe local.currentMatch.winner
        table.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        // Cada convidado viu o fim do jogo, com o placar e o histórico do anfitrião.
        table.guests.forEach { (seat, guest) ->
            val view = guest.view.value!!
            view.stage shouldBe TableStage.GAME_OVER
            view.totals shouldBe match.totals
            view.winner shouldBe match.winner
            view.history shouldBe match.history
            finished[seat]?.winner shouldBe match.winner
        }
    }

    /**
     * §16.4 nenhum convidado recebe mão alheia, monte, mortos, semente nem token alheio: varre cada mensagem **serializada**
     * no momento do envio, contra o estado real do anfitrião naquele instante.
     */
    @Test
    fun `§16_4 nenhuma mensagem a um convidado traz carta oculta, semente ou token de outro`() = runTest {
        val seed = 0x5EED_0BAD_CAFEL
        val network = MemoryNetwork()
        val scanner = LeakScanner(seed)
        network.tap = scanner.tap()
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), targetScore = 1000, network = network)
        scanner.registerTable(t)
        scanner.watch(this, t.host)
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        scanner.checked shouldBeGreaterThan 100
        scanner.failures.shouldBeEmpty()
        // §16.10 o 1º e o 2º a entrar (assentos 1 e 2) são os sucessores; o 3º nunca recebe o estado completo.
        scanner.successorMessages shouldBe setOf(Seat(1), Seat(2))
        // §16.4, §16.5 o token não faz parte da identificação pública.
        val tokens = t.guests.values.mapNotNull { it.token }
        t.guests.values.forEach { g -> val r = g.roster.value.toString(); tokens.forEach { r shouldNotContain it } }
    }

    /** Controle negativo do §16.4: a varredura acusa a vista de outro assento e o jogo completo. */
    @Test
    fun `§16_4 a varredura detecta vazamento quando ele existe`() = runTest {
        val seed = 987_654_321_123L
        val local = reference(GameMode.DUPLAS, seed, 1000, StandardTestDispatcher(testScheduler))
        val match = local.currentMatch
        local.state.value.stage shouldBe Stage.PLAYING
        val partnerView = match.currentRound.viewFor(Seat(2), match.rules)
        val leakView = ProtocolCodec.json.encodeToString(com.gtranca.engine.PlayerView.serializer(), partnerView).encodeToByteArray()
        val leakMatch = ProtocolCodec.json.encodeToString(Match.serializer(), match).encodeToByteArray()
        listOf(leakView, leakMatch).forEach { bytes ->
            runCatching {
                scanGuestMessage(Delivery(com.gtranca.net.EndpointId("h"), com.gtranca.net.EndpointId("g"), bytes), Seat(0), match, emptySet(), emptyList(), seed, roundOf = { match.roundNumber })
            }.isFailure shouldBe true
        }
    }

    /** §16.1 jogada fora da vez, ilegal, repetida ou com o pedido de outro assento é recusada sem alterar o jogo. */
    @Test
    fun `§16_1 jogada fora da vez, ilegal, repetida ou de outro assento e recusada sem alterar o jogo`() = runTest {
        val seed = 41L
        val table = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), targetScore = 3000)
        val rejections = ConcurrentHashMap<Seat, MutableList<ActionRejected>>()
        table.guests.forEach { (seat, guest) ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                guest.rejections.collect { rejections.getOrPut(seat) { mutableListOf() } += it }
            }
        }
        val controller = table.host.controller
        val game = launch { table.host.run() }
        advanceUntilIdle()

        // Leva a vez até um convidado: o anfitrião joga o que o bot dele jogaria.
        val hostBot = testBot(seed, Seat(0))
        while (controller.currentMatch.currentRound.currentSeat == Seat(0)) {
            val s = controller.stateOf(Seat(0)).value
            controller.submit(Seat(0), hostBot.chooseAction(s.view, s.humanLegal), s.humanRequestId)
            advanceUntilIdle()
        }
        val onTurn = controller.currentMatch.currentRound.currentSeat
        val other = table.guests.keys.first { it != onTurn }
        val onTurnGuest = table.guests.getValue(onTurn)
        val otherGuest = table.guests.getValue(other)
        val requestId = onTurnGuest.view.value!!.requestId
        (requestId > 0L) shouldBe true
        // §16.4 a vista recebida é exatamente a do assento (a mesma de um robô), com o pedido e as jogadas legais dele.
        val hostSide = controller.stateOf(onTurn).value
        onTurnGuest.view.value!!.toPlayerView(onTurnGuest.rules.value!!.rules) shouldBe hostSide.view
        onTurnGuest.view.value!!.legal shouldBe hostSide.humanLegal
        requestId shouldBe hostSide.humanRequestId
        val before = controller.currentMatch

        // Fora da vez.
        otherGuest.propose(Action.DrawFromStock, otherGuest.view.value!!.requestId)
        // Assento alheio: o pedido do outro, mandado por quem não está na vez (o assento vem da sessão, não da mensagem).
        otherGuest.propose(Action.DrawFromStock, requestId)
        // Ilegal: descartar na fase de compra.
        onTurnGuest.propose(Action.Discard(onTurnGuest.view.value!!.hand.first()), requestId)
        // Pedido inexistente.
        onTurnGuest.propose(Action.DrawFromStock, requestId + 5)
        advanceUntilIdle()
        controller.currentMatch shouldBe before
        rejections[other]!!.map { it.reason } shouldBe listOf(RejectReason.NOT_YOUR_TURN, RejectReason.NOT_YOUR_TURN)
        rejections[onTurn]!!.map { it.reason } shouldBe listOf(RejectReason.ILLEGAL, RejectReason.STALE_REQUEST)

        // A jogada certa é aceita uma vez; a repetida (mesmo pedido, nova mensagem) é recusada.
        val legal = onTurnGuest.view.value!!.legal
        onTurnGuest.propose(legal.first(), requestId)
        advanceUntilIdle()
        val after = controller.currentMatch
        (after != before) shouldBe true
        onTurnGuest.propose(legal.first(), requestId)
        advanceUntilIdle()
        rejections[onTurn]!!.last().reason shouldBe RejectReason.STALE_REQUEST
        controller.currentMatch shouldBe after
        game.cancel()
    }

    /** §16.5 mensagem duplicada (mesmo número de sequência) é descartada; reordenada é entregue em ordem. */
    @Test
    fun `§16_5 mensagens duplicadas e reordenadas nao corrompem o jogo`() = runTest {
        val seed = 51L
        val local = reference(GameMode.DUPLAS, seed, 1000, StandardTestDispatcher(testScheduler))
        local.run()

        val network = MemoryNetwork()
        var held: Delivery? = null
        var count = 0
        network.tap = { delivery ->
            count++
            when {
                // A cada 7 mensagens, segura uma e a entrega depois da seguinte (reordena), duplicando a seguinte.
                held == null && count % 7 == 0 -> { held = delivery; emptyList() }
                held != null -> { val h = held!!; held = null; listOf(delivery, h, delivery) }
                else -> listOf(delivery)
            }
        }
        val table = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)), network = network)
        table.hostSession.guestSeats().keys shouldBe setOf(Seat(1), Seat(3))
        driveGuests(table, seed)
        driveHost(table, seed)
        // Libera, depois de um tempo, uma mensagem retida que não teve sucessora (fim de uma troca).
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            while (true) {
                delay(10)
                held?.let { held = null; network.deliver(it) }
            }
        }
        table.host.run()
        advanceUntilIdle()
        val match = table.host.controller.currentMatch
        table.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        match.history shouldBe local.currentMatch.history
        match.totals shouldBe local.currentMatch.totals
        table.guests.values.forEach { guest ->
            guest.view.value!!.stage shouldBe TableStage.GAME_OVER
            guest.view.value!!.totals shouldBe match.totals
        }
    }

    /**
     * §16.11 cada convidado recebe o pacote de fim de partida (só o que é visível a ele) e conduz as telas sozinho: o
     * anfitrião distribui a próxima partida sem esperar quem ainda está nas telas, e o convidado guarda a partida nova
     * até avançar. O jogo só espera a jogada do jogador da vez; o resultado é o mesmo da partida local.
     */
    @Test
    fun `§16_11 o jogo nao espera o convidado nas telas de resultado`() = runTest {
        val seed = 61L
        val local = reference(GameMode.DUPLAS, seed, 1000, StandardTestDispatcher(testScheduler))
        local.run()

        val table = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)))
        val slow = table.guests.getValue(Seat(2))
        val packages = ConcurrentHashMap<Seat, MutableList<RoundOver>>()
        table.guests.forEach { (seat, guest) ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                guest.roundOver.collect { if (it != null) packages.getOrPut(seat) { mutableListOf() } += it }
            }
        }
        // No meio das telas da partida 1 do convidado lento, o anfitrião já distribuiu a 2 (e o jogo foi até a vez de quem
        // falta jogar), mas o lento continua vendo a mesa final da 1: a partida nova está guardada.
        var slowStillOnResults: Boolean? = null
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            slow.roundOver.first { it?.roundNumber == 1 }
            delay(30_000)
            slowStillOnResults = table.host.controller.currentMatch.roundNumber == 2 &&
                slow.roundOver.value?.roundNumber == 1 && slow.view.value!!.roundNumber == 1
        }
        driveGuests(table, seed, advanceDelay = { if (it == Seat(2)) 60_000L else 0L })
        driveHost(table, seed)
        table.host.run()
        advanceUntilIdle()

        val match = table.host.controller.currentMatch
        table.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        match.history shouldBe local.currentMatch.history
        match.totals shouldBe local.currentMatch.totals
        (match.history.size > 1) shouldBe true
        slowStillOnResults shouldBe true
        table.guests.keys.forEach { seat ->
            val received = packages.getValue(seat)
            received.map { it.roundNumber } shouldBe match.history.map { it.number }
            received.map { it.record } shouldBe match.history
            received.last().gameWinner shouldBe match.winner
            received.dropLast(1).all { it.gameWinner == null } shouldBe true
            // A mesa final do pacote é a do próprio assento.
            received.all { it.view.seat == seat && it.view.legal.isEmpty() } shouldBe true
            table.guests.getValue(seat).view.value!!.stage shouldBe TableStage.GAME_OVER
        }
    }

    /**
     * §16.9 desistência do convidado na mesa de 4, a qualquer momento: o assento fica "VAGO" (sem robô na hora) e o jogo
     * segue até a vez dele; aí pausa e o anfitrião decide. Na vez do próprio assento a decisão abre na hora. Aqui: um
     * vira robô (§16.8 opção 2) e o outro é aberto a um novo convidado, aprovado pelo anfitrião, que herda a aposta retida
     * (§16.7, §16.13) e joga até o fim.
     */
    @Test
    fun `§16_9 desistencia deixa o assento VAGO ate a vez dele e o anfitriao decide`() = runTest {
        val seed = 71L
        val network = MemoryNetwork()
        val scanner = LeakScanner(seed)
        network.tap = scanner.tap()
        val table = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), stake = 100, network = network)
        scanner.registerTable(table)
        scanner.watch(this, table.host)
        val controller = table.host.controller
        val notices = ConcurrentHashMap<Seat, MutableList<TableNotice>>()
        table.guests.forEach { (seat, guest) ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                guest.notices.collect { notices.getOrPut(seat) { mutableListOf() } += it }
            }
        }
        val game = launch { table.host.run() }
        advanceUntilIdle()
        val hostBot = testBot(seed, Seat(0))
        while (controller.currentMatch.currentRound.currentSeat == Seat(0)) {
            val s = controller.stateOf(Seat(0)).value
            controller.submit(Seat(0), hostBot.chooseAction(s.view, s.humanLegal), s.humanRequestId)
            advanceUntilIdle()
        }
        val onTurn = controller.currentMatch.currentRound.currentSeat
        val offTurn = table.guests.keys.first { it != onTurn }
        val stays = table.guests.keys.single { it != onTurn && it != offTurn }
        val offTurnId = table.hostSession.playerAt(offTurn)!!.id

        table.guests.getValue(offTurn).resign()
        table.guests.getValue(onTurn).resign()
        advanceUntilIdle()
        table.guests.getValue(onTurn).status.value shouldBe GuestStatus.Resigned
        table.guests.getValue(offTurn).status.value shouldBe GuestStatus.Resigned
        // Os dois assentos aparecem "VAGO" para quem ficou, e todos foram avisados.
        val stayer = table.guests.getValue(stays)
        stayer.roster.value.filter { it.resigned }.map { it.seat }.toSet() shouldBe setOf(onTurn, offTurn)
        notices.getValue(stays).filter { it.kind == NoticeKind.RESIGNED }.map { it.seat }.toSet() shouldBe setOf(onTurn, offTurn)
        // Nenhum robô entrou: a vez de quem desistiu está parada, com a decisão do anfitrião aberta.
        (controller.stateOf(onTurn).value.humanRequestId > 0L) shouldBe true
        table.host.decision.value?.seat shouldBe onTurn
        table.host.decision.value?.reason shouldBe PauseReason.VACANT
        stayer.paused.value?.seat shouldBe onTurn
        table.host.ledger.value.seats[onTurn.index].forfeited shouldBe true

        // §16.8 opção 2: robô na hora; o jogo segue.
        table.host.replaceWithRobot() shouldBe true
        advanceUntilIdle()
        notices.getValue(stays).any { it.kind == NoticeKind.REPLACED_BY_ROBOT && it.seat == onTurn } shouldBe true

        // O jogo segue até a vez do outro assento VAGO (o anfitrião e quem ficou jogam como o bot deles).
        driveGuests(table, seed, guests = mapOf(stays to stayer))
        driveHost(table, seed)
        advanceUntilIdle()
        table.host.decision.value?.seat shouldBe offTurn
        stayer.paused.value?.seat shouldBe offTurn

        // §16.8 opção 3: abre a vaga; quem pede para entrar espera a aprovação e ocupa exatamente esse assento.
        table.host.openSeatForRecruit()
        advanceUntilIdle()
        stayer.paused.value?.hostChoice shouldBe NoticeKind.SEAT_OPENED
        val newcomerTransport = table.network.transport()
        val newcomer = GuestSession(newcomerTransport, "Recruta")
        scanner.register(newcomerTransport.id, offTurn, newcomer)
        newcomer.join(table.scope, "mesa-1")
        advanceUntilIdle()
        newcomer.status.value shouldBe GuestStatus.WaitingApproval
        val request = table.hostSession.joinRequests.value.single()
        request.seat shouldBe offTurn
        driveGuests(table, seed, guests = mapOf(offTurn to newcomer))
        table.host.approveJoin(request.requestId) shouldBe true
        advanceUntilIdle()
        newcomer.status.value shouldBe GuestStatus.Playing
        newcomer.seat shouldBe offTurn
        // §16.7, §16.13 herda a aposta retida: nada a debitar; o assento passa a ser dele.
        newcomer.stakeDebit.value shouldBe 0
        table.host.ledger.value.seats[offTurn.index].holder shouldBe newcomer.playerId
        (newcomer.playerId != offTurnId) shouldBe true
        game.join()
        advanceUntilIdle()
        controller.state.value.stage shouldBe Stage.GAME_OVER
        controller.state.value.resigned shouldBe false
        stayer.view.value!!.stage shouldBe TableStage.GAME_OVER
        newcomer.view.value!!.stage shouldBe TableStage.GAME_OVER
        newcomer.view.value!!.history shouldBe controller.currentMatch.history
        // §16.13 o resultado em moedas chega a cada humano (só o dele).
        val payouts = table.host.payouts.value!!
        newcomer.payout.value?.received shouldBe (payouts[newcomer.playerId] ?: 0)
        stayer.payout.value?.received shouldBe (payouts[stayer.playerId] ?: 0)
        // §16.4 as mensagens novas (pausa, avisos, pedido de entrada, débito, resultado, entrada no meio) também passaram
        // pela varredura.
        scanner.failures.shouldBeEmpty()
        scanner.types.containsAll(listOf("paused", "resumed", "notice", "joinWaiting", "stakeDebit", "payout", "welcome")) shouldBe true
    }

    /** §16.3, §16.9, §13.1 no 1×1 entre dois humanos não há robô: quem desiste perde e o outro vence. */
    @Test
    fun `§16_9 §13_1 desistencia no 1x1 entre humanos da a vitoria ao outro`() = runTest {
        val seed = 81L
        val table = openTable(GameMode.INDIVIDUAL, seed, listOf(Seat(1)))
        val finished = mutableListOf<GameFinished>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            table.host.controller.events.collect { if (it is GameEvent.GameFinished) finished += GameFinished(it.winner, it.resigned) }
        }
        val game = launch { table.host.run() }
        advanceUntilIdle()
        table.guests.getValue(Seat(1)).resign()
        advanceUntilIdle()
        game.join()
        val state = table.host.controller.state.value
        state.stage shouldBe Stage.GAME_OVER
        state.resigned shouldBe true
        state.winner shouldBe GameMode.INDIVIDUAL.sideOf(Seat(0))
        finished shouldBe listOf(GameFinished(GameMode.INDIVIDUAL.sideOf(Seat(0)), resigned = true))
    }}

private val forbiddenKeys = setOf("seed", "stock", "mortos", "hands", "dealReplacements", "currentRound", "match")

/**
 * Varre uma mensagem ao convidado do [seat]. Falha se ela traz uma chave do estado completo, a semente, o token de outro
 * ou uma carta oculta a esse assento no [match] (mãos alheias, monte e mortos, menos as cartas tiradas do lixo).
 * Devolve se a varredura de cartas foi feita (a mensagem é da partida atual).
 */
internal fun scanGuestMessage(
    delivery: Delivery,
    seat: Seat,
    match: Match,
    takenFromDiscard: Set<Card>,
    otherTokens: List<String>,
    seed: Long,
    roundOf: (JsonObject) -> Int? = ::roundOfMessage,
): Boolean {
    val text = delivery.bytes.decodeToString()
    // Semente curta (testes de queda) coincidiria com números comuns (tamanhos, ids); a varredura dela usa sementes longas.
    if (kotlin.math.abs(seed) >= 1_000_000) text shouldNotContain seed.toString()
    otherTokens.forEach { text shouldNotContain it }
    val root = Json.parseToJsonElement(text).jsonObject
    val keys = mutableSetOf<String>()
    val cards = mutableSetOf<Card>()
    collect(root, keys, cards)
    keys.filter { it in forbiddenKeys }.shouldBeEmpty()
    val round = roundOf(root)
    if (round == null) {
        cards.shouldBeEmpty()
        return false
    }
    if (round != match.roundNumber) return false
    val r = match.currentRound
    val hidden = (match.mode.seats.filter { it != seat }.flatMap { r.handOf(it) } + r.stock + r.mortos.flatten()).toSet() - takenFromDiscard
    cards.filter { it in hidden }.shouldBeEmpty()
    return true
}

private fun roundOfMessage(root: JsonObject): Int? {
    val p = root["p"]?.jsonObject ?: return null
    return when (p["t"]?.jsonPrimitive?.content) {
        "view" -> p["view"]!!.jsonObject["roundNumber"]!!.jsonPrimitive.int
        "played", "roundOver" -> p["roundNumber"]!!.jsonPrimitive.int
        else -> null
    }
}

private fun collect(element: JsonElement, keys: MutableSet<String>, cards: MutableSet<Card>) {
    when (element) {
        is JsonObject -> element.forEach { (k, v) -> keys += k; collect(v, keys, cards) }
        is JsonArray -> element.forEach { collect(it, keys, cards) }
        is JsonPrimitive -> if (element.isString) runCatching { Card.parse(element.content) }.getOrNull()?.let { cards += it }
    }
}
