package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.game.GameConfig
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.TableDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** docs/telas.md 3.8.1: o `GameViewModel` entrega os voos de uma jogada só depois de a anterior pousar. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightSequenceTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeDriver(initial: GameSnapshot) : TableDriver {
        override val state = MutableStateFlow(initial)
        override fun awaitingHuman() = false
        override fun submit(action: Action, requestId: Long) = false
        override fun explain(action: Action, requestId: Long): RuleError? = null
        override var dealDelayScale = 1.0
        override fun continueToNextRound() = Unit
        override fun resign() = Unit
        override suspend fun run() = awaitCancellation()
    }

    private fun c(text: String) = Card.parse(text)

    private fun snapshot(partnerHand: Int, taken: Boolean, partnerEvents: List<PublicEvent>): GameSnapshot {
        val hand = listOf(c("4S"), c("7H"))
        val view = PlayerView(
            mode = GameMode.DUPLAS, seat = Seat(0), side = Side(0), currentSeat = Seat(2), firstSeat = Seat(0),
            phase = Phase.PLAYING, result = null, hand = hand, handSizes = listOf(2, 9, partnerHand, 9),
            discardPile = listOf(c("5S")), stockSize = 30,
            mortoStatus = listOf(if (taken) MortoStatus.Taken(Side(0), Seat(2)) else MortoStatus.Available, MortoStatus.Available),
            mortoSizes = listOf(11, 11), redThrees = List(2) { emptyList() }, tables = listOf(SideTable(), SideTable()),
            redThreeLog = emptyList(),
        )
        val events = List(4) { emptyList<PublicEvent>() }.toMutableList()
        events[2] = partnerEvents
        return GameSnapshot(
            GameConfig(GameMode.DUPLAS, Difficulty.MEDIO, 3000), Seat(0), view, 1, listOf(0, 0), emptyList(), Stage.PLAYING,
            null, emptyList(), null, events,
        )
    }

    @Test
    fun `telas R1 R2 R3 o descarte do parceiro que chega logo depois do morto direto espera o morto pousar`() = runTest(dispatcher) {
        val meld = PublicEvent(Seat(2), Action.CreateMeld(listOf(c("5H"), c("5D"), c("5C"))))
        val discard = PublicEvent(Seat(2), Action.Discard(c("9C")))
        val driver = FakeDriver(snapshot(partnerHand = 3, taken = false, partnerEvents = emptyList()))
        val vm = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.MEDIO, 3000), gameSeed = 1, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 450,
            externalDriver = driver, continuing = true,
        )
        advanceUntilIdle()
        vm.uiState.value.flights.shouldBeEmpty()

        // 1ª vista: o parceiro baixa tudo e pega o morto (11 cartas na mão dele, mas a pílula ainda não as conta: R6).
        driver.state.value = snapshot(11, taken = true, partnerEvents = listOf(meld))
        runCurrent()
        val first = vm.uiState.value
        first.flights.any { it.from is AnimAnchor.Morto } shouldBe true
        first.flights.none { it.to == AnimAnchor.DiscardPile } shouldBe true

        // 2ª vista chega antes de o morto pousar: o descarte espera na fila (R2), sem voar junto com o morto.
        advanceTimeBy(100)
        driver.state.value = snapshot(11, taken = true, partnerEvents = listOf(meld, discard))
        runCurrent()
        val queued = vm.uiState.value
        queued.queuedFlights.any { it.to == AnimAnchor.DiscardPile } shouldBe true
        queued.flights.none { it.to == AnimAnchor.DiscardPile } shouldBe true

        // Antes do pouso do morto (~1170 ms) o descarte segue na fila; depois, entra no ar.
        advanceTimeBy(900)
        runCurrent()
        vm.uiState.value.flights.none { it.to == AnimAnchor.DiscardPile } shouldBe true
        advanceTimeBy(300)
        runCurrent()
        vm.uiState.value.queuedFlights.shouldBeEmpty()
        vm.uiState.value.flights.any { it.to == AnimAnchor.DiscardPile } shouldBe true
    }
}
