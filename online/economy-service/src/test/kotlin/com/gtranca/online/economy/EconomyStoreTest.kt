package com.gtranca.online.economy

import com.gtranca.engine.Shop
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** §18.5 carteira e inventário online num Postgres de verdade (embutido, sem Docker). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyStoreTest {
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
    fun `18_5 o convidado nasce com 1000 moedas e os boosters iniciais, e criar de novo nao muda nada`() {
        val s = store.ensureAccount("g1")
        s.balance shouldBe 1_000
        s.inventory shouldBe mapOf("b1" to 5, "b2" to 5, "b3" to 5)
        store.ensureAccount("g1") shouldBe s
    }

    @Test
    fun `14_6 ganho diario de 200 uma vez por dia e o relogio atrasado nao libera de novo`() {
        store.ensureAccount("g1")
        store.claimDaily("g1", today = 100).balance shouldBe 1_200
        shouldThrow<EconomyException> { store.claimDaily("g1", today = 100) }.code shouldBe EconomyException.Code.JA_RESGATADO
        shouldThrow<EconomyException> { store.claimDaily("g1", today = 99) }.code shouldBe EconomyException.Code.JA_RESGATADO
        store.claimDaily("g1", today = 101).balance shouldBe 1_400
    }

    @Test
    fun `17_10 a compra debita e soma os boosters juntos e repetir a mesma chave nao cobra de novo`() {
        store.ensureAccount("g1")
        val offer = Shop.OFFERS[0]
        val after = store.buy("g1", 0, key = "pedido-1")
        after.balance shouldBe 1_000L - offer.price
        after.inventory.getValue(offer.boosterId) shouldBe 5 + offer.quantity
        store.buy("g1", 0, key = "pedido-1") shouldBe after
    }

    @Test
    fun `17_10 saldo menor que o valor nao compra e nada muda`() {
        store.ensureAccount("g1")
        val price = Shop.OFFERS[0].price
        repeat(1_000 / price) { store.buy("g1", 0, key = "p$it") } // gasta até não caber outra
        val before = store.state("g1")
        (before.balance < price) shouldBe true
        shouldThrow<EconomyException> { store.buy("g1", 0, key = "sem-saldo") }.code shouldBe EconomyException.Code.SALDO_INSUFICIENTE
        store.state("g1") shouldBe before
    }

    @Test
    fun `18_5 compras simultaneas nunca deixam o saldo negativo`() {
        store.ensureAccount("g1")
        val price = Shop.OFFERS[0].price
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..40).map { i -> pool.submit<Boolean> { runCatching { store.buy("g1", 0, key = "c$i") }.isSuccess } }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        val ok = results.count { it.get() }
        val s = store.state("g1")
        (s.balance >= 0) shouldBe true
        s.balance shouldBe 1_000L - ok * price
        ok shouldBe (1_000 / price)
    }

    @Test
    fun `18_5 conta inexistente e oferta inexistente sao recusadas`() {
        shouldThrow<EconomyException> { store.claimDaily("ninguem", 1) }.code shouldBe EconomyException.Code.CONTA_INEXISTENTE
        store.ensureAccount("g1")
        shouldThrow<EconomyException> { store.buy("g1", 999, "x") }.code shouldBe EconomyException.Code.OFERTA_INEXISTENTE
    }
}
