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

/** Estado da tela de perfil: o rascunho em edição e se o perfil gravado já foi lido. */
data class ProfileUiState(val profile: PlayerProfile = PlayerProfile(), val loaded: Boolean = false)

/** Perfil do jogador (§14.1): edita um rascunho e grava ao salvar. Preferências ilegíveis: perfil padrão. */
class ProfileViewModel(
    private val settings: SettingsRepository? = null,
    private val writes: WriteQueue? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState(loaded = settings == null))
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu no rascunho: a leitura tardia do perfil gravado não o sobrescreve. */
    private var touched = false

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                val saved = PlayerProfile.decode(repo.settings.map { it.profileId }.catch { emit(null) }.first())
                _uiState.update { if (touched) it.copy(loaded = true) else ProfileUiState(saved, loaded = true) }
            }
        }
    }

    private fun edit(change: (PlayerProfile) -> PlayerProfile) {
        touched = true
        _uiState.update { it.copy(profile = change(it.profile)) }
    }

    fun onName(raw: String) = edit { it.copy(name = PlayerProfile.sanitizeName(raw)) }

    fun onGender(gender: Gender) = edit { it.withGender(gender) }

    fun onProfession(profession: Profession) = edit { it.copy(profession = profession) }

    fun onSkin(index: Int) = edit { it.copy(look = it.look.copy(skin = index)) }

    fun onHair(style: HairStyle) = edit { if (style.gender == it.gender) it.copy(look = it.look.copy(hair = style)) else it }

    fun onHairColor(index: Int) = edit { it.copy(look = it.look.copy(hairColor = index)) }

    fun onFace(face: FaceShape) = edit { it.copy(look = it.look.copy(face = face)) }

    fun onBeard(beard: Boolean) = edit { if (it.gender == Gender.MALE) it.copy(look = it.look.copy(beard = beard)) else it }

    /** Grava o rascunho (nome sem espaços nas pontas) pela fila durável e devolve o perfil gravado. */
    fun onSave(): PlayerProfile {
        val profile = _uiState.value.profile.let { it.copy(name = it.name.trim()) }
        val encoded = profile.encode()
        settings?.let { repo -> writes?.enqueue { repo.setProfile(encoded) } }
        return profile
    }
}
