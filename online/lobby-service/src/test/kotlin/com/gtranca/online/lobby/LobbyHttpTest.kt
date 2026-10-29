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

    private suspend fun HttpClient.table(token: String): TableView =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/tables", token, json.encodeToString(CreateTableRequest(rules))).bodyAsText())

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
        val t = client.table(ana.token)
        client.call(HttpMethod.Get, "/api/lobby/tables/${t.id}", beto.token).status shouldBe HttpStatusCode.OK
        val joined = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/join", beto.token, "{}")
        joined.status shouldBe HttpStatusCode.OK
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", beto.token).status shouldBe HttpStatusCode.Forbidden
        val started = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
        json.decodeFromString<TableView>(started.bodyAsText()).state shouldBe TableState.EM_JOGO
    }

    @Test
    fun `18_2 iniciar com lugar vago e recusado`() = withApp {
        val ana = client.guest("Ana")
        val t = client.table(ana.token)
        val r = client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
        r.status shouldBe HttpStatusCode.Conflict
        r.error() shouldBe ErrorCode.LUGARES_VAGOS
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/seats/1/robot", ana.token).status shouldBe HttpStatusCode.OK
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token).status shouldBe HttpStatusCode.OK
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
    fun `18_2 anfitriao encerra a mesa com DELETE e so ele`() = withApp {
        val ana = client.guest("Ana")
        val beto = client.guest("Beto")
        val t = client.table(ana.token)
        client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", beto.token).status shouldBe HttpStatusCode.Forbidden
        val r = client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token)
        json.decodeFromString<TableView>(r.bodyAsText()).state shouldBe TableState.ENCERRADA
    }

    @Test
    fun `seguranca corpo grande demais e recusado e corpo invalido da 400`() = withApp {
        val ana = client.guest("Ana")
        val big = client.call(HttpMethod.Post, "/api/lobby/tables", ana.token, "x".repeat(40_000))
        big.status shouldBe HttpStatusCode.PayloadTooLarge
        client.call(HttpMethod.Post, "/api/lobby/tables", ana.token, "{nao e json").status shouldBe HttpStatusCode.BadRequest
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
    fun `18_2 pagina do convite mostra id, botao com link do app e aviso`() = withApp {
        val r = client.request("/m/abc234") { header(HttpHeaders.Host, "192.168.1.5") }
        r.status shouldBe HttpStatusCode.OK
        val html = r.bodyAsText()
        html shouldContain "ABC234"
        html shouldContain "Abrir no Tranca-Bot"
        html shouldContain "tranca://mesa/ABC234?s=192.168.1.5"
        html shouldContain "configure o servidor e digite o id"
        html shouldContain "instalado"
        html shouldContain "viewport"
    }

    @Test
    fun `18_2 pagina do convite usa o cabecalho Host codificado no link`() = withApp {
        val r2 = client.request("/m/ABC234") { header(HttpHeaders.Host, "192.168.1.5:80") }
        r2.bodyAsText() shouldContain "tranca://mesa/ABC234?s=192.168.1.5%3A80"
    }

    @Test
    fun `18_2 pagina do convite com id invalido nao reflete a entrada`() = withApp {
        val r = client.call(HttpMethod.Get, "/m/%3Cscript%3Ealert(1)%3C%2Fscript%3E", version = null)
        r.status shouldBe HttpStatusCode.NotFound
        val html = r.bodyAsText()
        html shouldNotContain "alert(1)"
        html shouldContain "não é válido"
        InvitePage.escape("<a href=\"x\">&'") shouldBe "&lt;a href=&quot;x&quot;&gt;&amp;&#39;"
    }

    @Test
    fun `18_2 pagina do convite ignora Host com formato estranho`() = withApp {
        val r = client.request("/m/ABC234") { header(HttpHeaders.Host, "evil.com\"><script>") }
        val html = r.bodyAsText()
        html shouldNotContain "evil"
    }
}
