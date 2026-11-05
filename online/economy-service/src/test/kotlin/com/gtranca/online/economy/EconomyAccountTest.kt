package com.gtranca.online.economy

import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** §18.5 "Do convidado para a conta" (plano 2.6). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyAccountTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val store = EconomyStore(db)

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun tipo(id: String): String = db.connection.use { c ->
        c.prepareStatement("SELECT tipo FROM conta WHERE id = ?").use { st -> st.setString(1, id); st.executeQuery().use { it.next(); it.getString(1) } }
    }

    @Test
    fun `18_5 a carteira do convidado passa para a conta nova, uma vez, e repetir nao muda nada`() {
        store.ensureAccount("conv1")
        store.buy("conv1", 0, "k1")
        store.claimDaily("conv1", 50)
        val guestBefore = store.state("conv1")
        val (migrated, acc) = store.adoptGuestWallet("G_a", "conv1")
        migrated shouldBe true
        acc.balance shouldBe guestBefore.balance
        acc.inventory shouldBe guestBefore.inventory
        // §14.6: o dia gravado e decidido pelo servidor (nao pelo "50" informativo que o teste passou); so precisa
        // ser o mesmo que ja estava no convidado antes de migrar.
        acc.lastDailyClaim shouldBe guestBefore.lastDailyClaim
        store.state("conv1").balance shouldBe 0
        store.state("conv1").inventory.values.all { it == 0 } shouldBe true
        tipo("G_a") shouldBe "GOOGLE"
        // vinculo repetido (mesmo login de novo): nada muda
        store.adoptGuestWallet("G_a", "conv1") shouldBe (false to acc)
    }

    @Test
    fun `18_5 conta que ja tem carteira nao recebe a do convidado, que fica com ele`() {
        store.adoptGuestWallet("G_a", null).second.balance shouldBe 1_000
        store.ensureAccount("conv2")
        store.claimDaily("conv2", 1)
        store.adoptGuestWallet("G_a", "conv2") shouldBe (false to store.state("G_a"))
        store.state("conv2").balance shouldBe 1_200
    }

    @Test
    fun `18_5 convidado ja migrado nao passa nada para outra conta, que nasce com o saldo inicial`() {
        store.ensureAccount("conv3")
        store.adoptGuestWallet("G_a", "conv3").first shouldBe true
        val (migrated, b) = store.adoptGuestWallet("G_b", "conv3")
        migrated shouldBe false
        b.balance shouldBe 1_000
        b.inventory shouldBe mapOf("b1" to 5, "b2" to 5, "b3" to 5)
    }

    @Test
    fun `18_5 conta Google criada por outra rota tem tipo GOOGLE`() {
        store.ensureAccount("G_x")
        tipo("G_x") shouldBe "GOOGLE"
    }

    @Test
    fun `18_1 conta de e-mail e senha (prefixo E_) tambem adota a carteira do convidado`() {
        store.ensureAccount("conv5")
        store.buy("conv5", 0, "k2")
        val guestBefore = store.state("conv5")
        val (migrated, acc) = store.adoptGuestWallet("E_a", "conv5")
        migrated shouldBe true
        acc.balance shouldBe guestBefore.balance
        tipo("E_a") shouldBe "EMAIL"
        store.state("conv5").balance shouldBe 0
        // vinculo repetido (mesmo login de novo): nada muda
        store.adoptGuestWallet("E_a", "conv5") shouldBe (false to acc)
    }

    @Test
    fun `18_5 logins simultaneos migram uma vez so`() {
        store.ensureAccount("conv4")
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map { pool.submit<Boolean> { runCatching { store.adoptGuestWallet("G_c", "conv4").first }.getOrDefault(false) } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        results.count { it.get() } shouldBe 1
        store.state("G_c").balance shouldBe 1_000
        store.state("conv4").balance shouldBe 0
    }
}
