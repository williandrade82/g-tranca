package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.GameMode
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.botSeed
import com.gtranca.game.cardClass
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Animações da mesa derivadas dos snapshots: nunca revelam informação oculta. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableAnimationsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `§3_3 §5_6 §6_4 voos de um jogo inteiro so mostram cartas publicas e vao aos lugares certos`() = runTest(dispatcher) {
        val mode = GameMode.DUPLAS
        val seed = 21L
        val controller = GameController(
            GameConfig(mode, Difficulty.MEDIO, targetScore = 1000), seed,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(seed, it.index)))) },
            // Pausa de 1 ms: o laço suspende a cada jogada e o coletor vê cada snapshot (o StateFlow é conflado).
            computeDispatcher = dispatcher, botDelayMillis = 1,
        )
        val snapshots = mutableListOf<GameSnapshot>()
        val collector = launch { controller.state.collect { snapshots += it } }
        controller.run()
        collector.cancel()
        var id = 0L
        val kinds = mutableSetOf<String>()
        var flashes = 0
        snapshots.zipWithNext().forEach { (old, new) ->
            if (new.stage != Stage.PLAYING) return@forEach
            val (flights, flash) = TableAnimations.derive(old, new, emptySet()) { ++id }
            val events = TableAnimations.newEvents(old, new)
            flashes += flash.size
            flash.forEach { f ->
                val meld = new.view.tables[f.side].melds.first { it.id.value == f.meldId }.meld
                meld.isCanasta() shouldBe true
                meld.isClean shouldBe f.clean
            }
            for (flight in flights) {
                when {
                    // §3.3 compra: carta do monte nunca aparece para outro assento (o assento 0 é a vista).
                    (flight.from == AnimAnchor.Stock || flight.from is AnimAnchor.Morto) && flight.to is AnimAnchor.SeatHand -> {
                        flight.card shouldBe null
                        kinds += "compra"
                    }
                    flight.to == AnimAnchor.DiscardPile -> {
                        // §8 o descarte é público: a carta é a do evento.
                        val discards = events.mapNotNull { (it.action as? Action.Discard)?.card }
                        (flight.card in discards) shouldBe true
                        kinds += "descarte"
                    }
                    flight.to is AnimAnchor.Meld -> {
                        // §6.4 o conjunto de destino existe na mesa do lado depois da ação.
                        val target = flight.to as AnimAnchor.Meld
                        new.view.tables[target.side].melds.any { it.id.value == target.meldId } shouldBe true
                        flight.card.shouldNotBeNull()
                        kinds += if (flight.from == AnimAnchor.DiscardPile) "topo do lixo" else "baixa"
                    }
                    flight.from == AnimAnchor.DiscardPile -> {
                        // §5.2/§5.6 o resto do lixo é público.
                        val taken = events.flatMap { it.takenFromDiscard }
                        (flight.card in taken) shouldBe true
                        kinds += "lixo"
                    }
                }
            }
        }
        kinds shouldBe setOf("compra", "descarte", "baixa", "topo do lixo", "lixo")
        (flashes > 0) shouldBe true
    }

    @Test
    fun `§4_3 compra do humano voa do monte para a mao com a carta comprada e a animacao some sozinha`() = runTest(dispatcher) {
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 400,
        )
        advanceUntilIdle()
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
        vm.uiState.value.awaitingDraw shouldBe true
        vm.onDraw()
        runCurrent()
        val state = vm.uiState.value
        val draw = state.flights.firstOrNull { it.to == AnimAnchor.OwnHand && it.from == AnimAnchor.Stock }.shouldNotBeNull()
        // Semente 3: a compra não traz 3 vermelho; a carta voa aberta e é a nova da mão.
        state.reveal shouldBe null
        draw.card shouldBe state.newCards.single()
        state.animationMillis shouldBe 400
        advanceTimeBy(2_000)
        runCurrent()
        vm.uiState.value.flights.none { it.id == draw.id } shouldBe true
    }

    @Test
    fun `animacoes desligadas nao geram voos`() = runTest(dispatcher) {
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
        )
        advanceUntilIdle()
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
        vm.onDraw()
        runCurrent()
        vm.uiState.value.flights shouldBe emptyList()
        vm.uiState.value.snapshot.turnEvents[0].shouldNotBeEmpty()
    }

    // ---------- snapshots montados à mão ----------

    private fun c(text: String) = Card.parse(text)

    private fun view(hand: List<Card>, mortoStatus: List<MortoStatus> = listOf(MortoStatus.Available, MortoStatus.Available)) =
        PlayerView(
            mode = GameMode.DUPLAS, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
            phase = Phase.PLAYING, result = null, hand = hand, handSizes = listOf(hand.size, 9, 9, 9),
            discardPile = listOf(c("5S")), stockSize = 30, mortoStatus = mortoStatus, mortoSizes = listOf(11, 11),
            redThrees = listOf(emptyList(), emptyList()), tables = listOf(SideTable(), SideTable()),
        )

    private fun snapshot(view: PlayerView, events: List<List<PublicEvent>>) = GameSnapshot(
        GameConfig(GameMode.DUPLAS, Difficulty.MEDIO, 3000), Seat(0), view, 1, listOf(0, 0), emptyList(), Stage.PLAYING,
        null, emptyList(), null, events,
    )

    @Test
    fun `§6_5 compra do humano que trouxe 3 vermelho voa virada enquanto a reposicao esta oculta`() {
        val hand = listOf(c("4S"), c("7H"))
        val old = snapshot(view(hand), List(4) { emptyList() })
        val replacement = c("KD")
        val new = snapshot(view(hand + replacement), listOf(listOf(PublicEvent(Seat(0), Action.DrawFromStock))) + List(3) { emptyList() })
        var id = 0L
        // Reposição oculta (encenação em curso): a compra voa virada.
        val (hidden, _) = TableAnimations.derive(old, new, setOf(replacement)) { ++id }
        hidden.single().card shouldBe null
        hidden.single().to shouldBe AnimAnchor.OwnHand
        // Sem nada oculto, a carta comprada voa aberta.
        TableAnimations.derive(old, new, emptySet()) { ++id }.first.single().card shouldBe replacement
    }

    @Test
    fun `conflacao - eventos de dois assentos num so snapshot geram os voos dos dois`() {
        val hand = listOf(c("4S"), c("7H"))
        val old = snapshot(view(hand), List(4) { emptyList() })
        val events = listOf(
            emptyList(),
            listOf(PublicEvent(Seat(1), Action.DrawFromStock), PublicEvent(Seat(1), Action.Discard(c("9C")))),
            listOf(PublicEvent(Seat(2), Action.DrawFromStock)),
            emptyList(),
        )
        val new = snapshot(view(hand), events)
        var id = 0L
        val (flights, _) = TableAnimations.derive(old, new, emptySet()) { ++id }
        flights.count { it.from == AnimAnchor.Stock && it.to == AnimAnchor.SeatHand(1) && it.card == null } shouldBe 1
        flights.count { it.from == AnimAnchor.SeatHand(1) && it.to == AnimAnchor.DiscardPile && it.card == c("9C") } shouldBe 1
        flights.count { it.from == AnimAnchor.Stock && it.to == AnimAnchor.SeatHand(2) && it.card == null } shouldBe 1
        flights.size shouldBe 3
    }

    @Test
    fun `§6_5 §9_4 voos da troca de 3 vermelho - virada de outro assento, revelada do humano, do morto sem reposicao`() {
        var id = 0L
        val other = RedThreeNotice(1, Side(1), Seat(1), listOf(c("3H")), atDeal = false)
        val fromOther = TableAnimations.redThreeSwap(other, Seat(0), TableAnimations.Replacement.FaceDown, { ++id })
        fromOther[0] shouldBe CardFlight(fromOther[0].id, AnimAnchor.SeatHand(1), AnimAnchor.RedThrees(1), c("3H"))
        fromOther[1].from shouldBe AnimAnchor.Stock
        fromOther[1].to shouldBe AnimAnchor.SeatHand(1)
        fromOther[1].card shouldBe null

        val own = RedThreeNotice(2, Side(0), Seat(0), listOf(c("3D")), atDeal = false)
        val revealed = TableAnimations.redThreeSwap(own, Seat(0), TableAnimations.Replacement.Revealed(listOf(c("QS"))), { ++id })
        revealed[0].from shouldBe AnimAnchor.OwnHand
        revealed[1] shouldBe CardFlight(revealed[1].id, AnimAnchor.Stock, AnimAnchor.OwnHand, c("QS"), revealed[1].delayMillis)

        val morto = TableAnimations.redThreeSwap(own, Seat(0), TableAnimations.Replacement.None, { ++id }, fromMorto = 1)
        morto.single().from shouldBe AnimAnchor.Morto(1)
        morto.single().to shouldBe AnimAnchor.RedThrees(0)
    }

    @Test
    fun `escala do sistema - expiracao acompanha a escala e escala 0 nao gera voos`() = runTest(dispatcher) {
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 400,
        )
        advanceUntilIdle()
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
        vm.uiState.value.awaitingDraw shouldBe true
        vm.onAnimationScaleChanged(2f)
        runCurrent()
        vm.uiState.value.animationMillis shouldBe 800
        vm.onDraw()
        runCurrent()
        val draw = vm.uiState.value.flights.single { it.from == AnimAnchor.Stock && it.to == AnimAnchor.OwnHand }
        // Com escala 2, o voo dura 800 ms: ainda está lá depois de 2 × 400 (a expiração antiga, sem escala).
        advanceTimeBy(1_000)
        runCurrent()
        vm.uiState.value.flights.any { it.id == draw.id } shouldBe true
        advanceTimeBy(2_000)
        runCurrent()
        vm.uiState.value.flights.none { it.id == draw.id } shouldBe true

        // "Remover animações": escala 0, nenhum voo.
        vm.onAnimationScaleChanged(0f)
        runCurrent()
        vm.uiState.value.animationMillis shouldBe 0
        val discard = vm.uiState.value.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.onCardClick(vm.uiState.value.hand.first { it.cardClass == discard.card.cardClass })
        vm.onDiscard()
        advanceUntilIdle()
        vm.uiState.value.flights shouldBe emptyList()
    }

    @Test
    fun `§7 destaque de canastra expira (o pulso nao fica preso)`() = runTest(dispatcher) {
        // Jogo só de bots: acha um destaque de canastra e confere que ele some do estado depois da duração.
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000), gameSeed = 11, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 300,
        )
        var seen = false
        var guard = 0
        while (!seen && guard++ < 3_000 && vm.uiState.value.snapshot.stage == Stage.PLAYING) {
            val state = vm.uiState.value
            when {
                state.reveal != null -> vm.onRevealConfirmed()
                state.awaitingDraw -> vm.onDraw()
                state.playing -> {
                    val legal = state.snapshot.humanLegal
                    val meld = legal.filterIsInstance<Action.CreateMeld>().firstOrNull { state.hand.size - it.cards.size >= 2 }
                    if (meld != null) {
                        meld.cards.forEach { card -> vm.onCardClick(state.hand.first { it.cardClass == card.cardClass && it !in vm.uiState.value.selected }) }
                        vm.onCreateMeld()
                    } else {
                        val add = legal.filterIsInstance<Action.AddToMeld>().firstOrNull { state.hand.size - it.cards.size >= 2 }
                        if (add != null) {
                            add.cards.forEach { card -> vm.onCardClick(state.hand.first { it.cardClass == card.cardClass && it !in vm.uiState.value.selected }) }
                            vm.onAddToMeld(add.meldId)
                        } else {
                            val discard = legal.filterIsInstance<Action.Discard>().first()
                            vm.onCardClick(state.hand.first { it.cardClass == discard.card.cardClass })
                            vm.onDiscard()
                        }
                    }
                }
            }
            runCurrent()
            seen = vm.uiState.value.canastaFlashes.isNotEmpty()
            if (!seen) advanceTimeBy(1_000)
        }
        seen shouldBe true
        advanceTimeBy(300L * 2 + 1_000)
        runCurrent()
        vm.uiState.value.canastaFlashes shouldBe emptyList()
    }

    @Test
    fun `§5_1 pegar o lixo com snapshot pulado - topo vem do conjunto e as cartas da mao do plano voam junto`() {
        val hand = listOf(c("8C"), c("8D"), c("4S"), c("7H"))
        // O snapshot anterior visto ainda mostrava outro topo (5♠): houve descartes no meio que não foram vistos.
        val old = snapshot(view(hand), List(4) { emptyList() })
        val meld = (SideTable().createMeld(listOf(c("8C"), c("8D"), c("8H'"))) as RuleResult.Ok).value
        val plan = DiscardPlan.NewMeld(listOf(c("8C"), c("8D")))
        val event = PublicEvent(Seat(0), Action.TakeDiscardPile(plan), takenFromDiscard = listOf(c("5S"), c("QS")))
        val newView = view(listOf(c("4S"), c("7H"), c("5S"), c("QS"))).copy(tables = listOf(meld, SideTable()), discardPile = emptyList())
        val new = snapshot(newView, listOf(listOf(event)) + List(3) { emptyList() })
        var id = 0L
        val (flights, _) = TableAnimations.derive(old, new, emptySet()) { ++id }
        val target = AnimAnchor.Meld(0, meld.melds.single().id.value)
        flights.single { it.from == AnimAnchor.DiscardPile && it.to == target }.card shouldBe c("8H'")
        flights.filter { it.from == AnimAnchor.OwnHand && it.to == target }.map { it.card } shouldBe listOf(c("8C"), c("8D"))
        flights.filter { it.from == AnimAnchor.DiscardPile && it.to == AnimAnchor.OwnHand }.map { it.card } shouldBe listOf(c("5S"), c("QS"))
    }
}

