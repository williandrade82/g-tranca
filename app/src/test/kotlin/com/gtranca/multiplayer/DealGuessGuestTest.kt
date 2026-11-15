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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
}
