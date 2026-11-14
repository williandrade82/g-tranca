package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.Card
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

    private fun TestScope.newGame(seed: Long, mode: GameMode = GameMode.INDIVIDUAL, animationMillis: Long = 0): GameViewModel {
        val vm = GameViewModel(
            GameConfig(mode, Difficulty.FACIL, 3000),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            swapAnimationMillis = 0,
            animationMillis = animationMillis,
        )
        advanceUntilIdle()
        return vm
    }

    /** Uma semente em que o assento 0 (humano) abre a partida: no individual, ele palpita a mão e o morto. */
    private fun TestScope.humanDealerGame(animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..200L) {
            val vm = newGame(seed, animationMillis = animationMillis)
            if (vm.uiState.value.snapshot.view.dealGuess?.dealerSeat == Seat(0)) return vm
        }
        error("Nenhuma semente com o assento 0 abrindo a partida em 200 tentativas")
    }

    /**
     * Uma semente em que, além do assento 0 ser o dealerSeat (abre a partida e palpita os dois, individual), a mão
     * dele já tem um 3 vermelho (ainda não trocado: `beginTurn` só roda depois do palpite do morto resolvido, §3.5,
     * `Deal.kt`) — reproduz o cenário do bug: a reação do palpite do morto e o aviso "Baixar 3 vermelho" do início
     * da vez chegam juntos, na mesma ação (`RoundEngine.beginTurn` dentro do `GuessDealRange` do morto).
     */
    private fun TestScope.humanDealerWithStartSwapGame(animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..500L) {
            val vm = newGame(seed, animationMillis = animationMillis)
            val view = vm.uiState.value.snapshot.view
            if (view.dealGuess?.dealerSeat == Seat(0) && view.hand.any { it.isRedThree }) return vm
        }
        error("Nenhuma semente com o assento 0 abrindo a partida com 3 vermelho na mão em 500 tentativas")
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

    /**
     * §3.6 item 7 do relato 2026-11-14: o alvo (44/22 ou 22) é número público das regras (`definition.md` §3.6),
     * mostrado no modal ("Selecione XX cartas..."). Em individual os dois alvos coincidem (22/22), o que não
     * distingue um bug de troca entre `dealGuessHandTarget`/`dealGuessMortoTarget`; por isso o teste roda em
     * duplas, onde a mão vale 44 e o morto 22 — valores diferentes o bastante para flagrar a troca. Em duplas
     * `dealerSeat` (mão) e `mortoDealerSeat` (morto) são sempre assentos diferentes (`dealGuessSeats`), então cada
     * palpite é buscado numa semente própria, não encadeado na mesma partida.
     */
    @Test
    fun `§3_6 o alvo mostrado no modal da mao bate com dealGuessHandTarget, em duplas`() = runTest(dispatcher) {
        var vm: GameViewModel? = null
        for (seed in 1L..200L) {
            val candidate = newGame(seed, mode = GameMode.DUPLAS)
            if (candidate.uiState.value.snapshot.view.dealGuess?.dealerSeat == Seat(0)) {
                vm = candidate
                break
            }
        }
        val game = vm ?: error("Nenhuma semente com o assento 0 abrindo a partida em duplas em 200 tentativas")
        val view0 = game.uiState.value.snapshot.view
        val handPrompt = game.uiState.value.dealGuessPrompt.shouldNotBeNull()
        handPrompt.stage shouldBe DealGuessStage.HAND
        handPrompt.target shouldBe dealGuessHandTarget(view0.mode, view0.rules)
        handPrompt.target shouldBe 44
    }

    @Test
    fun `§3_6 o alvo mostrado no modal do morto bate com dealGuessMortoTarget, em duplas`() = runTest(dispatcher) {
        var vm: GameViewModel? = null
        for (seed in 1L..200L) {
            val candidate = newGame(seed, mode = GameMode.DUPLAS)
            if (candidate.uiState.value.snapshot.view.dealGuess?.mortoDealerSeat == Seat(0)) {
                vm = candidate
                break
            }
        }
        val game = vm ?: error("Nenhuma semente com o assento 0 distribuindo o morto em duplas em 200 tentativas")
        // `newGame` já roda `advanceUntilIdle()`: a vez do assento 0 só é o palpite do morto se o da mão (de outro
        // assento, sempre diferente em duplas) já foi resolvido por um robô antes de pausar aqui.
        val mortoPrompt = game.uiState.value.dealGuessPrompt.shouldNotBeNull()
        mortoPrompt.stage shouldBe DealGuessStage.MORTO
        mortoPrompt.target shouldBe dealGuessMortoTarget(game.uiState.value.snapshot.view.rules)
        mortoPrompt.target shouldBe 22
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

    /**
     * BUG (item 2 do relato 2026-11-14): o motor só guarda a largura do palpite — `legalActions` representa cada
     * largura por um único candidato `GuessDealRange(0, width)` (`LegalActions.dealGuessCandidates`), então
     * `dealGuess.hand.start`/`.end` chegam **sempre** `0`/`width`, nunca o par que o jogador tocou de verdade. A
     * reação de erro (`DealGuessRevealOverlay`) precisa mostrar o que foi tocado, não o que o motor normalizou.
     */
    @Test
    fun `§3_6 o motor so guarda a largura do palpite, nunca o par tocado`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val target = dealGuessHandTarget(view0.mode, view0.rules)
        vm.onGuessDealRange(10, 10 + target)
        runCurrent()
        val hand = vm.uiState.value.snapshot.view.dealGuess?.hand.shouldNotBeNull()
        hand.start shouldBe 0
        hand.end shouldBe target
    }

    /**
     * §3.6 item 2: a reação de erro mostra o Início/Fim que o PRÓPRIO jogador deste aparelho tocou (não "0" e "a
     * largura", que é tudo que `dealGuess.hand` guarda — ver teste acima). Vem de `Local.dealGuessTaps`, guardado em
     * [GameViewModel.onGuessDealRange] no toque, não do motor.
     */
    @Test
    fun `§3_6 a reacao de erro mostra o Inicio e o Fim que o proprio jogador tocou de verdade`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val handStages = com.gtranca.engine.model.dealGuessHandStages(view0.rules)
        val target = dealGuessHandTarget(view0.mode, view0.rules)
        val wrongWidth = if (target + 1 <= handStages) target + 1 else target - 1
        val tappedStart = 5
        val tappedEnd = tappedStart + wrongWidth

        vm.onGuessDealRange(tappedStart, tappedEnd)
        runCurrent()

        val reveal = vm.uiState.value.dealGuessReveal.shouldNotBeNull()
        reveal.correct shouldBe false
        reveal.partner shouldBe false
        reveal.start shouldBe tappedStart
        reveal.end shouldBe tappedEnd
        reveal.width shouldBe wrongWidth
    }

    /**
     * BUG relatado 2026-11-14: o palpite do morto e a troca do 3 vermelho do início da vez do próprio dealer (§3.5,
     * `RoundEngine.beginTurn` roda dentro do `GuessDealRange` do morto, `RoundEngine.kt` linha ~220) resolvem na
     * MESMA ação: a reação em tela cheia do palpite e o aviso "Baixar 3 vermelho" chegam juntos. Na tela real
     * (zIndex mais alto por cima), o jogador fecha a reação do palpite primeiro — mas antes da correção o
     * controlador só armava a espera desse toque (`awaitingGuessReveal`) DEPOIS de esperar a confirmação da troca
     * (`presentationSignal`), então o toque chegava cedo demais e se perdia (`guessRevealDone` devolve `false` sem
     * ninguém esperando): o motor nunca mais publicava o próximo pedido, e os botões ficavam presos em
     * "Comprar"/"Pegar" (nenhuma ação em `legalActions`).
     */
    @Test
    fun `§3_6 §3_5 fechar a reacao do palpite antes de baixar o 3 vermelho do inicio da vez nao trava o jogo`() = runTest(dispatcher) {
        // §3.6 BUG: usa `runCurrent()`, nunca `advanceUntilIdle()`, logo depois de cada `GuessDealRange` — a espera do
        // motor pelo toque (`guessRevealSignal`) agora tem um tempo limite de segurança bem longo (ver
        // `GameController.GUESS_REVEAL_SAFETY_MILLIS`); com `advanceUntilIdle()` o relógio virtual adiantaria direto
        // até esse limite e o teste passaria mesmo SEM a correção de verdade (o toque nunca chegando a valer nada),
        // só por sorte do tempo virtual. `runCurrent()` só processa o que já está pronto agora, sem pular à frente —
        // é o que de fato obriga o toque adiantado a já valer (o canal conflado guarda o sinal até ser esperado).
        val vm = humanDealerWithStartSwapGame()
        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealRange(0, dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()
        vm.onDealGuessRevealDismissed()
        runCurrent()

        val beforeMorto = vm.uiState.value
        beforeMorto.dealGuessPrompt.shouldNotBeNull()
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)

        vm.onGuessDealRange(0, mortoTarget)
        runCurrent()

        // Os dois avisos chegam juntos: a reação do palpite (por cima) e "Baixar 3 vermelho" do início da vez.
        vm.uiState.value.dealGuessReveal.shouldNotBeNull()
        vm.uiState.value.ownSwapPending shouldBe true

        // Ordem real da tela: o toque fecha primeiro o que está por cima. Pode haver mais de um 3 vermelho na mão
        // inicial (cada um é uma troca própria em `state.swaps`, confirmada uma por vez em "Baixar 3 vermelho").
        vm.onDealGuessRevealDismissed()
        runCurrent()
        repeat(20) {
            if (!vm.uiState.value.ownSwapPending) return@repeat
            vm.onRevealConfirmed()
            runCurrent()
        }
        vm.uiState.value.ownSwapPending shouldBe false

        // A regressão: sem a correção, o motor nunca publica o próximo pedido e `canDraw` fica preso em `false`
        // (sem o `runCurrent()` acima, só chegaria a `true` depois de adiantar o relógio pelo tempo limite inteiro).
        vm.uiState.value.canDraw shouldBe true

        // E o jogo segue normalmente: a próxima jogada publica botões novos de verdade.
        vm.onDraw()
        runCurrent()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING
    }

    /**
     * §3.5 BUG (pista B do mesmo relato): `dealPending` (`!dealDone`) só deve ficar `true` na transição real de
     * saída dos palpites para a 1ª jogada (quando a distribuição de verdade seria animada) — nunca durante os
     * palpites em si, e nunca "esquecido" em `true`/`false` por partida inteira (`dealDone` não podia ficar só
     * recalculado no 1º snapshot da partida, que ainda está em fase de palpite).
     */
    @Test
    fun `§3_5 dealPending so fica verdadeiro na transicao real pra 1a jogada, nao durante os palpites`() = runTest(dispatcher) {
        val vm = humanDealerGame(animationMillis = 50)
        vm.uiState.value.dealPending shouldBe false

        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealRange(0, dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()
        vm.uiState.value.dealPending shouldBe false
        vm.onDealGuessRevealDismissed()
        advanceUntilIdle()
        vm.uiState.value.dealPending shouldBe false // ainda palpitando o morto

        vm.onGuessDealRange(0, dealGuessMortoTarget(vm.uiState.value.snapshot.view.rules))
        runCurrent()
        // §3.5 saiu da fase de palpite: agora é que a distribuição de verdade seria animada.
        vm.uiState.value.dealPending shouldBe true

        vm.onDealAnimationFinished()
        runCurrent()
        vm.uiState.value.dealPending shouldBe false
    }
}
