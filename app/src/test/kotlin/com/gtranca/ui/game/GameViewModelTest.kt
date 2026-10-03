package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.HumanTurnResolver
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newGame(
        seed: Long,
        target: Int = 3000,
        mode: GameMode = GameMode.INDIVIDUAL,
        botDelayMillis: Long = 0,
    ): GameViewModel {
        val vm = GameViewModel(
            GameConfig(mode, Difficulty.FACIL, target),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = botDelayMillis,
        )
        advanceUntilIdle()
        return vm
    }

    private fun GameViewModel.select(cards: List<Card>) = cards.forEach(::onCardClick)

    /** Cartas físicas da mão com as classes de [wanted], preferindo a 2ª cópia (deck 1) quando houver. */
    private fun GameUiState.physical(wanted: List<Card>): List<Card> {
        val available = hand.sortedByDescending { it.deck }.toMutableList()
        return wanted.map { w -> available.first { it.cardClass == w.cardClass }.also { available.remove(it) } }
    }

    @Test
    fun `comeca na vez do humano com a mao ordenada e so a compra habilitada`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        val state = vm.uiState.value
        state.isHumanTurn shouldBe true
        state.awaitingDraw shouldBe true
        state.canDraw shouldBe true
        state.canMeld shouldBe false
        state.hand.size shouldBe state.snapshot.view.hand.size
        state.hand shouldBe GameViewModel.sortHand(state.snapshot.view.hand, HandSort.BY_SUIT)
        vm.onToggleSort()
        advanceUntilIdle()
        vm.uiState.value.sort shouldBe HandSort.BY_RANK
        vm.uiState.value.hand.map { it.rank }.shouldBeSortedWith(compareBy { it.ordinal })
    }

    @Test
    fun `comprar, recusa explicada, baixar com a outra copia e descartar`() = runTest(dispatcher) {
        // Procura uma semente em que o humano possa baixar depois de comprar.
        var vm: GameViewModel? = null
        var meld: Action.CreateMeld? = null
        for (seed in 1L..60L) {
            val candidate = newGame(seed)
            candidate.onDraw()
            advanceUntilIdle()
            val found = candidate.uiState.value.snapshot.humanLegal.filterIsInstance<Action.CreateMeld>().firstOrNull()
            if (candidate.uiState.value.playing && found != null) {
                vm = candidate
                meld = found
                break
            }
        }
        vm.shouldNotBeNull()
        meld.shouldNotBeNull()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING

        // Seleção inválida: o motivo vem do motor.
        val single = vm.uiState.value.physical(meld.cards.take(1))
        vm.select(single)
        vm.onCreateMeld()
        advanceUntilIdle()
        vm.uiState.value.message shouldBe UiMessage.Rejected(MeldError.TOO_FEW_CARDS)
        vm.onClearSelection()

        // Seleção válida (com a 2ª cópia quando houver) vira a ação de legal.
        val selection = vm.uiState.value.physical(meld.cards)
        vm.select(selection)
        advanceUntilIdle()
        vm.uiState.value.selected shouldContainExactly selection
        vm.onCreateMeld()
        advanceUntilIdle()
        val afterMeld = vm.uiState.value
        afterMeld.snapshot.view.tables[afterMeld.snapshot.viewerSide.index].melds.size shouldBe 1
        afterMeld.selected.shouldBeEmpty()

        // Descartar com 2 cartas selecionadas pede uma só.
        val two = afterMeld.hand.take(2)
        vm.select(two)
        vm.onDiscard()
        advanceUntilIdle()
        vm.uiState.value.message shouldBe UiMessage.SelectOneCardToDiscard
        vm.onClearSelection()

        val discard = vm.uiState.value.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(vm.uiState.value.physical(listOf(discard.card)))
        vm.onDiscard()
        advanceUntilIdle()
        val after = vm.uiState.value
        if (after.snapshot.stage == Stage.PLAYING) {
            // O bot jogou a vez inteira e a vez voltou ao humano; a última jogada dele está visível.
            after.isHumanTurn shouldBe true
            after.snapshot.turnEvents[1].shouldNotBeEmpty()
        }
    }

    @Test
    fun `destaque vem de legalActions e recusa da compra so quando legal`() = runTest(dispatcher) {
        val vm = newGame(seed = 7)
        vm.onDraw()
        advanceUntilIdle()
        val state = vm.uiState.value
        state.highlighted shouldBe HumanTurnResolver.highlightedClasses(state.snapshot.humanLegal, emptyList())
        vm.onDeclineDraw()
        advanceUntilIdle()
        vm.uiState.value.confirmDecline shouldBe false
    }

    @Test
    fun `jogo inteiro pela interface ate o fim de jogo`() = runTest(dispatcher) {
        val vm = newGame(seed = 9, target = 1)
        var guard = 0
        while (vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 2_000) {
            val state = vm.uiState.value
            when {
                state.awaitingDraw -> if (state.canDraw) vm.onDraw() else vm.onTakeDiscardPile()
                state.playing -> {
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card)))
                    vm.onDiscard()
                }
            }
            advanceUntilIdle()
        }
        val ended = vm.uiState.value.snapshot
        ended.history.size shouldBe 1
        ended.view.seat shouldBe Seat(0)
        if (ended.stage == Stage.ROUND_OVER) {
            vm.onNextRound()
            advanceUntilIdle()
            vm.uiState.value.snapshot.roundNumber shouldBe 2
        } else {
            ended.stage shouldBe Stage.GAME_OVER
            vm.onShowFinalResult()
            advanceUntilIdle()
            vm.uiState.value.showFinalResult shouldBe true
        }
    }

    /** Uma vez do humano: compra e descarta a primeira carta descartável. */
    private fun TestScope.drawAndDiscard(vm: GameViewModel) {
        vm.onDraw()
        advanceUntilIdle()
        val state = vm.uiState.value
        val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(state.physical(listOf(discard.card)))
        vm.onDiscard()
    }

    @Test
    fun `duplas - humano no assento 0 ve so a propria mao e a jogada dos tres bots`() = runTest(dispatcher) {
        val vm = newGame(seed = 4, mode = GameMode.DUPLAS)
        val first = vm.uiState.value
        first.isHumanTurn shouldBe true
        first.snapshot.viewerSeat shouldBe Seat(0)
        val view = first.snapshot.view
        view.handSizes.size shouldBe 4
        // §1.1 o parceiro (assento 2) é do mesmo lado; a mão dele nunca chega à interface, só o tamanho.
        view.mode.sideOf(Seat(2)) shouldBe view.side
        view.hand.size shouldBe view.handSizes[0]
        first.hand.size shouldBe view.handSizes[0]

        drawAndDiscard(vm)
        advanceUntilIdle()
        val after = vm.uiState.value
        if (after.snapshot.stage == Stage.PLAYING) {
            // A vez deu a volta: direita (1), parceiro (2) e esquerda (3) jogaram, nessa ordem (§4.2, anti-horário).
            after.isHumanTurn shouldBe true
            listOf(1, 2, 3).forEach { after.snapshot.turnEvents[it].shouldNotBeEmpty() }
        }
    }

    @Test
    fun `§4_2 duplas - a vez passa a direita, ao parceiro e a esquerda com a pausa entre bots`() = runTest(dispatcher) {
        val vm = newGame(seed = 4, mode = GameMode.DUPLAS, botDelayMillis = 700)
        vm.uiState.value.isHumanTurn shouldBe true
        drawAndDiscard(vm)
        runCurrent()
        val thinkingOrder = mutableListOf<Seat>()
        var guard = 0
        while (!vm.uiState.value.isHumanTurn && vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 500) {
            vm.uiState.value.snapshot.thinkingSeat?.let { if (thinkingOrder.lastOrNull() != it) thinkingOrder += it }
            advanceTimeBy(100)
            runCurrent()
        }
        if (vm.uiState.value.snapshot.stage == Stage.PLAYING) {
            thinkingOrder shouldContainExactly listOf(Seat(1), Seat(2), Seat(3))
        }
    }

    @Test
    fun `toque duplo - o segundo toque nao mostra motivo de outra situacao`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        vm.uiState.value.awaitingDraw shouldBe true
        vm.select(vm.uiState.value.hand.take(3))
        vm.onDraw()
        // Antes de o controlador retomar, um toque em "Baixar" (que daria "compre primeiro") é ignorado.
        vm.onCreateMeld()
        vm.onDraw()
        vm.uiState.value.message shouldBe null
        advanceUntilIdle()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING
        vm.uiState.value.message shouldBe null
    }
}
