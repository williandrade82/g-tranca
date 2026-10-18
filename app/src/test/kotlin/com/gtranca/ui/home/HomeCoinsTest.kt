package com.gtranca.ui.home

import com.gtranca.data.GameResult
import com.gtranca.data.GameStats
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.data.StatsRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.Wallet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §14.6 Início: saldo, ganho diário (um por dia) e a aposta perdida ao abandonar o jogo salvo. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCoinsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeWallet(initial: Wallet = Wallet()) : WalletRepository {
        val state = MutableStateFlow(initial)
        override val wallet: Flow<Wallet> = state
        override val lifetimeNet: Flow<Int> = emptyFlow()
        override suspend fun debit(stake: Int): Boolean {
            val debited = state.value.debit(stake) ?: return false
            state.value = debited
            return true
        }
        override suspend fun claimDaily(today: Long): Boolean {
            val claimed = state.value.claimDaily(today) ?: return false
            state.value = claimed
            return true
        }
    }

    @Test
    fun `§14_6 o inicio mostra o saldo e o ganho diario aparece uma vez por dia`() = runTest(dispatcher) {
        val wallet = FakeWallet()
        var today = 20_000L
        val vm = HomeViewModel(wallet = wallet, today = { today })
        advanceUntilIdle()
        vm.uiState.value.balance shouldBe 1_000
        vm.uiState.value.canClaimDaily shouldBe true
        vm.onClaimDaily()
        advanceUntilIdle()
        vm.uiState.value.balance shouldBe 1_200
        vm.uiState.value.canClaimDaily shouldBe false
        vm.onClaimDaily() // no mesmo dia: nada acontece
        advanceUntilIdle()
        vm.uiState.value.balance shouldBe 1_200
        // dia seguinte, ao voltar para a tela
        today = 20_001
        vm.refresh()
        vm.uiState.value.canClaimDaily shouldBe true
    }

    @Test
    fun `§14_6 abandonar o jogo salvo por um novo perde a aposta dele`() = runTest(dispatcher) {
        val match = startMatch(GameMode.DUPLAS, 2000, Random(1))
        val store = object : SavedGameStore {
            var game: SavedGame? = SavedGame(
                gameId = "g",
                config = SavedConfig(GameMode.DUPLAS, "medio", 2000, stake = 250),
                gameSeed = 1,
                match = match,
                events = emptyList(),
            )
            override suspend fun load() = game
            override suspend fun save(game: SavedGame) { this.game = game }
            override suspend fun clear() { game = null }
        }
        val bets = mutableListOf<List<Any>>()
        val stats = object : StatsRepository {
            override val stats = flowOf(GameStats())
            override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult) = true
            override suspend fun recordBet(gameId: String, mode: GameMode, difficultyId: String, result: GameResult, stake: Int, payout: Int): Boolean {
                bets += listOf(gameId, result, stake, payout)
                return true
            }
            override suspend fun reset() = Unit
        }
        val vm = HomeViewModel(store, stats = stats)
        advanceUntilIdle()
        vm.onNewGame()
        vm.onConfirmNewGame()
        advanceUntilIdle()
        bets shouldBe listOf(listOf("g", GameResult.RESIGNATION, 250, 0))
    }
}
