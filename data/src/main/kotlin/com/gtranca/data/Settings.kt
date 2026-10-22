package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import kotlinx.serialization.json.Json
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
 * @property slowAnimations velocidade das animações: `true` = 30% mais lentas (padrão), `false` = normal.
 * @property wifiOn §16 Wi-Fi do jogo (Perfil): multijogador por perto ligado (desligado por padrão).
 * @property showSharedPhotos §16.2 ver as fotos dos outros jogadores nas mesas multijogador (ligado por padrão).
 * @property toursSeen tours guiados já vistos (um id por tela; docs/telas.md 3.16).
 * @property customRules §14.4 regras personalizadas do jogador, ou `null` se ainda não salvou (a interface parte da Clássica).
 */
data class Settings(
    val lastMode: GameMode = GameMode.INDIVIDUAL,
    val handSortId: String? = null,
    val profileId: String? = null,
    val soundOn: Boolean = true,
    val slowAnimations: Boolean = true,
    val customRules: RuleSet? = null,
    val wifiOn: Boolean = false,
    val showSharedPhotos: Boolean = true,
    val toursSeen: Set<String> = emptySet(),
)

interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun setLastMode(mode: GameMode)

    suspend fun setHandSort(id: String)

    suspend fun setProfile(id: String) = Unit

    suspend fun setSoundOn(on: Boolean) = Unit

    suspend fun setSlowAnimations(slow: Boolean) = Unit

    suspend fun setCustomRules(rules: RuleSet) = Unit

    suspend fun setWifiOn(on: Boolean) = Unit

    suspend fun setShowSharedPhotos(on: Boolean) = Unit

    /** O tour [id] já foi visto (não aparece sozinho de novo). */
    suspend fun markTourSeen(id: String) = Unit

    /** "Rever o tour guiado": todos os tours voltam a aparecer na próxima vez. */
    suspend fun resetTours() = Unit
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
            slowAnimations = prefs[SLOW_ANIMATIONS] ?: true,
            customRules = prefs[CUSTOM_RULES]?.let(::decodeRules),
            wifiOn = prefs[WIFI_ON] ?: false,
            showSharedPhotos = prefs[SHOW_SHARED_PHOTOS] ?: true,
            toursSeen = prefs[TOURS_SEEN] ?: emptySet(),
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

    override suspend fun setSlowAnimations(slow: Boolean) {
        store.edit { it[SLOW_ANIMATIONS] = slow }
    }

    override suspend fun markTourSeen(id: String) {
        store.edit { it[TOURS_SEEN] = (it[TOURS_SEEN] ?: emptySet()) + id }
    }

    override suspend fun resetTours() {
        store.edit { it.remove(TOURS_SEEN) }
    }

    override suspend fun setShowSharedPhotos(on: Boolean) {
        store.edit { it[SHOW_SHARED_PHOTOS] = on }
    }

    override suspend fun setWifiOn(on: Boolean) {
        store.edit { it[WIFI_ON] = on }
    }

    override suspend fun setCustomRules(rules: RuleSet) {
        store.edit { it[CUSTOM_RULES] = RULES_JSON.encodeToString(RuleSet.serializer(), rules) }
    }

    /** Texto ilegível ou valores fora das faixas (§14.2): como se não houvesse regras salvas. */
    private fun decodeRules(text: String): RuleSet? =
        runCatching { RULES_JSON.decodeFromString(RuleSet.serializer(), text) }.getOrNull()

    private companion object {
        val SOUND_ON = booleanPreferencesKey("sound_on")
        val WIFI_ON = booleanPreferencesKey("wifi_on")
        val SHOW_SHARED_PHOTOS = booleanPreferencesKey("show_shared_photos")
        val TOURS_SEEN = androidx.datastore.preferences.core.stringSetPreferencesKey("tours_seen")
        val SLOW_ANIMATIONS = booleanPreferencesKey("slow_animations")
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULES_JSON = Json { ignoreUnknownKeys = true }
        val PROFILE = stringPreferencesKey("profile")
        val LAST_MODE = stringPreferencesKey("last_mode")
        val HAND_SORT = stringPreferencesKey("hand_sort")
    }
}
