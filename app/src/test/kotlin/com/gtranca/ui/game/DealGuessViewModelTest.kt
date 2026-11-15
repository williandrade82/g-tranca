package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.dealGuessHandRange
import com.gtranca.engine.model.dealGuessHandTarget
import com.gtranca.engine.model.dealGuessMortoRange
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.game.GameConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import com.gtranca.game.GameController
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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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

    /**
     * Uma semente em que o assento 0 (humano) é `dealerSeat`: ele palpita a mão (§3.6). Desde a correção de §3.6
     * (dealGuessSeats sempre dá assentos diferentes, inclusive em individual), `mortoDealerSeat` nunca é o mesmo
     * assento: o morto é palpitado por outro (um robô), resolvido sozinho (`reactToGuess` libera na hora para quem
     * não é `YOU`/`PARTNER`) sem exigir toque nenhum deste aparelho.
     */
    private fun TestScope.humanDealerGame(animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..200L) {
            val vm = newGame(seed, animationMillis = animationMillis)
            if (vm.uiState.value.snapshot.view.dealGuess?.dealerSeat == Seat(0)) return vm
        }
        error("Nenhuma semente com o assento 0 sendo dealerSeat (palpite da mao) em 200 tentativas")
    }

    /**
     * Uma semente em que o assento 0 (humano) é `mortoDealerSeat`: ele palpita o morto (§3.6). O palpite da mão, de
     * outro assento (um robô), já foi resolvido e liberado sozinho antes de chegar aqui — `newGame` já roda
     * `advanceUntilIdle()`, então o laço do controlador só pausa de novo quando é a vez do humano palpitar.
     */
    private fun TestScope.humanMortoDealerGame(animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..200L) {
            val vm = newGame(seed, animationMillis = animationMillis)
            if (vm.uiState.value.snapshot.view.dealGuess?.mortoDealerSeat == Seat(0)) return vm
        }
        error("Nenhuma semente com o assento 0 sendo mortoDealerSeat (palpite do morto) em 200 tentativas")
    }

    /**
     * Uma semente em que, além do assento 0 ser o `mortoDealerSeat` (em individual, `mortoDealerSeat` é sempre quem
     * abre a partida, `firstSeat` — ver `DealGuess.kt`), a mão dele já tem um 3 vermelho (ainda não trocado:
     * `beginTurn` só roda depois do palpite do morto resolvido, §3.5, `Deal.kt`) — reproduz o cenário do bug: a
     * reação do palpite do morto e o aviso "Baixar 3 vermelho" do início da vez chegam juntos, na mesma ação
     * (`RoundEngine.beginTurn` dentro do `GuessDealRange` do morto). O palpite da mão, de outro assento (um robô),
     * já foi resolvido e liberado sozinho antes de chegar aqui.
     */
    private fun TestScope.humanDealerWithStartSwapGame(animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..500L) {
            val vm = newGame(seed, animationMillis = animationMillis)
            val view = vm.uiState.value.snapshot.view
            if (view.dealGuess?.mortoDealerSeat == Seat(0) && view.hand.any { it.isRedThree }) return vm
        }
        error("Nenhuma semente com o assento 0 sendo mortoDealerSeat com 3 vermelho na mao em 500 tentativas")
    }

    @Test
    fun `§3_6 o monte fica escondido e o modal da mao aparece so para quem palpita`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val state = vm.uiState.value
        state.snapshot.view.phase shouldBe Phase.DEAL_GUESS_HAND
        state.isDealGuessing shouldBe true
        state.dealGuessPrompt.shouldNotBeNull()
        state.dealGuessPrompt!!.stage shouldBe DealGuessStage.HAND
        state.dealGuessPrompt!!.range shouldBe dealGuessHandRange(state.snapshot.view.mode)
    }

    /**
     * §3.6 (ajuste de 15/11/2026, faixa fixa): a faixa oferecida na barra é sempre a mesma, por papel/modo —
     * 35..55 na mão em duplas, 12..32 na mão em individual, 12..32 no morto em qualquer modo.
     */
    @Test
    fun `§3_6 a faixa da mao e 12-32 em individual e 35-55 em duplas`() = runTest(dispatcher) {
        val individual = humanDealerGame()
        individual.uiState.value.dealGuessPrompt!!.range shouldBe (12..32)

        var duplas: GameViewModel? = null
        for (seed in 1L..200L) {
            val candidate = newGame(seed, mode = GameMode.DUPLAS)
            if (candidate.uiState.value.snapshot.view.dealGuess?.dealerSeat == Seat(0)) {
                duplas = candidate
                break
            }
        }
        val game = duplas ?: error("Nenhuma semente com o assento 0 abrindo a partida em duplas em 200 tentativas")
        game.uiState.value.dealGuessPrompt!!.range shouldBe (35..55)
    }

    @Test
    fun `§3_6 a faixa do morto e sempre 12-32, em qualquer modo`() = runTest(dispatcher) {
        val vm = humanMortoDealerGame()
        vm.uiState.value.dealGuessPrompt!!.range shouldBe dealGuessMortoRange()
        vm.uiState.value.dealGuessPrompt!!.range shouldBe (12..32)
    }

    /**
     * §3.6 a faixa mostrada na barra nunca oferece um número que o motor rejeitaria: todo número de
     * [DealGuessPrompt.range] tem o candidato correspondente em `humanLegal` (a única fonte de jogadas válidas).
     */
    @Test
    fun `§3_6 todo numero da faixa do prompt tem uma acao legal correspondente`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val state = vm.uiState.value
        val prompt = state.dealGuessPrompt.shouldNotBeNull()
        val legalNumbers = state.snapshot.humanLegal.filterIsInstance<Action.GuessDealRange>()
            .map { it.end - it.start }
            .toSet()
        prompt.range.toSet() shouldBe legalNumbers
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

        vm.onGuessDealNumber(target)
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
        afterGuess.dealGuessPrompt.shouldBeNull()
        afterGuess.snapshot.view.phase shouldBe Phase.DEAL_GUESS_MORTO
        // O morto (agora sempre outro assento, um robô) ainda não foi palpitado: o controlador espera o toque
        // antes de liberar a próxima etapa, mesmo não sendo mais o mesmo jogador quem palpita os dois.
        afterGuess.snapshot.view.dealGuess?.morto shouldBe null

        vm.onDealGuessRevealDismissed()
        advanceUntilIdle()

        val afterDismiss = vm.uiState.value
        afterDismiss.dealGuessReveal.shouldBeNull()
        // §3.6 o robô palpita o morto sozinho, sem modal (reactToGuess libera `guessRevealDone` na hora para quem
        // não é YOU/PARTNER): nenhum prompt novo aparece para o humano, e o palpite já foi resolvido.
        afterDismiss.dealGuessPrompt.shouldBeNull()
        afterDismiss.snapshot.view.dealGuess?.morto.shouldNotBeNull()
    }

    @Test
    fun `§3_6 §12_1 palpite errado do morto mostra nao foi dessa vez e a partida comeca so apos o toque`() = runTest(dispatcher) {
        // §3.6 o assento 0 agora só palpita o morto aqui: a mão, de outro assento (um robô), já foi resolvida e
        // liberada sozinha (`reactToGuess` chama `guessRevealDone` na hora para quem não é YOU/PARTNER) dentro do
        // `advanceUntilIdle()` que `newGame` já roda.
        val vm = humanMortoDealerGame()
        val beforeMorto = vm.uiState.value
        beforeMorto.dealGuessPrompt.shouldNotBeNull()
        beforeMorto.dealGuessPrompt!!.stage shouldBe DealGuessStage.MORTO
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)

        // Larguras diferentes da certa sempre erram (§3.6): "largura certa + 1" nunca é o alvo.
        vm.onGuessDealNumber(mortoTarget + 1)
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
    /**
     * §3.6 (ajuste de 15/11/2026, toque único): o motor codifica exatamente o número tocado (`start` fixo em 1,
     * `dealGuessAction`); não há mais um par (início, fim) distinto do número escolhido.
     */
    @Test
    fun `§3_6 o motor guarda exatamente o numero tocado (start fixo em 1)`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val target = dealGuessHandTarget(view0.mode, view0.rules)
        vm.onGuessDealNumber(target)
        runCurrent()
        val hand = vm.uiState.value.snapshot.view.dealGuess?.hand.shouldNotBeNull()
        hand.start shouldBe com.gtranca.engine.model.DEAL_GUESS_FIXED_START
        hand.end shouldBe com.gtranca.engine.model.DEAL_GUESS_FIXED_START + target
        hand.width shouldBe target
    }

    /** §3.6 a reação de erro mostra o número que o PRÓPRIO jogador tocou (`DealGuessReveal.width`). */
    @Test
    fun `§3_6 a reacao de erro mostra o numero que o proprio jogador tocou de verdade`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val range = dealGuessHandRange(view0.mode)
        val target = dealGuessHandTarget(view0.mode, view0.rules)
        val wrongNumber = if (target + 1 <= range.last) target + 1 else target - 1

        vm.onGuessDealNumber(wrongNumber)
        runCurrent()

        val reveal = vm.uiState.value.dealGuessReveal.shouldNotBeNull()
        reveal.correct shouldBe false
        reveal.partner shouldBe false
        reveal.width shouldBe wrongNumber
    }

    /**
     * §3.6 um toque fora da faixa fixa do papel/modo não corresponde a nenhuma ação em `legalActions`: a tela
     * nunca decide por conta própria, então o toque é ignorado (nada é enviado ao motor, o prompt continua lá).
     */
    @Test
    fun `§3_6 um toque fora da faixa nao envia nada ao motor`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val range = vm.uiState.value.dealGuessPrompt!!.range

        vm.onGuessDealNumber(range.first - 1)
        runCurrent()
        vm.uiState.value.snapshot.view.dealGuess?.hand.shouldBeNull()
        vm.uiState.value.dealGuessPrompt.shouldNotBeNull()

        vm.onGuessDealNumber(range.last + 1)
        runCurrent()
        vm.uiState.value.snapshot.view.dealGuess?.hand.shouldBeNull()
        vm.uiState.value.dealGuessPrompt.shouldNotBeNull()
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
        // §3.6 BUG: usa `runCurrent()`, nunca `advanceUntilIdle()`, logo depois do `GuessDealRange` do morto — a
        // espera do motor pelo toque (`guessRevealSignal`) agora tem um tempo limite de segurança bem longo (ver
        // `GameController.GUESS_REVEAL_SAFETY_MILLIS`); com `advanceUntilIdle()` o relógio virtual adiantaria direto
        // até esse limite e o teste passaria mesmo SEM a correção de verdade (o toque nunca chegando a valer nada),
        // só por sorte do tempo virtual. `runCurrent()` só processa o que já está pronto agora, sem pular à frente —
        // é o que de fato obriga o toque adiantado a já valer (o canal conflado guarda o sinal até ser esperado).
        // A mão (de outro assento, um robô) já foi resolvida e liberada sozinha dentro do `advanceUntilIdle()` que
        // `humanDealerWithStartSwapGame`/`newGame` já rodaram: chegamos direto no palpite do morto, do humano.
        val vm = humanDealerWithStartSwapGame()
        val beforeMorto = vm.uiState.value
        beforeMorto.dealGuessPrompt.shouldNotBeNull()
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)

        vm.onGuessDealNumber(mortoTarget)
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
        vm.onGuessDealNumber(dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()
        vm.uiState.value.dealPending shouldBe false

        // §3.6 o morto agora é sempre outro assento (um robô): o mesmo `runCurrent()` do toque já libera
        // `guessRevealDone` (nenhum toque esperado dele) e deixa o robô responder pelo morto sem nenhum delay real
        // de por meio — é aqui que a distribuição de verdade seria animada: a transição real pra 1ª jogada, saindo
        // da fase de palpite, tudo dentro deste mesmo `runCurrent()`.
        vm.onDealGuessRevealDismissed()
        runCurrent()
        vm.uiState.value.dealPending shouldBe true

        vm.onDealAnimationFinished()
        runCurrent()
        vm.uiState.value.dealPending shouldBe false
    }

    /**
     * Uma semente em duplas em que o parceiro (assento 2, sempre `PARTNER` do ponto de vista do assento 0) é quem
     * palpita [stage]. `dealGuess.dealerSeat`/`.mortoDealerSeat` são fixados na distribuição ([GameController.init],
     * síncrono) — dá pra checar direto no 1º snapshot, antes de rodar qualquer coisa do relógio virtual (sem isso,
     * `phase`/`canDraw` abaixo não provariam nada: ver a prova de não travar nos testes). Em duplas (4 assentos,
     * `GameMode.previousSeat`), `dealerSeat == Seat(2)` sempre dá `mortoDealerSeat == Seat(1)`, e
     * `mortoDealerSeat == Seat(2)` sempre dá `dealerSeat == Seat(3)` e `firstSeat == Seat(0)` — nos dois casos o
     * OUTRO papel nunca cai no assento 0 (humano), então um único `runCurrent()` resolve a cadeia inteira (o outro
     * palpite e, no caso do morto, a 1ª jogada de verdade) sem travar esperando toque humano nenhum.
     */
    private fun TestScope.partnerGuessReactionGame(stage: DealGuessStage, animationMillis: Long = 0): GameViewModel {
        for (seed in 1L..300L) {
            val vm = GameViewModel(
                GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
                gameSeed = seed, computeDispatcher = dispatcher, botDelayMillis = 0,
                swapAnimationMillis = 0, animationMillis = animationMillis,
            )
            val view = vm.uiState.value.snapshot.view
            val guess = view.dealGuess
            val seat = if (stage == DealGuessStage.HAND) guess?.dealerSeat else guess?.mortoDealerSeat
            // Sem 3 vermelho na mão do humano: evita a troca do início da vez mascarar `canDraw` na prova de não
            // travar (o caso do morto termina com o próprio assento 0 começando a jogar).
            if (seat == Seat(2) && view.hand.none { it.isRedThree }) {
                runCurrent()
                return vm
            }
        }
        error("Nenhuma semente com o parceiro (assento 2) palpitando $stage em 300 tentativas")
    }

    /**
     * §3.6, telas-app.md 3.8.2 (14/11/2026): quando é o PARCEIRO (duplas) quem palpita, este aparelho (que só está
     * acompanhando) nunca mostra a reação em tela cheia — só a reação pequena, não bloqueante, no canto da mesa. A
     * prova de que o motor foi liberado na hora (`guessRevealDone`, sem esperar nenhum `onDealGuessRevealDismissed`
     * que não existe pra esse caso): o morto, perguntado a outro assento (sempre `Seat(1)`, nunca o humano) só
     * depois da mão resolver, já aparece resolvido no mesmo `runCurrent()` — se a reação do parceiro tivesse ficado
     * bloqueante (regressão para o comportamento antigo da tela cheia), o motor nunca teria perguntado o morto.
     *
     * §3.6 pedido do usuário (14/11/2026): o cartão ganhou um parágrafo fixo com os números de verdade — o alvo
     * público da regra (`dealGuessHandTarget`, nunca segredo) e a largura que o parceiro realmente escolheu
     * (`guess.width`, do motor). `PartnerGuessReaction.target`/`.guessedWidth` guardam esses dois valores.
     */
    @Test
    fun `§3_6 o palpite da mao do parceiro mostra so a reacao pequena e libera o motor sem esperar toque`() = runTest(dispatcher) {
        val vm = partnerGuessReactionGame(DealGuessStage.HAND)
        val state = vm.uiState.value
        // Tela cheia é exclusiva de quem palpitou de verdade (role == YOU); o parceiro nunca a recebe.
        state.dealGuessReveal.shouldBeNull()
        val reaction = state.partnerGuessReaction.shouldNotBeNull()
        reaction.stage shouldBe DealGuessStage.HAND
        reaction.correct shouldBe state.snapshot.view.dealGuess?.hand?.correct
        reaction.target shouldBe dealGuessHandTarget(state.snapshot.view.mode, state.snapshot.view.rules)
        reaction.guessedWidth shouldBe state.snapshot.view.dealGuess?.hand?.width

        state.snapshot.view.dealGuess?.morto.shouldNotBeNull()

        // E a reação pequena some sozinha, sem exigir nada do jogador.
        advanceUntilIdle()
        vm.uiState.value.partnerGuessReaction.shouldBeNull()
    }

    /**
     * Mesma prova do teste acima, para o palpite do morto: `mortoDealerSeat == Seat(2)` sempre dá `firstSeat ==
     * Seat(0)` (o humano abre a partida), então `canDraw` só fica `true` se o motor já tiver avançado da fase de
     * palpite pra 1ª jogada de verdade — o que só acontece se `guessRevealDone` já tiver sido chamado pelo palpite
     * do parceiro, sem esperar nenhum toque.
     *
     * §3.6 pedido do usuário (14/11/2026): mesma prova de `target`/`guessedWidth` do teste da mão, aqui para o
     * morto (`dealGuessMortoTarget`, que não depende do modo).
     */
    @Test
    fun `§3_6 o palpite do morto do parceiro tambem libera o motor sem esperar toque`() = runTest(dispatcher) {
        val vm = partnerGuessReactionGame(DealGuessStage.MORTO)
        val state = vm.uiState.value
        state.dealGuessReveal.shouldBeNull()
        val reaction = state.partnerGuessReaction.shouldNotBeNull()
        reaction.stage shouldBe DealGuessStage.MORTO
        reaction.correct shouldBe state.snapshot.view.dealGuess?.morto?.correct
        reaction.target shouldBe dealGuessMortoTarget(state.snapshot.view.rules)
        reaction.guessedWidth shouldBe state.snapshot.view.dealGuess?.morto?.width

        state.canDraw shouldBe true

        advanceUntilIdle()
        vm.uiState.value.partnerGuessReaction.shouldBeNull()
    }

    /**
     * §3.6 relato 2026-11-14: a hipótese era que o atraso de 1,5 s do "Prepare-se!" na tela ([GameUiState.opening])
     * e o atraso igual armado dentro do [GameController] (antes do 1º palpite da 1ª partida) contam a partir de
     * instantes diferentes — o do controlador começando bem antes do da tela, de modo que o palpite do parceiro já
     * teria resolvido (e a reação pequena já teria sumido sozinha) atrás da cortina, antes dela sumir. Em
     * [GameViewModel], os dois atrasos são armados no MESMO `init` (`opening` logo depois de `controller.run()` ser
     * ligado), então os dois relógios começam juntos — e o laço do controlador só decide a vez de quem palpita
     * depois de alguns passos síncronos mais a pausa real entre jogadas de robô ([GameController.DEFAULT_BOT_DELAY_MILLIS],
     * nunca zero fora dos testes), o que empurra qualquer palpite de robô para depois do fim da cortina, nunca
     * antes. Este teste usa `forcePartnerDeals = true` (como `AppNavHost.GameDestination` liga para todo jogo novo
     * local, ajustado em 14/11/2026 pra sempre cair no parceiro, não mais no próprio humano, e assim testar mais
     * rápido a reação pequena dele): em duplas isso faz o PARCEIRO (assento 2) ser sempre `dealGuess.dealerSeat`, a
     * mão nunca sendo palpitada pelo humano — e prova a invariante de forma determinística, sem precisar buscar
     * semente: enquanto `opening` ainda está `true`, a reação do parceiro nunca aparece — ela só nasce depois, e
     * fica à vista o tempo inteiro ([GameViewModel.PARTNER_GUESS_REACTION_MILLIS]) sem a cortina por cima.
     */
    @Test
    fun `§3_6 a reacao do parceiro so nasce depois do Prepare-se, nunca escondida atras dele`() = runTest(dispatcher) {
        val vm = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
            gameSeed = 42L,
            computeDispatcher = dispatcher,
            botDelayMillis = GameController.DEFAULT_BOT_DELAY_MILLIS,
            swapAnimationMillis = 300,
            animationMillis = 450,
            forcePartnerDeals = true,
        )
        // Com `forcePartnerDeals`, o parceiro (assento 2) é sempre quem palpita a mão (§3.6, `dealGuessSeats`): o
        // humano (assento 0) não palpita nada nesta partida (nem a mão, nem o morto, que cai num adversário) —
        // por isso a prova é inteiramente sobre `partnerGuessReaction`, e `dealGuessPrompt` nunca deve aparecer.
        var openingWasTrueWhileReacting = false
        var sawHumanPrompt = false
        var sawPartnerReaction = false
        var sawReactionDisappear = false

        repeat(80) {
            advanceTimeBy(100)
            runCurrent()
            val state = vm.uiState.value
            if (state.dealGuessPrompt != null) sawHumanPrompt = true
            if (state.partnerGuessReaction != null) {
                sawPartnerReaction = true
                if (state.opening) openingWasTrueWhileReacting = true
            } else if (sawPartnerReaction) {
                sawReactionDisappear = true
            }
        }
        sawHumanPrompt.shouldBeFalse()
        sawPartnerReaction.shouldBeTrue()
        sawReactionDisappear.shouldBeTrue()
        openingWasTrueWhileReacting.shouldBeFalse()
    }

    /**
     * Mesma invariante do teste acima, mas buscando por semente (sem `forcePartnerDeals`, como numa partida depois
     * da 1ª do jogo, em que `firstSeat` não é mais forçado) um caso em que o parceiro (assento 2) é mesmo quem
     * palpita — a mão ou o morto, o que a semente der. Prova que
     * `partnerGuessReaction` nunca aparece enquanto `opening` ainda vale `true`, e que ela fica à vista o tempo
     * inteiro depois de nascer (nunca é apagada por um "catch-up" que a esconderia atrás do Prepare-se).
     */
    @Test
    fun `§3_6 a reacao pequena do parceiro nunca aparece atras do Prepare-se`() = runTest(dispatcher) {
        var seed: Long? = null
        for (candidate in 1L..500L) {
            val probe = GameViewModel(
                GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
                gameSeed = candidate, computeDispatcher = dispatcher, botDelayMillis = 0,
                swapAnimationMillis = 0, animationMillis = 0,
            )
            val guess = probe.uiState.value.snapshot.view.dealGuess
            if (guess != null && (guess.dealerSeat == Seat(2) || guess.mortoDealerSeat == Seat(2)) &&
                guess.dealerSeat != Seat(0) && guess.mortoDealerSeat != Seat(0)
            ) {
                seed = candidate
                break
            }
        }
        val chosenSeed = seed ?: error("Nenhuma semente com o parceiro (assento 2) palpitando, sem o humano, em 500 tentativas")

        val vm = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
            gameSeed = chosenSeed,
            computeDispatcher = dispatcher,
            botDelayMillis = GameController.DEFAULT_BOT_DELAY_MILLIS,
            swapAnimationMillis = 300,
            animationMillis = 450,
            forcePartnerDeals = false,
        )
        var openingWasTrueWhileReacting = false
        var sawPartnerReaction = false
        var sawReactionDisappear = false
        repeat(120) {
            advanceTimeBy(100)
            runCurrent()
            val state = vm.uiState.value
            if (state.partnerGuessReaction != null) {
                sawPartnerReaction = true
                if (state.opening) openingWasTrueWhileReacting = true
            } else if (sawPartnerReaction) {
                sawReactionDisappear = true
            }
        }
        sawPartnerReaction.shouldBeTrue()
        sawReactionDisappear.shouldBeTrue()
        openingWasTrueWhileReacting.shouldBeFalse()
    }
}
