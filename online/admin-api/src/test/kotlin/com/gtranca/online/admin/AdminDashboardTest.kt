package com.gtranca.online.admin

import io.kotest.matchers.collections.shouldContainExactly
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

/** Sec18.7 Painel (docs/telas-admin-web.md): saude dos servicos, leitor+, sem numeros inventados. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminDashboardTest {
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

    private fun deps(
        healthTargets: List<HealthTarget> = emptyList(),
        healthGateway: HealthGateway,
        grafanaUrl: String? = null,
        lokiUrl: String? = null,
        lobbyAdmin: LobbyAdminGateway? = null,
    ) = AdminDeps(
        store = TeamStore(db, passwordCost = 4),
        sessions = SessionStore(db),
        ipLimitPerMinute = 1_000,
        healthTargets = healthTargets,
        healthGateway = healthGateway,
        grafanaUrl = grafanaUrl,
        lokiUrl = lokiUrl,
        lobbyAdmin = lobbyAdmin,
    )

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    @Test
    fun `18_7 sem token da 401`() = testApplication {
        application { adminModule(deps(healthGateway = HealthGateway { error("nao deveria sondar sem sessao") })) }
        client.get("/admin/dashboard").status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) ve a saude de cada servico configurado`() = testApplication {
        val lobby = HealthTarget("lobby-service", "http://lobby/health")
        val game = HealthTarget("game-service", "http://game/health")
        val gateway = HealthGateway { target ->
            when (target.name) {
                "lobby-service" -> ServiceHealthInfo(target.name, ServiceStatusLevel.VERDE, 10)
                else -> ServiceHealthInfo(target.name, ServiceStatusLevel.VERMELHO, null)
            }
        }
        val d = deps(healthTargets = listOf(lobby, game), healthGateway = gateway, grafanaUrl = "https://grafana.local", lokiUrl = "https://loki.local")
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }

        // administrador cadastra um leitor (sem autocadastro, Sec18.7) e e o leitor quem chama o Painel.
        val adminToken = json.decodeFromString(LoginResponse.serializer(), client.login("admin@exemplo.com", "segredo123").bodyAsText()).token
        client.post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "cadastro para o teste do Painel")))
        }
        val token = json.decodeFromString(LoginResponse.serializer(), client.login("leitor@exemplo.com", "segredo123").bodyAsText()).token

        val r = client.get("/admin/dashboard") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(DashboardResponse.serializer(), r.bodyAsText())
        body.services shouldContainExactly listOf(
            ServiceHealthInfo("lobby-service", ServiceStatusLevel.VERDE, 10),
            ServiceHealthInfo("game-service", ServiceStatusLevel.VERMELHO, null),
        )
        body.grafanaUrl shouldBe "https://grafana.local"
        body.lokiUrl shouldBe "https://loki.local"
        // Sem LOBBY_SERVICE_URL/ADMIN_INTERNAL_SECRET configurados (lobbyAdmin=null nesta chamada): nulo, nao inventado.
        body.playersOnline shouldBe null
        body.tablesOpen shouldBe null
        body.tablesInGame shouldBe null
        body.matchesEndedLastHour shouldBe null
    }

    @Test
    fun `18_7 sem servicos configurados a lista vem vazia (nao inventa saude)`() = testApplication {
        val d = deps(healthGateway = HealthGateway { error("nenhum alvo configurado, nao deveria chamar") })
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val token = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText()).token
        val r = client.get("/admin/dashboard") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        json.decodeFromString(DashboardResponse.serializer(), r.bodyAsText()).services shouldBe emptyList()
    }

    @Test
    fun `18_7 numeros do lobby-service aparecem no Painel quando o gateway responde`() = testApplication {
        val stats = com.gtranca.online.contracts.AdminStatsDto(playersOnline = 7, tablesOpen = 2, tablesInGame = 1, matchesEndedLastHour = 3)
        val d = deps(healthGateway = HealthGateway { error("sem servico de saude neste teste") }, lobbyAdmin = fakeLobbyAdmin(stats = stats))
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val token = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText()).token
        val r = client.get("/admin/dashboard") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(DashboardResponse.serializer(), r.bodyAsText())
        body.playersOnline shouldBe 7
        body.tablesOpen shouldBe 2
        body.tablesInGame shouldBe 1
        body.matchesEndedLastHour shouldBe 3
    }

    @Test
    fun `18_7 lobby-service fora do ar nao quebra o Painel, numeros ficam nulos`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun stats() = throw LobbyAdminGatewayException("lobby-service inacessível: Timeout")
        }
        val d = deps(healthGateway = HealthGateway { error("sem servico de saude neste teste") }, lobbyAdmin = lobbyAdmin)
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val token = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText()).token
        val r = client.get("/admin/dashboard") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(DashboardResponse.serializer(), r.bodyAsText())
        body.playersOnline shouldBe null
        body.matchesEndedLastHour shouldBe null
    }
}

/** Fabrica de teste: um [LobbyAdminGateway] que sempre devolve o mesmo [com.gtranca.online.contracts.AdminStatsDto] (sem rede). */
private fun fakeLobbyAdmin(stats: com.gtranca.online.contracts.AdminStatsDto): LobbyAdminGateway =
    object : FakeLobbyAdminGateway() {
        override suspend fun stats() = stats
    }
