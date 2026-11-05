package com.gtranca.online.admin

import com.gtranca.online.contracts.ApiError
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

/** Sec18.7: entrar com e-mail e senha (equipe do console), token Bearer e as rotas protegidas de Equipe. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminLoginTest {
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

    // bcrypt real e lento de proposito; custo baixo so nos testes.
    private fun deps(loginAttemptLimit: Int = 5, loginLockoutMinutes: Long = 15L, idleTtlMs: Long = 30 * 60_000L, absoluteTtlMs: Long = 8 * 3_600_000L) =
        AdminDeps(
            store = TeamStore(db, passwordCost = 4, clock = { now }),
            sessions = SessionStore(db, idleTtlMs = idleTtlMs, absoluteTtlMs = absoluteTtlMs, clock = { now }),
            clock = { now },
            ipLimitPerMinute = 1_000,
            loginAttemptLimit = loginAttemptLimit,
            loginLockoutMinutes = loginLockoutMinutes,
        )

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }

    @Test
    fun `18_7 login certo devolve token e o papel do membro`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val r = client.login("Ana@Exemplo.com", "segredo123")
        r.status.value shouldBe 200
        val body = json.decodeFromString(LoginResponse.serializer(), r.bodyAsText())
        body.role shouldBe Role.ADMINISTRADOR
        body.email shouldBe "ana@exemplo.com"
        body.token.isNotBlank() shouldBe true
    }

    @Test
    fun `18_7 senha errada ou e-mail inexistente dao o mesmo erro`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        val senhaErrada = client.login("ana@exemplo.com", "senha-errada")
        senhaErrada.status.value shouldBe 401
        json.decodeFromString(ApiError.serializer(), senhaErrada.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO

        val semConta = client.login("ninguem@exemplo.com", "segredo123")
        semConta.status.value shouldBe 401
        json.decodeFromString(ApiError.serializer(), semConta.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO
    }

    @Test
    fun `18_7 forca bruta bloqueia o e-mail depois de N erros e libera depois da janela`() = testApplication {
        val d = deps(loginAttemptLimit = 3, loginLockoutMinutes = 1L)
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        repeat(3) { client.login("ana@exemplo.com", "senha-errada").status.value shouldBe 401 }
        val bloqueado = client.login("ana@exemplo.com", "segredo123")
        bloqueado.status.value shouldBe 429
        json.decodeFromString(ApiError.serializer(), bloqueado.bodyAsText()).code shouldBe ErrorCode.LOGIN_BLOQUEADO

        now += 61_000L
        client.login("ana@exemplo.com", "segredo123").status.value shouldBe 200
    }

    @Test
    fun `18_7 rota protegida sem token, com token invalido ou expirado da 401`() = testApplication {
        val d = deps(idleTtlMs = 60_000L)
        d.store.seedFirstAdmin("ana@exemplo.com", "segredo123")
        application { adminModule(d) }

        client.get("/admin/team").status.value shouldBe 401

        val semToken = client.get("/admin/team") { header("Authorization", "Bearer token-invalido") }
        semToken.status.value shouldBe 401
        json.decodeFromString(ApiError.serializer(), semToken.bodyAsText()).code shouldBe ErrorCode.NAO_AUTENTICADO

        val login = json.decodeFromString(LoginResponse.serializer(), client.login("ana@exemplo.com", "segredo123").bodyAsText())
        val ok = client.get("/admin/team") { header("Authorization", "Bearer ${login.token}") }
        ok.status.value shouldBe 200

        now += 61_000L // passa da ociosidade (1 min)
        val expirado = client.get("/admin/team") { header("Authorization", "Bearer ${login.token}") }
        expirado.status.value shouldBe 401
    }

    @Test
    fun `18_7 papel insuficiente da 403, nao 401`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = json.decodeFromString(LoginResponse.serializer(), client.login("admin@exemplo.com", "segredo123").bodyAsText()).token

        // administrador cria um leitor
        client.post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(
                json.encodeToString(
                    CreateMemberRequest.serializer(),
                    CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "cadastro inicial da equipe"),
                ),
            )
        }
        val leitorToken = json.decodeFromString(LoginResponse.serializer(), client.login("leitor@exemplo.com", "segredo123").bodyAsText()).token

        // leitor consegue listar (leitor+)...
        client.get("/admin/team") { header("Authorization", "Bearer $leitorToken") }.status.value shouldBe 200

        // ...mas nao cadastrar outro membro (sec18.7: so administrador)
        val recusado = client.post("/admin/team") {
            header("Authorization", "Bearer $leitorToken")
            setBody(
                json.encodeToString(
                    CreateMemberRequest.serializer(),
                    CreateMemberRequest("outro@exemplo.com", "segredo123", Role.LEITOR, "tentativa indevida"),
                ),
            )
        }
        recusado.status.value shouldBe 403
        json.decodeFromString(ApiError.serializer(), recusado.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO
    }
}
