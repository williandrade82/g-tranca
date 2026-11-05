package com.gtranca.online.client

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.EmailLoginRequest
import com.gtranca.online.contracts.EmailRegisterRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
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

class OnlineClientTest {
    private val rules = TableRules(TableMode.DUPLAS, "{}", targetScore = 200)
    private val table = TableView(
        id = "ABC234", hostId = "g1", hostName = "Ana", state = TableState.ABERTA, rules = rules,
        seats = listOf(SeatView(0, SeatKind.HUMANO, "g1", "Ana"), SeatView(1, SeatKind.VAGO)),
    )
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OnlineClient {
        val http = OnlineClient.newHttpClient(MockEngine(handler))
        return OnlineClient(OnlineEndpoint.parse("192.0.2.1:8080", cleartext = true)!!, http)
    }

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: ErrorCode, message: String = "x") =
        respond(OnlineJson.encodeToString(ApiError.serializer(), ApiError(code, message)), status, json)

    // §18.1
    @Test
    fun `cria o convidado com a versao do protocolo e sem token`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        var body = ""
        val c = client { req ->
            seen = req
            body = String(req.body.toByteArray())
            respond(OnlineJson.encodeToString(GuestIdentity.serializer(), GuestIdentity("g1", "Ana", "tok")), HttpStatusCode.Created, json)
        }
        val guest = c.createGuest("Ana")
        guest.token shouldBe "tok"
        seen!!.url.toString() shouldBe "http://192.0.2.1:8080/api/lobby/guests"
        seen!!.method shouldBe HttpMethod.Post
        seen!!.headers["X-Protocol-Version"] shouldBe ONLINE_PROTOCOL_VERSION.toString()
        seen!!.headers[HttpHeaders.Authorization] shouldBe null
        OnlineJson.decodeFromString(CreateGuestRequest.serializer(), body).name shouldBe "Ana"
    }

    // §18.1 (mudanca de 02/11/2026)
    @Test
    fun `registerEmail manda email, senha, nome e o token do convidado para accounts_email_register`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        var body = ""
        val c = client { req ->
            seen = req
            body = String(req.body.toByteArray())
            respond(
                OnlineJson.encodeToString(AccountLogin.serializer(), AccountLogin("E_1", "Ana", "tok2", kind = AccountKind.EMAIL)),
                HttpStatusCode.Created, json,
            )
        }
        val acc = c.registerEmail("ana@exemplo.com", "segredo123", "Ana", "guest-tok")
        acc.kind shouldBe AccountKind.EMAIL
        acc.token shouldBe "tok2"
        seen!!.url.encodedPath shouldBe "/api/lobby/accounts/email/register"
        seen!!.method shouldBe HttpMethod.Post
        val sent = OnlineJson.decodeFromString(EmailRegisterRequest.serializer(), body)
        sent.email shouldBe "ana@exemplo.com"
        sent.password shouldBe "segredo123"
        sent.name shouldBe "Ana"
        sent.guestToken shouldBe "guest-tok"
    }

    // §18.1
    @Test
    fun `loginEmail manda email, senha e o token do convidado para accounts_email_login`() = runBlocking<Unit> {
        var body = ""
        val c = client { req ->
            body = String(req.body.toByteArray())
            respond(
                OnlineJson.encodeToString(AccountLogin.serializer(), AccountLogin("E_1", "Ana", "tok3", kind = AccountKind.EMAIL)),
                HttpStatusCode.OK, json,
            )
        }
        c.loginEmail("ana@exemplo.com", "segredo123", null).id shouldBe "E_1"
        val sent = OnlineJson.decodeFromString(EmailLoginRequest.serializer(), body)
        sent.email shouldBe "ana@exemplo.com"
        sent.password shouldBe "segredo123"
        sent.guestToken shouldBe null
    }

    // §18.1: e-mail ja cadastrado chega com o codigo, para a tela mostrar o texto certo.
    @Test
    fun `registerEmail com e-mail ja cadastrado chega com EMAIL_JA_CADASTRADO`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Conflict, ErrorCode.EMAIL_JA_CADASTRADO, "Esse e-mail já tem uma conta.") }
        val e = shouldThrow<OnlineException> { c.registerEmail("ana@exemplo.com", "segredo123", null, null) }
        e.code shouldBe ErrorCode.EMAIL_JA_CADASTRADO
    }

    // §18.2 so o aplicativo oficial
    @Test
    fun `com a chave do app cada pedido leva horario e assinatura que o servidor confere`() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val http = OnlineClient.newHttpClient(
            MockEngine { req ->
                seen = req
                respond(OnlineJson.encodeToString(TableView.serializer(), table), HttpStatusCode.OK, json)
            },
        )
        val c = OnlineClient(OnlineEndpoint.parse("192.0.2.1:8080", cleartext = true)!!, http, appKey = "chave", clock = { 1_000_000L })
        c.table("tok", "ABC234")
        val h = seen!!.headers
        h[com.gtranca.online.contracts.AppSignature.TIME_HEADER] shouldBe "1000000"
        com.gtranca.online.contracts.AppSignature.verify(
            "chave", "GET", "/api/lobby/tables/ABC234", h[com.gtranca.online.contracts.AppSignature.TIME_HEADER],
            h[com.gtranca.online.contracts.AppSignature.SIGNATURE_HEADER], "tok", 1_000_000L,
        ) shouldBe true
        com.gtranca.online.contracts.AppSignature.verify(
            "outra", "GET", "/api/lobby/tables/ABC234", h[com.gtranca.online.contracts.AppSignature.TIME_HEADER],
            h[com.gtranca.online.contracts.AppSignature.SIGNATURE_HEADER], "tok", 1_000_000L,
        ) shouldBe false
    }

    // §18.2
    @Test
    fun `cria a mesa com o token e as regras e consulta pelo id`() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        var body = ""
        val c = client { req ->
            seen += req
            if (req.method == HttpMethod.Post) body = String(req.body.toByteArray())
            respond(OnlineJson.encodeToString(TableView.serializer(), table), HttpStatusCode.OK, json)
        }
        c.createTable("tok", rules).id shouldBe "ABC234"
        c.table("tok", "abc234").hostName shouldBe "Ana"
        OnlineJson.decodeFromString(CreateTableRequest.serializer(), body).rules shouldBe rules
        seen[0].headers[HttpHeaders.Authorization] shouldBe "Bearer tok"
        seen[1].url.encodedPath shouldBe "/api/lobby/tables/ABC234"
    }

    // §18.2
    @Test
    fun `entrar, sair, robo, remover, iniciar e encerrar usam as rotas do lobby`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val c = client { req ->
            calls += "${req.method.value} ${req.url.encodedPath}"
            respond(OnlineJson.encodeToString(TableView.serializer(), table), HttpStatusCode.OK, json)
        }
        c.join("t", "ABC234", null)
        c.leave("t", "ABC234")
        c.seatRobot("t", "ABC234", 2)
        c.removeHuman("t", "ABC234", 1)
        c.start("t", "ABC234")
        c.close("t", "ABC234")
        calls shouldBe listOf(
            "POST /api/lobby/tables/ABC234/join",
            "POST /api/lobby/tables/ABC234/leave",
            "POST /api/lobby/tables/ABC234/seats/2/robot",
            "POST /api/lobby/tables/ABC234/seats/1/remove",
            "POST /api/lobby/tables/ABC234/start",
            "DELETE /api/lobby/tables/ABC234",
        )
    }

    // §18.8
    @Test
    fun `426 vira UPDATE_APP`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.UpgradeRequired, ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo") }
        val e = shouldThrow<OnlineException> { c.createGuest("Ana") }
        e.kind shouldBe OnlineErrorKind.UPDATE_APP
        e.status shouldBe 426
    }

    // §18.8
    @Test
    fun `503 de versao vira MAINTENANCE`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.ServiceUnavailable, ErrorCode.VERSAO_INCOMPATIVEL, "Servidor em manutenção") }
        shouldThrow<OnlineException> { c.createGuest("Ana") }.kind shouldBe OnlineErrorKind.MAINTENANCE
    }

    // §18.2
    @Test
    fun `recusas do lobby chegam com o codigo`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.NotFound, ErrorCode.MESA_INEXISTENTE) }
        val e = shouldThrow<OnlineException> { c.table("t", "ABC234") }
        e.kind shouldBe OnlineErrorKind.API
        e.code shouldBe ErrorCode.MESA_INEXISTENTE
    }

    // §18.1
    @Test
    fun `401 vira UNAUTHENTICATED`() = runBlocking<Unit> {
        val c = client { error(HttpStatusCode.Unauthorized, ErrorCode.NAO_AUTENTICADO) }
        shouldThrow<OnlineException> { c.table("t", "ABC234") }.kind shouldBe OnlineErrorKind.UNAUTHENTICATED
    }

    @Test
    fun `sem rede vira NETWORK e retryable`() = runBlocking<Unit> {
        val c = client { throw IOException("sem rota") }
        val e = shouldThrow<OnlineException> { c.createGuest("Ana") }
        e.kind shouldBe OnlineErrorKind.NETWORK
        e.retryable shouldBe true
    }

    @Test
    fun `resposta que nao e do jogo vira NETWORK e 502 de proxy vira MAINTENANCE`() = runBlocking<Unit> {
        val html = client { respond("<html>oi</html>", HttpStatusCode.OK) }
        shouldThrow<OnlineException> { html.createGuest("Ana") }.kind shouldBe OnlineErrorKind.NETWORK
        val bad = client { respond("Bad Gateway", HttpStatusCode.BadGateway) }
        shouldThrow<OnlineException> { bad.createGuest("Ana") }.kind shouldBe OnlineErrorKind.MAINTENANCE
    }
}
