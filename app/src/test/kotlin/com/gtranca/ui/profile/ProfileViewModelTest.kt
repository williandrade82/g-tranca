package com.gtranca.ui.profile

import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.model.GameMode
import com.gtranca.game.Gender
import com.gtranca.game.HairStyle
import com.gtranca.game.PlayerProfile
import com.gtranca.game.Profession
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
    }

    @Test
    fun `§14_1 le o perfil gravado ao abrir`() = runTest(dispatcher) {
        val saved = PlayerProfile("Bia", Gender.FEMALE, Profession.PILOT, PlayerProfile().look.copy(hair = HairStyle.BOB))
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
    fun `§14_1 edicao do avatar respeita o genero`() = runTest(dispatcher) {
        val vm = ProfileViewModel()
        vm.onBeard(true)
        vm.uiState.value.profile.look.beard shouldBe true
        vm.onGender(Gender.FEMALE)
        vm.uiState.value.profile.look.beard shouldBe false
        // Barba e penteado do outro gênero são ignorados.
        vm.onBeard(true)
        vm.onHair(HairStyle.SHORT)
        vm.uiState.value.profile.look.beard shouldBe false
        vm.uiState.value.profile.look.hair.gender shouldBe Gender.FEMALE
        vm.onHair(HairStyle.BUN)
        vm.onProfession(Profession.LAWYER)
        vm.onSkin(4)
        vm.onHairColor(3)
        val look = vm.uiState.value.profile.look
        look.hair shouldBe HairStyle.BUN
        look.skin shouldBe 4
        look.hairColor shouldBe 3
        vm.uiState.value.profile.profession shouldBe Profession.LAWYER
    }

    @Test
    fun `§14_1 nome com no maximo 16 caracteres`() = runTest(dispatcher) {
        val vm = ProfileViewModel()
        vm.onName("x".repeat(30))
        vm.uiState.value.profile.name.length shouldBe PlayerProfile.MAX_NAME
    }

    @Test
    fun `§14_1 salvar grava o perfil sem espacos nas pontas`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = ProfileViewModel(settings, WriteQueue(CoroutineScope(dispatcher)))
        advanceUntilIdle()
        vm.onName("  Rui  ")
        vm.onProfession(Profession.FARMER)
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
}
