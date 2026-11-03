package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminTablesPage
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
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

/** Sec18.7 tela Mesas (docs/telas-admin-web.md): lista, ficha e "encerrar mesa travada", agregadas do lobby-service. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTablesTest {
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

    private fun table(id: String = "T1", state: TableState = TableState.ABERTA) = TableView(
        id = id, hostId = "G_host", hostName = "Ana", state = state,
        rules = TableRules(mode = TableMode.DUPLAS, rulesJson = "{}", bet = 50),
        seats = listOf(SeatView(index = 0, kind = SeatKind.HUMANO, guestId = "G_host", name = "Ana")),
    )

    // ------------------------------------------------------------------------------------------------------- acesso

    @Test
    fun `18_7 sem token da 401 na lista, na ficha e ao encerrar`() = testApplication {
        application { adminModule(deps()) }
        client.get("/admin/tables").status.value shouldBe 401
        client.get("/admin/tables/T1").status.value shouldBe 401
        client.post("/admin/tables/T1/close") { setBody("""{"reason":"teste"}""") }.status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) ve a lista e a ficha, mas nao pode encerrar`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tables(limit: Int, offset: Int) = AdminTablesPage(total = 1, tables = listOf(table()))
            override suspend fun tableDetail(id: String) = table(id)
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        client.get("/admin/tables") { header("Authorization", "Bearer $token") }.status.value shouldBe 200
        client.get("/admin/tables/T1") { header("Authorization", "Bearer $token") }.status.value shouldBe 200

        val close = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody("""{"reason":"teste"}""")
        }
        close.status.value shouldBe 403
        json.decodeFromString(ApiError.serializer(), close.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO
    }

    // -------------------------------------------------------------------------------------------------------- lista

    @Test
    fun `18_7 lista mesas com os parametros de paginacao repassados ao lobby-service`() = testApplication {
        var receivedLimit: Int? = null
        var receivedOffset: Int? = null
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tables(limit: Int, offset: Int): AdminTablesPage {
                receivedLimit = limit; receivedOffset = offset
                return AdminTablesPage(total = 1, tables = listOf(table(state = TableState.EM_JOGO)))
            }
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        val r = client.get("/admin/tables?limit=5&offset=10") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val body = json.decodeFromString(TablesPageResponse.serializer(), r.bodyAsText())
        body.total shouldBe 1
        body.items.single().state shouldBe TableState.EM_JOGO
        body.limit shouldBe 5
        body.offset shouldBe 10
        receivedLimit shouldBe 5
        receivedOffset shouldBe 10
    }

    @Test
    fun `18_7 sem LOBBY_SERVICE_URL configurado a lista recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)
        client.get("/admin/tables") { header("Authorization", "Bearer $token") }.status.value shouldBe 503
    }

    @Test
    fun `18_7 lobby-service fora do ar na lista da 503, nao 500`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tables(limit: Int, offset: Int) = throw LobbyAdminGatewayException("timeout")
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)
        val r = client.get("/admin/tables") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 503
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.SERVICO_INDISPONIVEL
    }

    // -------------------------------------------------------------------------------------------------------- ficha

    @Test
    fun `18_7 a ficha da mesa mostra assentos e regras, mesa inexistente da 404 tipado`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tableDetail(id: String) = if (id == "T1") table() else null
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        val r = client.get("/admin/tables/T1") { header("Authorization", "Bearer $token") }
        r.status.value shouldBe 200
        val detail = json.decodeFromString(TableView.serializer(), r.bodyAsText())
        detail.rules.bet shouldBe 50
        detail.seats.first().name shouldBe "Ana"

        val missing = client.get("/admin/tables/ninguem") { header("Authorization", "Bearer $token") }
        missing.status.value shouldBe 404
        json.decodeFromString(ApiError.serializer(), missing.bodyAsText()).code shouldBe ErrorCode.MESA_INEXISTENTE
    }

    // ---------------------------------------------------------------------------------------------------- encerrar

    /** Fake de fechamento: [before] e o estado que a ficha mostra ANTES (consultado pela rota antes de fechar). */
    private fun fakeClose(before: TableState, onClose: (String, String?) -> TableView = { id, _ -> table(id, TableState.ENCERRADA) }) =
        object : FakeLobbyAdminGateway() {
            override suspend fun tableDetail(id: String) = table(id, before)
            override suspend fun closeTable(id: String, reason: String?) = onClose(id, reason)
        }

    @Test
    fun `18_7 operador encerra mesa aberta com motivo, chama o lobby-service e grava a auditoria com o estado anterior`() = testApplication {
        var chamou: Pair<String, String?>? = null
        val lobbyAdmin = fakeClose(before = TableState.ABERTA, onClose = { id, reason -> chamou = id to reason; table(id, TableState.ENCERRADA) })
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("mesa travada ha 2 horas")))
        }
        r.status.value shouldBe 200
        json.decodeFromString(TableView.serializer(), r.bodyAsText()).state shouldBe TableState.ENCERRADA
        chamou shouldBe ("T1" to "mesa travada ha 2 horas")

        val rows = auditRows("ENCERRAR_MESA")
        rows.size shouldBe 1
        rows.first()["ator_email"] shouldBe "membro@exemplo.com"
        rows.first()["alvo_id"] shouldBe "T1"
        rows.first()["motivo"] shouldBe "mesa travada ha 2 horas"
        rows.first()["detalhe"] shouldBe "estado anterior: ABERTA"
    }

    @Test
    fun `18_7 encerrar sem motivo da 400, nunca chama o lobby-service`() = testApplication {
        var chamado = false
        val lobbyAdmin = fakeClose(before = TableState.ABERTA, onClose = { id, _ -> chamado = true; table(id, TableState.ENCERRADA) })
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("")))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        chamado shouldBe false
        auditRows("ENCERRAR_MESA") shouldBe emptyList()
    }

    @Test
    fun `18_7 encerrar mesa em jogo repassa o pedido ao lobby-service e grava que a aposta foi devolvida`() = testApplication {
        val lobbyAdmin = fakeClose(before = TableState.EM_JOGO)
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("mesa travada em jogo")))
        }
        r.status.value shouldBe 200
        json.decodeFromString(TableView.serializer(), r.bodyAsText()).state shouldBe TableState.ENCERRADA
        auditRows("ENCERRAR_MESA").first()["detalhe"] shouldBe "estado anterior: EM_JOGO, partida encerrada, aposta devolvida a todos"
    }

    @Test
    fun `18_7 encerrar mesa ja encerrada grava que nao teve efeito`() = testApplication {
        val lobbyAdmin = fakeClose(before = TableState.ENCERRADA)
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("so por garantia")))
        }.status.value shouldBe 200
        auditRows("ENCERRAR_MESA").first()["detalhe"] shouldBe "já estava ENCERRADA (sem efeito)"
    }

    @Test
    fun `18_7 se o lobby-service nao conseguir encerrar (ex game-service fora do ar) da 503, sem gravar auditoria`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tableDetail(id: String) = table(id, TableState.EM_JOGO)
            override suspend fun closeTable(id: String, reason: String?) = throw LobbyAdminGatewayException("game-service fora do ar")
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("mesa travada em jogo")))
        }
        r.status.value shouldBe 503
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.SERVICO_INDISPONIVEL
        auditRows("ENCERRAR_MESA") shouldBe emptyList()
    }

    @Test
    fun `18_7 encerrar mesa inexistente da 404 tipado, sem tentar fechar`() = testApplication {
        val lobbyAdmin = object : FakeLobbyAdminGateway() {
            override suspend fun tableDetail(id: String): TableView? = null
        }
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/ninguem/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("motivo valido")))
        }
        r.status.value shouldBe 404
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.MESA_INEXISTENTE
    }

    @Test
    fun `18_7 se a mesa virou EM_JOGO depois que o operador abriu o aviso, recusa com 409 em vez de encerrar a partida sem o aviso certo`() = testApplication {
        var fechou = false
        val lobbyAdmin = fakeClose(before = TableState.EM_JOGO, onClose = { id, _ -> fechou = true; table(id, TableState.ENCERRADA) })
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("motivo valido", expectedState = TableState.ABERTA)))
        }
        r.status.value shouldBe 409
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        fechou shouldBe false
        auditRows("ENCERRAR_MESA") shouldBe emptyList()
    }

    @Test
    fun `18_7 expectedState batendo com EM_JOGO de verdade encerra normalmente`() = testApplication {
        val lobbyAdmin = fakeClose(before = TableState.EM_JOGO)
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("motivo valido", expectedState = TableState.EM_JOGO)))
        }.status.value shouldBe 200
    }

    @Test
    fun `18_7 mesa ja ENCERRADA com expectedState diferente nao da 409 (nao tem mais como piorar)`() = testApplication {
        val lobbyAdmin = fakeClose(before = TableState.ENCERRADA)
        val d = deps(lobbyAdmin = lobbyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("motivo valido", expectedState = TableState.ABERTA)))
        }.status.value shouldBe 200
    }

    @Test
    fun `18_7 sem LOBBY_SERVICE_URL configurado encerrar recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.OPERADOR)

        val r = client.post("/admin/tables/T1/close") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(CloseTableRequest.serializer(), CloseTableRequest("motivo valido")))
        }
        r.status.value shouldBe 503
    }
}
