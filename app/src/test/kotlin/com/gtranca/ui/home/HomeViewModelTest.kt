package com.gtranca.ui.home

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.parseTargetScore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.startMatch
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.random.Random
import org.junit.jupiter.api.Test

class HomeViewModelTest {

    @Test
    fun `padroes de §14 - medio e alvo 3000`() {
        val state = HomeViewModel().uiState.value
        state.difficulty shouldBe Difficulty.MEDIO
        state.targetScore shouldBe 3000
        state.targetError.shouldBeNull()
        state.canStart shouldBe true
        state.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
    }

    @Test
    fun `§14 alvo deve ser inteiro positivo, e o texto nunca e truncado`() {
        val vm = HomeViewModel()
        vm.onTargetChange("0")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.uiState.value.canStart shouldBe false
        vm.uiState.value.toConfig().shouldBeNull()
        vm.onTargetChange("")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        vm.onTargetChange("15a0")
        vm.uiState.value.targetText shouldBe "15a0"
        vm.uiState.value.targetError shouldBe TargetError.INVALID
        // Antes, 10 dígitos eram cortados em 9 sem aviso; agora o texto fica e o erro explica.
        vm.onTargetChange("1234567890123")
        vm.uiState.value.targetText shouldBe "1234567890123"
        vm.uiState.value.targetError shouldBe TargetError.TOO_LARGE
        vm.uiState.value.canStart shouldBe false
        vm.onTargetChange("2147483647")
        vm.uiState.value.targetScore shouldBe Int.MAX_VALUE
        vm.onTargetChange("1000")
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.uiState.value.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.DIFICIL, 1000)
    }

    @Test
    fun `§14 duplas pode ser escolhido`() {
        val vm = HomeViewModel()
        vm.onModeChange(GameMode.DUPLAS)
        vm.uiState.value.canStart shouldBe true
        vm.uiState.value.toConfig() shouldBe GameConfig(GameMode.DUPLAS, Difficulty.MEDIO, 3000)
    }

    @Test
    fun `§14 leitura da pontuacao alvo - so digitos ASCII`() {
        parseTargetScore(" 2500 ") shouldBe 2500
        parseTargetScore("-5").shouldBeNull()
        parseTargetScore("99999999999").shouldBeNull()
        parseTargetScore("1.5").shouldBeNull()
        // Sem sinal e sem dígitos de outros sistemas de escrita (ex.: árabe-índicos, de largura total).
        parseTargetScore("+3000").shouldBeNull()
        parseTargetScore("٣٠٠٠").shouldBeNull()
        parseTargetScore("３０００").shouldBeNull()
        val vm = HomeViewModel()
        vm.onTargetChange("+3000")
        vm.uiState.value.targetError shouldBe TargetError.INVALID
    }

    private class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<Settings> = state
        override suspend fun setLastMode(mode: GameMode) = state.update { it.copy(lastMode = mode) }
        override suspend fun setHandSort(id: String) = state.update { it.copy(handSortId = id) }
    }

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
    fun `§14 modo vem pre-selecionado com o ultimo escolhido e e lembrado ao comecar`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settings = FakeSettings(Settings(lastMode = GameMode.DUPLAS))
            val vm = HomeViewModel(settings, FakeStore())
            advanceUntilIdle()
            vm.uiState.value.mode shouldBe GameMode.DUPLAS
            // Dificuldade e alvo continuam com os padrões de §14.
            vm.uiState.value.difficulty shouldBe Difficulty.MEDIO
            vm.uiState.value.targetScore shouldBe 3000
            vm.onModeChange(GameMode.INDIVIDUAL)
            vm.onNewGame() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
            advanceUntilIdle()
            settings.state.value.lastMode shouldBe GameMode.INDIVIDUAL
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `com jogo salvo, continuar aparece com o resumo e novo jogo pede confirmacao`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeStore(saved())
            val vm = HomeViewModel(FakeSettings(), store)
            advanceUntilIdle()
            val summary = vm.uiState.value.saved.shouldNotBeNull()
            summary.mode shouldBe GameMode.DUPLAS
            summary.difficulty shouldBe Difficulty.DIFICIL
            summary.roundNumber shouldBe 1
            summary.ownTotal shouldBe 0
            // Novo jogo: primeiro a confirmação; cancelar não começa nada.
            vm.onNewGame().shouldBeNull()
            vm.uiState.value.confirmNewGame shouldBe true
            vm.onDismissNewGame()
            vm.uiState.value.confirmNewGame shouldBe false
            vm.onNewGame().shouldBeNull()
            // Confirmar apaga o salvo antigo antes de liberar o novo jogo (sem janela para retomar o antigo).
            vm.onConfirmNewGame()
            vm.uiState.value.pendingStart.shouldBeNull()
            vm.uiState.value.canStart shouldBe false
            advanceUntilIdle()
            store.game.shouldBeNull()
            vm.uiState.value.pendingStart shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
            vm.onStartHandled()
            vm.uiState.value.pendingStart.shouldBeNull()
            store.game = saved()
            // Ao voltar para a tela, o salvo é relido (ex.: o jogo terminou e foi apagado).
            store.game = null
            vm.refresh()
            advanceUntilIdle()
            vm.uiState.value.saved.shouldBeNull()
            vm.onNewGame() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
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
            val vm = HomeViewModel(FakeSettings(), store, stats = stats)
            advanceUntilIdle()
            vm.onNewGame().shouldBeNull()
            vm.onConfirmNewGame()
            advanceUntilIdle()
            recorded shouldBe listOf(listOf("g", GameMode.DUPLAS, "dificil", com.gtranca.data.GameResult.RESIGNATION))
            store.game.shouldBeNull()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `botoes de jogo so ficam ativos depois de ler o salvo e o ultimo modo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = HomeViewModel(FakeSettings(Settings(lastMode = GameMode.DUPLAS)), FakeStore(saved()))
            vm.uiState.value.ready shouldBe false
            vm.uiState.value.canStart shouldBe false
            vm.onNewGame().shouldBeNull()
            vm.uiState.value.confirmNewGame shouldBe false
            advanceUntilIdle()
            vm.uiState.value.ready shouldBe true
            vm.uiState.value.mode shouldBe GameMode.DUPLAS
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `preferencias e jogo salvo ilegiveis viram valores padrao, sem derrubar a tela`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val broken = object : SettingsRepository {
                override val settings: Flow<Settings> = flow { throw IOException("arquivo corrompido") }
                override suspend fun setLastMode(mode: GameMode) = throw IOException("disco cheio")
                override suspend fun setHandSort(id: String) = throw IOException("disco cheio")
            }
            val brokenStore = object : SavedGameStore {
                override suspend fun load(): SavedGame? = throw IOException("sem leitura")
                override suspend fun save(game: SavedGame) = throw IOException("disco cheio")
                override suspend fun clear() = throw IOException("disco cheio")
            }
            val vm = HomeViewModel(broken, brokenStore)
            advanceUntilIdle()
            vm.uiState.value.mode shouldBe GameMode.INDIVIDUAL
            vm.uiState.value.saved.shouldBeNull()
            vm.uiState.value.ready shouldBe true
            // Gravar o último modo falha em silêncio: o jogo começa mesmo assim.
            vm.onNewGame() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
