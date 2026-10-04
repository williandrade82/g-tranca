package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.GameMode
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
        val drawn = state.newCards.singleOrNull()
        if (drawn != null) draw.card?.cardClass shouldBe drawn.cardClass
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
}
