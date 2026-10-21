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
        val guestSeats = listOf(Seat(1), Seat(2), Seat(3))
        val endpointSeat = ConcurrentHashMap<String, Seat>()
        val failures = mutableListOf<String>()
        var table: TestTable? = null
        var checked = 0
        // Cartas tiradas do lixo por partida: são públicas mesmo estando na mão de outro (§5.6).
        val fromDiscard = ConcurrentHashMap<Int, MutableSet<Card>>()
        network.tap = { delivery ->
            val seat = endpointSeat[delivery.to.value]
            val current = table
            if (seat != null && current != null) {
                val match = current.host.controller.currentMatch
                val tokens = current.guests.filterKeys { it != seat }.values.mapNotNull { it.token }
                runCatching { scanGuestMessage(delivery, seat, match, fromDiscard[match.roundNumber].orEmpty(), tokens, seed) }
                    .onSuccess { if (it) checked++ }
                    .onFailure { failures += "assento ${seat.index}: ${it.message}" }
            }
            listOf(delivery)
        }
        val t = openTable(GameMode.DUPLAS, seed, guestSeats, targetScore = 1000, network = network)
        t.endpoints.forEach { (seat, transport) -> endpointSeat[transport.id.value] = seat }
        table = t
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            t.host.controller.events.collect { event ->
                if (event is GameEvent.ActionApplied) {
                    fromDiscard.getOrPut(event.roundNumber) { ConcurrentHashMap.newKeySet() } += event.event.takenFromDiscard
                }
            }
        }
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        checked shouldBeGreaterThan 100
        failures.shouldBeEmpty()
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
}

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
    text shouldNotContain seed.toString()
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
        "played" -> p["roundNumber"]!!.jsonPrimitive.int
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
