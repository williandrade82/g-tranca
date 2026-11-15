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

    /**
     * §3.6 (ajuste de 15/11/2026, reação própria não bloqueante): o motor é liberado **na hora**, para qualquer
     * papel — não existe mais um toque que "fecha" a reação e só então libera o morto. Por isso, dentro do MESMO
     * `runCurrent()` do palpite da mão, o morto (sempre outro assento, um robô) já resolve sozinho e a mesa já
     * avança até a 1ª jogada de verdade (`Phase.AWAITING_DRAW`). O monte continua escondido
     * ([GameUiState.isDealGuessing]) só enquanto a reação pequena do próprio jogador ([GameUiState.ownGuessReaction])
     * ainda está à vista, por [GameViewModel.OWN_GUESS_REACTION_MILLIS] (1,5 s) — sem esperar toque nenhum.
     */
    @Test
    fun `§3_6 §12_1 palpite certo da mao mostra a reacao propria e libera o morto no mesmo runCurrent`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val rules = vm.uiState.value.snapshot.view.rules
        val mode = vm.uiState.value.snapshot.view.mode
        val target = dealGuessHandTarget(mode, rules)

        vm.onGuessDealNumber(target)
        runCurrent()

        val afterGuess = vm.uiState.value
        // §3.6 o palpite nunca é jogada da vez: não aparece no registro do próprio assento.
        afterGuess.snapshot.view.dealGuess?.hand?.correct shouldBe true
        val reaction = afterGuess.ownGuessReaction.shouldNotBeNull()
        reaction.correct shouldBe true
        reaction.own shouldBe true
        reaction.stage shouldBe DealGuessStage.HAND
        afterGuess.dealGuessPills[0]?.correct shouldBe true
        afterGuess.dealGuessPrompt.shouldBeNull()
        // O morto (outro assento, um robô) já resolveu sozinho, sem nenhum toque deste aparelho.
        afterGuess.snapshot.view.dealGuess?.morto.shouldNotBeNull()
        afterGuess.snapshot.view.phase shouldBe Phase.AWAITING_DRAW
        // O monte continua escondido enquanto a reação própria (1,5 s) ainda está à vista.
        afterGuess.isDealGuessing shouldBe true

        advanceTimeBy(GameViewModel.OWN_GUESS_REACTION_MILLIS)
        runCurrent()

        val afterReaction = vm.uiState.value
        afterReaction.ownGuessReaction.shouldBeNull()
        afterReaction.isDealGuessing shouldBe false
        afterReaction.dealGuessPrompt.shouldBeNull()
    }

    /**
     * §3.6 (ajuste de 15/11/2026): o motor já avança para a 1ª jogada de verdade na hora (sem esperar toque); o
     * monte só reaparece quando a reação pequena do próprio jogador ([GameUiState.ownGuessReaction]) some sozinha,
     * depois de [GameViewModel.OWN_GUESS_REACTION_MILLIS] (1,5 s) — nunca por um toque explícito.
     */
    @Test
    fun `§3_6 §12_1 palpite errado do morto mostra a reacao propria e some sozinha apos 1,5s`() = runTest(dispatcher) {
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
        runCurrent()

        val afterGuess = vm.uiState.value
        val reaction = afterGuess.ownGuessReaction.shouldNotBeNull()
        reaction.correct shouldBe false
        reaction.own shouldBe true
        reaction.stage shouldBe DealGuessStage.MORTO
        afterGuess.dealGuessPills[0]?.correct shouldBe false
        // A 1ª jogada de verdade já começou no motor, mas o monte continua escondido até a reação própria terminar.
        afterGuess.isDealGuessing shouldBe true
        afterGuess.snapshot.view.phase shouldBe Phase.AWAITING_DRAW

        advanceTimeBy(GameViewModel.OWN_GUESS_REACTION_MILLIS)
        runCurrent()

        val afterReaction = vm.uiState.value
        afterReaction.ownGuessReaction.shouldBeNull()
        afterReaction.isDealGuessing shouldBe false
        afterReaction.dealGuessPrompt.shouldBeNull()
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

    /** §3.6 a reação de erro mostra o número que o PRÓPRIO jogador tocou (`DealGuessSideReaction.guessedWidth`). */
    @Test
    fun `§3_6 a reacao de erro mostra o numero que o proprio jogador tocou de verdade`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        val range = dealGuessHandRange(view0.mode)
        val target = dealGuessHandTarget(view0.mode, view0.rules)
        val wrongNumber = if (target + 1 <= range.last) target + 1 else target - 1

        vm.onGuessDealNumber(wrongNumber)
        runCurrent()

        val reaction = vm.uiState.value.ownGuessReaction.shouldNotBeNull()
        reaction.correct shouldBe false
        reaction.own shouldBe true
        reaction.guessedWidth shouldBe wrongNumber
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
     * BUG relatado 2026-11-14 (contexto histórico): o palpite do morto e a troca do 3 vermelho do início da vez do
     * próprio dealer (§3.5, `RoundEngine.beginTurn` roda dentro do `GuessDealRange` do morto, `RoundEngine.kt`
     * linha ~220) resolvem na MESMA ação: antes do ajuste de 15/11/2026 (reação própria não bloqueante), a tela
     * mostrava uma reação em tela cheia que só fechava no toque, e o jogador podia fechá-la antes de confirmar a
     * troca — corrida que dependia da ORDEM de dois toques. Essa corrida não existe mais: [GameViewModel.reactToGuess]
     * chama `controller.guessRevealDone` **sincronamente**, ao processar o snapshot, sempre antes de qualquer
     * confirmação de troca (nenhum toque envolvido) — este teste agora prova a invariante que sobrou: os dois
     * avisos (reação própria e "Baixar 3 vermelho") podem chegar juntos sem travar o jogo, em qualquer ordem que a
     * tela os resolva.
     */
    @Test
    fun `§3_6 §3_5 reacao propria e 3 vermelho do inicio da vez chegando juntos nao travam o jogo`() = runTest(dispatcher) {
        // A mão (de outro assento, um robô) já foi resolvida e liberada sozinha dentro do `advanceUntilIdle()` que
        // `humanDealerWithStartSwapGame`/`newGame` já rodaram: chegamos direto no palpite do morto, do humano.
        val vm = humanDealerWithStartSwapGame()
        val beforeMorto = vm.uiState.value
        beforeMorto.dealGuessPrompt.shouldNotBeNull()
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)

        vm.onGuessDealNumber(mortoTarget)
        runCurrent()

        // Os dois avisos chegam juntos: a reação própria do palpite e "Baixar 3 vermelho" do início da vez — e o
        // motor já foi reconhecido na hora (sem esperar a troca ser confirmada).
        vm.uiState.value.ownGuessReaction.shouldNotBeNull()
        vm.uiState.value.ownSwapPending shouldBe true

        // Pode haver mais de um 3 vermelho na mão inicial (cada um é uma troca própria em `state.swaps`,
        // confirmada uma por vez em "Baixar 3 vermelho").
        repeat(20) {
            if (!vm.uiState.value.ownSwapPending) return@repeat
            vm.onRevealConfirmed()
            runCurrent()
        }
        vm.uiState.value.ownSwapPending shouldBe false
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
     *
     * Ajuste de 15/11/2026: como o motor agora é liberado na hora (sem toque), o morto (outro assento, um robô) já
     * resolve dentro do MESMO `runCurrent()` do palpite da mão — por isso a transição real pra 1ª jogada acontece
     * nesse `runCurrent()`, e não mais num passo seguinte, explícito, de dismiss.
     */
    @Test
    fun `§3_5 dealPending so fica verdadeiro na transicao real pra 1a jogada, nao durante os palpites`() = runTest(dispatcher) {
        val vm = humanDealerGame(animationMillis = 50)
        vm.uiState.value.dealPending shouldBe false

        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealNumber(dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()
        // §3.6 o morto (outro assento, um robô) já resolveu dentro deste mesmo `runCurrent()`, saindo de vez da
        // fase de palpite: é aqui que a distribuição de verdade seria animada.
        vm.uiState.value.dealPending shouldBe true

        vm.onDealAnimationFinished()
        runCurrent()
        vm.uiState.value.dealPending shouldBe false
    }

    /**
     * §3.8.1/§3.8.2 (15/11/2026, pedido do usuário, só apresentação local): enquanto `dealPending` é verdadeiro, a
     * decisão que `CardFlow` usa para cobrir a própria mão ([ownHandCover], `TableAnimations.kt`) é
     * [OwnHandCover.DEALING] — as cartas já pousadas aparecem de costas; depois que a distribuição termina
     * (`onDealAnimationFinished`), a mesma decisão volta a [OwnHandCover.NONE] (face normal), sozinha, sem toque.
     */
    @Test
    fun `§3_8_1 §3_8_2 enquanto dealPending for verdadeiro a propria mao fica de costas, depois volta sozinha`() = runTest(dispatcher) {
        val vm = humanDealerGame(animationMillis = 50)
        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealNumber(dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()

        val dealing = vm.uiState.value
        dealing.dealPending shouldBe true
        ownHandCover(dealing.dealPending, dealing.handHidden) shouldBe OwnHandCover.DEALING

        vm.onDealAnimationFinished()
        runCurrent()

        val done = vm.uiState.value
        done.dealPending shouldBe false
        ownHandCover(done.dealPending, done.handHidden) shouldBe OwnHandCover.NONE
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
        // A reação própria é exclusiva de quem palpitou de verdade (role == YOU); o parceiro nunca a recebe.
        state.ownGuessReaction.shouldBeNull()
        val reaction = state.partnerGuessReaction.shouldNotBeNull()
        reaction.own shouldBe false
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
        state.ownGuessReaction.shouldBeNull()
        val reaction = state.partnerGuessReaction.shouldNotBeNull()
        reaction.own shouldBe false
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
