package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminAdjustRequest
import com.gtranca.online.contracts.AdminLedgerEntryDto
import com.gtranca.online.contracts.AdminLedgerPage
import com.gtranca.online.contracts.AdminMigrationDto
import com.gtranca.online.contracts.AdminMigrationsPage
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.ErrorCode
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Sec18.7 tela Economia (docs/telas-admin-web.md): livro-razao, ajustar saldo/booster e migracoes globais. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminEconomyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pg = EmbeddedPostgres.start()
    private val db: DataSource = pg.postgresDatabase.also { Database.migrate(it) }

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA IF EXISTS admin CASCADE") }
        Database.migrate(db)
    }

    @AfterAll
    fun stop() = pg.close()

    private fun deps(economyAdmin: EconomyAdminGateway? = null) =
        AdminDeps(store = TeamStore(db, passwordCost = 4), sessions = SessionStore(db), ipLimitPerMinute = 1_000, economyAdmin = economyAdmin)

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    /** Cadastra o administrador semente e mais um membro com [role], devolve o token do membro novo. */
    private suspend fun HttpClient.loginAs(d: AdminDeps, role: Role): String {
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        val adminToken = json.decodeFromString(LoginResponse.serializer(), login("admin@exemplo.com", "segredo123").bodyAsText()).token
        post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest("membro@exemplo.com", "segredo123", role, "cadastro para o teste")))
        }
        return json.decodeFromString(LoginResponse.serializer(), login("membro@exemplo.com", "segredo123").bodyAsText()).token
    }

    private fun auditRows(action: String): List<Map<String, String?>> = db.connection.use { c ->
        c.prepareStatement("SELECT ator_email, acao, alvo_id, motivo, detalhe FROM admin.auditoria WHERE acao = ?").use { st ->
            st.setString(1, action)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(mapOf("ator_email" to rs.getString(1), "acao" to rs.getString(2), "alvo_id" to rs.getString(3), "motivo" to rs.getString(4), "detalhe" to rs.getString(5)))
                    }
                }
            }
        }
    }

    private val entrada = AdminLedgerEntryDto(reason = "SALDO_INICIAL", coins = 1_000L, key = "saldo-inicial", createdMs = 1L)
    private val carteira = AdminWalletDto(coins = 1_500L, inventory = mapOf("ESPIAR" to 2), migratedLocal = false)

    // -------------------------------------------------------------------------------------------------------- acesso

    @Test
    fun `18_7 sem token da 401 no livro-razao, no ajuste e nas migracoes`() = testApplication {
        application { adminModule(deps()) }
        client.get("/admin/accounts/G_1/ledger").status.value shouldBe 401
        client.post("/admin/accounts/G_1/adjust") { setBody("""{"reason":"x","operationId":"op-1","coinsDelta":1}""") }.status.value shouldBe 401
        client.get("/admin/migrations").status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) ve o livro-razao e as migracoes, mas nao pode ajustar`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun ledger(id: String, limit: Int, offset: Int) = AdminLedgerPage(total = 1, entries = listOf(entrada))
            override suspend fun migrations(limit: Int, offset: Int) = AdminMigrationsPage(total = 0, migrations = emptyList())
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        client.get("/admin/accounts/G_1/ledger") { header("Authorization", "Bearer $token") }.status.value shouldBe 200
        client.get("/admin/migrations") { header("Authorization", "Bearer $token") }.status.value shouldBe 200

        val adjust = client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody("""{"reason":"x","operationId":"op-1","coinsDelta":1}""")
        }
        adjust.status.value shouldBe 403
        json.decodeFromString(ApiError.serializer(), adjust.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO
    }

    // ------------------------------------------------------------------------------------------------- livro-razao

    @Test
    fun `18_7 livro-razao repassa limit e offset, conta inexistente da 404`() = testApplication {
        var receivedLimit: Int? = null
        var receivedOffset: Int? = null
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun ledger(id: String, limit: Int, offset: Int): AdminLedgerPage? {
                receivedLimit = limit; receivedOffset = offset
                return if (id == "G_1") AdminLedgerPage(total = 1, entries = listOf(entrada)) else null
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        val r = client.get("/admin/accounts/G_1/ledger?limit=5&offset=10") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(LedgerPageResponse.serializer(), r.bodyAsText())
        body.total shouldBe 1
        body.items shouldBe listOf(entrada)
        body.limit shouldBe 5
        body.offset shouldBe 10
        receivedLimit shouldBe 5
        receivedOffset shouldBe 10

        val missing = client.get("/admin/accounts/ninguem/ledger") { header("Authorization", "Bearer $token") }
        missing.status.value shouldBe 404
        json.decodeFromString(ApiError.serializer(), missing.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado o livro-razao recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)
        client.get("/admin/accounts/G_1/ledger") { header("Authorization", "Bearer $token") }.status.value shouldBe 503
    }

    @Test
    fun `18_7 economy-service fora do ar no livro-razao da 503, nao 500`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun ledger(id: String, limit: Int, offset: Int) = throw EconomyAdminGatewayException("timeout")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)
        val r = client.get("/admin/accounts/G_1/ledger") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 503
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.SERVICO_INDISPONIVEL
    }

    // ----------------------------------------------------------------------------------------------------- ajustar

    @Test
    fun `18_7 operador ajusta moedas, chama o economy-service e grava a auditoria com o delta`() = testApplication {
        var chamou: Pair<String, AdminAdjustRequest>? = null
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto {
                chamou = id to request
                return carteira
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val req = AdminAdjustRequest(reason = "corrigindo um bug", operationId = "op-1", coinsDelta = 500)
        val r = client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), req))
        }
        r.status.value shouldBe 200
        json.decodeFromString(AdminWalletDto.serializer(), r.bodyAsText()) shouldBe carteira
        chamou shouldBe ("G_1" to req)

        val rows = auditRows("AJUSTAR_CARTEIRA")
        rows.size shouldBe 1
        rows.first()["ator_email"] shouldBe "membro@exemplo.com"
        rows.first()["alvo_id"] shouldBe "G_1"
        rows.first()["motivo"] shouldBe "corrigindo um bug"
        rows.first()["detalhe"] shouldBe "moedas: +500"
    }

    @Test
    fun `18_7 operador ajusta booster e grava o detalhe do booster na auditoria`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjust(id: String, request: AdminAdjustRequest) = carteira
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(
                json.encodeToString(
                    AdminAdjustRequest.serializer(),
                    AdminAdjustRequest(reason = "deu premio", operationId = "op-2", boosterId = "ESPIAR", quantityDelta = 3),
                ),
            )
        }.status.value shouldBe 200

        auditRows("AJUSTAR_CARTEIRA").first()["detalhe"] shouldBe "ESPIAR: +3"
    }

    @Test
    fun `18_7 ajustar sem motivo da 400, nunca chama o economy-service`() = testApplication {
        var chamado = false
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto {
                chamado = true
                return carteira
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest(reason = "", operationId = "op-1", coinsDelta = 1)))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        chamado shouldBe false
        auditRows("AJUSTAR_CARTEIRA") shouldBe emptyList()
    }

    @Test
    fun `18_7 ajustar sem nenhum delta real da 400`() = testApplication {
        val d = deps(economyAdmin = object : FakeEconomyAdminGateway() {})
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest(reason = "motivo valido", operationId = "op-1")))
        }.status.value shouldBe 400
    }

    @Test
    fun `18_7 ajustar booster sem quantidade (ou o contrario) da 400`() = testApplication {
        val d = deps(economyAdmin = object : FakeEconomyAdminGateway() {})
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest(reason = "motivo valido", operationId = "op-1", boosterId = "ESPIAR")))
        }.status.value shouldBe 400
    }

    @Test
    fun `18_7 economy-service recusa por saldo insuficiente da 409, sem gravar auditoria`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto =
                throw EconomyAdjustRejected(EconomyErrorCode.SALDO_INSUFICIENTE, "O ajuste deixaria o saldo negativo.")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest(reason = "motivo valido", operationId = "op-1", coinsDelta = -99_999)))
        }
        r.status.value shouldBe 409
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.SALDO_INSUFICIENTE
        auditRows("AJUSTAR_CARTEIRA") shouldBe emptyList()
    }

    @Test
    fun `18_7 economy-service recusa o booster invalido com 400, sem gravar auditoria`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto =
                throw EconomyAdjustRejected(EconomyErrorCode.REQUISICAO_INVALIDA, "Booster inválido.")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(
                json.encodeToString(
                    AdminAdjustRequest.serializer(),
                    AdminAdjustRequest(reason = "motivo valido", operationId = "op-1", boosterId = "nao-existe", quantityDelta = 1),
                ),
            )
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        auditRows("AJUSTAR_CARTEIRA") shouldBe emptyList()
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado ajustar recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/accounts/G_1/adjust") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminAdjustRequest.serializer(), AdminAdjustRequest(reason = "motivo valido", operationId = "op-1", coinsDelta = 1)))
        }.status.value shouldBe 503
    }

    // -------------------------------------------------------------------------------------------------- migracoes

    @Test
    fun `18_7 migracoes repassam limit e offset (lista global, nunca por conta)`() = testApplication {
        var receivedLimit: Int? = null
        var receivedOffset: Int? = null
        val migracao = AdminMigrationDto(accountId = "E_1", coins = 300L, createdMs = 1L)
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun migrations(limit: Int, offset: Int): AdminMigrationsPage {
                receivedLimit = limit; receivedOffset = offset
                return AdminMigrationsPage(total = 1, migrations = listOf(migracao))
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        val r = client.get("/admin/migrations?limit=2&offset=1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(MigrationsPageResponse.serializer(), r.bodyAsText())
        body.items shouldBe listOf(migracao)
        body.limit shouldBe 2
        body.offset shouldBe 1
        receivedLimit shouldBe 2
        receivedOffset shouldBe 1
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado migracoes recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)
        client.get("/admin/migrations") { header("Authorization", "Bearer $token") }.status.value shouldBe 503
    }
}
