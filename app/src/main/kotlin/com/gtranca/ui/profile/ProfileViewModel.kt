package com.gtranca.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.SettingsRepository
import com.gtranca.game.FaceShape
import com.gtranca.game.Gender
import com.gtranca.game.HairStyle
import com.gtranca.game.PlayerProfile
import com.gtranca.game.Profession
import com.gtranca.game.WriteQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §14.1 foto do avatar no rascunho: nenhuma, a já gravada ou uma escolhida agora (ainda não salva). */
enum class PhotoState { NONE, SAVED, DRAFT }

/** Estado da tela de perfil: o rascunho em edição e se o perfil gravado já foi lido. */
data class ProfileUiState(
    val profile: PlayerProfile = PlayerProfile(),
    val loaded: Boolean = false,
    /** Chave de som (grava na hora, sem o botão Salvar). */
    val soundOn: Boolean = true,
    /** Velocidade das animações (grava na hora): `true` = 30% mais lentas. */
    val slowAnimations: Boolean = true,
    val photo: PhotoState = PhotoState.NONE,
    /** Lendo a foto escolhida. */
    val importingPhoto: Boolean = false,
    /** A foto escolhida não pôde ser usada. */
    val photoError: Boolean = false,
)

/** Perfil do jogador (§14.1): edita um rascunho e grava ao salvar. Preferências ilegíveis: perfil padrão. */
class ProfileViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
    /** §14.1 fotos do perfil no aparelho (`null` = sem suporte a foto). */
    private val photos: com.gtranca.ui.profile.PhotoStore? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState(loaded = settings == null))
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu no rascunho: a leitura tardia do perfil gravado não o sobrescreve. */
    private var touched = false

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                val stored = repo.settings.catch { emit(com.gtranca.data.Settings()) }.first()
                val saved = PlayerProfile.decode(stored.profileId)
                _uiState.update {
                    if (touched) {
                        it.copy(loaded = true, soundOn = stored.soundOn, slowAnimations = stored.slowAnimations)
                    } else {
                        val photo = if (saved.hasPhoto && photos != null) PhotoState.SAVED else PhotoState.NONE
                        ProfileUiState(saved, loaded = true, soundOn = stored.soundOn, slowAnimations = stored.slowAnimations, photo = photo)
                    }
                }
            }
        }
    }

    private fun edit(change: (PlayerProfile) -> PlayerProfile) {
        touched = true
        _uiState.update { it.copy(profile = change(it.profile)) }
    }

    /** Liga ou desliga os sons do jogo; grava na hora pela fila durável. */
    fun onSoundToggle(on: Boolean) {
        _uiState.update { it.copy(soundOn = on) }
        settings?.let { repo -> writes?.enqueue { repo.setSoundOn(on) } }
    }

    /** Velocidade das animações; grava na hora pela fila durável. */
    fun onSpeedChange(slow: Boolean) {
        _uiState.update { it.copy(slowAnimations = slow) }
        settings?.let { repo -> writes?.enqueue { repo.setSlowAnimations(slow) } }
    }

    fun onName(raw: String) = edit { it.copy(name = PlayerProfile.sanitizeName(raw)) }

    fun onGender(gender: Gender) = edit { it.withGender(gender) }

    fun onProfession(profession: Profession) = edit { it.copy(profession = profession) }

    fun onSkin(index: Int) = edit { it.copy(look = it.look.copy(skin = index)) }

    fun onHair(style: HairStyle) = edit { if (style.gender == it.gender) it.copy(look = it.look.copy(hair = style)) else it }

    fun onHairColor(index: Int) = edit { it.copy(look = it.look.copy(hairColor = index)) }

    fun onFace(face: FaceShape) = edit { it.copy(look = it.look.copy(face = face)) }

    fun onBeard(beard: Boolean) = edit { if (it.gender == Gender.MALE) it.copy(look = it.look.copy(beard = beard)) else it }

    /** Caminho da foto a mostrar na prévia (rascunho ou gravada), ou `null`. */
    val previewPhoto: String?
        get() = when (_uiState.value.photo) {
            PhotoState.NONE -> null
            PhotoState.SAVED -> photos?.savedPath
            PhotoState.DRAFT -> photos?.draftPath
        }

    /** §14.1 o jogador escolheu uma imagem da galeria: recorta e guarda como rascunho (só vale ao salvar). */
    fun onPhotoPicked(uri: String) {
        val store = photos ?: return
        touched = true
        _uiState.update { it.copy(importingPhoto = true, photoError = false) }
        viewModelScope.launch {
            val ok = store.importDraft(uri)
            _uiState.update {
                it.copy(importingPhoto = false, photoError = !ok, photo = if (ok) PhotoState.DRAFT else it.photo)
            }
        }
    }

    /** §14.1 tira a foto: o avatar volta a ser a persona (apaga o arquivo ao salvar). */
    fun onRemovePhoto() {
        touched = true
        _uiState.update { it.copy(photo = PhotoState.NONE, photoError = false) }
    }

    fun onPhotoErrorShown() = _uiState.update { it.copy(photoError = false) }

    /** Grava o rascunho (nome sem espaços nas pontas) pela fila durável e devolve o perfil gravado. */
    fun onSave(): PlayerProfile {
        val state = _uiState.value
        val profile = state.profile.let { it.copy(name = it.name.trim(), hasPhoto = state.photo != PhotoState.NONE) }
        val encoded = profile.encode()
        val store = photos
        // A foto vai para o lugar definitivo antes de o perfil apontar para ela.
        val photoStep: suspend () -> Unit = {
            when (state.photo) {
                PhotoState.DRAFT -> store?.commitDraft()
                PhotoState.NONE -> store?.deleteSaved()
                PhotoState.SAVED -> Unit
            }
        }
        if (writes != null) {
            writes.enqueue { photoStep(); settings?.setProfile(encoded) }
        } else {
            viewModelScope.launch { photoStep(); settings?.setProfile(encoded) }
        }
        return profile
    }
}
