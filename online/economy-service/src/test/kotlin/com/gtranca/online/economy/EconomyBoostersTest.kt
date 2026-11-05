package com.gtranca.online.economy

import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** §17.3/§18.3 debito dos boosters do seletor online, num Postgres de verdade (embutido, sem Docker). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyBoostersTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val store = EconomyStore(db)

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    @Test
    fun `17_3 debita 1 de cada booster escolhido e repetir a mesma partida e assento nao debita de novo`() {
        store.ensureAccount("g1") // nasce com b1=5, b2=5, b3=5 (§17.1)
        store.debitBoosters("ABC234-1", 0, "g1", listOf("b1", "b2")) shouldBe setOf("b1", "b2")
        store.state("g1").inventory shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 5)
        // Rede falhou depois de debitar: repetir a mesma chamada nao debita de novo.
        store.debitBoosters("ABC234-1", 0, "g1", listOf("b1", "b2")) shouldBe setOf("b1", "b2")
        store.state("g1").inventory shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 5)
    }

    @Test
    fun `17_3 booster sem estoque fica de fora, nunca interrompe os demais`() {
        store.ensureAccount("g1")
        repeat(5) { store.debitBoosters("jogo-$it", 0, "g1", listOf("b1")) } // esgota o b1 (5)
        store.state("g1").inventory.getValue("b1") shouldBe 0
        store.debitBoosters("ABC234-9", 1, "g1", listOf("b1", "b2")) shouldBe setOf("b2")
        store.state("g1").inventory shouldBe mapOf("b1" to 0, "b2" to 4, "b3" to 5)
    }

    @Test
    fun `17_3 id fora do catalogo e ignorado, e no maximo 3 boosters contam (Loadout_MAX)`() {
        store.ensureAccount("g1")
        store.debitBoosters("ABC234-1", 0, "g1", listOf("b1", "lixo", "b2", "b3", "b1")) shouldBe setOf("b1", "b2", "b3")
        store.state("g1").inventory shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 4)
    }

    @Test
    fun `17_3 assentos diferentes da mesma conta nao se confundem (chave inclui o assento)`() {
        // Caso hipotetico de teste: a mesma conta com uma chave por assento nao colide numa outra partida.
        store.ensureAccount("g1")
        store.debitBoosters("ABC234-1", 0, "g1", listOf("b1")) shouldBe setOf("b1")
        store.debitBoosters("ABC234-1", 1, "g1", listOf("b1")) shouldBe setOf("b1")
        store.state("g1").inventory.getValue("b1") shouldBe 3
    }

    @Test
    fun `17_3 selecao vazia nao debita nada`() {
        store.ensureAccount("g1")
        store.debitBoosters("ABC234-1", 0, "g1", emptyList()) shouldBe emptySet()
        store.state("g1").inventory shouldBe mapOf("b1" to 5, "b2" to 5, "b3" to 5)
    }
}
