package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.game.GameConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §3.6, telas-app.md 3.8.2: a UI do minijogo "distribuição certeira". `dealGuessBonusEnabled` (§14.2) é a base da
 * mesa (sim), então todo jogo criado aqui já nasce com a regra ligada — ao contrário de [GameViewModelTest], que a
 * desliga para não interferir nos testes escritos antes dela existir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealGuessViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newGame(seed: Long, mode: GameMode = GameMode.INDIVIDUAL): GameViewModel {
        val vm = GameViewModel(
            GameConfig(mode, Difficulty.FACIL, 3000),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            swapAnimationMillis = 0,
            animationMillis = 0,
        )
        advanceUntilIdle()
        return vm
    }

    /** Uma semente em que o assento 0 (humano) abre a partida: no individual, ele palpita a mão e o morto. */
    private fun TestScope.humanDealerGame(): GameViewModel {
        for (seed in 1L..200L) {
            val vm = newGame(seed)
            if (vm.uiState.value.snapshot.view.dealGuess?.dealerSeat == Seat(0)) return vm
        }
        error("Nenhuma semente com o assento 0 abrindo a partida em 200 tentativas")
    }

    @Test
    fun `§3_6 o monte fica escondido e o modal da mao aparece so para quem palpita`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val state = vm.uiState.value
        state.snapshot.view.phase shouldBe Phase.DEAL_GUESS_HAND
        state.isDealGuessing shouldBe true
        state.dealGuessPrompt.shouldNotBeNull()
        state.dealGuessPrompt!!.stage shouldBe DealGuessStage.HAND
        (state.dealGuessPrompt!!.totalStages > 0) shouldBe true
    }

    @Test
    fun `§3_6 §12_1 palpite certo da mao mostra a reacao de vitoria e so libera o morto apos o toque`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val rules = vm.uiState.value.snapshot.view.rules
        val mode = vm.uiState.value.snapshot.view.mode
        val target = dealGuessHandTarget(mode, rules)

        vm.onGuessDealRange(0, target)
        // §3.6 só o que já está pronto agora: `advanceUntilIdle` adiantaria o relógio virtual além do tempo da
        // pílula (ela já teria sumido sozinha antes da conferência abaixo).
        runCurrent()

        val afterGuess = vm.uiState.value
        // §3.6 o palpite nunca é jogada da vez: não aparece no registro do próprio assento.
        afterGuess.snapshot.view.dealGuess?.hand?.correct shouldBe true
        val reveal = afterGuess.dealGuessReveal.shouldNotBeNull()
        reveal.correct shouldBe true
        reveal.partner shouldBe false
        reveal.stage shouldBe DealGuessStage.HAND
        afterGuess.dealGuessPills[0]?.correct shouldBe true
        // Individual: o mesmo jogador palpitaria o morto em seguida, mas o modal espera a reação fechar primeiro.
        afterGuess.dealGuessPrompt.shouldBeNull()
        afterGuess.snapshot.view.phase shouldBe Phase.DEAL_GUESS_MORTO

        vm.onDealGuessRevealDismissed()
        advanceUntilIdle()

        val afterDismiss = vm.uiState.value
        afterDismiss.dealGuessReveal.shouldBeNull()
        afterDismiss.dealGuessPrompt.shouldNotBeNull()
        afterDismiss.dealGuessPrompt!!.stage shouldBe DealGuessStage.MORTO
    }

    @Test
    fun `§3_6 §12_1 palpite errado do morto mostra nao foi dessa vez e a partida comeca so apos o toque`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val handTarget = dealGuessHandTarget(view0.mode, view0.rules)
        // Larguras diferentes da certa sempre erram (§3.6): "largura certa + 1" nunca é o alvo.
        vm.onGuessDealRange(0, handTarget + 1)
        advanceUntilIdle()
        vm.onDealGuessRevealDismissed()
        advanceUntilIdle()

        val beforeMorto = vm.uiState.value
        beforeMorto.dealGuessPrompt.shouldNotBeNull()
        beforeMorto.dealGuessPrompt!!.stage shouldBe DealGuessStage.MORTO
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)

        vm.onGuessDealRange(0, mortoTarget + 1)
        // §3.6 idem: só o que já está pronto agora, antes da pílula sumir sozinha.
        runCurrent()

        val afterGuess = vm.uiState.value
        val reveal = afterGuess.dealGuessReveal.shouldNotBeNull()
        reveal.correct shouldBe false
        reveal.stage shouldBe DealGuessStage.MORTO
        afterGuess.dealGuessPills[0]?.correct shouldBe false
        // A 1ª jogada de verdade ainda não começou: o monte continua escondido até o toque fechar a reação.
        afterGuess.isDealGuessing shouldBe true
        afterGuess.snapshot.view.phase shouldBe Phase.AWAITING_DRAW

        vm.onDealGuessRevealDismissed()
        advanceUntilIdle()

        val afterDismiss = vm.uiState.value
        afterDismiss.dealGuessReveal.shouldBeNull()
        afterDismiss.isDealGuessing shouldBe false
        afterDismiss.dealGuessPrompt.shouldBeNull()
    }
}
