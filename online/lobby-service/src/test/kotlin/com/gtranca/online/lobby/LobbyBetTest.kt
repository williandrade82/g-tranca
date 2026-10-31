package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

/** §18.2 aposta: para ocupar um assento o humano precisa de saldo maior ou igual a aposta da mesa (50). */
class LobbyBetTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.call(method: HttpMethod, path: String, token: String? = null, body: String? = null): HttpResponse =
        request(path) {
            this.method = method
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) setBody(body)
        }

    private suspend fun HttpClient.guest(name: String): GuestIdentity =
        json.decodeFromString(call(HttpMethod.Post, "/api/lobby/guests", body = json.encodeToString(CreateGuestRequest(name))).bodyAsText())

    private suspend fun HttpClient.openTable(token: String): String =
        json.decodeFromString<TableListView>(call(HttpMethod.Get, "/api/lobby/tables", token).bodyAsText())
            .tables.first { it.state == TableState.ABERTA && it.seats.any { s -> s.kind == SeatKind.VAGO } }.id

    private fun app(balances: Map<String, Long>, down: () -> Boolean = { false }, block: suspend HttpClient.(MutableMap<String, String>) -> Unit) {
        val names = mutableMapOf<String, String>() // nome -> id do convidado
        val eco = EconomyGateway { id ->
            if (down()) throw EconomyGatewayException("fora")
            balances[names.entries.first { it.value == id }.key] ?: 1_000
        }
        testApplication {
            application { lobbyModule(LobbyDeps(maintainEveryMs = 0, economy = eco)) }
            client.block(names)
        }
    }

    @Test
    fun `18_2 saldo menor que a aposta nao senta e recebe o motivo em pt-BR`() = app(mapOf("Pobre" to 49)) { names ->
        val g = guest("Pobre").also { names[it.name] = it.id }
        val r = call(HttpMethod.Post, "/api/lobby/tables/${openTable(g.token)}/join", g.token, "{}")
        r.status shouldBe HttpStatusCode.Conflict
        val err = json.decodeFromString<ApiError>(r.bodyAsText())
        err.code shouldBe ErrorCode.SALDO_INSUFICIENTE
        err.message shouldContain "50 moedas"
    }

    @Test
    fun `18_2 saldo igual a aposta senta`() = app(mapOf("Justo" to 50)) { names ->
        val g = guest("Justo").also { names[it.name] = it.id }
        call(HttpMethod.Post, "/api/lobby/tables/${openTable(g.token)}/join", g.token, "{}").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `18_2 economia fora do ar recusa sentar (falha fechada)`() = app(emptyMap(), down = { true }) { names ->
        val g = guest("Ana").also { names[it.name] = it.id }
        val r = call(HttpMethod.Post, "/api/lobby/tables/${openTable(g.token)}/join", g.token, "{}")
        r.status shouldBe HttpStatusCode.ServiceUnavailable
        json.decodeFromString<ApiError>(r.bodyAsText()).message shouldContain "saldo"
    }
}
