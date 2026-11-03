package com.gtranca.online.lobby

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminCloseTableRequest
import com.gtranca.online.contracts.AdminTablesPage
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** §18.7, tela Mesas: lista/detalhe (qualquer estado) e "Encerrar mesa travada" (idempotente; EM_JOGO fica de fora). */
class LobbyAdminTablesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val internalSecret = "segredo-interno"
    private val adminSecret = "segredo-admin"

    private fun deps() = LobbyDeps(
        internalSecret = internalSecret,
        adminSecret = adminSecret,
        guestCreationPerMinute = 1_000,
        ipLimitPerMinute = 1_000,
    )

    private suspend fun HttpClient.call(method: HttpMethod, path: String, token: String? = null, body: String? = null): HttpResponse =
        request(path) {
            this.method = method
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) setBody(body)
        }

    private suspend fun HttpClient.guest(name: String): GuestIdentity =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/guests", body = json.encodeToString(CreateGuestRequest(name))).bodyAsText())

    private suspend fun HttpClient.serverTable(token: String): TableView {
        val list = json.decodeFromString<TableListView>(call(HttpMethod.Get, "/api/lobby/tables", token).bodyAsText())
        val id = list.tables.first { it.state == TableState.ABERTA && it.seats.any { s -> s.kind == SeatKind.VAGO } }.id
        return json.decodeFromString(call(HttpMethod.Post, "/api/lobby/tables/$id/join", token, "{}").bodyAsText())
    }

    private suspend fun HttpClient.admin(path: String, body: String? = null): HttpResponse = request(path) {
        method = if (body != null) HttpMethod.Post else HttpMethod.Get
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        if (body != null) setBody(body)
    }

    private suspend fun HttpResponse.error(): ErrorCode = json.decodeFromString<ApiError>(bodyAsText()).code

    @Test
    fun `18_7 a lista de mesas do console mostra ABERTA, EM_JOGO e ENCERRADA, ao contrario da lista publica`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)

        val page = json.decodeFromString<AdminTablesPage>(client.admin("/internal/admin/tables").bodyAsText())
        page.tables.any { it.id == t.id && it.state == TableState.EM_JOGO } shouldBe true
    }

    @Test
    fun `18_7 a ficha da mesa tem os assentos, e mesa inexistente da 404 tipado`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)

        val detail = json.decodeFromString<TableView>(client.admin("/internal/admin/tables/${t.id}").bodyAsText())
        detail.id shouldBe t.id
        detail.seats.count { it.kind == SeatKind.HUMANO } shouldBe 1

        val missing = client.admin("/internal/admin/tables/ZZZZZZ")
        missing.status shouldBe HttpStatusCode.NotFound
        missing.error() shouldBe ErrorCode.MESA_INEXISTENTE
    }

    @Test
    fun `18_7 encerrar mesa aberta funciona e e idempotente`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)

        val closed = client.admin("/internal/admin/tables/${t.id}/close", body = "{}")
        closed.status shouldBe HttpStatusCode.OK
        json.decodeFromString<TableView>(closed.bodyAsText()).state shouldBe TableState.ENCERRADA

        // Encerrar de novo nao e erro.
        val again = client.admin("/internal/admin/tables/${t.id}/close", body = "{}")
        again.status shouldBe HttpStatusCode.OK
        json.decodeFromString<TableView>(again.bodyAsText()).state shouldBe TableState.ENCERRADA
    }

    @Test
    fun `18_7 encerrar mesa em jogo ainda nao e suportado, a mesa continua em jogo`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)
        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)

        val close = client.admin("/internal/admin/tables/${t.id}/close", body = json.encodeToString(AdminCloseTableRequest.serializer(), AdminCloseTableRequest("teste")))
        close.status shouldBe HttpStatusCode.NotImplemented
        close.error() shouldBe ErrorCode.SERVICO_INDISPONIVEL

        val still = json.decodeFromString<TableView>(client.admin("/internal/admin/tables/${t.id}").bodyAsText())
        still.state shouldBe TableState.EM_JOGO
    }

    @Test
    fun `18_7 as rotas de mesas exigem o segredo e a versao do console`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)

        client.request("/internal/admin/tables") { method = HttpMethod.Get }.status shouldBe HttpStatusCode.Unauthorized
        client.request("/internal/admin/tables") {
            method = HttpMethod.Get
            header(INTERNAL_SECRET_HEADER, internalSecret)
        }.status shouldBe HttpStatusCode.Unauthorized
        client.request("/internal/admin/tables/${t.id}/close") {
            method = HttpMethod.Post
            header(ADMIN_SECRET_HEADER, adminSecret)
            setBody("{}")
        }.status shouldBe HttpStatusCode.UpgradeRequired // sem X-Admin-Api-Version
    }
}
