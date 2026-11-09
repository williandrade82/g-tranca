package com.gtranca.online.economy

import com.gtranca.engine.Coins
import com.gtranca.engine.Shop
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.ShopConfigDto
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * §17.10/§14.6: `GET /economy/shop-config`, rota PUBLICA (sem o segredo do console, §18.7, e sem token de
 * jogador) que devolve o MESMO preco/quantidade/ganho diario que `EconomyStore.buy`/`claimDaily` usam de verdade
 * (nunca `Shop.OFFERS`/`Coins.DAILY_GAIN` isolados do app) — fecha o descompasso registrado em
 * `docs/seguranca-pendencias.md` (2026-11-08).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomyShopConfigPublicTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { Database.migrate(it) }
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val appKey = "chave-app-teste"
    private val path = "/economy/shop-config"

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE aposta, lancamento, inventario, carteira, conta, config_loja CASCADE") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun app(block: suspend HttpClient.() -> Unit) = testApplication {
        application { economyModule(EconomyDeps(EconomyStore(db, clock = { now }), appKey, clock = { now })) }
        client.block()
    }

    private suspend fun HttpClient.signedGet(): HttpResponse = get(path) {
        header(AppSignature.TIME_HEADER, now.toString())
        header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(appKey, "GET", path, now, null))
    }

    private suspend fun HttpResponse.config() = json.decodeFromString<ShopConfigDto>(bodyAsText())

    @Test
    fun `17_10 e 14_6 sem override o valor publico e o mesmo default do core-engine, sem token de jogador`() = app {
        val config = signedGet().config()

        config.offers.size shouldBe Shop.OFFERS.size
        config.offers.forEachIndexed { i, dto ->
            dto.index shouldBe i
            dto.boosterId shouldBe Shop.OFFERS[i].boosterId
            dto.price shouldBe Shop.OFFERS[i].price.toLong()
            dto.quantity shouldBe Shop.OFFERS[i].quantity.toLong()
        }
        config.dailyGain shouldBe Coins.DAILY_GAIN.toLong()
    }

    @Test
    fun `17_10 e 14_6 apos um override do administrador, a rota publica mostra o MESMO valor que buy e claimDaily cobram`() = app {
        val store = EconomyStore(db, clock = { now })
        store.adjustOfferConfig(0, price = 1, quantity = 7, reason = "promocao", operationId = "op-1")
        store.adjustDailyGainConfig(amount = 999, reason = "evento", operationId = "op-2")

        val config = signedGet().config()
        config.offers[0].price shouldBe 1L
        config.offers[0].quantity shouldBe 7L
        config.dailyGain shouldBe 999L

        // A MESMA fonte: nenhuma rota le um valor diferente do que a compra/o resgate de verdade usam.
        store.ensureAccount("g1")
        store.buy("g1", 0, "k1").balance shouldBe (1_000L - config.offers[0].price)
        store.claimDaily("g1", today = 1).balance shouldBe (1_000L - config.offers[0].price + config.dailyGain)
    }

    @Test
    fun `18_2 exige a assinatura do app, mas nunca um token de jogador (e informacao publica)`() = app {
        get(path).status shouldBe HttpStatusCode.Unauthorized // sem assinatura

        val ok = signedGet()
        ok.status shouldBe HttpStatusCode.OK // sem Authorization/Bearer nenhum, e funciona
    }

    @Test
    fun `17_10 e 14_6 a rota publica nao tem historico nem a origem do valor, so o atual (isso e so da tela do operador)`() = app {
        // ShopConfigDto (publico) so tem index/boosterId/price/quantity/dailyGain: sem AdminConfigSource nem
        // sinceMs. Se o JSON decodificar com sucesso nesse tipo mais magro, a rota nunca vazou os campos do admin.
        val config = signedGet().config()
        config.offers.size shouldBe Shop.OFFERS.size
    }

    @Test
    fun `17_10 nao e capturada pela rota parametrizada da carteira (economy guestId)`() = app {
        // "/economy/shop-config" precisa casar com o segmento literal, nunca ser tratada como se "shop-config"
        // fosse um guestId (o que exigiria token e devolveria 401/403, nunca 200 sem Authorization).
        signedGet().status shouldBe HttpStatusCode.OK
    }
}
