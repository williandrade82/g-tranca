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
        s.inventory shouldBe mapOf("b1" to 5, "b2" to 5, "b3" to 5, "b4" to 5)
        store.ensureAccount("g1") shouldBe s
    }

    @Test
    fun `14_6 ganho diario de 200 uma vez por dia real, decidido pelo relogio do servidor`() {
        // (a) resgate normal funciona.
        var now = 1_700_000_000_000L
        val clockStore = EconomyStore(db, clock = { now })
        clockStore.ensureAccount("g1")
        clockStore.claimDaily("g1", today = 1).balance shouldBe 1_200

        // (b) resgatar de novo no MESMO dia real falha, mesmo que o app mande um "today" bem adiantado.
        shouldThrow<EconomyException> { clockStore.claimDaily("g1", today = 2) }.code shouldBe EconomyException.Code.JA_RESGATADO
        shouldThrow<EconomyException> { clockStore.claimDaily("g1", today = 999) }.code shouldBe EconomyException.Code.JA_RESGATADO
        clockStore.state("g1").balance shouldBe 1_200

        // (c) no dia seguinte REAL (relogio do servidor avanca 24h), o resgate funciona, mesmo com "today" atrasado.
        now += 24 * 60 * 60 * 1_000L
        clockStore.claimDaily("g1", today = 0).balance shouldBe 1_400
    }

    @Test
    fun `14_6 a chave do ganho diario usa o dia do servidor, nao o today do cliente (livro-razao idempotente)`() {
        // (d) mesmo perdendo o "ultimo_resgate" (corrida, bug), a chave do lancamento - baseada no dia do SERVIDOR -
        // ainda impede dois creditos para o mesmo dia real, mesmo com "today" bem diferente do cliente.
        var now = 1_700_000_000_000L
        val clockStore = EconomyStore(db, clock = { now })
        clockStore.ensureAccount("g2")
        clockStore.claimDaily("g2", today = 5).balance shouldBe 1_200
        db.connection.use { it.createStatement().execute("UPDATE carteira SET ultimo_resgate = NULL WHERE conta_id = 'g2'") }
        shouldThrow<EconomyException> { clockStore.claimDaily("g2", today = 500) }.code shouldBe EconomyException.Code.JA_RESGATADO
        clockStore.state("g2").balance shouldBe 1_200
    }

    @Test
    fun `14_6 o dia vira na meia-noite UTC do servidor, resgatar pouco depois da virada ja libera de novo`() {
        // Documenta que a regra e por DIA DE CALENDARIO (UTC), nao por "24 horas corridas": perto da virada, um
        // resgate pode liberar outro so alguns segundos de relogio real depois.
        val midnightUtcMs = (1_700_000_000_000L / 86_400_000L) * 86_400_000L + 86_400_000L
        var now = midnightUtcMs - 1_000 // 23:59:59 UTC
        val clockStore = EconomyStore(db, clock = { now })
        clockStore.ensureAccount("g3")
        clockStore.claimDaily("g3", today = 0).balance shouldBe 1_200
        now = midnightUtcMs + 2_000 // 00:00:02 UTC do dia seguinte, so 3s de relogio real depois
        clockStore.claimDaily("g3", today = 0).balance shouldBe 1_400
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

    // §18.6: a carteira local do aparelho nao tem representacao nenhuma no servidor (nenhuma tabela, nenhum campo) -
    // os testes abaixo so podem provar que o servidor aceita e aplica exatamente o que a CHAMADA manda, nunca algo
    // que ele "ja sabia" sobre o aparelho (nao existe como ele saber).
    @Test
    fun `18_6 migra o saldo e cada booster informado, somando ao que ja existia na conta`() {
        val before = store.ensureAccount("E_a")
        before.migratedLocal shouldBe false
        val after = store.migrateLocalWallet("E_a", 500, mapOf("b1" to 3, "b2" to 0))
        after.balance shouldBe before.balance + 500
        after.inventory["b1"] shouldBe before.inventory.getValue("b1") + 3
        after.inventory["b2"] shouldBe before.inventory["b2"] // quantidade 0 no pedido: nao soma nada
        after.migratedLocal shouldBe true
        store.state("E_a") shouldBe after
    }

    @Test
    fun `18_6 booster fora do catalogo e recusado (nunca perdido silenciosamente numa operacao de uma vez so)`() {
        val before = store.ensureAccount("E_x")
        shouldThrow<EconomyException> { store.migrateLocalWallet("E_x", 500, mapOf("x9" to 10)) }.code shouldBe
            EconomyException.Code.REQUISICAO_INVALIDA
        store.state("E_x") shouldBe before // nada migrou, nem o saldo: a tentativa inteira foi recusada
    }

    @Test
    fun `18_6 migrar de novo depois de ja ter migrado nao soma outra vez, nem da erro`() {
        store.ensureAccount("E_b")
        val after = store.migrateLocalWallet("E_b", 500, mapOf("b1" to 3))
        store.migrateLocalWallet("E_b", 999_999, mapOf("b1" to 50, "b2" to 50)) shouldBe after
        store.state("E_b") shouldBe after
    }

    @Test
    fun `18_6 convidado nao e elegivel para migrar a carteira local`() {
        store.ensureAccount("conv1")
        val before = store.state("conv1")
        shouldThrow<EconomyException> { store.migrateLocalWallet("conv1", 100, mapOf("b1" to 1)) }.code shouldBe
            EconomyException.Code.CONTA_NAO_ELEGIVEL
        store.state("conv1") shouldBe before // nada mudou na tentativa recusada
    }

    @Test
    fun `18_6 conta Google tambem e elegivel (so convidado nao e)`() {
        store.migrateLocalWallet("G_a", 10, emptyMap()).migratedLocal shouldBe true
    }

    @Test
    fun `18_6 saldo negativo ou absurdo e quantidade de booster negativa sao recusados, sem mudar nada`() {
        val before = store.ensureAccount("E_c")
        shouldThrow<EconomyException> { store.migrateLocalWallet("E_c", -1, emptyMap()) }.code shouldBe EconomyException.Code.REQUISICAO_INVALIDA
        shouldThrow<EconomyException> { store.migrateLocalWallet("E_c", Long.MAX_VALUE, emptyMap()) }.code shouldBe
            EconomyException.Code.REQUISICAO_INVALIDA
        shouldThrow<EconomyException> { store.migrateLocalWallet("E_c", 0, mapOf("b1" to -1)) }.code shouldBe
            EconomyException.Code.REQUISICAO_INVALIDA
        store.state("E_c") shouldBe before // nenhuma tentativa recusada mudou a conta nem gravou a chave de migracao
    }

    @Test
    fun `18_6 migracoes simultaneas da mesma conta migram uma vez so (sem teto, mas idempotente)`() {
        store.ensureAccount("E_d")
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map { pool.submit<Long> { store.migrateLocalWallet("E_d", 777, mapOf("b1" to 9)).balance } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        results.map { it.get() }.distinct().size shouldBe 1 // todas as chamadas veem o mesmo resultado final
        store.state("E_d").balance shouldBe 1_000L + 777
        store.state("E_d").inventory.getValue("b1") shouldBe 5 + 9
    }
}
