package com.gtranca.online.client

import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.BuyRequest
import com.gtranca.online.contracts.DailyClaimRequest
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.MigrateLocalWalletRequest
import com.gtranca.online.contracts.OnlineJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException

/** §18.5 / §17.10: rotas publicas do economy-service pelo :online-client. */
class EconomyClientTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val state = EconomyStateDto(balance = 100, inventory = mapOf("SKILL" to 1), lastDailyClaim = 19_000)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OnlineClient {
        val http = OnlineClient.newHttpClient(MockEngine(handler))
        return OnlineClient(OnlineEndpoint.parse("192.0.2.1:8080", cleartext = true)!!, http)
    }

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: EconomyErrorCode, message: String = "x") =
        respond(OnlineJson.encodeToString(EconomyError.serializer(), EconomyError(code, message)), status, json)

    // §18.5
    @Test
    fun `consulta a carteira pelo caminho sem api lobby`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val c = client { req ->
            seen = req
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
        }
        c.economyState("tok", "g1") shouldBe state
        seen!!.url.toString() shouldBe "http://192.0.2.1:8080/economy/g1"
        seen!!.method shouldBe HttpMethod.Get
        seen!!.headers[HttpHeaders.Authorization] shouldBe "Bearer tok"
    }

    // §14.6
    @Test
    fun `ganho diario envia o dia de hoje`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        var body = ""
        val c = client { req ->
            seen = req
            body = String(req.body.toByteArray())
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
        }
        c.claimDaily("tok", "g1", 20_300) shouldBe state
        seen!!.url.encodedPath shouldBe "/economy/g1/daily"
        seen!!.method shouldBe HttpMethod.Post
        OnlineJson.decodeFromString(DailyClaimRequest.serializer(), body).today shouldBe 20_300L
    }

    // §17.10
    @Test
    fun `compra envia a oferta e a chave de idempotencia`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        var body = ""
        val c = client { req ->
            seen = req
            body = String(req.body.toByteArray())
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
        }
        c.buyOffer("tok", "g1", 2, "compra-1") shouldBe state
        seen!!.url.encodedPath shouldBe "/economy/g1/buy"
        val req = OnlineJson.decodeFromString(BuyRequest.serializer(), body)
        req.offerIndex shouldBe 2
        req.key shouldBe "compra-1"
    }

    // §18.6
    @Test
    fun `migracao da carteira local envia o saldo e o inventario para a rota migrate`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        var body = ""
        val migrated = state.copy(migratedLocal = true)
        val c = client { req ->
            seen = req
            body = String(req.body.toByteArray())
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), migrated), HttpStatusCode.OK, json)
        }
        c.migrateLocalWallet("tok", "g1", 500, mapOf("b1" to 3)) shouldBe migrated
        seen!!.url.encodedPath shouldBe "/economy/g1/migrate"
        seen!!.method shouldBe HttpMethod.Post
        val req = OnlineJson.decodeFromString(MigrateLocalWalletRequest.serializer(), body)
        req.coins shouldBe 500L
        req.inventory shouldBe mapOf("b1" to 3)
    }

    // §18.6: convidado nao e elegivel
    @Test
    fun `convidado recebe CONTA_NAO_ELEGIVEL ao tentar migrar`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Forbidden, EconomyErrorCode.CONTA_NAO_ELEGIVEL) }
        val e = shouldThrow<OnlineException> { c.migrateLocalWallet("tok", "conv1", 100, emptyMap()) }
        e.kind shouldBe OnlineErrorKind.API
        e.economyCode shouldBe EconomyErrorCode.CONTA_NAO_ELEGIVEL
    }

    // §18.2
    @Test
    fun `com a chave do app a assinatura cobre o caminho da economia`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val http = OnlineClient.newHttpClient(
            MockEngine { req ->
                seen = req
                respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
            },
        )
        val c = OnlineClient(OnlineEndpoint.parse("192.0.2.1:8080", cleartext = true)!!, http, appKey = "chave", clock = { 1_000_000L })
        c.economyState("tok", "g1")
        val h = seen!!.headers
        AppSignature.verify(
            "chave", "GET", "/economy/g1", h[AppSignature.TIME_HEADER], h[AppSignature.SIGNATURE_HEADER], "tok", 1_000_000L,
        ) shouldBe true
    }

    // §17.10: saldo insuficiente e um erro de negocio, nao de rede
    @Test
    fun `saldo insuficiente vira API com o codigo da economia`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Conflict, EconomyErrorCode.SALDO_INSUFICIENTE) }
        val e = shouldThrow<OnlineException> { c.buyOffer("tok", "g1", 0, "k") }
        e.kind shouldBe OnlineErrorKind.API
        e.economyCode shouldBe EconomyErrorCode.SALDO_INSUFICIENTE
        e.retryable shouldBe false
    }

    // §14.6
    @Test
    fun `ja resgatado vira API com o codigo (nao cai no generico por status)`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Conflict, EconomyErrorCode.JA_RESGATADO) }
        val e = shouldThrow<OnlineException> { c.claimDaily("tok", "g1", 1) }
        e.kind shouldBe OnlineErrorKind.API
        e.economyCode shouldBe EconomyErrorCode.JA_RESGATADO
    }

    // §18.2: app sem a chave certa nao e token vencido
    @Test
    fun `app nao reconhecido fica API, nao UNAUTHENTICATED, mesmo com 401`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Unauthorized, EconomyErrorCode.APP_NAO_RECONHECIDO) }
        val e = shouldThrow<OnlineException> { c.economyState("tok", "g1") }
        e.kind shouldBe OnlineErrorKind.API
        e.economyCode shouldBe EconomyErrorCode.APP_NAO_RECONHECIDO
    }

    // §18.1
    @Test
    fun `sem token ou token vencido vira UNAUTHENTICATED`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Unauthorized, EconomyErrorCode.NAO_AUTENTICADO) }
        shouldThrow<OnlineException> { c.economyState("tok", "g1") }.kind shouldBe OnlineErrorKind.UNAUTHENTICATED
    }

    // §18.5: carteira de outra conta
    @Test
    fun `carteira de outra conta vira API sem permissao`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Forbidden, EconomyErrorCode.SEM_PERMISSAO) }
        val e = shouldThrow<OnlineException> { c.economyState("tok", "g1") }
        e.kind shouldBe OnlineErrorKind.API
        e.economyCode shouldBe EconomyErrorCode.SEM_PERMISSAO
    }

    // servidor indisponivel: vale tentar de novo, diferente de saldo insuficiente
    @Test
    fun `indisponivel vira MAINTENANCE e retryable`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.ServiceUnavailable, EconomyErrorCode.INDISPONIVEL) }
        val e = shouldThrow<OnlineException> { c.economyState("tok", "g1") }
        e.kind shouldBe OnlineErrorKind.MAINTENANCE
        e.retryable shouldBe true
    }

    @Test
    fun `sem rede vira NETWORK e retryable`() = runBlocking<Unit> {
        val c = client { throw IOException("sem rota") }
        val e = shouldThrow<OnlineException> { c.economyState("tok", "g1") }
        e.kind shouldBe OnlineErrorKind.NETWORK
        e.retryable shouldBe true
    }

    @Test
    fun `resposta que nao e do jogo vira NETWORK e 502 de proxy vira MAINTENANCE`() = runBlocking<Unit> {
        val html = client { respond("<html>oi</html>", HttpStatusCode.OK) }
        shouldThrow<OnlineException> { html.economyState("tok", "g1") }.kind shouldBe OnlineErrorKind.NETWORK
        val bad = client { respond("Fora do ar", HttpStatusCode.BadGateway) }
        shouldThrow<OnlineException> { bad.economyState("tok", "g1") }.kind shouldBe OnlineErrorKind.MAINTENANCE
    }

    // id de conta invalido nunca vira um caminho de requisicao
    @Test
    fun `id de conta invalido e recusado sem chamar o servidor`() = runBlocking<Unit> {
        var called = false
        val c = client { req ->
            called = true
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
        }
        shouldThrow<IllegalArgumentException> { c.economyState("tok", "g1/../outra") }
        called shouldBe false
    }

    // §17.10: a chave de compra vazia ou grande demais nao sai do aparelho
    @Test
    fun `chave de compra vazia e recusada sem chamar o servidor`() = runBlocking<Unit> {
        var called = false
        val c = client { req ->
            called = true
            respond(OnlineJson.encodeToString(EconomyStateDto.serializer(), state), HttpStatusCode.OK, json)
        }
        shouldThrow<IllegalArgumentException> { c.buyOffer("tok", "g1", 0, "") }
        called shouldBe false
    }
}
