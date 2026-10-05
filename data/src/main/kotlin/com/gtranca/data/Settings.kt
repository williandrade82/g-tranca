package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.model.GameMode
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import kotlinx.coroutines.flow.map

/**
 * Preferências do jogador.
 *
 * @property lastMode §14 último modo escolhido (individual na primeira vez).
 * @property handSortId ordem da mão preferida (identificador da interface), ou `null` para o padrão dela.
 * @property profileId §14.1 perfil do jogador (texto da interface), ou `null` para o padrão dela.
 * @property soundOn chave de som do jogador (ligado por padrão).
 */
data class Settings(
    val lastMode: GameMode = GameMode.INDIVIDUAL,
    val handSortId: String? = null,
    val profileId: String? = null,
    val soundOn: Boolean = true,
)

interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun setLastMode(mode: GameMode)

    suspend fun setHandSort(id: String)

    suspend fun setProfile(id: String) = Unit

    suspend fun setSoundOn(on: Boolean) = Unit
}

/** Preferências no DataStore Preferences. */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    /** Arquivo ilegível: valores padrão (nunca derruba o app). */
    override val settings: Flow<Settings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        Settings(
            lastMode = prefs[LAST_MODE]?.let { name -> GameMode.entries.firstOrNull { it.name == name } } ?: GameMode.INDIVIDUAL,
            handSortId = prefs[HAND_SORT],
            profileId = prefs[PROFILE],
            soundOn = prefs[SOUND_ON] ?: true,
        )
    }

    override suspend fun setLastMode(mode: GameMode) {
        store.edit { it[LAST_MODE] = mode.name }
    }

    override suspend fun setHandSort(id: String) {
        store.edit { it[HAND_SORT] = id }
    }

    override suspend fun setProfile(id: String) {
        store.edit { it[PROFILE] = id }
    }

    override suspend fun setSoundOn(on: Boolean) {
        store.edit { it[SOUND_ON] = on }
    }

    private companion object {
        val SOUND_ON = booleanPreferencesKey("sound_on")
        val PROFILE = stringPreferencesKey("profile")
        val LAST_MODE = stringPreferencesKey("last_mode")
        val HAND_SORT = stringPreferencesKey("hand_sort")
    }
}
