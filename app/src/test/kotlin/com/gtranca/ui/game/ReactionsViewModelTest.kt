package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Reaction
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §17.8 b3 Reações na mesa: 3 usos por partida, a reação fica à vista por um tempo e a partida seguinte renova os usos. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(boosters: List<String>): GameViewModel {
        val game = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1_000_000),
            gameSeed = 9, computeDispatcher = dispatcher, botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            initialBoosters = boosters,
        )
        advanceUntilIdle()
        return game
    }

    @Test
    fun `§17_8 com o b3 a mesa mostra o botao com 3 usos`() = runTest(dispatcher) {
        val game = vm(listOf("b3"))
        game.uiState.value.reactionsEnabled shouldBe true
        game.uiState.value.reactionsLeft shouldBe 3
    }

    @Test
    fun `§17_8 sem o b3 nao ha botao nem uso`() = runTest(dispatcher) {
        val game = vm(listOf("b1", "b2"))
        game.uiState.value.reactionsEnabled shouldBe false
        game.onReaction(Reaction.LOVE)
        advanceUntilIdle()
        game.uiState.value.reactionsLeft shouldBe 0
        game.uiState.value.ownReaction shouldBe null
    }

    @Test
    fun `§17_8 cada envio gasta um dos tres usos e o quarto e ignorado`() = runTest(dispatcher) {
        val game = vm(listOf("b3"))
        listOf(Reaction.APPLAUSE, Reaction.ANGRY, Reaction.CHICKEN).forEachIndexed { i, reaction ->
            game.onReaction(reaction)
            runCurrent()
            game.uiState.value.ownReaction shouldBe reaction
            game.uiState.value.reactionsLeft shouldBe 2 - i
        }
        game.onReaction(Reaction.LOVE)
        runCurrent()
        game.uiState.value.reactionsLeft shouldBe 0
        game.uiState.value.ownReaction shouldBe Reaction.CHICKEN // a quarta não substituiu
    }

    @Test
    fun `§17_8 a reacao some depois do tempo e uma nova reinicia a contagem`() = runTest(dispatcher) {
        val game = vm(listOf("b3"))
        game.onReaction(Reaction.LOVE)
        runCurrent()
        val first = game.uiState.value.ownReactionId
        advanceTimeBy(GameViewModel.REACTION_SHOW_MILLIS - 100)
        game.uiState.value.ownReaction shouldBe Reaction.LOVE
        game.onReaction(Reaction.THINKING)
        runCurrent()
        (game.uiState.value.ownReactionId != first) shouldBe true
        // o tempo da primeira acabaria agora, mas a segunda continua à vista
        advanceTimeBy(200)
        game.uiState.value.ownReaction shouldBe Reaction.THINKING
        advanceTimeBy(GameViewModel.REACTION_SHOW_MILLIS)
        game.uiState.value.ownReaction shouldBe null
    }
}
