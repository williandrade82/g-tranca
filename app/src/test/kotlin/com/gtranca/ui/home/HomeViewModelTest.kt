package com.gtranca.ui.home

import com.gtranca.ai.Difficulty
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.random.Random

class HomeViewModelTest {

    private class FakeStore(var game: SavedGame? = null) : SavedGameStore {
        override suspend fun load(): SavedGame? = game
        override suspend fun save(game: SavedGame) {
            this.game = game
        }
        override suspend fun clear() {
            game = null
        }
    }

    private fun saved(): SavedGame {
        val match = startMatch(GameMode.DUPLAS, targetScore = 2000, random = Random(1))
        return SavedGame(gameId = "g", config = SavedConfig(GameMode.DUPLAS, "dificil", 2000), gameSeed = 1, match = match, events = emptyList())
    }

    @Test
    fun `sem jogo salvo, novo jogo abre as mesas direto`() {
        val vm = HomeViewModel()
        vm.uiState.value.ready shouldBe true
        vm.onNewGame() shouldBe true
        vm.uiState.value.confirmNewGame shouldBe false
    }

    @Test
    fun `com jogo salvo, continuar aparece com o resumo e novo jogo pede confirmacao`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeStore(saved())
            val vm = HomeViewModel(store)
            advanceUntilIdle()
            val summary = vm.uiState.value.saved.shouldNotBeNull()
            summary.mode shouldBe GameMode.DUPLAS
            summary.difficulty shouldBe Difficulty.DIFICIL
            summary.roundNumber shouldBe 1
            summary.ownTotal shouldBe 0
            // Novo jogo: primeiro a confirmação; cancelar não começa nada.
            vm.onNewGame() shouldBe false
            vm.uiState.value.confirmNewGame shouldBe true
            vm.onDismissNewGame()
            vm.uiState.value.confirmNewGame shouldBe false
            // Confirmar apaga o salvo antigo antes de liberar as mesas (sem janela para retomar o antigo).
            vm.onNewGame() shouldBe false
            vm.onConfirmNewGame()
            vm.uiState.value.pendingNewGame shouldBe false
            vm.uiState.value.ready shouldBe false
            advanceUntilIdle()
            store.game.shouldBeNull()
            vm.uiState.value.pendingNewGame shouldBe true
            vm.onStartHandled()
            vm.uiState.value.pendingNewGame shouldBe false
            // Ao voltar para a tela, o salvo é relido.
            vm.refresh()
            advanceUntilIdle()
            vm.uiState.value.saved.shouldBeNull()
            vm.onNewGame() shouldBe true
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `novo jogo sobre o salvo conta o abandonado como desistencia, uma vez`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val recorded = mutableListOf<List<Any>>()
            val stats = object : com.gtranca.data.StatsRepository {
                override val stats = kotlinx.coroutines.flow.flowOf(com.gtranca.data.GameStats())
                override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: com.gtranca.data.GameResult): Boolean {
                    recorded += listOf(gameId, mode, difficultyId, result)
                    return true
                }
                override suspend fun reset() {}
            }
            val store = FakeStore(saved())
            val vm = HomeViewModel(store, stats = stats)
            advanceUntilIdle()
            vm.onNewGame() shouldBe false
            vm.onConfirmNewGame()
            advanceUntilIdle()
            recorded shouldBe listOf(listOf("g", GameMode.DUPLAS, "dificil", com.gtranca.data.GameResult.RESIGNATION))
            store.game.shouldBeNull()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `botoes de jogo so ficam ativos depois de ler o salvo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = HomeViewModel(FakeStore(saved()))
            vm.uiState.value.ready shouldBe false
            vm.onNewGame() shouldBe false
            vm.uiState.value.confirmNewGame shouldBe false
            advanceUntilIdle()
            vm.uiState.value.ready shouldBe true
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `jogo salvo ilegivel vira nada, sem derrubar a tela`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val brokenStore = object : SavedGameStore {
                override suspend fun load(): SavedGame? = throw IOException("sem leitura")
                override suspend fun save(game: SavedGame) = throw IOException("disco cheio")
                override suspend fun clear() = throw IOException("disco cheio")
            }
            val vm = HomeViewModel(brokenStore)
            advanceUntilIdle()
            vm.uiState.value.saved.shouldBeNull()
            vm.uiState.value.ready shouldBe true
            vm.onNewGame() shouldBe true
        } finally {
            Dispatchers.resetMain()
        }
    }
}
