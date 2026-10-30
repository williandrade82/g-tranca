package com.gtranca.ui.profile

import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.game.WriteQueue
import com.gtranca.online.client.OnlineEndpoint
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** §18.1 campo temporário "Servidor online" do Perfil (docs/telas.md 6e): rascunho, validação e gravação ao salvar. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileOnlineServerTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<Settings> = state
        override suspend fun setLastMode(mode: com.gtranca.engine.model.GameMode) = Unit
        override suspend fun setHandSort(id: String) = Unit
        override suspend fun setProfile(id: String) = state.update { it.copy(profileId = id) }
        override suspend fun setOnlineServer(server: String) = state.update { it.copy(onlineServer = server) }
    }

    @Test
    fun `por padrao o servidor e vazio e le o gravado`() = runTest(dispatcher) {
        val vm = ProfileViewModel(FakeSettings(Settings(onlineServer = "meu-servidor:8080")), WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.uiState.value.onlineServer shouldBe "meu-servidor:8080"
        ProfileViewModel(FakeSettings(), WriteQueue(CoroutineScope(dispatcher))).also { advanceUntilIdle() }.uiState.value.onlineServer shouldBe ""
    }

    @Test
    fun `grava o servidor normalizado ao salvar`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onOnlineServer(" http://Servidor.Local:8080/ ")
        vm.uiState.value.onlineServerValid shouldBe true
        vm.onSave("Sem nome")
        advanceUntilIdle()
        settings.state.value.onlineServer shouldBe "servidor.local:8080"
    }

    @Test
    fun `endereco invalido e marcado e nao e gravado`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(onlineServer = "antigo"))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onOnlineServer("host com espaco")
        vm.uiState.value.onlineServerValid shouldBe false
        vm.onSave("Sem nome")
        advanceUntilIdle()
        settings.state.value.onlineServer shouldBe "antigo"
    }

    @Test
    fun `salvar sem mexer no campo nao apaga o servidor gravado`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(onlineServer = "guardado"))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        vm.onName("Ana") // mexeu no nome antes de a leitura terminar
        vm.onSave("Sem nome")
        advanceUntilIdle()
        settings.state.value.onlineServer shouldBe "guardado"
    }

    @Test
    fun `campo em branco desliga o online`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(onlineServer = "guardado"))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onOnlineServer("   ")
        vm.uiState.value.onlineServerValid shouldBe true
        vm.onSave("Sem nome")
        advanceUntilIdle()
        settings.state.value.onlineServer shouldBe ""
    }

    @Test
    fun `release exige TLS e o debug aceita texto puro`() {
        OnlineEndpoint.parse("host", cleartext = false)!!.httpBase shouldBe "https://host"
        OnlineEndpoint.parse("host", cleartext = true)!!.httpBase shouldBe "http://host"
    }
}
