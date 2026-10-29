package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ResolveGuestRequest
import com.gtranca.online.contracts.ResolvedGuest
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

/** §18.3: passagem da mesa ao game-service ao iniciar, e introspeccao de convidados para o game-service. */
class LobbyHandoffTest {
    private val json = OnlineJson
    private val secret = "segredo-de-teste"

    private class FakeGateway(var fail: GameGatewayException? = null) : GameGateway {
        val requests = mutableListOf<CreateMatchRequest>()
        override suspend fun createMatch(request: CreateMatchRequest) {
            fail?.let { throw it }
            requests += request
        }
    }

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: String? = null,
        secretHeader: String? = null,
    ): HttpResponse = request(path) {
        this.method = method
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (secretHeader != null) header(INTERNAL_SECRET_HEADER, secretHeader)
        if (body != null) setBody(body)
    }

    private suspend fun HttpClient.guest(name: String): GuestIdentity =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/guests", body = json.encodeToString(CreateGuestRequest(name))).bodyAsText())

    private suspend fun HttpClient.table(token: String, rules: TableRules): TableView =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/tables", token, json.encodeToString(CreateTableRequest(rules))).bodyAsText())

    private suspend fun HttpClient.view(token: String, id: String): TableView =
        json.decodeFromString(call(HttpMethod.Get, "/api/lobby/tables/$id", token).bodyAsText())

    private suspend fun HttpResponse.error(): ErrorCode = json.decodeFromString<ApiError>(bodyAsText()).code

    private fun withApp(gateway: GameGateway?, secretConfigured: String? = secret, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { lobbyModule(LobbyDeps(gateway = gateway, internalSecret = secretConfigured)) }
            block()
        }

    private val duplas = TableRules(TableMode.DUPLAS, "{}", targetScore = 500, robotLevel = BotLevel.MEDIO)

    @Test
    fun `18_3 ao iniciar o lobby cria a partida no game-service com humanos e robos de perfis diferentes`() {
        val gw = FakeGateway()
        withApp(gw) {
            val ana = client.guest("Ana")
            val beto = client.guest("Beto")
            val t = client.table(ana.token, duplas)
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/join", beto.token, "{}")
            // Ainda com lugares vagos: nada e enviado ao game-service.
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token).error() shouldBe ErrorCode.LUGARES_VAGOS
            gw.requests shouldHaveSize 0
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/seats/2/robot", ana.token)
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/seats/3/robot", ana.token)

            val started = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
            started.status shouldBe HttpStatusCode.OK
            json.decodeFromString<TableView>(started.bodyAsText()).state shouldBe TableState.EM_JOGO
            val req = gw.requests.single()
            req.tableId shouldBe t.id
            req.rules shouldBe duplas
            req.seats[0] shouldBe SeatSpec.Human(ana.id, "Ana")
            req.seats[1] shouldBe SeatSpec.Human(beto.id, "Beto")
            val robots = req.seats.filterIsInstance<SeatSpec.Robot>()
            robots shouldHaveSize 2
            robots.map { it.profile }.toSet() shouldHaveSize 2 // perfis diferentes entre si (§14.5)
            robots.all { it.level == BotLevel.MEDIO } shouldBe true
            // Iniciar de novo: a mesa ja comecou.
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token).error() shouldBe ErrorCode.MESA_JA_INICIADA
            gw.requests shouldHaveSize 1
        }
    }

    @Test
    fun `18_3 se o game-service falha a mesa continua ABERTA e o erro e tipado`() {
        val gw = FakeGateway(fail = GameGatewayException(null, "fora do ar"))
        withApp(gw) {
            val ana = client.guest("Ana")
            val t = client.table(ana.token, TableRules(TableMode.INDIVIDUAL, "{}"))
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/seats/1/robot", ana.token)

            val r = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
            r.status shouldBe HttpStatusCode.ServiceUnavailable
            r.error() shouldBe ErrorCode.PARTIDA_INDISPONIVEL
            client.view(ana.token, t.id).state shouldBe TableState.ABERTA
            // A mesa segue editavel (nao ficou presa em "iniciando") e da para tentar de novo.
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/seats/1/remove", ana.token).error() shouldBe ErrorCode.LUGAR_INVALIDO
            gw.fail = GameGatewayException(400, "regras")
            val bad = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
            bad.status shouldBe HttpStatusCode.BadRequest
            bad.error() shouldBe ErrorCode.REQUISICAO_INVALIDA
            client.view(ana.token, t.id).state shouldBe TableState.ABERTA
            gw.fail = null
            client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token).status shouldBe HttpStatusCode.OK
            client.view(ana.token, t.id).state shouldBe TableState.EM_JOGO
        }
    }

    @Test
    fun `18_3 enquanto a partida e criada a mesa nao aceita entrada, saida nem encerramento`() {
        val service = LobbyService(InMemoryTableStore(), { 1_000L }, kotlin.random.Random(1))
        val ana = Guest("a", "Ana")
        val beto = Guest("b", "Beto")
        val t = service.createTable(ana, TableRules(TableMode.INDIVIDUAL, "{}"))
        service.seatRobot(ana, t.id, 1)
        service.beginStart(ana, t.id)
        fun code(block: () -> Unit) = io.kotest.assertions.throwables.shouldThrow<LobbyException> { block() }.code
        code { service.join(beto, t.id, null) } shouldBe ErrorCode.MESA_JA_INICIADA
        code { service.close(ana, t.id) } shouldBe ErrorCode.MESA_JA_INICIADA
        code { service.beginStart(ana, t.id) } shouldBe ErrorCode.MESA_JA_INICIADA
        service.get(ana, t.id).state shouldBe TableState.ABERTA
        service.abortStart(t.id).state shouldBe TableState.ABERTA
        code { service.join(beto, t.id, null) } shouldBe ErrorCode.MESA_CHEIA // aceita de novo (e a mesa ja esta cheia)
    }

    @Test
    fun `18_3 introspeccao de convidado exige o segredo e nao expoe o token`() {
        withApp(null) {
            val ana = client.guest("Ana")
            val body = json.encodeToString(ResolveGuestRequest(ana.token))
            client.call(HttpMethod.Post, "/internal/guests/resolve", body = body).status shouldBe HttpStatusCode.Unauthorized
            client.call(HttpMethod.Post, "/internal/guests/resolve", body = body, secretHeader = "errado").status shouldBe HttpStatusCode.Unauthorized
            val ok = client.call(HttpMethod.Post, "/internal/guests/resolve", body = body, secretHeader = secret)
            ok.status shouldBe HttpStatusCode.OK
            ok.bodyAsText().also { it shouldNotContain ana.token }
            json.decodeFromString<ResolvedGuest>(ok.bodyAsText()) shouldBe ResolvedGuest(ana.id, "Ana")
            val unknown = json.encodeToString(ResolveGuestRequest("token-que-nao-existe"))
            client.call(HttpMethod.Post, "/internal/guests/resolve", body = unknown, secretHeader = secret).status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `18_3 sem segredo configurado a introspeccao fica fechada`() {
        withApp(null, secretConfigured = null) {
            val body = json.encodeToString(ResolveGuestRequest("x"))
            client.call(HttpMethod.Post, "/internal/guests/resolve", body = body, secretHeader = "qualquer").status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }
}
