package com.gtranca.online.economy

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** §18.7: rota de LEITURA do console de gerenciamento (admin-api -> economy-service), com segredo e versao proprios. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyAdminTest {
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

    private fun app(adminSecretConfigured: String? = adminSecret, block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            economyModule(
                EconomyDeps(EconomyStore(db, clock = { now }), internalSecret = internalSecret, adminSecret = adminSecretConfigured),
            )
        }
        client.block()
    }

    private fun wallet(id: String) = "/internal/admin/accounts/$id/wallet"

    /** GET com o segredo e a versao certos por padrao; `null` omite o cabecalho, outro valor o substitui. */
    private suspend fun HttpClient.adminGet(path: String, secret: String? = adminSecret, version: Int? = ADMIN_API_VERSION): HttpResponse =
        get(path) {
            if (secret != null) header(ADMIN_SECRET_HEADER, secret)
            if (version != null) header(ADMIN_API_VERSION_HEADER, version)
        }

    @Test
    fun `18_7 sem o cabecalho, com o segredo errado ou com o INTERNAL_SECRET a rota recusa`() = app {
        adminGet(wallet("g1"), secret = null).status shouldBe HttpStatusCode.Unauthorized
        adminGet(wallet("g1"), secret = "chave-qualquer-errada").status shouldBe HttpStatusCode.Unauthorized
        // O segredo servico a servico (lobby/game/economy) nao abre a rota do console.
        adminGet(wallet("g1"), secret = internalSecret).status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_7 sem ADMIN_INTERNAL_SECRET configurado a rota fica desativada (503)`() = app(adminSecretConfigured = null) {
        adminGet(wallet("g1")).status shouldBe HttpStatusCode.ServiceUnavailable
    }

    @Test
    fun `18_7 e 18_8 versao dos contratos administrativos ausente ou diferente e recusada`() = app {
        adminGet(wallet("g1"), version = null).status shouldBe HttpStatusCode.BadRequest
        adminGet(wallet("g1"), version = ADMIN_API_VERSION - 1).status shouldBe HttpStatusCode.BadRequest
        adminGet(wallet("g1"), version = ADMIN_API_VERSION + 1).status shouldBe HttpStatusCode.ServiceUnavailable
    }

    @Test
    fun `18_7 o segredo do console nunca abre as rotas internas servico a servico, nem pelo valor no cabecalho certo delas`() = app {
        get("/internal/economy/accounts/g1") { header(ADMIN_SECRET_HEADER, adminSecret) }.status shouldBe HttpStatusCode.Unauthorized
        // Cenario de vazamento de verdade: o VALOR do segredo do admin, no cabecalho que a rota antiga confere.
        post("/internal/economy/bets/b1/hold") {
            header(INTERNAL_SECRET_HEADER, adminSecret)
            contentType(ContentType.Application.Json)
            setBody("""{"amount":50,"accounts":["g1"]}""")
        }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_7 conta inexistente da 404 e nao a cria (sem efeito colateral)`() = app {
        val r = adminGet(wallet("ninguem"))
        r.status shouldBe HttpStatusCode.NotFound
        // Confirma que a consulta do console nao criou a conta (como EconomyStore.ensureAccount faria).
        val store = EconomyStore(db, clock = { now })
        runCatching { store.state("ninguem") }.isFailure shouldBe true
    }

    @Test
    fun `18_7 conta existente devolve saldo, inventario e migracao, sem o segredo servico a servico`() = app {
        // A conta nasce por uma operacao normal (nunca pela consulta do console).
        EconomyStore(db, clock = { now }).ensureAccount("g1")

        val r = adminGet(wallet("g1"))
        r.status shouldBe HttpStatusCode.OK
        val dto = json.decodeFromString<AdminWalletDto>(r.bodyAsText())
        dto.coins shouldBe 1_000L
        dto.migratedLocal shouldBe false

        // O segredo servico a servico (INTERNAL_SECRET) nao serve para esta rota nova.
        get(wallet("g1")) { header(INTERNAL_SECRET_HEADER, internalSecret) }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_7 id invalido (maior que o limite) da 400, nao 500`() = app {
        adminGet(wallet("g".repeat(65))).status shouldBe HttpStatusCode.BadRequest
    }
}
