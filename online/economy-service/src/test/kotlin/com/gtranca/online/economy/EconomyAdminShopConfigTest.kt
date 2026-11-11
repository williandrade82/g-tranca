package com.gtranca.online.economy

import com.gtranca.engine.Coins
import com.gtranca.engine.Shop
import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminConfigSource
import com.gtranca.online.contracts.AdminShopConfigDto
import com.gtranca.online.contracts.AdminShopConfigHistoryPage
import com.gtranca.online.contracts.AdminShopGainAdjustRequest
import com.gtranca.online.contracts.AdminShopOfferAdjustRequest
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * §17.10/§14.6/§18.7, tela "Loja e configuração": preço e quantidade de cada oferta e o ganho diário ficam
 * editáveis pelo administrador, versionados (append-only, nunca `UPDATE`). O presente diário (§17.5) NÃO está
 * nesta etapa (ver relatório: não existe hoje como rota online e não tem um único "valor" no `:core-engine`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyAdminShopConfigTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val internalSecret = "segredo-interno"
    private val adminSecret = "segredo-admin"

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta, config_loja CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun app(block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            economyModule(EconomyDeps(EconomyStore(db, clock = { now }), internalSecret = internalSecret, adminSecret = adminSecret))
        }
        client.block()
    }

    private val shopConfig = "/internal/admin/shop-config"
    private val shopConfigHistory = "/internal/admin/shop-config/history"
    private fun offer(index: Int) = "/internal/admin/shop-config/offer/$index"
    private val dailyGain = "/internal/admin/shop-config/daily-gain"

    private suspend fun HttpClient.adminGet(path: String): HttpResponse = get(path) {
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
    }

    private suspend fun HttpClient.adminPost(path: String, body: String): HttpResponse = post(path) {
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.error(): EconomyErrorCode = json.decodeFromString<EconomyError>(bodyAsText()).code
    private suspend fun HttpResponse.config(): AdminShopConfigDto = json.decodeFromString(bodyAsText())

    // ------------------------------------------------------------------------------------------------ leitura (default)

    @Test
    fun `17_10 e 14_6 sem override o valor atual e o default do core-engine, fonte DEFAULT, desde nulo`() = app {
        val config = adminGet(shopConfig).config()

        config.offers.size shouldBe Shop.OFFERS.size
        config.offers.forEachIndexed { i, dto ->
            dto.boosterId shouldBe Shop.OFFERS[i].boosterId
            dto.price.value shouldBe Shop.OFFERS[i].price.toLong()
            dto.price.source shouldBe AdminConfigSource.DEFAULT
            dto.price.sinceMs shouldBe null
            dto.quantity.value shouldBe Shop.OFFERS[i].quantity.toLong()
            dto.quantity.source shouldBe AdminConfigSource.DEFAULT
        }
        config.dailyGain.value shouldBe Coins.DAILY_GAIN.toLong()
        config.dailyGain.source shouldBe AdminConfigSource.DEFAULT
    }

    // ------------------------------------------------------------------------------------------------ ajustar oferta

    @Test
    fun `18_7 ajustar o preco de uma oferta muda so o preco, a quantidade continua default`() = app {
        val r = adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 70, reason = "promocao", operationId = "op-1")))
        r.status shouldBe HttpStatusCode.OK

        val config = adminGet(shopConfig).config()
        config.offers[0].price.value shouldBe 70L
        config.offers[0].price.source shouldBe AdminConfigSource.OVERRIDE
        (config.offers[0].price.sinceMs != null) shouldBe true
        config.offers[0].quantity.value shouldBe Shop.OFFERS[0].quantity.toLong()
        config.offers[0].quantity.source shouldBe AdminConfigSource.DEFAULT
    }

    @Test
    fun `18_7 ajustar preco e quantidade juntos muda os dois, e o historico grava o valor anterior default na 1a mudanca`() = app {
        adminPost(
            offer(1),
            json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 25, quantity = 4, reason = "ajuste de catalogo", operationId = "op-1")),
        ).status shouldBe HttpStatusCode.OK

        val history = json.decodeFromString<AdminShopConfigHistoryPage>(adminGet(shopConfigHistory).bodyAsText())
        history.total shouldBe 2
        val priceRow = history.entries.first { it.field == "oferta-1-preco" }
        priceRow.value shouldBe 25L
        priceRow.previousValue shouldBe Shop.OFFERS[1].price.toLong()
        priceRow.reason shouldBe "ajuste de catalogo"
        val qtyRow = history.entries.first { it.field == "oferta-1-quantidade" }
        qtyRow.value shouldBe 4L
        qtyRow.previousValue shouldBe Shop.OFFERS[1].quantity.toLong()
    }

    @Test
    fun `18_7 uma 2a mudanca grava como valor anterior o valor da mudanca passada, nunca o default`() = app {
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 70, reason = "1a mudanca", operationId = "op-1")))
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 90, reason = "2a mudanca", operationId = "op-2")))

        val history = json.decodeFromString<AdminShopConfigHistoryPage>(adminGet(shopConfigHistory).bodyAsText())
        val second = history.entries.first { it.operationId == "op-2" }
        second.value shouldBe 90L
        second.previousValue shouldBe 70L
    }

    @Test
    fun `18_7 repetir o mesmo operationId nao grava de novo nem muda o valor`() = app {
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 70, reason = "promocao", operationId = "op-1")))
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 70, reason = "promocao de novo", operationId = "op-1")))

        val config = adminGet(shopConfig).config()
        config.offers[0].price.value shouldBe 70L
        val history = json.decodeFromString<AdminShopConfigHistoryPage>(adminGet(shopConfigHistory).bodyAsText())
        history.total shouldBe 1
    }

    @Test
    fun `18_7 ajustar oferta exige preco ou quantidade, motivo, chave de idempotencia, e nunca aceita zero ou negativo`() = app {
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(reason = "motivo", operationId = "op-1")))
            .status shouldBe HttpStatusCode.BadRequest // nem preco nem quantidade
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 10, reason = "", operationId = "op-1")))
            .status shouldBe HttpStatusCode.BadRequest // sem motivo
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 10, reason = "motivo", operationId = "")))
            .status shouldBe HttpStatusCode.BadRequest // sem chave
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 0, reason = "motivo", operationId = "op-1")))
            .error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA // zero
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = -10, reason = "motivo", operationId = "op-1")))
            .error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA // negativo
        adminPost(offer(0), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(quantity = 0, reason = "motivo", operationId = "op-1")))
            .error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_7 ajustar uma oferta fora do catalogo da oferta inexistente`() = app {
        val r = adminPost(offer(999), json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 10, reason = "motivo", operationId = "op-1")))
        r.error() shouldBe EconomyErrorCode.OFERTA_INEXISTENTE
    }

    // ------------------------------------------------------------------------------------------------ ganho diario

    @Test
    fun `14_6 e 18_7 ajustar o ganho diario muda o valor atual, com historico, e nunca aceita zero ou negativo`() = app {
        adminPost(dailyGain, json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = 300, reason = "inflacao do jogo", operationId = "op-1")))
            .status shouldBe HttpStatusCode.OK

        val config = adminGet(shopConfig).config()
        config.dailyGain.value shouldBe 300L
        config.dailyGain.source shouldBe AdminConfigSource.OVERRIDE

        adminPost(dailyGain, json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = 0, reason = "motivo", operationId = "op-2")))
            .error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA
        adminPost(dailyGain, json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = -5, reason = "motivo", operationId = "op-3")))
            .error() shouldBe EconomyErrorCode.REQUISICAO_INVALIDA
    }

    @Test
    fun `18_7 shop-config e o historico exigem o segredo do console, nunca o servico a servico`() = app {
        get(shopConfig).status shouldBe HttpStatusCode.Unauthorized
        get(shopConfigHistory).status shouldBe HttpStatusCode.Unauthorized
        post(offer(0)) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AdminShopOfferAdjustRequest.serializer(), AdminShopOfferAdjustRequest(price = 10, reason = "motivo", operationId = "op-1")))
        }.status shouldBe HttpStatusCode.Unauthorized
        post(dailyGain) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AdminShopGainAdjustRequest.serializer(), AdminShopGainAdjustRequest(amount = 10, reason = "motivo", operationId = "op-1")))
        }.status shouldBe HttpStatusCode.Unauthorized
    }

    // ------------------------------------------------------------------------------------------------ o jogo usa o valor mudado (cerne da etapa)

    @Test
    fun `17_10 apos mudar preco e quantidade, a compra debita e credita os valores NOVOS, nunca os do catalogo fixo`() = app {
        val store = EconomyStore(db, clock = { now })
        store.ensureAccount("g1")
        val boosterId = Shop.OFFERS[0].boosterId
        val before = store.state("g1").inventory[boosterId] ?: 0

        store.adjustOfferConfig(0, price = 1, quantity = 7, reason = "promocao", operationId = "op-1")
        val after = store.buy("g1", 0, "compra-1")

        after.balance shouldBe (1_000L - 1L) // debitou o preco NOVO (1), nao o original (Shop.OFFERS[0].price)
        after.inventory[boosterId] shouldBe before + 7 // creditou a quantidade NOVA (7)
    }

    @Test
    fun `14_6 apos mudar o ganho diario, o resgate credita o valor NOVO, nunca Coins_DAILY_GAIN`() = app {
        val store = EconomyStore(db, clock = { now })
        store.ensureAccount("g1")
        store.adjustDailyGainConfig(amount = 1, reason = "teste", operationId = "op-1")

        val after = store.claimDaily("g1", today = 1)
        after.balance shouldBe 1_001L // 1.000 iniciais + 1 (o NOVO ganho diario, nao os 200 de Coins.DAILY_GAIN)
    }
}
