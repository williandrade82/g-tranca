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

/** Sec18.7 tela Auditoria: leitura, paginada, mais recente primeiro - de tudo que a Equipe/Contas/Mesas/Economia ja gravam. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuditListTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pg = EmbeddedPostgres.start()
    private val db: DataSource = pg.postgresDatabase.also { Database.migrate(it) }
    private var now = 1_800_000_000_000L

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA IF EXISTS admin CASCADE") }
        Database.migrate(db)
        now = 1_800_000_000_000L
    }

    @AfterAll
    fun stop() = pg.close()

    private fun deps() = AdminDeps(store = TeamStore(db, passwordCost = 4, clock = { now }), sessions = SessionStore(db, clock = { now }), clock = { now }, ipLimitPerMinute = 1_000)

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    @Test
    fun `18_7 sem token da 401`() = testApplication {
        application { adminModule(deps()) }
        client.get("/admin/audit").status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) ve a auditoria, mais recente primeiro, sem o resultado`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123") // grava SEMEAR_ADMINISTRADOR
        val adminToken = json.decodeFromString(LoginResponse.serializer(), client.login("admin@exemplo.com", "segredo123").bodyAsText()).token

        now += 1_000
        client.post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "cadastro inicial da equipe")))
        } // grava CRIAR_MEMBRO, depois do SEMEAR_ADMINISTRADOR
        val leitorToken = json.decodeFromString(LoginResponse.serializer(), client.login("leitor@exemplo.com", "segredo123").bodyAsText()).token

        val r = client.get("/admin/audit") { header("Authorization", "Bearer $leitorToken") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AuditPageResponse.serializer(), r.bodyAsText())
        body.total shouldBe 2
        body.items.size shouldBe 2
        // Mais recente primeiro: CRIAR_MEMBRO (depois) antes de SEMEAR_ADMINISTRADOR (antes).
        body.items[0].action shouldBe "CRIAR_MEMBRO"
        body.items[0].actorEmail shouldBe "admin@exemplo.com"
        body.items[0].targetEmail shouldBe "leitor@exemplo.com"
        body.items[0].reason shouldBe "cadastro inicial da equipe"
        body.items[1].action shouldBe "SEMEAR_ADMINISTRADOR"

        // Nunca o "resultado" (ver AuditEntryResponse): nao tem esse campo no corpo bruto.
        r.bodyAsText().contains("\"resultado\"") shouldBe false
    }

    @Test
    fun `18_7 paginacao respeita limit e offset`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        val adminToken = json.decodeFromString(LoginResponse.serializer(), client.login("admin@exemplo.com", "segredo123").bodyAsText()).token
        repeat(3) { i ->
            now += 1_000
            client.post("/admin/team") {
                header("Authorization", "Bearer $adminToken")
                setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest("membro$i@exemplo.com", "segredo123", Role.LEITOR, "cadastro $i")))
            }
        }
        // 1 SEMEAR_ADMINISTRADOR + 3 CRIAR_MEMBRO = 4 linhas.
        val r = client.get("/admin/audit?limit=2&offset=1") { header("Authorization", "Bearer $adminToken") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(AuditPageResponse.serializer(), r.bodyAsText())
        body.total shouldBe 4
        body.items.size shouldBe 2
        body.limit shouldBe 2
        body.offset shouldBe 1
    }
}
