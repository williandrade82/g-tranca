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

/** Perfil, "Ações impedidas": o que a mesa mostra quando o motor recusa uma jogada (docs/telas.md 3.6, item 6d). */
enum class RefusalAlert {
    /** Nada aparece. */
    NONE,

    /** Faixa pequena perto da barra de ações, com uma frase simples do problema. */
    SUBTLE,

    /** Modal que explica o que aconteceu, a regra da mesa e o que fazer. */
    EXPLAIN,
}

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
 * @property refusalAlert Perfil, "Ações impedidas": como avisar de uma jogada recusada (explicar sempre por padrão).
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
    /** Tour guiado automático (Perfil): ligado, cada tela mostra o seu tour sozinha na primeira vez. */
    val tourAuto: Boolean = true,
    val refusalAlert: RefusalAlert = RefusalAlert.EXPLAIN,
    /** §14.4 última montagem da mesa (docs/telas.md 3.2), ou `null` se ainda não montou nenhuma. */
    val tableSetup: TableSetup? = null,
    /** §18.1 servidor online (campo temporário do Perfil, só no aparelho): `host` ou `host:porta`; vazio = online desligado. */
    val onlineServer: String = "",
    /** §18.1 convidado provisório no servidor online (fase 1; some quando entrar o login Google), ou `null` se ainda não entrou. */
    val onlineGuest: OnlineGuest? = null,
)

/**
 * Identidade de convidado no servidor online (§18.1, fase 1): [id], [name] e o [token] de acesso. O token fica só no
 * armazenamento privado do app; nunca vai em log nem em URL.
 */
@kotlinx.serialization.Serializable
data class OnlineGuest(val id: String, val name: String, val token: String) {
    override fun toString() = "OnlineGuest(id=$id, name=$name, token=***)"
}

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

    suspend fun setTourAuto(on: Boolean) = Unit

    suspend fun setRefusalAlert(alert: RefusalAlert) = Unit

    /** §14.4 grava a montagem da mesa (substitui a configuração antiga de "Criar mesa"). */
    suspend fun setTableSetup(setup: TableSetup) = Unit

    /** §18.1 grava o servidor online (sem espaços nas pontas; vazio desliga). Trocar de servidor esquece o convidado. */
    suspend fun setOnlineServer(server: String) = Unit

    /** §18.1 guarda (ou, com `null`, esquece) o convidado provisório do servidor online. */
    suspend fun setOnlineGuest(guest: OnlineGuest?) = Unit
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
            tourAuto = prefs[TOUR_AUTO] ?: true,
            refusalAlert = prefs[REFUSAL_ALERT]?.let { name -> RefusalAlert.entries.firstOrNull { it.name == name } } ?: RefusalAlert.EXPLAIN,
            // A configuração antiga de "Criar mesa" vale só enquanto não há montagem nova (tipo e nome da mesa ignorados).
            tableSetup = prefs[TABLE_SETUP]?.let(TableSetup::decode) ?: prefs[CREATE_TABLE]?.let(TableSetup::fromLegacy),
            onlineServer = prefs[ONLINE_SERVER].orEmpty(),
            onlineGuest = prefs[ONLINE_GUEST]?.let { text -> runCatching { RULES_JSON.decodeFromString(OnlineGuest.serializer(), text) }.getOrNull() },
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

    override suspend fun setTableSetup(setup: TableSetup) {
        store.edit {
            it[TABLE_SETUP] = setup.encode()
            it.remove(CREATE_TABLE)
        }
    }

    override suspend fun setOnlineServer(server: String) {
        store.edit {
            val clean = server.trim()
            // O convidado é do servidor: outro endereço, outro convidado.
            if (clean != (it[ONLINE_SERVER] ?: "")) it.remove(ONLINE_GUEST)
            if (clean.isEmpty()) it.remove(ONLINE_SERVER) else it[ONLINE_SERVER] = clean
        }
    }

    override suspend fun setOnlineGuest(guest: OnlineGuest?) {
        store.edit {
            if (guest == null) it.remove(ONLINE_GUEST) else it[ONLINE_GUEST] = RULES_JSON.encodeToString(OnlineGuest.serializer(), guest)
        }
    }

    override suspend fun setTourAuto(on: Boolean) {
        store.edit { it[TOUR_AUTO] = on }
    }

    override suspend fun setRefusalAlert(alert: RefusalAlert) {
        store.edit { it[REFUSAL_ALERT] = alert.name }
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
        val TOUR_AUTO = booleanPreferencesKey("tour_auto")
        val REFUSAL_ALERT = stringPreferencesKey("refusal_alert")
        /** Formato antigo (até a mesa montada): lido uma vez como [TableSetup.fromLegacy] e apagado ao gravar. */
        val CREATE_TABLE = stringPreferencesKey("create_table")
        val TABLE_SETUP = stringPreferencesKey("table_setup")
        val ONLINE_SERVER = stringPreferencesKey("online_server")
        val ONLINE_GUEST = stringPreferencesKey("online_guest")
        val TOURS_SEEN = androidx.datastore.preferences.core.stringSetPreferencesKey("tours_seen")
        val SLOW_ANIMATIONS = booleanPreferencesKey("slow_animations")
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val RULES_JSON = Json { ignoreUnknownKeys = true }
        val PROFILE = stringPreferencesKey("profile")
        val LAST_MODE = stringPreferencesKey("last_mode")
        val HAND_SORT = stringPreferencesKey("hand_sort")
    }
}
