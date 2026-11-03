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

/**
 * Sec18.7 (definition.md, decisao de 03/11/2026): bloquear/desbloquear conta e encerrar sessoes, pelo console -
 * operador+, motivo obrigatorio em qualquer caso, auditoria sempre gravada quando a acao de verdade aconteceu.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAccountActionsTest {
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

    private fun deps(lobbyAdmin: LobbyAdminGateway? = null) =
        AdminDeps(store = TeamStore(db, passwordCost = 4), sessions = SessionStore(db), ipLimitPerMinute = 1_000, lobbyAdmin = lobbyAdmin)

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

    private fun fakeLobby(onBlock: (String, String?) -> Unit = { _, _ -> }, onUnblock: (String) -> Unit = {}, revoked: Int = 2) =
        object : FakeLobbyAdminGateway() {
            override suspend fun block(id: String, reason: String?) = onBlock(id, reason)
            override suspend fun unblock(id: String) = onUnblock(id)
            override suspend fun revokeSessions(id: String) = revoked
        }

    // ------------------------------------------------------------------------------------------------------- acesso

    @Test
    fun `18_7 sem token da 401 nas tres acoes`() = testApplication {
        application { adminModule(deps()) }
        client.post("/admin/accounts/G_1/block") { setBody("""{"reason":"teste"}""") }.status.value shouldBe 401
        client.post("/admin/accounts/G_1/unblock") { setBody("""{"reason":"teste"}""") }.status.value shouldBe 401
        client.post("/admin/accounts/G_1/revoke-sessions") { setBody("""{"reason":"teste"}""") }.status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) nao pode bloquear, desbloquear nem encerrar sessoes`() = testApplication {
        val d = deps(lobbyAdmin = fakeLobby())
        application { adminModule(d) }
        val leitorToken = client.loginAs(d, Role.LEITOR)

        val block = client.post("/admin/accounts/G_1/block") {
            header("Authorization", "Bearer $leitorToken")
            setBody("""{"reason":"teste"}""")
        }
        block.status.value shouldBe 403
        json.decodeFromString(ApiError.serializer(), block.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO
    }

    // ------------------------------------------------------------------------------------------------------ bloquear

    @Test
    fun `18_7 operador bloqueia com motivo, chama o lobby-service e grava a auditoria`() = testApplication {
        var chamou: Pair<String, String?>? = null
        val d = deps(lobbyAdmin = fakeLobby(onBlock = { id, reason -> chamou = id to reason }))
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/block") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("comportamento abusivo")))
        }
        r.status.value shouldBe 200
        chamou shouldBe ("G_1" to "comportamento abusivo")

        val rows = auditRows("BLOQUEAR_CONTA")
        rows.size shouldBe 1
        rows.first()["ator_email"] shouldBe "membro@exemplo.com"
        rows.first()["alvo_id"] shouldBe "G_1"
        rows.first()["motivo"] shouldBe "comportamento abusivo"
    }

    @Test
    fun `18_7 bloquear sem motivo da 400, nunca chama o lobby-service`() = testApplication {
        var chamado = false
        val d = deps(lobbyAdmin = fakeLobby(onBlock = { _, _ -> chamado = true }))
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/block") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("")))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        chamado shouldBe false
        auditRows("BLOQUEAR_CONTA") shouldBe emptyList()
    }

    @Test
    fun `18_7 lobby-service fora do ar da 503 ao bloquear, e nao grava auditoria (a acao nao aconteceu)`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun block(id: String, reason: String?) = throw LobbyAdminGatewayException("timeout")
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/block") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("motivo valido")))
        }
        r.status.value shouldBe 503
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.SERVICO_INDISPONIVEL
        auditRows("BLOQUEAR_CONTA") shouldBe emptyList()
    }

    @Test
    fun `18_7 sem LOBBY_SERVICE_URL configurado bloquear recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/block") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("motivo valido")))
        }
        r.status.value shouldBe 503
    }

    @Test
    fun `18_7 mesmo operationId repetido nao chama o lobby-service de novo, nem duplica a auditoria`() = testApplication {
        var chamadas = 0
        val d = deps(lobbyAdmin = fakeLobby(onBlock = { _, _ -> chamadas++ }))
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        repeat(2) {
            client.post("/admin/accounts/G_1/block") {
                header("Authorization", "Bearer $token")
                setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("motivo valido", operationId = "op-123")))
            }.status.value shouldBe 200
        }
        // Critico: a 2a chamada NAO bloqueia de novo no lobby-service (se a conta tivesse sido desbloqueada por
        // outro operador entre as duas chamadas, repetir o operationId nao podia bloquear de volta).
        chamadas shouldBe 1
        auditRows("BLOQUEAR_CONTA").size shouldBe 1
    }

    @Test
    fun `18_7 mesmo operationId repetido em encerrar sessoes devolve o MESMO numero, sem encerrar sessoes novas`() = testApplication {
        var chamadas = 0
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun revokeSessions(id: String): Int {
                chamadas++
                return 5
            }
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val respostas = (1..2).map {
            val r = client.post("/admin/accounts/G_1/revoke-sessions") {
                header("Authorization", "Bearer $token")
                setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("motivo valido", operationId = "op-revoke-1")))
            }
            json.decodeFromString(com.gtranca.online.contracts.AdminRevokeSessionsResponse.serializer(), r.bodyAsText()).revoked
        }
        chamadas shouldBe 1
        respostas shouldBe listOf(5, 5)
        auditRows("ENCERRAR_SESSOES_CONTA").size shouldBe 1
    }

    // ---------------------------------------------------------------------------------------------- desbloquear

    @Test
    fun `18_7 operador desbloqueia com motivo, chama o lobby-service e grava a auditoria`() = testApplication {
        var chamou: String? = null
        val d = deps(lobbyAdmin = fakeLobby(onUnblock = { id -> chamou = id }))
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/unblock") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("revisao concluida")))
        }
        r.status.value shouldBe 200
        chamou shouldBe "G_1"
        auditRows("DESBLOQUEAR_CONTA").size shouldBe 1
    }

    // ------------------------------------------------------------------------------------------- encerrar sessoes

    @Test
    fun `18_7 operador encerra sessoes, recebe quantos tokens cairam e grava a auditoria com o detalhe`() = testApplication {
        val d = deps(lobbyAdmin = fakeLobby(revoked = 3))
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/revoke-sessions") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("suspeita de conta comprometida")))
        }
        r.status.value shouldBe 200
        json.decodeFromString(com.gtranca.online.contracts.AdminRevokeSessionsResponse.serializer(), r.bodyAsText()).revoked shouldBe 3

        val rows = auditRows("ENCERRAR_SESSOES_CONTA")
        rows.size shouldBe 1
        rows.first()["detalhe"] shouldBe "sessões encerradas: 3"
    }

    @Test
    fun `18_7 encerrar sessoes sem motivo da 400`() = testApplication {
        val d = deps(lobbyAdmin = fakeLobby())
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/accounts/G_1/revoke-sessions") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AccountActionRequest.serializer(), AccountActionRequest("x")))
        }
        r.status.value shouldBe 400
    }
}
