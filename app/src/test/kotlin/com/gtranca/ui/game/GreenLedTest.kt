package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.ui.cards.CardEmphasis
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §17.6 b1 Green-LED: o realce das cartas jogáveis só existe com o booster na partida. */
@OptIn(ExperimentalCoroutinesApi::class)
class GreenLedTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(boosters: List<String>): GameViewModel {
        val game = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000),
            gameSeed = 9, computeDispatcher = dispatcher, botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            initialBoosters = boosters,
        )
        advanceUntilIdle()
        while (game.uiState.value.reveal != null) { game.onRevealConfirmed(); advanceUntilIdle() }
        return game
    }

    @Test
    fun `§17_6 sem o b1 nenhuma carta e realcada, mesmo na vez do jogador`() = runTest(dispatcher) {
        val game = vm(emptyList())
        game.uiState.value.isHumanTurn shouldBe true
        game.uiState.value.highlighted shouldBe emptySet()
    }

    @Test
    fun `§17_6 com o b1 as cartas que participam de jogada sao realcadas`() = runTest(dispatcher) {
        val game = vm(listOf("b1"))
        game.onDraw()
        advanceUntilIdle()
        while (game.uiState.value.reveal != null) { game.onRevealConfirmed(); advanceUntilIdle() }
        game.uiState.value.playing shouldBe true
        val state = game.uiState.value
        state.highlighted shouldBe com.gtranca.game.HumanTurnResolver.highlightedCards(state.hand, state.snapshot.humanLegal, emptyList())
    }

    @Test
    fun `§17_6 handEmphasis sem o b1 so mostra a carta selecionada`() {
        handEmphasis(selected = true, highlighted = true, hasSelection = true, humanTurn = true, greenLed = false) shouldBe CardEmphasis.SELECTED
        handEmphasis(selected = false, highlighted = true, hasSelection = false, humanTurn = true, greenLed = false) shouldBe CardEmphasis.NONE
        handEmphasis(selected = false, highlighted = true, hasSelection = true, humanTurn = true, greenLed = false) shouldBe CardEmphasis.NONE
        handEmphasis(selected = false, highlighted = false, hasSelection = true, humanTurn = true, greenLed = false) shouldBe CardEmphasis.NONE
    }

    @Test
    fun `§17_6 handEmphasis com o b1 mantem o realce de sempre`() {
        handEmphasis(selected = false, highlighted = true, hasSelection = false, humanTurn = true, greenLed = true) shouldBe CardEmphasis.SUBTLE
        handEmphasis(selected = false, highlighted = true, hasSelection = true, humanTurn = true, greenLed = true) shouldBe CardEmphasis.STRONG
        handEmphasis(selected = false, highlighted = false, hasSelection = true, humanTurn = true, greenLed = true) shouldBe CardEmphasis.DIMMED
    }
}
