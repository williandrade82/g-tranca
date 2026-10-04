package com.gtranca.ui.stats

import com.gtranca.data.GameResult
import com.gtranca.data.GameStats
import com.gtranca.data.StatsRepository
import com.gtranca.engine.model.GameMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    @Test
    fun `estatisticas ilegiveis aparecem zeradas e zerar com falha de disco nao derruba a tela`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var resets = 0
            val broken = object : StatsRepository {
                override val stats: Flow<GameStats> = flow { throw IOException("arquivo corrompido") }
                override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult) =
                    throw IOException("disco cheio")
                override suspend fun reset() {
                    resets++
                    throw IOException("disco cheio")
                }
            }
            val vm = StatsViewModel(broken)
            advanceUntilIdle()
            vm.stats.value shouldBe GameStats()
            vm.onReset()
            advanceUntilIdle()
            resets shouldBe 1
            vm.stats.value shouldBe GameStats()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
