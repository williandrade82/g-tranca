package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.DailyGift
import com.gtranca.engine.Inventory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/** §17 inventário de boosters: catálogo JSON, estoque, marcação, débito do seletor e presente diário. */
class InventoryRepositoryTest {

    /** DataStore em memória, como em [PersistenceTest]. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private val store = MemoryDataStore()
    private val repo = DataStoreInventoryRepository(store)

    @Test
    fun `§17_1 o JSON do catalogo e igual ao catalogo embutido do motor`() {
        val text = File("src/main/assets/boosters.json").readText()
        BoosterCatalogJson.parse(text) shouldBe BoosterCatalog.DEFAULT
    }

    @Test
    fun `§17_1 catalogo ilegivel, vazio ou de outra versao vira o embutido`() {
        BoosterCatalogJson.parse("não é json") shouldBe BoosterCatalog.DEFAULT
        BoosterCatalogJson.parse("""{"formatVersion":1,"boosters":[]}""") shouldBe BoosterCatalog.DEFAULT
        BoosterCatalogJson.parse("""{"formatVersion":9,"boosters":[{"id":"z","name":"Z","summary":"z","initialQuantity":1,"giftMin":1,"giftMax":1}]}""") shouldBe
            BoosterCatalog.DEFAULT
        // custo ausente vale 1 (padrão)
        BoosterCatalogJson.parse("""{"formatVersion":1,"boosters":[{"id":"z","name":"Z","summary":"z","initialQuantity":2,"giftMin":1,"giftMax":2}]}""")
            .single().cost shouldBe 1
    }

    @Test
    fun `§17_1 sem nada gravado o inventario e o inicial e a marcacao e vazia`() = runBlocking<Unit> {
        repo.inventory.first() shouldBe Inventory.initial()
        repo.selection.first() shouldBe emptyList()
    }

    @Test
    fun `§17_2 a marcacao e normalizada e fica gravada`() = runBlocking<Unit> {
        repo.setSelection(listOf("b2", "coins", "b2", "b3", "b1", "bx"))
        repo.selection.first() shouldBe listOf("b2", "b3", "b1")
    }

    @Test
    fun `§17_2 booster que ficou sem quantidade sai da marcacao`() = runBlocking<Unit> {
        repo.setSelection(listOf("b1", "b2"))
        // esgota o b1 (5 usos) e o b2 continua
        repeat(5) { repo.confirmLoadout(listOf("b1")) }
        repo.inventory.first().quantity("b1") shouldBe 0
        repo.selection.first() shouldBe listOf("b2")
    }

    @Test
    fun `§17_3 Seguir debita o custo, grava e mantem a marcacao`() = runBlocking<Unit> {
        repo.setSelection(listOf("b1", "b3"))
        val done = repo.confirmLoadout(listOf("b1", "b3"))
        done.boosters shouldBe listOf("b1", "b3")
        repo.inventory.first().quantities shouldBe mapOf("b1" to 4, "b2" to 5, "b3" to 4)
        repo.selection.first() shouldBe listOf("b1", "b3")
    }

    @Test
    fun `§17_3 sem selecionar nada nao debita`() = runBlocking<Unit> {
        repo.confirmLoadout(emptyList()).boosters shouldBe emptyList()
        repo.inventory.first() shouldBe Inventory.initial()
    }

    /** Uma semente que ganha (ou perde) o sorteio sobre o inventário inicial. */
    private fun seed(wins: Boolean): Int =
        (0..1_000).first { (DailyGift.draw(Inventory.initial(), BoosterCatalog.DEFAULT, Random(it)) != null) == wins }

    @Test
    fun `§17_5 o presente do dia entra no inventario e so ha um sorteio por dia`() = runBlocking<Unit> {
        val won = repo.claimGift(20_000, Random(seed(true))).shouldNotBeNull()
        val inv = repo.inventory.first()
        inv.quantity(won.boosterId) shouldBe 5 + won.quantity
        // o mesmo dia e dias anteriores não sorteiam de novo
        repo.claimGift(20_000, Random(seed(true))).shouldBeNull()
        repo.claimGift(19_999, Random(seed(true))).shouldBeNull()
        repo.inventory.first() shouldBe inv
        // o dia seguinte sorteia
        repo.claimGift(20_001, Random(seed(true))).shouldNotBeNull()
    }

    @Test
    fun `§17_5 o dia e marcado mesmo sem premio`() = runBlocking<Unit> {
        repo.claimGift(5, Random(seed(false))).shouldBeNull()
        repo.inventory.first() shouldBe Inventory.initial()
        // o dia já foi usado: uma semente que ganharia não vale hoje
        repo.claimGift(5, Random(seed(true))).shouldBeNull()
        repo.inventory.first() shouldBe Inventory.initial()
    }

    @Test
    fun `§17_5 quem tem 10 de todos nao recebe nada`() = runBlocking<Unit> {
        store.updateData { prefs ->
            prefs.toMutablePreferences().also {
                it[stringPreferencesKey("boosters:inventory")] =
                    Json.encodeToString(Inventory.serializer(), Inventory(BoosterCatalog.DEFAULT.associate { d -> d.id to 10 }))
            }
        }
        repeat(50) { repo.claimGift(100L + it, Random(it)).shouldBeNull() }
        repo.inventory.first().quantities.values.all { it == 10 } shouldBe true
    }

    @Test
    fun `§17 valor gravado ilegivel vale como inventario inicial`() = runBlocking<Unit> {
        store.updateData { prefs ->
            prefs.toMutablePreferences().also { it[stringPreferencesKey("boosters:inventory")] = "{lixo" }
        }
        repo.inventory.first() shouldBe Inventory.initial()
    }

    @Test
    fun `§17_3 jogo salvo guarda os boosters da partida e o antigo vale como confirmado e sem boosters`() {
        val json = Json { encodeDefaults = true }
        val saved = SavedGame(
            gameId = "g", config = SavedConfig(com.gtranca.engine.model.GameMode.INDIVIDUAL, "medio", 3000),
            gameSeed = 1, match = com.gtranca.engine.startMatch(com.gtranca.engine.model.GameMode.INDIVIDUAL, 3000, Random(1)),
            events = emptyList(), tableBoosters = com.gtranca.engine.TableBoosters.single(0, listOf("b1", "b3")).confirm(2, listOf("b2"), 1),
        )
        val back = json.decodeFromString(SavedGame.serializer(), json.encodeToString(SavedGame.serializer(), saved))
        back.tableBoosters shouldBe saved.tableBoosters
        back.tableBoosters.of(2).boosters shouldBe listOf("b2")

        // arquivo gravado antes dos boosters
        val old = json.parseToJsonElement(json.encodeToString(SavedGame.serializer(), saved)).let {
            kotlinx.serialization.json.JsonObject(
                (it as kotlinx.serialization.json.JsonObject).filterKeys { k -> k != "tableBoosters" },
            )
        }
        val legacy = json.decodeFromJsonElement(SavedGame.serializer(), old)
        legacy.tableBoosters shouldBe com.gtranca.engine.TableBoosters()
    }
}
