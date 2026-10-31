package com.gtranca.online.economy

import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.PayoutKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** §18.2 / §14.6 / §18.5 aposta retida ao iniciar e liquidada no fim, idempotente, num Postgres de verdade. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyBetsTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val store = EconomyStore(db)

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun reasons(id: String): List<String> = db.connection.use { c ->
        c.prepareStatement("SELECT motivo FROM lancamento WHERE conta_id = ? AND moedas <> 0 ORDER BY id").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    @Test
    fun `18_2 a aposta e retida ao iniciar e repetir a retencao nao debita de novo`() {
        store.holdBets("b1", 50, listOf("g1", "g2")) shouldBe setOf("g1", "g2")
        store.holdBets("b1", 50, listOf("g1", "g2")) shouldBe setOf("g1", "g2")
        store.state("g1").balance shouldBe 950
        store.bet("b1", "g1") shouldBe (50L to "RETIDA")
        reasons("g1") shouldBe listOf("SALDO_INICIAL", "APOSTA")
    }

    @Test
    fun `14_6 sem saldo para a aposta o jogador fica de fora e nada e debitado`() {
        store.ensureAccount("pobre")
        db.connection.use { it.createStatement().execute("UPDATE carteira SET saldo = 30 WHERE conta_id = 'pobre'") }
        store.holdBets("b1", 50, listOf("pobre", "g1")) shouldBe setOf("g1")
        store.state("pobre").balance shouldBe 30
        store.bet("b1", "pobre") shouldBe null
    }

    @Test
    fun `14_6 premio, perda e devolucao uma vez so e o id da mesa repetido em outro jogo nao confunde`() {
        store.holdBets("ABC234-1", 50, listOf("g1", "g2", "g3"))
        val payouts = listOf(
            BetPayout("g1", PayoutKind.PREMIO, 100),
            BetPayout("g2", PayoutKind.PERDA, 0),
            BetPayout("g3", PayoutKind.DEVOLUCAO, 50),
        )
        store.settleBets("ABC234-1", payouts) shouldBe (3 to 0)
        store.settleBets("ABC234-1", payouts) shouldBe (0 to 3)
        store.state("g1").balance shouldBe 1_050
        store.state("g2").balance shouldBe 950
        store.state("g3").balance shouldBe 1_000
        reasons("g1") shouldBe listOf("SALDO_INICIAL", "APOSTA", "PREMIO")
        reasons("g3") shouldBe listOf("SALDO_INICIAL", "APOSTA", "DEVOLUCAO")
        store.bet("ABC234-1", "g2") shouldBe (50L to "LIQUIDADA")
        // Outro jogo na mesma mesa (outro id de aposta): nova retencao normal.
        store.holdBets("ABC234-2", 50, listOf("g1")) shouldBe setOf("g1")
        store.state("g1").balance shouldBe 1_000
    }

    @Test
    fun `14_6 nunca se ganha sem apostar e valores fora da regra sao recusados`() {
        store.ensureAccount("g9")
        shouldThrow<EconomyException> { store.settleBets("b1", listOf(BetPayout("g9", PayoutKind.PREMIO, 100))) }
            .code shouldBe EconomyException.Code.APOSTA_INEXISTENTE
        store.holdBets("b1", 50, listOf("g1"))
        shouldThrow<EconomyException> { store.settleBets("b1", listOf(BetPayout("g1", PayoutKind.DEVOLUCAO, 60))) }
            .code shouldBe EconomyException.Code.REQUISICAO_INVALIDA
        shouldThrow<EconomyException> { store.settleBets("b1", listOf(BetPayout("g1", PayoutKind.PREMIO, 201))) }
            .code shouldBe EconomyException.Code.REQUISICAO_INVALIDA
        store.bet("b1", "g1") shouldBe (50L to "RETIDA")
    }

    @Test
    fun `18_5 liquidacoes e retencoes simultaneas nunca pagam duas vezes nem deixam saldo negativo`() {
        store.holdBets("b1", 50, listOf("g1", "g2"))
        val pool = Executors.newFixedThreadPool(8)
        val payouts = listOf(BetPayout("g1", PayoutKind.PREMIO, 100), BetPayout("g2", PayoutKind.PERDA, 0))
        val jobs = (1..20).map { i ->
            pool.submit<Unit> {
                if (i % 2 == 0) store.settleBets("b1", payouts) else store.holdBets("b-$i", 50, listOf("g2", "g1"))
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        jobs.forEach { it.get() }
        store.state("g1").balance shouldBe 1_050L - 10 * 50
        store.state("g2").balance shouldBe 950L - 10 * 50
    }

    @Test
    fun `18_5 devolver uma retencao parcial nao falha e a retencao atrasada depois da devolucao nao debita`() {
        store.ensureAccount("pobre")
        db.connection.use { it.createStatement().execute("UPDATE carteira SET saldo = 30 WHERE conta_id = 'pobre'") }
        store.holdBets("b1", 50, listOf("g1", "pobre")) shouldBe setOf("g1")
        val refund = listOf(BetPayout("g1", PayoutKind.DEVOLUCAO, 50), BetPayout("pobre", PayoutKind.DEVOLUCAO, 50))
        store.settleBets("b1", refund) shouldBe (1 to 1)
        store.state("g1").balance shouldBe 1_000
        store.state("pobre").balance shouldBe 30
        // Devolucao chegou antes da retencao (tempo esgotado na rede): a retencao atrasada nao debita.
        store.settleBets("b2", listOf(BetPayout("g2", PayoutKind.DEVOLUCAO, 50)))
        store.holdBets("b2", 50, listOf("g2")) shouldBe emptySet()
        store.state("g2").balance shouldBe 1_000
    }
}
