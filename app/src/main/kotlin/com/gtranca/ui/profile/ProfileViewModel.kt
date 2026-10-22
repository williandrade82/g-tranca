package com.gtranca.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.SettingsRepository
import com.gtranca.game.PlayerProfile
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
    /** §16 Wi-Fi do jogo (grava na hora). */
    val wifiOn: Boolean = false,
    /** §16.2 ver as fotos dos outros nas mesas multijogador (grava na hora). */
    val showSharedPhotos: Boolean = true,
    /** Tour guiado automático (grava na hora). */
    val tourAuto: Boolean = true,
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
                        it.copy(loaded = true, soundOn = stored.soundOn, slowAnimations = stored.slowAnimations, wifiOn = stored.wifiOn, showSharedPhotos = stored.showSharedPhotos, tourAuto = stored.tourAuto)
                    } else {
                        val photo = if (saved.hasPhoto && photos != null) PhotoState.SAVED else PhotoState.NONE
                        ProfileUiState(saved, loaded = true, soundOn = stored.soundOn, slowAnimations = stored.slowAnimations, wifiOn = stored.wifiOn, showSharedPhotos = stored.showSharedPhotos, tourAuto = stored.tourAuto, photo = photo)
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

    /** §16 Wi-Fi do jogo; grava na hora. Ligar só depois da porta das permissões (a tela cuida disso). */
    fun onWifiToggle(on: Boolean) {
        _uiState.update { it.copy(wifiOn = on) }
        settings?.let { repo -> writes?.enqueue { repo.setWifiOn(on) } }
    }

    /** §16.2 ver as fotos dos outros jogadores nas mesas multijogador; grava na hora. */
    fun onShowSharedPhotos(on: Boolean) {
        _uiState.update { it.copy(showSharedPhotos = on) }
        settings?.let { repo -> writes?.enqueue { repo.setShowSharedPhotos(on) } }
    }

    /** "Rever o tour guiado" (docs/telas.md 3.16): todos os tours voltam a aparecer na próxima vez. */
    fun onTourToggle(on: Boolean) {
        _uiState.update { it.copy(tourAuto = on) }
        settings?.let { repo ->
            writes?.enqueue {
                // Ligar zera os vistos: os tours aparecem de novo.
                if (on) repo.resetTours()
                repo.setTourAuto(on)
            }
        }
    }

    /** Velocidade das animações; grava na hora pela fila durável. */
    fun onSpeedChange(slow: Boolean) {
        _uiState.update { it.copy(slowAnimations = slow) }
        settings?.let { repo -> writes?.enqueue { repo.setSlowAnimations(slow) } }
    }

    fun onName(raw: String) = edit { it.copy(name = PlayerProfile.sanitizeName(raw)) }

    /** §14.1 cor do medalhão (índice da paleta); índice fora dela é ignorado. */
    fun onColor(index: Int) = edit { if (index in 0 until PlayerProfile.COLORS) it.copy(color = index) else it }

    /** §14.1 cor do baralho (verso das cartas); grava ao salvar, como o resto do perfil. */
    fun onDeck(deck: com.gtranca.game.DeckColor) = edit { it.copy(deck = deck) }

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

    /** §14.1 tira a foto: o avatar volta a ser o medalhão (apaga o arquivo ao salvar). */
    fun onRemovePhoto() {
        touched = true
        _uiState.update { it.copy(photo = PhotoState.NONE, photoError = false) }
    }

    fun onPhotoErrorShown() = _uiState.update { it.copy(photoError = false) }

    /**
     * Grava o rascunho (nome sem espaços nas pontas) pela fila durável e devolve o perfil gravado. Nome em branco vira
     * [unnamed] ("Sem nome", vindo de strings.xml pela tela).
     */
    fun onSave(unnamed: String = ""): PlayerProfile {
        val state = _uiState.value
        val profile = state.profile.let {
            it.copy(name = it.name.trim().ifBlank { unnamed }, hasPhoto = state.photo != PhotoState.NONE)
        }
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
