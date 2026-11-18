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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
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
     * §3.6 (ajuste de 15/11/2026, reação própria não bloqueante; 17/11/2026, selo dentro do modal): o motor é
     * liberado **na hora**, para qualquer papel — não existe mais um toque que "fecha" a reação e só então libera o
     * morto. Por isso, dentro do MESMO `runCurrent()` do palpite da mão, o morto (sempre outro assento, um robô) já
     * resolve sozinho e a mesa já avança até a 1ª jogada de verdade (`Phase.AWAITING_DRAW`). O monte continua
     * escondido ([GameUiState.isDealGuessing]) durante DUAS pausas em sequência: primeiro o selo dentro do modal
     * ([GameUiState.dealGuessModalResult], [GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS]), só depois a reação
     * pequena na mesa ([GameUiState.ownGuessReaction], [GameViewModel.OWN_GUESS_REACTION_MILLIS]) — sem esperar
     * toque nenhum em nenhuma das duas.
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
        // Fase 1: o modal continua aberto, com o selo — a reação na mesa ainda não começou.
        val modalResult = afterGuess.dealGuessModalResult.shouldNotBeNull()
        modalResult.correct shouldBe true
        modalResult.own shouldBe true
        modalResult.stage shouldBe DealGuessStage.HAND
        afterGuess.ownGuessReaction.shouldBeNull()
        afterGuess.dealGuessPills[0]?.correct shouldBe true
        afterGuess.dealGuessPrompt.shouldNotBeNull() // o modal (com o selo) ainda é o que a tela mostra.
        // O morto (outro assento, um robô) já resolveu sozinho, sem nenhum toque deste aparelho.
        afterGuess.snapshot.view.dealGuess?.morto.shouldNotBeNull()
        afterGuess.snapshot.view.phase shouldBe Phase.AWAITING_DRAW
        // O monte continua escondido enquanto o selo do modal ainda está à vista.
        afterGuess.isDealGuessing shouldBe true

        advanceTimeBy(GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS)
        runCurrent()

        // Fase 2: o modal fechou e a reação própria na mesa começou.
        val afterModal = vm.uiState.value
        afterModal.dealGuessModalResult.shouldBeNull()
        afterModal.dealGuessPrompt.shouldBeNull()
        val reaction = afterModal.ownGuessReaction.shouldNotBeNull()
        reaction.correct shouldBe true
        reaction.own shouldBe true
        reaction.stage shouldBe DealGuessStage.HAND
        afterModal.isDealGuessing shouldBe true

        advanceTimeBy(GameViewModel.OWN_GUESS_REACTION_MILLIS)
        runCurrent()

        val afterReaction = vm.uiState.value
        afterReaction.ownGuessReaction.shouldBeNull()
        afterReaction.isDealGuessing shouldBe false
        afterReaction.dealGuessPrompt.shouldBeNull()
    }

    /**
     * §3.6 (ajuste de 15/11/2026; 17/11/2026, selo dentro do modal): o motor já avança para a 1ª jogada de verdade
     * na hora (sem esperar toque); o monte só reaparece depois das DUAS pausas em sequência — primeiro o selo
     * dentro do modal ([GameUiState.dealGuessModalResult], [GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS]), depois
     * a reação pequena na mesa ([GameUiState.ownGuessReaction], [GameViewModel.OWN_GUESS_REACTION_MILLIS]) — nunca
     * por um toque explícito em nenhuma das duas.
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
        // Fase 1: o selo dentro do modal, com o erro — a reação na mesa ainda não começou.
        val modalResult = afterGuess.dealGuessModalResult.shouldNotBeNull()
        modalResult.correct shouldBe false
        modalResult.own shouldBe true
        modalResult.stage shouldBe DealGuessStage.MORTO
        afterGuess.ownGuessReaction.shouldBeNull()
        afterGuess.dealGuessPills[0]?.correct shouldBe false
        // A 1ª jogada de verdade já começou no motor, mas o monte continua escondido até o selo do modal terminar.
        afterGuess.isDealGuessing shouldBe true
        afterGuess.snapshot.view.phase shouldBe Phase.AWAITING_DRAW
        // §3.6 BUG em potencial: a antiga tela cheia consumia qualquer toque por baixo; nem a reação pequena nem o
        // selo do modal cobrem a tela por conta própria, então sem gating explícito `canDraw` ficaria `true` (e
        // tocável) com o monte ainda escondido — em individual, `mortoDealerSeat` é sempre quem abre a partida
        // (`firstSeat`), então é exatamente este caso (humano palpitando o morto) que exporia o vazamento.
        afterGuess.canDraw shouldBe false

        advanceTimeBy(GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS)
        runCurrent()

        // Fase 2: o modal fechou, a reação própria na mesa começou, e `canDraw` continua bloqueado.
        val afterModal = vm.uiState.value
        afterModal.dealGuessModalResult.shouldBeNull()
        afterModal.ownGuessReaction.shouldNotBeNull()
        afterModal.canDraw shouldBe false

        advanceTimeBy(GameViewModel.OWN_GUESS_REACTION_MILLIS)
        runCurrent()

        val afterReaction = vm.uiState.value
        afterReaction.ownGuessReaction.shouldBeNull()
        afterReaction.isDealGuessing shouldBe false
        afterReaction.dealGuessPrompt.shouldBeNull()
        // Sem conferir `canDraw` aqui: `humanMortoDealerGame` não evita uma troca de 3 vermelho do início da vez
        // (§3.5) na mesma semente, que também bloquearia `legal` por `ownSwapPending` (um motivo diferente, nada a
        // ver com a reação própria) — confundiria esta prova, que é só sobre o timer dela. O vazamento de
        // `canDraw` durante a janela (o que importa corrigir) já está provado acima (`afterGuess.canDraw shouldBe
        // false`); a prova de que ele some depois de liberado está na corrida com o 3 vermelho, abaixo.
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

        // Primeiro mostra no selo dentro do modal, com o número de verdade.
        val modalResult = vm.uiState.value.dealGuessModalResult.shouldNotBeNull()
        modalResult.correct shouldBe false
        modalResult.own shouldBe true
        modalResult.guessedWidth shouldBe wrongNumber

        advanceTimeBy(GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS)
        runCurrent()

        // E depois na reação pequena da mesa, com o mesmo número.
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

        // Os dois avisos chegam juntos: o selo do modal (ainda não a reação na mesa, §3.6 17/11/2026) e "Baixar 3
        // vermelho" do início da vez — e o motor já foi reconhecido na hora (sem esperar a troca ser confirmada).
        vm.uiState.value.dealGuessModalResult.shouldNotBeNull()
        vm.uiState.value.ownSwapPending shouldBe true

        // Pode haver mais de um 3 vermelho na mão inicial (cada um é uma troca própria em `state.swaps`,
        // confirmada uma por vez em "Baixar 3 vermelho").
        repeat(20) {
            if (!vm.uiState.value.ownSwapPending) return@repeat
            vm.onRevealConfirmed()
            runCurrent()
        }
        vm.uiState.value.ownSwapPending shouldBe false
        // §3.6 `canDraw` continua bloqueado enquanto o selo do modal e, depois, a reação própria na mesa (duas
        // pausas de 1,5 s em sequência) ainda estão à vista — mesmo gate que evita o vazamento provado em "palpite
        // errado do morto mostra a reacao propria..." (acima); só depois das duas é que o monte (e `canDraw`)
        // voltam a valer.
        vm.uiState.value.canDraw shouldBe false
        advanceTimeBy(GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS)
        runCurrent()
        vm.uiState.value.dealGuessModalResult.shouldBeNull()
        vm.uiState.value.ownGuessReaction.shouldNotBeNull()
        vm.uiState.value.canDraw shouldBe false
        advanceTimeBy(GameViewModel.OWN_GUESS_REACTION_MILLIS)
        runCurrent()
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
     * antes. Busca por semente um caso em que o parceiro (assento 2) é quem palpita — a mão ou o morto, o que a
     * semente der — e prova que `partnerGuessReaction` nunca aparece enquanto `opening` ainda vale `true`, e que
     * ela fica à vista o tempo inteiro depois de nascer ([GameViewModel.PARTNER_GUESS_REACTION_MILLIS]), nunca
     * apagada por um "catch-up" que a esconderia atrás do Prepare-se.
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

    /**
     * §3.6 (15/11/2026, pedido do usuário) os selos permanentes da pílula (`DealGuessBadges`, `TableScreen.kt`) são
     * derivados direto de `view.dealGuess` ([TableAnimations.dealGuessBadges]) — ao contrário da pílula/reação
     * transitória (`dealGuessPills`/`ownGuessReaction`/`partnerGuessReaction`, que somem em poucos segundos),
     * `dealGuess` continua presente bem depois de todas elas sumirem sozinhas, e também depois de uma jogada de
     * verdade (a `view.dealGuess` só é reiniciado numa partida nova, `RoundState`, não a cada ação). O reset entre
     * partidas em si é responsabilidade do motor (`:core-engine`, `MatchTest`/`MatchSimulationPropertyTest`): não é
     * duplicado aqui.
     */
    @Test
    fun `§3_6 view_dealGuess (selos permanentes) continua presente apos as reacoes transitorias sumirem e apos jogar`() = runTest(dispatcher) {
        val vm = humanDealerGame()
        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealNumber(dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()

        // Adianta alem de TODAS as reacoes transitorias (pilula, selo do modal, reacao propria e do parceiro) —
        // nenhuma delas precisa estar viva para os selos permanentes continuarem corretos. A do próprio jogador
        // (modal + reação) é a soma das DUAS pausas em sequência (17/11/2026), não só uma.
        val pastAllTransientReactions = maxOf(
            GameViewModel.DEAL_GUESS_PILL_MILLIS,
            GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS + GameViewModel.OWN_GUESS_REACTION_MILLIS,
            GameViewModel.PARTNER_GUESS_REACTION_MILLIS,
        ) + 500
        advanceTimeBy(pastAllTransientReactions)
        runCurrent()

        val settled = vm.uiState.value
        settled.dealGuessPills.shouldBeEmpty()
        settled.dealGuessModalResult.shouldBeNull()
        settled.ownGuessReaction.shouldBeNull()
        settled.partnerGuessReaction.shouldBeNull()
        val dealGuess = settled.snapshot.view.dealGuess.shouldNotBeNull()
        dealGuess.hand.shouldNotBeNull()
        dealGuess.morto.shouldNotBeNull()
        dealGuessBadges(dealGuess, dealGuess.dealerSeat) shouldBe listOf(DealGuessStage.HAND to dealGuess.hand!!.correct)
        dealGuessBadges(dealGuess, dealGuess.mortoDealerSeat) shouldBe listOf(DealGuessStage.MORTO to dealGuess.morto!!.correct)

        // Uma jogada de verdade (compra) não reinicia nem apaga o palpite já resolvido desta partida.
        if (settled.canDraw) {
            vm.onDraw()
            runCurrent()
            vm.uiState.value.snapshot.view.dealGuess.shouldNotBeNull()
            vm.uiState.value.snapshot.view.dealGuess?.hand.shouldNotBeNull()
            vm.uiState.value.snapshot.view.dealGuess?.morto.shouldNotBeNull()
        }
    }

    /**
     * §3.6 BUG relatado 17/11/2026 (confirmado ao vivo no emulador): os voos de verdade da distribuição (R12,
     * `AnimAnchor.DealEntry`) nasciam no mesmo instante em que o palpite do PRÓPRIO jogador resolvia — `absorb`
     * calculava `dealFlights` a partir de `leavingGuessPhases`, sem olhar para a reação própria (armada só DEPOIS,
     * em `reactToGuess`, no mesmo `collect`) — então a distribuição de verdade voava por cima da reação pequena
     * desde o primeiro quadro, em vez de esperar os 1,5 s dela (telas-app.md 3.8.2: "antes de qualquer carta do
     * monte aparecer na tela"/"é também o tempo em que o monte continua escondido"). Corrigido com a mesma técnica
     * já usada para `GameUiState.opening` (atraso em `CardFlight.delayMillis`, nunca escondendo o voo da lista).
     * Ampliado em 17/11/2026 (pedido do usuário) com o selo dentro do próprio modal
     * ([GameUiState.dealGuessModalResult], antes mesmo da reação na mesa): aqui provamos que todo voo de
     * `AnimAnchor.DealEntry` nasce com `delayMillis >= DEAL_GUESS_MODAL_RESULT_MILLIS + OWN_GUESS_REACTION_MILLIS`
     * (as DUAS pausas em sequência) quando quem acabou de resolver o palpite é o PRÓPRIO assento do visualizador
     * (mão ou morto).
     */
    @Test
    fun `§3_6 os voos da distribuicao esperam a reacao propria antes de comecar, no palpite da mao`() = runTest(dispatcher) {
        val vm = humanDealerGame(animationMillis = 50)
        val view0 = vm.uiState.value.snapshot.view
        vm.onGuessDealNumber(dealGuessHandTarget(view0.mode, view0.rules))
        runCurrent()

        vm.uiState.value.dealGuessModalResult.shouldNotBeNull()
        val dealFlights = vm.uiState.value.flights.filter { it.from is AnimAnchor.DealEntry }
        dealFlights.shouldNotBeEmpty()
        val minExpected = GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS + GameViewModel.OWN_GUESS_REACTION_MILLIS
        dealFlights.forEach { it.delayMillis shouldBeGreaterThanOrEqual minExpected }
    }

    /** Mesma prova do teste acima, para quem palpita o morto (e, em individual, também abre a partida). */
    @Test
    fun `§3_6 os voos da distribuicao esperam a reacao propria antes de comecar, no palpite do morto`() = runTest(dispatcher) {
        val vm = humanMortoDealerGame(animationMillis = 50)
        val beforeMorto = vm.uiState.value
        val mortoTarget = dealGuessMortoTarget(beforeMorto.snapshot.view.rules)
        vm.onGuessDealNumber(mortoTarget)
        runCurrent()

        vm.uiState.value.dealGuessModalResult.shouldNotBeNull()
        val dealFlights = vm.uiState.value.flights.filter { it.from is AnimAnchor.DealEntry }
        dealFlights.shouldNotBeEmpty()
        val minExpected = GameViewModel.DEAL_GUESS_MODAL_RESULT_MILLIS + GameViewModel.OWN_GUESS_REACTION_MILLIS
        dealFlights.forEach { it.delayMillis shouldBeGreaterThanOrEqual minExpected }
    }

    /**
     * Contraprova: quando é o PARCEIRO (duplas) quem acabou de resolver o palpite (nunca o próprio assento), os
     * voos da distribuição NÃO esperam nada — a reação do parceiro nunca bloqueia (telas-app.md 3.8.2: "não bloqueia
     * nada"), só a do próprio jogador hospeda esse atraso.
     */
    @Test
    fun `§3_6 os voos da distribuicao NAO esperam a reacao do parceiro`() = runTest(dispatcher) {
        // Acha a semente direto no motor (`:core-engine`, sem nenhum `GameViewModel`/`GameController` no caminho da
        // busca): mesma conta de `GameController` (`startMatch(mode, alvo, Random(gameSeed), regras)`), bem mais
        // barata que construir e descartar até 300 `GameViewModel` no mesmo `TestScope`.
        var seed: Long? = null
        for (candidate in 1L..300L) {
            val match = com.gtranca.engine.startMatch(GameMode.DUPLAS, 3000, kotlin.random.Random(candidate))
            val guess = match.currentRound.dealGuess
            if (guess?.mortoDealerSeat == Seat(2) && match.currentRound.hands[0].none { it.isRedThree }) {
                seed = candidate
                break
            }
        }
        val chosenSeed = seed ?: error("Nenhuma semente com o parceiro (assento 2) distribuindo o morto em duplas em 300 tentativas")

        val vm = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
            gameSeed = chosenSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
            swapAnimationMillis = 0, animationMillis = 50,
        )
        // §3.6 a partida 1 espera `PREPARE_MILLIS` (o "Prepare-se!") antes mesmo do 1º palpite começar
        // (`GameController`, `dealDelayMillis = dealAnimationMillis(config)` quando `animationMillis > 0`) — um
        // `delay()` de verdade, que só passa com `advanceTimeBy`, nunca só `runCurrent()`.
        var waited = 0L
        while (vm.uiState.value.partnerGuessReaction == null && waited < 6_000L) {
            advanceTimeBy(100)
            runCurrent()
            waited += 100
        }

        vm.uiState.value.ownGuessReaction.shouldBeNull()
        vm.uiState.value.partnerGuessReaction.shouldNotBeNull()
        val dealFlights = vm.uiState.value.flights.filter { it.from is AnimAnchor.DealEntry }
        dealFlights.shouldNotBeEmpty()
        // `minOf`, não `forEach`: os voos são escalonados (um atraso crescente por carta), então uma carta mais
        // tardia pode já passar de [GameViewModel.OWN_GUESS_REACTION_MILLIS] por conta própria, sem nenhum atraso
        // extra desta correção — o que prova "não espera nada" é o PRIMEIRO voo, sem atraso nenhum somado.
        dealFlights.minOf { it.delayMillis } shouldBeLessThan GameViewModel.OWN_GUESS_REACTION_MILLIS
    }
}
