package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.data.InventoryRepository
import com.gtranca.engine.Action
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Gift
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §17.3 cada partida depois da primeira começa pelo seletor de boosters; a nova partida só começa depois do Seguir. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoosterGateTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeInventory : InventoryRepository {
        override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT
        override val inventory: Flow<Inventory> = MutableStateFlow(Inventory.initial())
        override val selection: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun setSelection(ids: List<String>) = Unit
        override suspend fun confirmLoadout(selection: List<String>) = Loadout.confirm(selection, Inventory.initial(), catalog)
        override suspend fun claimGift(today: Long, random: Random): Gift? = null
    }

    private fun TestScope.vm(inventory: InventoryRepository?, boosters: List<String> = emptyList()): GameViewModel {
        val game = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1_000_000),
            gameSeed = 9,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            swapAnimationMillis = 0,
            animationMillis = 0,
            initialBoosters = boosters,
            boosterInventory = inventory,
        )
        advanceUntilIdle()
        while (game.uiState.value.reveal != null) { game.onRevealConfirmed(); advanceUntilIdle() }
        return game
    }

    /** Joga (descartando) até a partida acabar. */
    private fun TestScope.playRound(vm: GameViewModel) {
        var guard = 0
        while (vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 2_000) {
            while (vm.uiState.value.reveal != null) { vm.onRevealConfirmed(); advanceUntilIdle() }
            val state = vm.uiState.value
            when {
                state.awaitingDraw -> if (state.canDraw) vm.onDraw() else vm.onTakeDiscardPile()
                state.playing -> {
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    val card = state.hand.first { it.cardClass == discard.card.cardClass }
                    vm.onCardClick(card)
                    vm.onDiscard()
                }
            }
            advanceUntilIdle()
        }
    }

    @Test
    fun `§17_3 a partida seguinte so comeca depois do Seguir do seletor e os boosters valem so nela`() = runTest(dispatcher) {
        val game = vm(FakeInventory(), boosters = listOf("b1"))
        game.uiState.value.boosters shouldBe listOf("b1")
        playRound(game)
        game.uiState.value.snapshot.stage shouldBe Stage.ROUND_OVER
        game.onNextRound()
        advanceUntilIdle()
        // o seletor está na tela e a partida 2 ainda não começou
        game.uiState.value.boosterSelectorPending shouldBe true
        game.uiState.value.snapshot.roundNumber shouldBe 1
        game.onBoostersConfirmed(listOf("b3"))
        advanceUntilIdle()
        game.uiState.value.boosterSelectorPending shouldBe false
        game.uiState.value.snapshot.roundNumber shouldBe 2
        game.uiState.value.boosters shouldBe listOf("b3")
    }

    @Test
    fun `§17_3 confirmar duas vezes nao pula de partida`() = runTest(dispatcher) {
        val game = vm(FakeInventory())
        playRound(game)
        game.onNextRound()
        advanceUntilIdle()
        game.onBoostersConfirmed(listOf("b2"))
        advanceUntilIdle()
        game.onBoostersConfirmed(listOf("b1"))
        advanceUntilIdle()
        game.uiState.value.boosters shouldBe listOf("b2")
        game.uiState.value.snapshot.roundNumber shouldBe 2
    }

    @Test
    fun `§17_3 sem inventario (testes e multijogador) a partida seguinte comeca direto`() = runTest(dispatcher) {
        val game = vm(null)
        playRound(game)
        game.onNextRound()
        advanceUntilIdle()
        game.uiState.value.boosterSelectorPending shouldBe false
        game.uiState.value.snapshot.roundNumber shouldBe 2
    }

    @Test
    fun `§17_8 a partida seguinte renova os 3 usos das reacoes`() = runTest(dispatcher) {
        val game = vm(FakeInventory(), boosters = listOf("b3"))
        repeat(3) { game.onReaction(com.gtranca.engine.Reaction.APPLAUSE) }
        advanceUntilIdle()
        game.uiState.value.reactionsLeft shouldBe 0
        playRound(game)
        game.onNextRound()
        advanceUntilIdle()
        game.onBoostersConfirmed(listOf("b3"))
        advanceUntilIdle()
        game.uiState.value.snapshot.roundNumber shouldBe 2
        game.uiState.value.reactionsLeft shouldBe 3
    }
}
