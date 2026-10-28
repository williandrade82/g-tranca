package com.gtranca.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.DailyGift
import com.gtranca.engine.Gift
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.random.Random

/**
 * Catálogo de boosters (§17.1): arquivo JSON local (`assets/boosters.json`), pensado para virar banco com
 * sincronização on-line depois. Só leitura.
 */
object BoosterCatalogJson {
    const val ASSET = "boosters.json"
    const val FORMAT_VERSION = 1

    @Serializable
    private data class File(val formatVersion: Int = FORMAT_VERSION, val boosters: List<BoosterDef>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Lê o catálogo de [text]; formato inválido ou de outra versão, ou catálogo vazio, vira o catálogo embutido. */
    fun parse(text: String): List<BoosterDef> = try {
        json.decodeFromString<File>(text).takeIf { it.formatVersion == FORMAT_VERSION && it.boosters.isNotEmpty() }
            ?.boosters?.distinctBy { it.id } ?: BoosterCatalog.DEFAULT
    } catch (_: Exception) {
        BoosterCatalog.DEFAULT
    }

    fun load(context: Context): List<BoosterDef> = try {
        parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (_: IOException) {
        BoosterCatalog.DEFAULT
    }
}

/**
 * Inventário local do jogador (§17): quantidade de cada booster, a marcação para a próxima partida (§17.2) e o último
 * dia do presente (§17.5). Cada operação que muda mais de um valor é uma só gravação.
 */
interface InventoryRepository {
    /** O catálogo em uso. */
    val catalog: List<BoosterDef>

    /** Sem nada gravado, a quantidade inicial de cada booster (§17.1). */
    val inventory: Flow<Inventory>

    /** A marcação do Inventário, sempre válida para o estoque de agora (sem repetidos, moedas ou quantidade 0; até 3). */
    val selection: Flow<List<String>>

    /** Grava a marcação ([ids] é normalizada). */
    suspend fun setSelection(ids: List<String>)

    /**
     * §17.3 Seguir: debita o custo de cada booster de [selection] e grava; devolve o que foi escolhido e o inventário
     * novo. A marcação do Inventário continua como estava (vale para a próxima partida).
     */
    suspend fun confirmLoadout(selection: List<String>): Loadout.Confirmed

    /**
     * §17.5 o presente do dia [today] (dias desde 1970): nulo se o dia já foi sorteado (ou o relógio está atrasado) ou
     * se o sorteio não deu prêmio; o dia é marcado em todo caso em que houve sorteio. O prêmio já entra no inventário.
     */
    suspend fun claimGift(today: Long, random: Random = Random.Default): Gift?
}

class DataStoreInventoryRepository(
    private val store: DataStore<Preferences>,
    override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT,
) : InventoryRepository {

    private val prefsFlow: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val inventory: Flow<Inventory> = prefsFlow.map { inventoryOf(it) }

    override val selection: Flow<List<String>> = prefsFlow.map { normalizedSelection(it, inventoryOf(it)) }

    override suspend fun setSelection(ids: List<String>) {
        store.edit { prefs -> prefs[SELECTION] = encodeIds(Loadout.normalize(ids, inventoryOf(prefs), catalog)) }
    }

    override suspend fun confirmLoadout(selection: List<String>): Loadout.Confirmed {
        var result: Loadout.Confirmed? = null
        store.edit { prefs ->
            val confirmed = Loadout.confirm(selection, inventoryOf(prefs), catalog)
            prefs[INVENTORY] = Json.encodeToString(Inventory.serializer(), confirmed.inventory)
            result = confirmed
        }
        return result!!
    }

    override suspend fun claimGift(today: Long, random: Random): Gift? {
        var gift: Gift? = null
        store.edit { prefs ->
            if (!DailyGift.isDue(today, prefs[LAST_GIFT_DAY])) return@edit
            val current = inventoryOf(prefs)
            gift = DailyGift.draw(current, catalog, random)
            gift?.let { prefs[INVENTORY] = Json.encodeToString(Inventory.serializer(), current.add(it.boosterId, it.quantity)) }
            prefs[LAST_GIFT_DAY] = today
        }
        return gift
    }

    /** Valor ilegível ou negativo (arquivo adulterado) vale como estoque inicial. */
    private fun inventoryOf(prefs: Preferences): Inventory =
        prefs[INVENTORY]?.let { text ->
            try {
                Json.decodeFromString(Inventory.serializer(), text)
            } catch (_: Exception) {
                null
            }
        } ?: Inventory.initial(catalog)

    private fun normalizedSelection(prefs: Preferences, inventory: Inventory): List<String> =
        Loadout.normalize(decodeIds(prefs[SELECTION]), inventory, catalog)

    private fun encodeIds(ids: List<String>): String = Json.encodeToString(ListSerializer(String.serializer()), ids)

    private fun decodeIds(text: String?): List<String> = text?.let {
        try {
            Json.decodeFromString(ListSerializer(String.serializer()), it)
        } catch (_: Exception) {
            null
        }
    }.orEmpty()

    private companion object {
        val INVENTORY = stringPreferencesKey("boosters:inventory")
        val SELECTION = stringPreferencesKey("boosters:selection")
        val LAST_GIFT_DAY = longPreferencesKey("boosters:last_gift_day")
    }
}
