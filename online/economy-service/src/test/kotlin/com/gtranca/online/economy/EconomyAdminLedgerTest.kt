package com.gtranca.online.economy

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminAdjustRequest
import com.gtranca.online.contracts.AdminLedgerPage
import com.gtranca.online.contracts.AdminMigrationsPage
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * §18.5/§18.6/§18.7, tela Economia: livro-razao de uma conta, ajustar saldo/booster (motivo e chave de
 * idempotencia obrigatorios, nunca saldo/quantidade negativos) e migracoes de carteira local ja feitas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyAdminLedgerTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val internalSecret = "segredo-interno"
    private val adminSecret = "segredo-admin"

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun app(block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            economyModule(EconomyDeps(EconomyStore(db, clock = { now }), internalSecret = internalSecret, adminSecret = adminSecret))
        }
        client.block()
    }

    private fun ledger(id: String) = "/internal/admin/accounts/$id/ledger"
    private fun adjust(id: String) = "/internal/admin/accounts/$id/adjust"
    private val migrations = "/internal/admin/migrations"

    private suspend fun HttpClient.adminGet(path: String): HttpResponse = get(path) {
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
    }

    private suspend fun HttpClient.adminPost(path: String, body: String): HttpResponse = post(path) {
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.error(): EconomyErrorCode = json.decodeFromString<EconomyError>(bodyAsText()).code

    // ------------------------------------------------------------------------------------------------ livro-razao

    @Test
    fun `18_5 e 18_7 o livro-razao lista mais recente primeiro, paginado, e conta inexistente da 404`() = app {
        EconomyStore(db, clock = { now }).ensureAccount("g1") // SALDO_INICIAL + INVENTARIO_INICIAL

        val page = json.decodeFromString<AdminLedgerPage>(adminGet(ledger("g1")).bodyAsText())
        page.total shouldBe (1 + com.gtranca.engine.BoosterCatalog.DEFAULT.size) // 1 lancamento de moedas + 1 por booster inicial
        // Mais recente primeiro: SALDO_INICIAL foi o 1o gravado (ensureAccount grava ele antes dos boosters),
        // entao e o ULTIMO da pagina (ordenado por id DESC).
        val saldoInicial = page.entries.last()
        saldoInicial.reason shouldBe "SALDO_INICIAL"
        saldoInicial.coins shouldBe 1_000L
        saldoInicial.key shouldBe "saldo-inicial"

        val missing = adminGet(ledger("ninguem"))
        missing.status shouldBe HttpStatusCode.NotFound
        missing.error() shouldBe EconomyErrorCode.CONTA_INEXISTENTE
    }

    @Test
    fun `18_7 o livro-razao respeita limit e offset`() = app {
        val store = EconomyStore(db, clock = { now })
        store.ensureAccount("g1")
        store.buy("g1", 0, "k1")
        store.buy("g1", 0, "k2")

        val full = json.decodeFromString<AdminLedgerPage>(adminGet(ledger("g1")).bodyAsText())
        full.total shouldBe full.entries.size

        val firstPage = json.decodeFromString<AdminLedgerPage>(adminGet("${ledger("g1")}?limit=1&offset=0").bodyAsText())
        val secondPage = json.decodeFromString<AdminLedgerPage>(adminGet("${ledger("g1")}?limit=1&offset=1").bodyAsText())
        firstPage.entries.size shouldBe 1
        secondPage.entries.size shouldBe 1
        firstPage.total shouldBe full.total
        (firstPage.entries.first().key == secondPage.entries.first().key) shouldBe false
    }

    // ------------------------------------------------------------------------------------------------ ajustar

    @Test
    fun `18_7 ajustar moedas soma ou subtrai, grava motivo AJUSTE com o texto do operador, e e idempotente`() = app {
        EconomyStore(db, clock = { now }).ensureAccount("g1")

        val up = adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("corrigindo um bug", "op-1", coinsDelta = 500)))
        up.status shouldBe HttpStatusCode.OK
        json.decodeFromString<AdminWalletDto>(up.bodyAsText()).coins shouldBe 1_500L

        // Repetir a MESMA chamada (retry de rede) nao soma de novo.
        val again = adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("corrigindo um bug", "op-1", coinsDelta = 500)))
        json.decodeFromString<AdminWalletDto>(again.bodyAsText()).coins shouldBe 1_500L

        val down = adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("corrigindo de volta", "op-2", coinsDelta = -200)))
        json.decodeFromString<AdminWalletDto>(down.bodyAsText()).coins shouldBe 1_300L

        val entries = json.decodeFromString<AdminLedgerPage>(adminGet(ledger("g1")).bodyAsText()).entries
        entries.first { it.key == "ajuste-op-1" }.detail shouldBe "corrigindo um bug"
        entries.first { it.key == "ajuste-op-2" }.detail shouldBe "corrigindo de volta"
    }

    @Test
    fun `18_7 ajustar nunca deixa o saldo negativo, mesmo sendo o operador`() = app {
        EconomyStore(db, clock = { now }).ensureAccount("g1") // 1.000 moedas

        val r = adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("teste", "op-1", coinsDelta = -2_000)))
        r.status shouldBe HttpStatusCode.Conflict
        r.error() shouldBe EconomyErrorCode.SALDO_INSUFICIENTE
        json.decodeFromString<AdminWalletDto>(adminGet(wallet("g1")).bodyAsText()).coins shouldBe 1_000L
    }

    @Test
    fun `18_7 ajustar um booster soma ou subtrai, e nunca deixa a quantidade negativa`() = app {
        val boosterId = com.gtranca.engine.BoosterCatalog.DEFAULT.first().id
        EconomyStore(db, clock = { now }).ensureAccount("g1")
        val before = json.decodeFromString<AdminWalletDto>(adminGet(wallet("g1")).bodyAsText()).inventory[boosterId] ?: 0

        val up = adminPost(
            adjust("g1"),
            json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("deu premio", "op-1", boosterId = boosterId, quantityDelta = 3)),
        )
        json.decodeFromString<AdminWalletDto>(up.bodyAsText()).inventory[boosterId] shouldBe before + 3

        val tooMuch = adminPost(
            adjust("g1"),
            json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("teste", "op-2", boosterId = boosterId, quantityDelta = -999)),
        )
        tooMuch.status shouldBe HttpStatusCode.Conflict
        tooMuch.error() shouldBe EconomyErrorCode.SALDO_INSUFICIENTE
    }

    @Test
    fun `18_7 ajustar exige motivo, chave de idempotencia e ao menos um ajuste real`() = app {
        EconomyStore(db, clock = { now }).ensureAccount("g1")

        adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("", "op-1", coinsDelta = 10)))
            .status shouldBe HttpStatusCode.BadRequest
        adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("motivo", "", coinsDelta = 10)))
            .status shouldBe HttpStatusCode.BadRequest
        adminPost(adjust("g1"), json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("motivo", "op-1")))
            .status shouldBe HttpStatusCode.BadRequest
        adminPost(
            adjust("g1"),
            json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("motivo", "op-1", boosterId = "qualquer")),
        ).status shouldBe HttpStatusCode.BadRequest // booster sem quantidade
        adminPost(
            adjust("g1"),
            json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("motivo", "op-1", boosterId = "booster-que-nao-existe", quantityDelta = 1)),
        ).error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_7 ajustar exige o segredo do console, nunca o servico a servico`() = app {
        post(adjust("g1")) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest("motivo", "op-1", coinsDelta = 10)))
        }.status shouldBe HttpStatusCode.Unauthorized
    }

    // ------------------------------------------------------------------------------------------------ migracoes

    @Test
    fun `18_6 e 18_7 as migracoes de carteira local aparecem na lista, so a linha de moedas, mais recente primeiro`() = app {
        val boosterId = com.gtranca.engine.BoosterCatalog.DEFAULT.first().id
        val store = EconomyStore(db, clock = { now })
        store.migrateLocalWallet("E_conta1", 300L, mapOf(boosterId to 2))
        store.migrateLocalWallet("E_conta2", 700L, emptyMap())

        val page = json.decodeFromString<AdminMigrationsPage>(adminGet(migrations).bodyAsText())
        page.total shouldBe 2
        page.migrations.map { it.accountId to it.coins }.toSet() shouldBe setOf("E_conta1" to 300L, "E_conta2" to 700L)
        // Nunca a linha do booster migrado (motivo igual, mas com booster_id preenchido).
        page.migrations.size shouldBe 2
    }

    @Test
    fun `18_7 migracoes nao exige id de conta e paginacao funciona`() = app {
        val store = EconomyStore(db, clock = { now })
        repeat(3) { i -> store.migrateLocalWallet("E_conta$i", 100L, emptyMap()) }

        val page = json.decodeFromString<AdminMigrationsPage>(adminGet("$migrations?limit=2&offset=0").bodyAsText())
        page.total shouldBe 3
        page.migrations.size shouldBe 2
    }

    private fun wallet(id: String) = "/internal/admin/accounts/$id/wallet"
}
