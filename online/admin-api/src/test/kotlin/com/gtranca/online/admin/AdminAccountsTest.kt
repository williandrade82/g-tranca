package com.gtranca.online.admin

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountSummary
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.AdminTableSummary
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.TableState
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

/** Sec18.7 tela Contas (docs/telas-admin-web.md): busca e ficha, agregadas do lobby/economy-service, leitor+. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAccountsTest {
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

    private fun deps(lobbyAdmin: LobbyAdminGateway? = null, economyAdmin: EconomyAdminGateway? = null) =
        AdminDeps(store = TeamStore(db, passwordCost = 4), sessions = SessionStore(db), ipLimitPerMinute = 1_000, lobbyAdmin = lobbyAdmin, economyAdmin = economyAdmin)

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    /** Cadastra um administrador (semente) e um LEITOR por ele, devolve o token do LEITOR (Sec18.7: "leitor+"). */
    private suspend fun HttpClient.loginAsLeitor(d: AdminDeps): String {
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        val adminToken = json.decodeFromString(LoginResponse.serializer(), login("admin@exemplo.com", "segredo123").bodyAsText()).token
        post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "cadastro para o teste de Contas")))
        }
        return json.decodeFromString(LoginResponse.serializer(), login("leitor@exemplo.com", "segredo123").bodyAsText()).token
    }

    private val ana = AdminAccountSummary(id = "G_1", kind = AccountKind.CONVIDADO, name = "Ana", lastSeenMs = 123L)

    // ---------------------------------------------------------------------------------------------------- /admin/accounts

    @Test
    fun `18_7 sem token da 401 em accounts e em accounts por id`() = testApplication {
        application { adminModule(deps()) }
        client.get("/admin/accounts").status.value shouldBe 401
        client.get("/admin/accounts/G_1").status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) busca contas e recebe a pagina do lobby-service, com os parametros repassados`() = testApplication {
        var receivedQuery: String? = null
        var receivedLimit: Int? = null
        var receivedOffset: Int? = null
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int): AdminAccountsPage {
                receivedQuery = query; receivedLimit = limit; receivedOffset = offset
                return AdminAccountsPage(total = 1, accounts = listOf(ana))
            }
            override suspend fun accountDetail(id: String) = error("nao usado neste teste")
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts?q=ana&limit=5&offset=10") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AccountsPageResponse.serializer(), r.bodyAsText())
        body.total shouldBe 1
        body.items shouldBe listOf(ana)
        body.limit shouldBe 5
        body.offset shouldBe 10
        receivedQuery shouldBe "ana"
        receivedLimit shouldBe 5
        receivedOffset shouldBe 10
    }

    @Test
    fun `18_7 sem LOBBY_SERVICE_URL configurado accounts recusa com 503, nunca inventa pagina`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 503
    }

    @Test
    fun `18_7 lobby-service fora do ar da 503 em accounts, nao 500`() = testApplication {
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = throw LobbyAdminGatewayException("timeout")
            override suspend fun accountDetail(id: String) = error("nao usado neste teste")
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 503
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.PARTIDA_INDISPONIVEL
    }

    // -------------------------------------------------------------------------------------------- /admin/accounts/{id}

    @Test
    fun `18_7 leitor (o menor papel) ve a ficha da conta, com mesas atuais e carteira juntas (lobby + economy)`() = testApplication {
        val detail = AdminAccountDetail(
            id = "G_1", kind = AccountKind.CONVIDADO, name = "Ana", blocked = false,
            currentTables = listOf(AdminTableSummary(id = "T1", name = "Mesa 1", state = TableState.ABERTA, updatedMs = 999L)),
        )
        val wallet = AdminWalletDto(coins = 1_000L, inventory = mapOf("ESPIAR" to 2), migratedLocal = false)
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = error("nao usado neste teste")
            override suspend fun accountDetail(id: String) = if (id == "G_1") detail else null
        }
        val economyAdmin = EconomyAdminGateway { id -> if (id == "G_1") wallet else null }
        val d = deps(lobbyAdmin = lobbyAdmin, economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts/G_1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AccountDetailResponse.serializer(), r.bodyAsText())
        body.account shouldBe detail
        body.wallet shouldBe wallet
        body.walletUnavailable shouldBe false
    }

    @Test
    fun `18_7 conta inexistente no lobby da 404 tipado`() = testApplication {
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = error("nao usado neste teste")
            override suspend fun accountDetail(id: String): AdminAccountDetail? = null
        }
        val economyAdmin = EconomyAdminGateway { null }
        val d = deps(lobbyAdmin = lobbyAdmin, economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts/ninguem") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 404
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_7 conta sem carteira online ainda (economy devolve null) e diferente de economy fora do ar`() = testApplication {
        val detail = AdminAccountDetail(id = "G_1", kind = AccountKind.CONVIDADO, name = "Ana")
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = error("nao usado neste teste")
            override suspend fun accountDetail(id: String) = detail
        }

        // (a) a conta genuinamente ainda nao tem carteira (404 no economy-service, ver HttpEconomyAdminGateway).
        val semCarteira = deps(lobbyAdmin = lobbyAdmin, economyAdmin = EconomyAdminGateway { null })
        application { adminModule(semCarteira) }
        val tokenA = client.loginAsLeitor(semCarteira)
        val rA = client.get("/admin/accounts/G_1") { header("Authorization", "Bearer $tokenA") }
        val bodyA = json.decodeFromString(AccountDetailResponse.serializer(), rA.bodyAsText())
        bodyA.wallet shouldBe null
        bodyA.walletUnavailable shouldBe false
    }

    @Test
    fun `18_7 economy-service fora do ar nao quebra a ficha, carteira fica indisponivel e nao sem carteira`() = testApplication {
        val detail = AdminAccountDetail(id = "G_1", kind = AccountKind.CONVIDADO, name = "Ana")
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = error("nao usado neste teste")
            override suspend fun accountDetail(id: String) = detail
        }
        val economyAdmin = EconomyAdminGateway { throw EconomyAdminGatewayException("timeout") }
        val d = deps(lobbyAdmin = lobbyAdmin, economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts/G_1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AccountDetailResponse.serializer(), r.bodyAsText())
        body.account shouldBe detail
        body.wallet shouldBe null
        body.walletUnavailable shouldBe true
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado a carteira fica indisponivel e nao sem carteira`() = testApplication {
        val detail = AdminAccountDetail(id = "G_1", kind = AccountKind.CONVIDADO, name = "Ana")
        val lobbyAdmin = object : LobbyAdminGateway {
            override suspend fun stats() = error("nao usado neste teste")
            override suspend fun searchAccounts(query: String, limit: Int, offset: Int) = error("nao usado neste teste")
            override suspend fun accountDetail(id: String) = detail
        }
        val d = deps(lobbyAdmin = lobbyAdmin, economyAdmin = null)
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts/G_1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AccountDetailResponse.serializer(), r.bodyAsText())
        body.wallet shouldBe null
        body.walletUnavailable shouldBe true
    }

    @Test
    fun `18_7 sem LOBBY_SERVICE_URL configurado a ficha recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAsLeitor(d)

        val r = client.get("/admin/accounts/G_1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 503
    }
}
