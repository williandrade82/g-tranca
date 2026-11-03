package com.gtranca.online.admin

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

/** Sec18.7 "Sair": revoga a sessao no servidor, nao so no navegador. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminLogoutTest {
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

    private fun deps() = AdminDeps(store = TeamStore(db, passwordCost = 4), sessions = SessionStore(db), ipLimitPerMinute = 1_000)

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    @Test
    fun `18_7 logout revoga o token no servidor`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val token = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText()).token
        client.get("/admin/team") { header("Authorization", "Bearer $token") }.status.value shouldBe 200

        client.post("/admin/logout") { header("Authorization", "Bearer $token") }.status.value shouldBe 200

        client.get("/admin/team") { header("Authorization", "Bearer $token") }.status.value shouldBe 401
    }

    @Test
    fun `18_7 logout sem token da 401, logout com token invalido tambem (idempotente, sem vazar nada)`() = testApplication {
        application { adminModule(deps()) }
        client.post("/admin/logout").status.value shouldBe 401
        client.post("/admin/logout") { header("Authorization", "Bearer token-que-nao-existe") }.status.value shouldBe 200
    }
}
