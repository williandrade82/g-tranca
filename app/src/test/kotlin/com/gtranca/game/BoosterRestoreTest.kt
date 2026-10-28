package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.data.GameResult
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.engine.model.GameMode
import com.gtranca.ui.game.GameViewModel
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §17.3 o booster fica na partida que foi salva: "Continuar" retoma o jogo com os mesmos boosters, sem novo seletor nem novo débito. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoosterRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val writeScope = CoroutineScope(dispatcher + SupervisorJob())

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private class FakePersistence : GamePersistence {
        val saves = mutableListOf<SavedGame>()
        override suspend fun save(game: SavedGame) { saves += game }
        override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) = Unit
        override suspend fun saveHandSort(id: String) = Unit
    }

    private fun TestScope.confirmSwaps(vm: GameViewModel) {
        while (vm.uiState.value.reveal != null) { vm.onRevealConfirmed(); advanceUntilIdle() }
    }

    private fun newVm(store: FakePersistence, boosters: List<String>) = GameViewModel(
        GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
        botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
        gameId = "jogo-b", persistence = store, writes = WriteQueue(writeScope), initialBoosters = boosters,
    )

    @Test
    fun `§17_3 todo salvamento do jogo carrega os boosters da partida, desde o primeiro`() = runTest(dispatcher) {
        val store = FakePersistence()
        val vm = newVm(store, listOf("b1", "b3"))
        advanceUntilIdle()
        confirmSwaps(vm)
        vm.onDraw()
        advanceUntilIdle()
        store.saves.shouldNotBeEmpty()
        store.saves.all { it.boosters == listOf("b1", "b3") } shouldBe true
    }

    @Test
    fun `§17_3 retomar o jogo salvo mantem os boosters da partida e o realce do b1`() = runTest(dispatcher) {
        val store = FakePersistence()
        val vm = newVm(store, listOf("b1"))
        advanceUntilIdle()
        confirmSwaps(vm)
        vm.onDraw()
        advanceUntilIdle()
        val saved = store.saves.last()

        // "Continuar": novo ViewModel a partir do jogo salvo, sem passar pelo seletor.
        val resumed = GameViewModel(
            saved.config.toConfig(), gameSeed = saved.gameSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
            swapAnimationMillis = 0, animationMillis = 0, restored = saved.toRestored(), gameId = saved.gameId,
            persistence = FakePersistence(), writes = WriteQueue(writeScope),
        )
        advanceUntilIdle()
        confirmSwaps(resumed)
        resumed.uiState.value.boosters shouldBe listOf("b1")
        resumed.uiState.value.boosterSelectorPending shouldBe false
        resumed.uiState.value.highlighted shouldBe com.gtranca.game.HumanTurnResolver.highlightedCards(
            resumed.uiState.value.hand, resumed.uiState.value.snapshot.humanLegal, emptyList(),
        )
    }

    @Test
    fun `§17_3 jogo salvo sem boosters retoma sem boosters`() = runTest(dispatcher) {
        val store = FakePersistence()
        val vm = newVm(store, emptyList())
        advanceUntilIdle()
        val saved = store.saves.first()
        saved.boosters shouldBe emptyList()
        val resumed = GameViewModel(
            saved.config.toConfig(), gameSeed = saved.gameSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
            swapAnimationMillis = 0, animationMillis = 0, restored = saved.toRestored(), gameId = saved.gameId,
            persistence = FakePersistence(), writes = WriteQueue(writeScope),
        )
        advanceUntilIdle()
        resumed.uiState.value.boosters shouldBe emptyList()
    }
}
