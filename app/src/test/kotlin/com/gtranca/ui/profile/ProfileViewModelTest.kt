package com.gtranca.ui.profile

import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.model.GameMode
import com.gtranca.game.PlayerProfile
import com.gtranca.game.WriteQueue
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

/** §14.1 tela de perfil: edição do rascunho, leitura do perfil gravado e gravação ao salvar. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<Settings> = state
        override suspend fun setLastMode(mode: GameMode) = state.update { it.copy(lastMode = mode) }
        override suspend fun setHandSort(id: String) = state.update { it.copy(handSortId = id) }
        override suspend fun setProfile(id: String) = state.update { it.copy(profileId = id) }
        override suspend fun setSoundOn(on: Boolean) = state.update { it.copy(soundOn = on) }
        override suspend fun setSlowAnimations(slow: Boolean) = state.update { it.copy(slowAnimations = slow) }
        override suspend fun setWifiOn(on: Boolean) = state.update { it.copy(wifiOn = on) }
    }

    @Test
    fun `§16 Wi-Fi do jogo le o valor gravado e grava na hora`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(wifiOn = true))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.uiState.value.wifiOn shouldBe true
        vm.onWifiToggle(false)
        advanceUntilIdle()
        vm.uiState.value.wifiOn shouldBe false
        settings.state.value.wifiOn shouldBe false
    }

    @Test
    fun `§14_1 le o perfil gravado ao abrir`() = runTest(dispatcher) {
        val saved = PlayerProfile("Bia", color = 4)
        val vm = ProfileViewModel(FakeSettings(Settings(profileId = saved.encode())), WriteQueue(CoroutineScope(dispatcher)))
        vm.uiState.value.loaded shouldBe false
        advanceUntilIdle()
        vm.uiState.value.profile shouldBe saved
        vm.uiState.value.loaded shouldBe true
    }

    @Test
    fun `§14_1 sem perfil gravado usa o padrao`() = runTest(dispatcher) {
        val vm = ProfileViewModel(FakeSettings(), WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.uiState.value.profile shouldBe PlayerProfile()
        vm.uiState.value.loaded shouldBe true
    }

    @Test
    fun `§14_1 cor do medalhao troca dentro da paleta e ignora indice invalido`() = runTest(dispatcher) {
        val vm = ProfileViewModel()
        vm.onColor(3)
        vm.uiState.value.profile.color shouldBe 3
        vm.onColor(PlayerProfile.COLORS)
        vm.onColor(-1)
        vm.uiState.value.profile.color shouldBe 3
    }

    @Test
    fun `§14_1 nome com no maximo 16 caracteres`() = runTest(dispatcher) {
        val vm = ProfileViewModel()
        vm.onName("x".repeat(30))
        vm.uiState.value.profile.name.length shouldBe PlayerProfile.MAX_NAME
    }

    @Test
    fun `§14_1 salvar sem nome grava Sem nome`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onName("   ")
        val returned = vm.onSave("Sem nome")
        advanceUntilIdle()
        returned.name shouldBe "Sem nome"
        PlayerProfile.decode(settings.state.value.profileId) shouldBe returned
    }

    @Test
    fun `§14_1 salvar grava o perfil sem espacos nas pontas`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onName("  Rui  ")
        vm.onColor(3)
        val returned = vm.onSave()
        advanceUntilIdle()
        returned.name shouldBe "Rui"
        PlayerProfile.decode(settings.state.value.profileId) shouldBe returned
    }

    @Test
    fun `§14_1 a leitura tardia nao apaga o que o jogador ja editou`() = runTest(dispatcher) {
        val saved = PlayerProfile("Antigo")
        val vm = ProfileViewModel(FakeSettings(Settings(profileId = saved.encode())), WriteQueue(CoroutineScope(dispatcher)))
        vm.onName("Novo")
        advanceUntilIdle()
        vm.uiState.value.profile.name shouldBe "Novo"
        vm.uiState.value.loaded shouldBe true
    }

    @Test
    fun `chave de som le o valor gravado e grava na hora`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(soundOn = false))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.uiState.value.soundOn shouldBe false
        vm.onSoundToggle(true)
        advanceUntilIdle()
        vm.uiState.value.soundOn shouldBe true
        settings.state.value.soundOn shouldBe true
        // Padrão: ligado.
        ProfileViewModel(FakeSettings(), WriteQueue(CoroutineScope(dispatcher))).also { advanceUntilIdle() }.uiState.value.soundOn shouldBe true
    }

    @Test
    fun `velocidade das animacoes grava na hora e o padrao e 30 por cento mais lenta`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(slowAnimations = false))
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.uiState.value.slowAnimations shouldBe false
        vm.onSpeedChange(true)
        advanceUntilIdle()
        vm.uiState.value.slowAnimations shouldBe true
        settings.state.value.slowAnimations shouldBe true
        ProfileViewModel(FakeSettings(), WriteQueue(CoroutineScope(dispatcher))).also { advanceUntilIdle() }.uiState.value.slowAnimations shouldBe true
    }

    private class FakePhotos(var importOk: Boolean = true) : PhotoStore {
        override val savedPath = "/app/profile_photo.jpg"
        override val draftPath = "/app/profile_photo_draft.jpg"
        var hasDraft = false
        var hasSaved = false
        override suspend fun importDraft(uri: String): Boolean {
            if (importOk) hasDraft = true
            return importOk
        }
        override suspend fun commitDraft(): Boolean {
            if (!hasDraft) return false
            hasDraft = false
            hasSaved = true
            return true
        }
        override suspend fun deleteSaved() {
            hasSaved = false
        }
    }

    @Test
    fun `§14_1 foto escolhida vira rascunho e so vale ao salvar`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val photos = FakePhotos()
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)), photos)
        advanceUntilIdle()
        vm.uiState.value.photo shouldBe PhotoState.NONE
        vm.previewPhoto shouldBe null
        vm.onPhotoPicked("content://galeria/1")
        advanceUntilIdle()
        vm.uiState.value.photo shouldBe PhotoState.DRAFT
        vm.previewPhoto shouldBe photos.draftPath
        photos.hasSaved shouldBe false
        val saved = vm.onSave()
        advanceUntilIdle()
        saved.hasPhoto shouldBe true
        photos.hasSaved shouldBe true
        PlayerProfile.decode(settings.state.value.profileId).hasPhoto shouldBe true
        // Reabrindo: a foto gravada aparece.
        val again = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)), photos)
        advanceUntilIdle()
        again.uiState.value.photo shouldBe PhotoState.SAVED
        again.previewPhoto shouldBe photos.savedPath
    }

    @Test
    fun `§14_1 remover a foto volta para a persona e apaga o arquivo ao salvar`() = runTest(dispatcher) {
        val settings = FakeSettings(Settings(profileId = PlayerProfile("Bia", hasPhoto = true).encode()))
        val photos = FakePhotos().apply { hasSaved = true }
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)), photos)
        advanceUntilIdle()
        vm.uiState.value.photo shouldBe PhotoState.SAVED
        vm.onRemovePhoto()
        vm.previewPhoto shouldBe null
        vm.onSave().hasPhoto shouldBe false
        advanceUntilIdle()
        photos.hasSaved shouldBe false
        PlayerProfile.decode(settings.state.value.profileId).hasPhoto shouldBe false
    }

    @Test
    fun `§14_1 foto que nao pode ser lida avisa e mantem o avatar`() = runTest(dispatcher) {
        val vm = ProfileViewModel(FakeSettings(), WriteQueue(CoroutineScope(dispatcher)), FakePhotos(importOk = false))
        advanceUntilIdle()
        vm.onPhotoPicked("content://galeria/quebrada")
        advanceUntilIdle()
        vm.uiState.value.photoError shouldBe true
        vm.uiState.value.photo shouldBe PhotoState.NONE
        vm.onPhotoErrorShown()
        vm.uiState.value.photoError shouldBe false
    }
}
