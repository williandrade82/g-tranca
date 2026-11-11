package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminConfigFieldDto
import com.gtranca.online.contracts.AdminConfigSource
import com.gtranca.online.contracts.AdminShopConfigDto
import com.gtranca.online.contracts.AdminShopConfigHistoryEntryDto
import com.gtranca.online.contracts.AdminShopConfigHistoryPage
import com.gtranca.online.contracts.AdminShopGainAdjustRequest
import com.gtranca.online.contracts.AdminShopOfferAdjustRequest
import com.gtranca.online.contracts.AdminShopOfferConfigDto
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

/** Sec17.10/18.7 tela "Loja e configuracao": preco/quantidade das ofertas e o ganho diario, com historico. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminShopConfigTest {
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

    /** E-mail PROPRIO por papel (nao "membro@exemplo.com" fixo): permite logar como mais de um papel na mesma
     * equipe dentro de um unico teste, sem um 2o cadastro do mesmo e-mail colidir (409) e silenciosamente
     * devolver o token do papel ERRADO - ja aconteceu aqui (achado pelo advisor). */
    private suspend fun HttpClient.loginAs(d: AdminDeps, role: Role): String {
        d.store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        val adminToken = json.decodeFromString(LoginResponse.serializer(), login("admin@exemplo.com", "segredo123").bodyAsText()).token
        if (role == Role.ADMINISTRADOR) return adminToken
        val email = "${role.name.lowercase()}@exemplo.com"
        post("/admin/team") {
            header("Authorization", "Bearer $adminToken")
            setBody(json.encodeToString(CreateMemberRequest.serializer(), CreateMemberRequest(email, "segredo123", role, "cadastro para o teste")))
        }
        val loginResponse = json.decodeFromString(LoginResponse.serializer(), login(email, "segredo123").bodyAsText())
        loginResponse.role shouldBe role
        return loginResponse.token
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

    private val precoDefault = AdminConfigFieldDto(value = 100, source = AdminConfigSource.DEFAULT)
    private val quantidadeDefault = AdminConfigFieldDto(value = 1, source = AdminConfigSource.DEFAULT)
    private val ganhoDiarioDefault = AdminConfigFieldDto(value = 50, source = AdminConfigSource.DEFAULT)
    private val config = AdminShopConfigDto(
        offers = listOf(AdminShopOfferConfigDto(index = 0, boosterId = "ESPIAR", price = precoDefault, quantity = quantidadeDefault)),
        dailyGain = ganhoDiarioDefault,
    )

    // -------------------------------------------------------------------------------------------------------- acesso

    @Test
    fun `18_7 sem token da 401 nas quatro rotas de loja e configuracao`() = testApplication {
        application { adminModule(deps()) }
        client.get("/admin/shop-config").status.value shouldBe 401
        client.get("/admin/shop-config/history").status.value shouldBe 401
        client.post("/admin/shop-config/offer/0") { setBody("""{"price":200,"reason":"x","operationId":"op-1"}""") }.status.value shouldBe 401
        client.post("/admin/shop-config/daily-gain") { setBody("""{"amount":80,"reason":"x","operationId":"op-1"}""") }.status.value shouldBe 401
    }

    @Test
    fun `18_7 leitor (o menor papel) ve a configuracao e o historico`() = testApplication {
        val historico = AdminShopConfigHistoryPage(total = 1, entries = listOf(AdminShopConfigHistoryEntryDto("oferta-0-preco", 200, 100, "motivo", "op-1", 1L)))
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun shopConfig() = config
            override suspend fun shopConfigHistory(limit: Int, offset: Int) = historico
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.LEITOR)

        val r1 = client.get("/admin/shop-config") { header("Authorization", "Bearer $token") }
        r1.status.value shouldBe 200
        json.decodeFromString(AdminShopConfigDto.serializer(), r1.bodyAsText()) shouldBe config

        val r2 = client.get("/admin/shop-config/history") { header("Authorization", "Bearer $token") }
        r2.status.value shouldBe 200
        json.decodeFromString(AdminShopConfigHistoryPage.serializer(), r2.bodyAsText()) shouldBe historico
    }

    @Test
    fun `18_7 leitor e operador nao podem ajustar oferta nem ganho diario, so administrador`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest) = error("nao deveria chamar")
            override suspend fun adjustDailyGain(request: AdminShopGainAdjustRequest) = error("nao deveria chamar")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }

        for (role in listOf(Role.LEITOR, Role.OPERADOR)) {
            val token = client.loginAs(d, role)
            val rOferta = client.post("/admin/shop-config/offer/0") {
                header("Authorization", "Bearer $token")
                setBody("""{"price":200,"reason":"motivo valido","operationId":"op-1"}""")
            }
            rOferta.status.value shouldBe 403
            json.decodeFromString(ApiError.serializer(), rOferta.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO

            val rGanho = client.post("/admin/shop-config/daily-gain") {
                header("Authorization", "Bearer $token")
                setBody("""{"amount":80,"reason":"motivo valido","operationId":"op-2"}""")
            }
            rGanho.status.value shouldBe 403
            json.decodeFromString(ApiError.serializer(), rGanho.bodyAsText()).code shouldBe ErrorCode.SEM_PERMISSAO
        }
    }

    // --------------------------------------------------------------------------------------------- ajustar oferta

    @Test
    fun `18_7 administrador ajusta preco e quantidade de uma oferta, grava a auditoria com o detalhe`() = testApplication {
        var chamou: Pair<Int, AdminShopOfferAdjustRequest>? = null
        val ajustada = AdminShopOfferConfigDto(
            index = 0, boosterId = "ESPIAR",
            price = AdminConfigFieldDto(200, AdminConfigSource.OVERRIDE, 1L),
            quantity = AdminConfigFieldDto(2, AdminConfigSource.OVERRIDE, 1L),
        )
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest): AdminShopOfferConfigDto {
                chamou = index to request
                return ajustada
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val req = AdminShopOfferAdjustRequest(price = 200, quantity = 2, reason = "promocao de fim de ano", operationId = "op-1")
        val r = client.post("/admin/shop-config/offer/0") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), req))
        }
        r.status.value shouldBe 200
        json.decodeFromString(AdminShopOfferConfigDto.serializer(), r.bodyAsText()) shouldBe ajustada
        chamou shouldBe (0 to req)

        val rows = auditRows("AJUSTAR_OFERTA_LOJA")
        rows.size shouldBe 1
        rows.first()["ator_email"] shouldBe "admin@exemplo.com"
        rows.first()["alvo_id"] shouldBe "oferta-0"
        rows.first()["motivo"] shouldBe "promocao de fim de ano"
        rows.first()["detalhe"] shouldBe "preço: 200; quantidade: 2"
    }

    @Test
    fun `18_7 ajustar oferta so o preco grava detalhe so do preco`() = testApplication {
        val ajustada = AdminShopOfferConfigDto(0, "ESPIAR", AdminConfigFieldDto(300, AdminConfigSource.OVERRIDE, 1L), quantidadeDefault)
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest) = ajustada
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        client.post("/admin/shop-config/offer/0") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 300, reason = "motivo valido", operationId = "op-1")))
        }.status.value shouldBe 200

        auditRows("AJUSTAR_OFERTA_LOJA").first()["detalhe"] shouldBe "preço: 300"
    }

    @Test
    fun `18_7 ajustar oferta sem preco nem quantidade da 400, nunca chama o economy-service`() = testApplication {
        var chamado = false
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest): AdminShopOfferConfigDto {
                chamado = true
                error("nao deveria chegar aqui")
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val r = client.post("/admin/shop-config/offer/0") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(reason = "motivo valido", operationId = "op-1")))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        chamado shouldBe false
        auditRows("AJUSTAR_OFERTA_LOJA") shouldBe emptyList()
    }

    @Test
    fun `18_7 ajustar oferta sem motivo da 400`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {}
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val r = client.post("/admin/shop-config/offer/0") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 200, reason = "", operationId = "op-1")))
        }
        r.status.value shouldBe 400
    }

    @Test
    fun `18_7 economy-service recusa oferta inexistente com 400, sem gravar auditoria`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest): AdminShopOfferConfigDto =
                throw EconomyAdjustRejected(EconomyErrorCode.OFERTA_INEXISTENTE, "Oferta inexistente.")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val r = client.post("/admin/shop-config/offer/99") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 200, reason = "motivo valido", operationId = "op-1")))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        auditRows("AJUSTAR_OFERTA_LOJA") shouldBe emptyList()
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado ajustar oferta recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        client.post("/admin/shop-config/offer/0") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 200, reason = "motivo valido", operationId = "op-1")))
        }.status.value shouldBe 503
    }

    // ----------------------------------------------------------------------------------------------- ganho diario

    @Test
    fun `18_7 administrador ajusta o ganho diario, grava a auditoria com o novo valor`() = testApplication {
        var chamou: AdminShopGainAdjustRequest? = null
        val ajustado = AdminConfigFieldDto(80, AdminConfigSource.OVERRIDE, 1L)
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustDailyGain(request: AdminShopGainAdjustRequest): AdminConfigFieldDto {
                chamou = request
                return ajustado
            }
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val req = AdminShopGainAdjustRequest(amount = 80, reason = "ajuste de economia do jogo", operationId = "op-1")
        val r = client.post("/admin/shop-config/daily-gain") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopGainAdjustRequest.serializer(), req))
        }
        r.status.value shouldBe 200
        json.decodeFromString(AdminConfigFieldDto.serializer(), r.bodyAsText()) shouldBe ajustado
        chamou shouldBe req

        val rows = auditRows("AJUSTAR_GANHO_DIARIO")
        rows.size shouldBe 1
        rows.first()["alvo_id"] shouldBe "ganho-diario"
        rows.first()["motivo"] shouldBe "ajuste de economia do jogo"
        rows.first()["detalhe"] shouldBe "novo valor: 80"
    }

    @Test
    fun `18_7 economy-service recusa valor invalido do ganho diario com 400, sem gravar auditoria`() = testApplication {
        val economyAdmin = object : FakeEconomyAdminGateway() {
            override suspend fun adjustDailyGain(request: AdminShopGainAdjustRequest): AdminConfigFieldDto =
                throw EconomyAdjustRejected(EconomyErrorCode.REQUISICAO_INVALIDA, "O ganho diário deve ser maior que zero.")
        }
        val d = deps(economyAdmin = economyAdmin)
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        val r = client.post("/admin/shop-config/daily-gain") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = -10, reason = "motivo valido", operationId = "op-1")))
        }
        r.status.value shouldBe 400
        json.decodeFromString(ApiError.serializer(), r.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        auditRows("AJUSTAR_GANHO_DIARIO") shouldBe emptyList()
    }

    @Test
    fun `18_7 sem ECONOMY_SERVICE_URL configurado ajustar ganho diario recusa com 503`() = testApplication {
        val d = deps()
        application { adminModule(d) }
        val token = client.loginAs(d, Role.ADMINISTRADOR)

        client.post("/admin/shop-config/daily-gain") {
            header("Authorization", "Bearer $token")
            setBody(json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = 80, reason = "motivo valido", operationId = "op-1")))
        }.status.value shouldBe 503
    }
}
