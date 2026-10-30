package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class LobbyHttpTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val rules = TableRules(TableMode.INDIVIDUAL, "{}")

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: String? = null,
        version: Int? = ONLINE_PROTOCOL_VERSION,
    ): HttpResponse = request(path) {
        this.method = method
        if (version != null) header(PROTOCOL_HEADER, version)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (body != null) setBody(body)
    }

    private suspend fun HttpClient.guest(name: String): GuestIdentity =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/guests", body = json.encodeToString(CreateGuestRequest(name))).bodyAsText())

    /** §18.2: o jogador entra na mesa aberta do servidor (nao cria mesa). */
    private suspend fun HttpClient.serverTable(token: String): TableView {
        val list = json.decodeFromString<TableListView>(call(HttpMethod.Get, "/api/lobby/tables", token).bodyAsText())
        val id = list.tables.first { it.state == TableState.ABERTA && it.seats.any { s -> s.kind == SeatKind.VAGO } }.id
        return json.decodeFromString(call(HttpMethod.Post, "/api/lobby/tables/$id/join", token, "{}").bodyAsText())
    }

    private suspend fun HttpResponse.error(): ErrorCode = json.decodeFromString<ApiError>(bodyAsText()).code

    private fun withApp(deps: LobbyDeps = LobbyDeps(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { lobbyModule(deps) }
            block()
        }

    @Test
    fun `18_2 fluxo completo convidado cria mesa, outro entra, anfitriao inicia`() = withApp {
        val ana = client.guest("Ana")
        val beto = client.guest("Beto")
        val t = client.serverTable(ana.token)
        client.call(HttpMethod.Get, "/api/lobby/tables/${t.id}", beto.token).status shouldBe HttpStatusCode.OK
        val joined = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/join", beto.token, "{}")
        joined.status shouldBe HttpStatusCode.OK
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", beto.token).status shouldBe HttpStatusCode.Forbidden
        val started = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
        json.decodeFromString<TableView>(started.bodyAsText()).state shouldBe TableState.EM_JOGO
    }

    @Test
    fun `18_2 o anfitriao inicia com lugares vagos e eles viram robos`() = withApp {
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)
        val r = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
        r.status shouldBe HttpStatusCode.OK
        json.decodeFromString<TableView>(r.bodyAsText()).seats.count { it.kind == SeatKind.ROBO } shouldBe 3
    }

    @Test
    fun `18_2 a lista mostra as mesas do servidor e os jogadores online`() = withApp {
        val ana = client.guest("Ana")
        client.serverTable(ana.token)
        val list = json.decodeFromString<TableListView>(client.call(HttpMethod.Get, "/api/lobby/tables", ana.token).bodyAsText())
        list.onlinePlayers shouldBe 1
        list.tables.first().seats.count { it.name == "Ana" } shouldBe 1
    }

    @Test
    fun `18_2 o jogador nao cria mesa e o anfitriao entrega a mesa`() = withApp {
        val ana = client.guest("Ana")
        val beto = client.guest("Beto")
        client.call(HttpMethod.Post, "/api/lobby/tables", ana.token, json.encodeToString(CreateTableRequest(rules))).error() shouldBe
            ErrorCode.SEM_PERMISSAO
        val t = client.serverTable(ana.token)
        val b = json.decodeFromString<TableView>(client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/join", beto.token, "{}").bodyAsText())
            .seats.first { it.name == "Beto" }.index
        val r = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/host", ana.token, """{"seat":$b}""")
        json.decodeFromString<TableView>(r.bodyAsText()).hostId shouldBe beto.id
    }

    @Test
    fun `18_8 versao ausente ou diferente e recusada com mensagem em pt-BR`() = withApp {
        val old = client.call(HttpMethod.Post, "/api/lobby/guests", body = """{"name":"A"}""", version = ONLINE_PROTOCOL_VERSION - 1)
        old.status shouldBe HttpStatusCode.UpgradeRequired
        json.decodeFromString<ApiError>(old.bodyAsText()).message shouldBe "Atualize o aplicativo"
        val newer = client.call(HttpMethod.Post, "/api/lobby/guests", body = """{"name":"A"}""", version = ONLINE_PROTOCOL_VERSION + 1)
        json.decodeFromString<ApiError>(newer.bodyAsText()).message shouldBe "Servidor em manutenção"
        client.call(HttpMethod.Post, "/api/lobby/guests", body = """{"name":"A"}""", version = null).error() shouldBe ErrorCode.VERSAO_INCOMPATIVEL
    }

    @Test
    fun `18_2 sem token ou token falso nao acessa`() = withApp {
        client.call(HttpMethod.Post, "/api/lobby/tables", null, "{}").error() shouldBe ErrorCode.NAO_AUTENTICADO
        client.call(HttpMethod.Get, "/api/lobby/tables/ABCDEF", "falso").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_2 mesa inexistente devolve 404 tipado`() = withApp {
        val ana = client.guest("Ana")
        val r = client.call(HttpMethod.Get, "/api/lobby/tables/ZZZZZZ", ana.token)
        r.status shouldBe HttpStatusCode.NotFound
        r.error() shouldBe ErrorCode.MESA_INEXISTENTE
    }

    @Test
    fun `18_2 a mesa do servidor nao e encerrada antes do jogo`() = withApp {
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)
        client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token).error() shouldBe ErrorCode.SEM_PERMISSAO
    }

    @Test
    fun `seguranca corpo grande demais e recusado e corpo invalido da 400`() = withApp {
        val ana = client.guest("Ana")
        val big = client.call(HttpMethod.Post, "/api/lobby/tables/ABCDEF/join", ana.token, "x".repeat(40_000))
        big.status shouldBe HttpStatusCode.PayloadTooLarge
        client.call(HttpMethod.Post, "/api/lobby/tables/ABCDEF/host", ana.token, "{nao e json").status shouldBe HttpStatusCode.BadRequest
        client.call(HttpMethod.Post, "/api/lobby/guests", body = """{"name":""}""").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `seguranca limite de taxa por IP responde 429`() = withApp(LobbyDeps(ipLimitPerMinute = 5, guestCreationPerMinute = 100)) {
        val statuses = (1..8).map { client.call(HttpMethod.Get, "/api/lobby/tables/ABCDEF", "x").status }
        statuses.last() shouldBe HttpStatusCode.TooManyRequests
        statuses.first() shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `seguranca criar convidados em rajada e limitado`() = withApp(LobbyDeps(guestCreationPerMinute = 3)) {
        val codes = (1..5).map { client.call(HttpMethod.Post, "/api/lobby/guests", body = """{"name":"A"}""").status.value }
        codes.take(3) shouldBe listOf(201, 201, 201)
        codes.last() shouldBe 429
    }

    @Test
    fun `18_2 sem convite nesta fase a pagina do convite nao existe`() = withApp {
        client.call(HttpMethod.Get, "/m/ABC234", version = null).status shouldBe HttpStatusCode.NotFound
    }
}
