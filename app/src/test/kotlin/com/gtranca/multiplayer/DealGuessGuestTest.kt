package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.DealGuess
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Guess
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.dealGuessMortoTarget
import com.gtranca.game.GameConfig
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.TableDriver
import com.gtranca.net.ProtocolCodec
import com.gtranca.net.SeatView
import com.gtranca.net.TableRules
import com.gtranca.net.TableStage
import com.gtranca.net.ViewUpdate
import com.gtranca.ui.game.GameViewModel
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * §3.6, telas-app.md 3.8.2: o relato de 14/11/2026 (mesa multijogador local, 2 celulares) — quando o CONVIDADO se
 * torna `dealGuess.mortoDealerSeat`, numa partida depois da 1ª, o modal nunca aparecia (`GameViewModel.dealGuessPrompt`
 * sempre `null` na fase `DEAL_GUESS_MORTO`, porque `view.dealGuess` chegava sempre `null` por causa de `SeatView`, ver
 * `SeatViewTest` em `:net`), travando o jogo pra sempre na vez dele. Este teste prova que isso não trava mais: a vista
 * do convidado passa pelo mesmo caminho da rede de verdade (`SeatView.of` → `ProtocolCodec` → `toPlayerView` →
 * `SeatView.toSnapshot`, todos de `:net`/`MultiplayerDriver`), alimentando um [GameViewModel] real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealGuessGuestTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Como [FlightSequenceTest.FakeDriver] (`:app`, docs/telas.md 3.8.1), mas registra as jogadas enviadas. */
    private class FakeDriver(initial: GameSnapshot) : TableDriver {
        override val state = MutableStateFlow(initial)
        val submitted = mutableListOf<Action>()
        override fun awaitingHuman() = true
        override fun submit(action: Action, requestId: Long): Boolean {
            submitted += action
            return true
        }
        override fun explain(action: Action, requestId: Long): RuleError? = null
        override var dealDelayScale = 1.0
        override fun continueToNextRound() = Unit
        override fun resign() = Unit
        override suspend fun run() = awaitCancellation()
    }

    private val rules = RuleSet.DEFAULT
    private val tableRules = TableRules("Mesa de teste", GameMode.INDIVIDUAL, rules, 1000, 0, Difficulty.FACIL.id)

    /**
     * A vista do CONVIDADO (assento 1) na 2ª partida (`roundNumber = 2`, "depois da 1ª"), já como
     * `mortoDealerSeat` com o palpite da mão resolvido (feito por outro assento) — exatamente a vez em que o
     * bug travava para sempre. Passa pelo [SeatView] e pelo [ProtocolCodec] de verdade, como a mensagem que
     * chegaria pela rede (`ViewUpdate`, §16.4/§16.6), não um [PlayerView] construído à mão para a tela.
     */
    private fun guestSnapshotAtMortoGuess(): GameSnapshot {
        val mortoTarget = dealGuessMortoTarget(rules)
        val view = PlayerView(
            mode = GameMode.INDIVIDUAL, seat = Seat(1), side = Side(1), currentSeat = Seat(1), firstSeat = Seat(1),
            phase = Phase.DEAL_GUESS_MORTO, result = null, hand = emptyList(), handSizes = listOf(22, 22),
            discardPile = emptyList(), stockSize = 20,
            mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available), mortoSizes = listOf(11, 11),
            redThrees = List(2) { emptyList() }, tables = listOf(SideTable(), SideTable()), rules = rules,
            dealGuess = DealGuess(dealerSeat = Seat(0), mortoDealerSeat = Seat(1), hand = Guess(0, 22, correct = true)),
        )
        val seatView = SeatView.of(
            view = view, roundNumber = 2, stage = TableStage.PLAYING, totals = listOf(0, 0), winner = null,
            history = null, turnEvents = List(2) { emptyList() }, turnRedThrees = List(2) { emptyList() },
            thinkingSeat = null, legal = listOf(Action.GuessDealRange(0, mortoTarget)), requestId = 9,
        )
        // Ida e volta pela rede de verdade (§16.4/§16.6): é assim que a vista chega ao convidado, não um objeto em
        // memória compartilhado com o anfitrião.
        val bytes = ProtocolCodec().encode(1, ViewUpdate(seatView))
        val received = ((ProtocolCodec().decode(bytes) as com.gtranca.net.Decoded.Ok).envelope.p as ViewUpdate).view
        return received.toSnapshot(tableRules, answered = 0)
    }

    @Test
    fun `§3_6 o convidado como mortoDealerSeat numa partida apos a 1a ve o modal, nao trava`() = runTest(dispatcher) {
        val driver = FakeDriver(guestSnapshotAtMortoGuess())
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1000), gameSeed = 1, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            externalDriver = driver, multiplayer = true, continuing = true,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        state.snapshot.roundNumber shouldBe 2
        state.snapshot.view.phase shouldBe Phase.DEAL_GUESS_MORTO
        state.isHumanTurn shouldBe true
        // Antes da correção de §3.6 em `:net`, `dealGuess` chegava sempre `null` aqui e o modal nunca aparecia.
        val prompt = state.dealGuessPrompt.shouldNotBeNull()
        prompt.stage shouldBe com.gtranca.ui.game.DealGuessStage.MORTO
        prompt.target shouldBe dealGuessMortoTarget(rules)

        // E o jogador consegue mesmo agir: a tela envia o palpite ao condutor da mesa.
        vm.onGuessDealRange(0, prompt.target)
        advanceUntilIdle()
        driver.submitted shouldBe listOf(Action.GuessDealRange(0, prompt.target))
    }

    @Test
    fun `§3_6 sem o dealGuess (regressao) o prompt do morto ficaria nulo para sempre`() = runTest(dispatcher) {
        // Reproduz o bug relatado: a mesma vista, mas sem o campo (como antes da correção em :net), prova que o
        // `dealGuessPrompt` dependia mesmo dele (não é um falso-positivo do teste acima).
        val base = guestSnapshotAtMortoGuess()
        val broken = base.copy(view = base.view.copy(dealGuess = null))
        val driver = FakeDriver(broken)
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1000), gameSeed = 1, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            externalDriver = driver, multiplayer = true, continuing = true,
        )
        advanceUntilIdle()
        vm.uiState.value.isHumanTurn shouldBe true
        vm.uiState.value.dealGuessPrompt.shouldBeNull()
    }

    /** Vista mínima de duplas (pacer puro): [viewerSeat] só acompanha os palpites de [dealerSeat]/[mortoDealerSeat]. */
    private fun bystanderSnapshot(viewerSeat: Seat, round: Int, phase: Phase, dealGuess: DealGuess?): GameSnapshot {
        val view = PlayerView(
            mode = GameMode.DUPLAS, seat = viewerSeat, side = com.gtranca.engine.model.GameMode.DUPLAS.sideOf(viewerSeat),
            currentSeat = viewerSeat, firstSeat = viewerSeat, phase = phase, result = null, hand = emptyList(),
            handSizes = listOf(11, 11, 11, 11), discardPile = emptyList(), stockSize = 30,
            mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available), mortoSizes = listOf(11, 11),
            redThrees = List(2) { emptyList() }, tables = listOf(SideTable(), SideTable()), rules = RuleSet.DEFAULT,
            dealGuess = dealGuess,
        )
        return GameSnapshot(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000), viewerSeat, view, round, listOf(0, 0), emptyList(),
            Stage.PLAYING, null, emptyList(), null, List(4) { emptyList() },
        )
    }

    /**
     * §3.6 regressão achada em revisão (14/11/2026): o motor exclui `Action.GuessDealRange` de `turnEvents`
     * (`GameController.recordEvent`), então `SnapshotPacer.othersPlayed` nunca reconhecia o palpite de outro
     * assento como "alguém jogou" — sem pausa nenhuma antes de mostrá-lo, como qualquer outra jogada de robô
     * (telas-app.md 3.8.2: "mesma pausa de pensando"). Numa virada de partida, o `SnapshotPacer` publica, sem
     * suspender entre uma vista e outra, "partida nova, ainda sem palpite" seguido de "palpite já resolvido por um
     * robô" — e o `StateFlow` conflado que o [GameViewModel] observa (`TableDriver.state`) pode nunca mostrar a
     * 1ª vista para o convidado: só a seguinte, já resolvida. Sem "antes" (`dealGuess.hand == null`) para comparar,
     * `handleDealGuessTransition` nunca detecta a transição, e a pílula do robô nunca aparece — o sintoma relatado
     * (ver `docs/plano-distribuicao-certeira.md`, item revertido do portão no pacer). Corrigido estendendo
     * `SnapshotPacer.othersPlayed` para também pausar quando um palpite resolvido é de outro assento (não o
     * `viewerSeat`): a pausa (`paceMillis`, a mesma de qualquer jogada de robô) garante que a vista "ainda sem
     * palpite" fica visível tempo suficiente para o coletor da VM processá-la antes da seguinte.
     */
    @Test
    fun `§3_6 pacer de verdade pausa antes do palpite de outro assento, a pilula nao se perde na rajada`() = runTest(dispatcher) {
        val viewerSeat = Seat(1)
        val dealerRobot = Seat(0)
        val mortoSeat = Seat(2)
        val initial = bystanderSnapshot(viewerSeat, round = 1, phase = Phase.PLAYING, dealGuess = null)
        // A partida 2 chega em DUAS vistas (rajada, como o anfitrião manda em `PacingPolicy.FREE`): primeiro sem
        // nenhum palpite resolvido, depois com o palpite da mão já resolvido pelo robô (`dealerRobot`).
        val roundStart = bystanderSnapshot(
            viewerSeat, round = 2, phase = Phase.DEAL_GUESS_HAND, dealGuess = DealGuess(dealerRobot, mortoSeat, hand = null),
        )
        val resolved = bystanderSnapshot(
            viewerSeat, round = 2, phase = Phase.DEAL_GUESS_MORTO,
            dealGuess = DealGuess(dealerRobot, mortoSeat, hand = Guess(0, 44, true)),
        )

        val state = MutableStateFlow(initial)
        val driver = object : TableDriver {
            override val state: StateFlow<GameSnapshot> = state
            override fun awaitingHuman() = false
            override fun submit(action: Action, requestId: Long) = false
            override fun explain(action: Action, requestId: Long): RuleError? = null
            override var dealDelayScale = 1.0
            override fun continueToNextRound() = Unit
            override fun resign() = Unit
            override suspend fun run() = awaitCancellation()
        }
        val vm = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000), gameSeed = 1, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            externalDriver = driver, multiplayer = true, continuing = true,
        )
        val pacer = com.gtranca.multiplayer.SnapshotPacer({ 700 }, { 0 }, { 0 }) { state.value = it }
        val job = launch { pacer.run(initial) }
        runCurrent()

        pacer.offer(roundStart)
        pacer.offer(resolved)
        runCurrent()

        // A pausa (700 ms, como a de qualquer jogada de robô) ainda não passou: a pílula do robô já deveria ter
        // aparecido (e ainda não teria sumido, `DEAL_GUESS_PILL_MILLIS` = 1 600 ms) se a vista intermediária tivesse
        // sido vista — a regressão faz isso nunca acontecer.
        advanceTimeBy(699)
        vm.uiState.value.dealGuessPills[dealerRobot.index].shouldBeNull()

        advanceTimeBy(1)
        // `advanceTimeBy` move o relógio até o instante exato do `delay(700)` do pacer, mas o `runCurrent()` abaixo
        // é quem efetivamente retoma a corrotina suspensa nele (publica `resolved` e a VM reage).
        runCurrent()
        val pill = vm.uiState.value.dealGuessPills[dealerRobot.index].shouldNotBeNull()
        pill.correct shouldBe true
        job.cancel()
    }
}
