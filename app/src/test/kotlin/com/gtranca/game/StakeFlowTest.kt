package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.data.GameResult
import com.gtranca.data.GameStats
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.data.StatsRepository
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import com.gtranca.ui.game.GameViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §14.6 a aposta é debitada ao começar o jogo, não de novo ao retomar, e o prêmio sai da vitória. */
@OptIn(ExperimentalCoroutinesApi::class)
class StakeFlowTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakePersistence : GamePersistence {
        val log = mutableListOf<String>()
        val debits = mutableListOf<Int>()
        val saves = mutableListOf<SavedGame>()
        val finishes = mutableListOf<Pair<SavedConfig, GameResult>>()
        override suspend fun save(game: SavedGame) {
            saves += game
            log += "save"
        }
        override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) {
            finishes += config to result
            log += "finish"
        }
        override suspend fun saveHandSort(id: String) = Unit
        var debitOk = true
        override suspend fun debitStake(stake: Int): Boolean {
            debits += stake
            log += "debit"
            return debitOk
        }
    }

    private fun vm(config: GameConfig, persistence: GamePersistence, restored: RestoredGame? = null) = GameViewModel(
        config,
        gameSeed = 5,
        computeDispatcher = dispatcher,
        botDelayMillis = 0,
        swapAnimationMillis = 0,
        animationMillis = 0,
        restored = restored,
        persistence = persistence,
        writes = WriteQueue(CoroutineScope(dispatcher)),
    )

    @Test
    fun `§14_6 a aposta e debitada ao comecar, antes do primeiro salvamento, e fica no jogo salvo`() = runTest(dispatcher) {
        val persistence = FakePersistence()
        vm(GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000, stake = 100), persistence)
        advanceUntilIdle()
        persistence.debits shouldBe listOf(100)
        persistence.log.first() shouldBe "debit"
        persistence.log[1] shouldBe "save"
        persistence.saves.first().config.stake shouldBe 100
    }

    @Test
    fun `§14_6 se o debito nao passa o jogo comeca sem aposta - nada salvo nem pago`() = runTest(dispatcher) {
        val persistence = FakePersistence().apply { debitOk = false }
        val game = vm(GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000, stake = 100), persistence)
        advanceUntilIdle()
        persistence.debits shouldBe listOf(100)
        persistence.saves.last().config.stake shouldBe 0
        game.onResign()
        game.onConfirmResign()
        advanceUntilIdle()
        persistence.finishes.single().first.stake shouldBe 0
    }

    @Test
    fun `§14_6 sem aposta nada e debitado`() = runTest(dispatcher) {
        val persistence = FakePersistence()
        vm(GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), persistence)
        advanceUntilIdle()
        persistence.debits shouldBe emptyList()
    }

    @Test
    fun `§14_6 retomar o jogo salvo nao debita a aposta de novo`() = runTest(dispatcher) {
        val persistence = FakePersistence()
        val match = startMatch(GameMode.INDIVIDUAL, 3000, Random(5))
        vm(GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000, stake = 100), persistence, RestoredGame(match, emptyList()))
        advanceUntilIdle()
        persistence.debits shouldBe emptyList()
    }

    @Test
    fun `§14_6 desistir termina o jogo com a aposta no registro`() = runTest(dispatcher) {
        val persistence = FakePersistence()
        val game = vm(GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000, stake = 250), persistence)
        advanceUntilIdle()
        game.onResign()
        game.onConfirmResign()
        advanceUntilIdle()
        persistence.finishes.single().let { (config, result) ->
            config.stake shouldBe 250
            result shouldBe GameResult.RESIGNATION
        }
    }

    /** Estatísticas falsas que guardam a aposta e o prêmio de cada jogo. */
    private class BetStats : StatsRepository {
        val bets = mutableListOf<Triple<GameResult, Int, Int>>()
        override val stats: Flow<GameStats> = emptyFlow()
        override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean = true
        override suspend fun recordBet(gameId: String, mode: GameMode, difficultyId: String, result: GameResult, stake: Int, payout: Int): Boolean {
            bets += Triple(result, stake, payout)
            return true
        }
        override suspend fun reset() = Unit
    }

    private object NoSettings : SettingsRepository {
        override val settings: Flow<Settings> = emptyFlow()
        override suspend fun setLastMode(mode: GameMode) = Unit
        override suspend fun setHandSort(id: String) = Unit
    }

    private object NoFile : SavedGameStore {
        override suspend fun load(): SavedGame? = null
        override suspend fun save(game: SavedGame) = Unit
        override suspend fun clear() = Unit
    }

    @Test
    fun `§14_6 so a vitoria recebe o premio - individual 2A, duplas 2A, derrota e desistencia 0`() = runTest(dispatcher) {
        val stats = BetStats()
        val persistence = DataGamePersistence(NoFile, stats, NoSettings)
        persistence.finish("a", SavedConfig(GameMode.INDIVIDUAL, "medio", 3000, 100), GameResult.WIN)
        persistence.finish("b", SavedConfig(GameMode.DUPLAS, "medio", 3000, 250), GameResult.WIN)
        persistence.finish("c", SavedConfig(GameMode.INDIVIDUAL, "medio", 3000, 100), GameResult.LOSS)
        persistence.finish("d", SavedConfig(GameMode.DUPLAS, "medio", 3000, 500), GameResult.RESIGNATION)
        persistence.finish("e", SavedConfig(GameMode.INDIVIDUAL, "medio", 3000), GameResult.WIN) // sem aposta
        stats.bets shouldBe listOf(
            Triple(GameResult.WIN, 100, 200),
            Triple(GameResult.WIN, 250, 500),
            Triple(GameResult.LOSS, 100, 0),
            Triple(GameResult.RESIGNATION, 500, 0),
            Triple(GameResult.WIN, 0, 0),
        )
    }
}
