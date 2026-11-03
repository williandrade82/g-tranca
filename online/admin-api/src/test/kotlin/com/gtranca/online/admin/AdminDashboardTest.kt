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

    private fun deps(healthTargets: List<HealthTarget> = emptyList(), healthGateway: HealthGateway, grafanaUrl: String? = null, lokiUrl: String? = null) =
        AdminDeps(
            store = TeamStore(db, passwordCost = 4),
            sessions = SessionStore(db),
            ipLimitPerMinute = 1_000,
            healthTargets = healthTargets,
            healthGateway = healthGateway,
            grafanaUrl = grafanaUrl,
            lokiUrl = lokiUrl,
        )

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    @Test
    fun `18_7 sem token da 401`() = testApplication {
        application { adminModule(deps(healthGateway = HealthGateway { error("nao deveria sondar sem sessao") })) }
        client.get("/admin/dashboard").status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor ve a saude de cada servico configurado`() = testApplication {
        val lobby = HealthTarget("lobby-service", "http://lobby/health")
        val game = HealthTarget("game-service", "http://game/health")
        val gateway = HealthGateway { target ->
            when (target.name) {
                "lobby-service" -> ServiceHealthInfo(target.name, ServiceStatusLevel.VERDE, 10)
                else -> ServiceHealthInfo(target.name, ServiceStatusLevel.VERMELHO, null)
            }
        }
        val d = deps(healthTargets = listOf(lobby, game), healthGateway = gateway, grafanaUrl = "https://grafana.local", lokiUrl = "https://loki.local")
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val token = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText()).token
        val r = client.get("/admin/dashboard") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(DashboardResponse.serializer(), r.bodyAsText())
        body.services shouldContainExactly listOf(
            ServiceHealthInfo("lobby-service", ServiceStatusLevel.VERDE, 10),
            ServiceHealthInfo("game-service", ServiceStatusLevel.VERMELHO, null),
        )
        body.grafanaUrl shouldBe "https://grafana.local"
        body.lokiUrl shouldBe "https://loki.local"
        // Nenhum servico dono do dado expoe essa agregacao ainda (ver relatorio ao online-backend-dev): nulo, nao inventado.
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
}
