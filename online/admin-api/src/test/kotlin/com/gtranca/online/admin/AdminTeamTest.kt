package com.gtranca.online.admin

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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

/** Sec18.7 "Equipe": so administrador cadastra/altera/remove; leitor+ consulta; sem autocadastro; ultimo administrador. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTeamTest {
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

    private fun deps() = AdminDeps(
        store = TeamStore(db, passwordCost = 4, clock = { now }),
        sessions = SessionStore(db, clock = { now }),
        clock = { now },
        ipLimitPerMinute = 1_000,
    )

    private suspend fun HttpClient.login(email: String, password: String): String =
        json.decodeFromString(
            LoginResponse.serializer(),
            post("/admin/login") { setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))) }.bodyAsText(),
        ).token

    private suspend fun HttpClient.createMember(token: String, req: CreateMemberRequest): HttpResponse =
        post("/admin/team") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), req))
        }

    private suspend fun HttpClient.updateMember(token: String, id: String, req: UpdateMemberRequest): HttpResponse =
        patch("/admin/team/$id") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(UpdateMemberRequest.serializer(), req))
        }

    private suspend fun HttpClient.removeMember(token: String, id: String, req: RemoveMemberRequest): HttpResponse =
        delete("/admin/team/$id") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(RemoveMemberRequest.serializer(), req))
        }

    @Test
    fun `18_7 leitor e operador consultam mas nao cadastram, alteram ou removem`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        val leitorId = json.decodeFromString(
            TeamMember.serializer(),
            client.createMember(adminToken, CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "equipe inicial")).bodyAsText(),
        ).id
        client.createMember(adminToken, CreateMemberRequest("operador@exemplo.com", "segredo123", Role.OPERADOR, "equipe inicial"))
        val leitorToken = client.login("leitor@exemplo.com", "segredo123")
        val operadorToken = client.login("operador@exemplo.com", "segredo123")

        for (token in listOf(leitorToken, operadorToken)) {
            client.get("/admin/team") { header("Authorization", "Bearer $token") }.status.value shouldBe 200
            client.createMember(token, CreateMemberRequest("x@exemplo.com", "segredo123", Role.LEITOR, "tentativa")).status.value shouldBe 403
            client.updateMember(token, leitorId, UpdateMemberRequest(role = Role.OPERADOR, reason = "tentativa")).status.value shouldBe 403
            client.removeMember(token, leitorId, RemoveMemberRequest("tentativa")).status.value shouldBe 403
        }
    }

    @Test
    fun `18_7 nao e possivel remover nem rebaixar o unico administrador`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        val adminId = json.decodeFromString(TeamListResponse.serializer(), client.get("/admin/team") { header("Authorization", "Bearer $adminToken") }.bodyAsText()).items.single().id

        val rebaixar = client.updateMember(adminToken, adminId, UpdateMemberRequest(role = Role.LEITOR, reason = "teste"))
        rebaixar.status.value shouldBe 409
        json.decodeFromString(ApiError.serializer(), rebaixar.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA

        val remover = client.removeMember(adminToken, adminId, RemoveMemberRequest("teste"))
        remover.status.value shouldBe 409

        // com DOIS administradores, rebaixar/remover um deles agora funciona
        val outroId = json.decodeFromString(
            TeamMember.serializer(),
            client.createMember(adminToken, CreateMemberRequest("outro@exemplo.com", "segredo123", Role.ADMINISTRADOR, "segundo admin")).bodyAsText(),
        ).id
        client.updateMember(adminToken, outroId, UpdateMemberRequest(role = Role.LEITOR, reason = "agora pode")).status.value shouldBe 200
    }

    @Test
    fun `18_7 trocar a senha invalida as sessoes abertas do membro na hora`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        val leitorId = json.decodeFromString(
            TeamMember.serializer(),
            client.createMember(adminToken, CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "equipe inicial")).bodyAsText(),
        ).id
        val leitorToken = client.login("leitor@exemplo.com", "segredo123")
        client.get("/admin/team") { header("Authorization", "Bearer $leitorToken") }.status.value shouldBe 200

        client.updateMember(adminToken, leitorId, UpdateMemberRequest(password = "senha-nova-123", reason = "redefinicao")).status.value shouldBe 200

        // o token antigo do leitor nao vale mais
        client.get("/admin/team") { header("Authorization", "Bearer $leitorToken") }.status.value shouldBe 401
        // mas a senha nova funciona
        val novoToken = client.login("leitor@exemplo.com", "senha-nova-123")
        client.get("/admin/team") { header("Authorization", "Bearer $novoToken") }.status.value shouldBe 200
    }

    @Test
    fun `18_7 rebaixar administrador para leitor faz o mesmo token receber 403 em POST team`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        val segundoId = json.decodeFromString(
            TeamMember.serializer(),
            client.createMember(adminToken, CreateMemberRequest("segundo@exemplo.com", "segredo123", Role.ADMINISTRADOR, "segundo admin")).bodyAsText(),
        ).id
        val segundoToken = client.login("segundo@exemplo.com", "segredo123")
        client.createMember(segundoToken, CreateMemberRequest("x1@exemplo.com", "segredo123", Role.LEITOR, "ainda admin")).status.value shouldBe 201

        client.updateMember(adminToken, segundoId, UpdateMemberRequest(role = Role.LEITOR, reason = "rebaixar")).status.value shouldBe 200

        // o MESMO token (nunca expirou) agora vale menos: o papel e relido do banco em cada chamada
        client.get("/admin/team") { header("Authorization", "Bearer $segundoToken") }.status.value shouldBe 200
        client.createMember(segundoToken, CreateMemberRequest("x2@exemplo.com", "segredo123", Role.LEITOR, "nao devia valer")).status.value shouldBe 403
    }

    @Test
    fun `18_7 GET admin team e paginado`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        repeat(5) { i -> client.createMember(adminToken, CreateMemberRequest("m$i@exemplo.com", "segredo123", Role.LEITOR, "lote")) }

        val pagina1 = json.decodeFromString(
            TeamListResponse.serializer(),
            client.get("/admin/team?limit=2&offset=0") { header("Authorization", "Bearer $adminToken") }.bodyAsText(),
        )
        pagina1.items.size shouldBe 2
        pagina1.total shouldBe 6 // admin + 5 leitores

        val pagina2 = json.decodeFromString(
            TeamListResponse.serializer(),
            client.get("/admin/team?limit=2&offset=2") { header("Authorization", "Bearer $adminToken") }.bodyAsText(),
        )
        pagina2.items.size shouldBe 2
        (pagina1.items.map { it.id } intersect pagina2.items.map { it.id }.toSet()).isEmpty() shouldBe true
    }

    @Test
    fun `18_7 cadastro com e-mail ja usado e recusado`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        client.createMember(adminToken, CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "primeiro")).status.value shouldBe 201
        val r = client.createMember(adminToken, CreateMemberRequest("LEITOR@exemplo.com", "outrasenha", Role.OPERADOR, "repetido"))
        r.status.value shouldBe 409
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.EMAIL_JA_CADASTRADO
    }

    @Test
    fun `18_7 alterar ou remover membro inexistente da erro`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        client.updateMember(adminToken, "M_naoexiste", UpdateMemberRequest(role = Role.OPERADOR, reason = "motivo válido")).status.value shouldBe 404
        client.removeMember(adminToken, "M_naoexiste", RemoveMemberRequest("motivo válido")).status.value shouldBe 404
    }

    @Test
    fun `18_7 motivo e obrigatorio em POST, PATCH e DELETE`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        client.createMember(adminToken, CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "")).status.value shouldBe 400
        val leitorId = json.decodeFromString(
            TeamMember.serializer(),
            client.createMember(adminToken, CreateMemberRequest("leitor2@exemplo.com", "segredo123", Role.LEITOR, "motivo válido")).bodyAsText(),
        ).id
        client.updateMember(adminToken, leitorId, UpdateMemberRequest(role = Role.OPERADOR, reason = "a")).status.value shouldBe 400
        client.removeMember(adminToken, leitorId, RemoveMemberRequest("a")).status.value shouldBe 400
    }

    @Test
    fun `18_7 operationId repetido nao duplica o membro nem a auditoria`() = testApplication {
        val d = deps()
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        application { adminModule(d) }
        val adminToken = client.login("admin@exemplo.com", "segredo123")
        val req = CreateMemberRequest("leitor@exemplo.com", "segredo123", Role.LEITOR, "cadastro", operationId = "op-1")

        val primeira = client.createMember(adminToken, req)
        primeira.status.value shouldBe 201
        val criado = json.decodeFromString(TeamMember.serializer(), primeira.bodyAsText())

        val repetida = client.createMember(adminToken, req)
        repetida.status.value shouldBe 201
        val replay = json.decodeFromString(TeamMember.serializer(), repetida.bodyAsText())
        replay shouldBe criado

        val (items, total) = d.store.list(10, 0)
        total shouldBe 2 // admin + o leitor (uma unica vez)
        items.count { it.email == "leitor@exemplo.com" } shouldBe 1
        val auditCount = db.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM admin.auditoria WHERE operacao_id = 'op-1'").use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        }
        auditCount shouldBe 1
    }
}
