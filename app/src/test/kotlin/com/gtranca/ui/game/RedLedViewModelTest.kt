package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.OpponentFit
import com.gtranca.engine.RedLed
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
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

/** §17.7 b2 Yellow-LED na mesa: as marcas só existem com o booster, na vez do jogador, depois de comprar. */
@OptIn(ExperimentalCoroutinesApi::class)
class RedLedViewModelTest {
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

    /** Joga a partida (comprar e descartar) e devolve, a cada vez em que o jogador baixa/descarta, as marcas mostradas. */
    private fun TestScope.marksPerTurn(game: GameViewModel): List<Pair<Map<com.gtranca.engine.model.Card, OpponentFit>, Map<com.gtranca.engine.model.Card, OpponentFit>>> {
        val seen = mutableListOf<Pair<Map<com.gtranca.engine.model.Card, OpponentFit>, Map<com.gtranca.engine.model.Card, OpponentFit>>>()
        var guard = 0
        while (game.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 2_000) {
            while (game.uiState.value.reveal != null) { game.onRevealConfirmed(); advanceUntilIdle() }
            val state = game.uiState.value
            when {
                state.awaitingDraw -> {
                    // §17.7 antes de comprar o b2 não marca nada
                    state.opponentFits shouldBe emptyMap()
                    if (state.canDraw) game.onDraw() else game.onTakeDiscardPile()
                }
                state.playing -> {
                    seen += state.opponentFits to RedLed.marks(state.snapshot.view)
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    game.onCardClick(state.hand.first { it.cardClass == discard.card.cardClass })
                    game.onDiscard()
                }
            }
            advanceUntilIdle()
        }
        return seen
    }

    @Test
    fun `§17_7 com o b2 a mesa mostra as marcas do motor depois de comprar, e antes de comprar nenhuma`() = runTest(dispatcher) {
        val seen = marksPerTurn(vm(listOf("b2")))
        seen.isNotEmpty() shouldBe true
        seen.all { (shown, expected) -> shown == expected } shouldBe true
        // com os robôs jogando por toda a partida, em algum momento há carta que se encaixa no jogo deles
        seen.any { (shown, _) -> shown.isNotEmpty() } shouldBe true
    }

    @Test
    fun `§17_7 sem o b2 nenhuma carta e marcada`() = runTest(dispatcher) {
        val seen = marksPerTurn(vm(emptyList()))
        seen.isNotEmpty() shouldBe true
        seen.all { (shown, _) -> shown.isEmpty() } shouldBe true
    }

    @Test
    fun `§17_7 o b1 sozinho nao liga o b2`() = runTest(dispatcher) {
        val seen = marksPerTurn(vm(listOf("b1")))
        seen.all { (shown, _) -> shown.isEmpty() } shouldBe true
    }
}
