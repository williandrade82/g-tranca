package com.gtranca.online.lobby

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminRevokeSessionsResponse
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.EmailLoginRequest
import com.gtranca.online.contracts.EmailRegisterRequest
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
import io.ktor.client.request.post
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

/**
 * §18.7 (decisao de 03/11/2026, definition.md): bloquear conta impede logar de novo e entrar em mesa nova (nunca
 * ejeta de partida em andamento, nunca invalida tokens ja emitidos). Encerrar sessoes e acao INDEPENDENTE.
 */
class LobbyAccountBlockTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_800_000_000_000L
    private val internalSecret = "segredo-interno"
    private val adminSecret = "segredo-admin"

    private fun deps() = LobbyDeps(
        clock = { now },
        internalSecret = internalSecret,
        adminSecret = adminSecret,
        emailPasswordCost = 4,
        walletLinker = { _, _ -> false },
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

    private suspend fun HttpClient.register(email: String, name: String): AccountLogin = json.decodeFromString(
        call(
            HttpMethod.Post, "/api/lobby/accounts/email/register",
            body = json.encodeToString(EmailRegisterRequest.serializer(), EmailRegisterRequest(email, "segredo123", name)),
        ).bodyAsText(),
    )

    private suspend fun HttpClient.loginRaw(email: String, password: String): HttpResponse = call(
        HttpMethod.Post, "/api/lobby/accounts/email/login",
        body = json.encodeToString(EmailLoginRequest.serializer(), EmailLoginRequest(email, password)),
    )

    private suspend fun HttpClient.admin(path: String, body: String? = null): HttpResponse = request(path) {
        method = if (body != null) HttpMethod.Post else HttpMethod.Get
        header(ADMIN_SECRET_HEADER, adminSecret)
        header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        if (body != null) setBody(body)
    }

    private suspend fun HttpResponse.error(): ErrorCode = json.decodeFromString<ApiError>(bodyAsText()).code

    @Test
    fun `18_7 bloquear impede logar de novo, desbloquear reverte`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        val block = client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}")
        block.status shouldBe HttpStatusCode.OK

        val recusado = client.loginRaw("ana@exemplo.com", "segredo123")
        recusado.status shouldBe HttpStatusCode.Forbidden
        recusado.error() shouldBe ErrorCode.CONTA_BLOQUEADA

        client.admin("/internal/admin/accounts/${ana.id}/unblock", body = "{}").status shouldBe HttpStatusCode.OK
        client.loginRaw("ana@exemplo.com", "segredo123").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `18_7 senha errada nunca revela se a conta esta bloqueada`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")
        client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}")

        val senhaErrada = client.loginRaw("ana@exemplo.com", "senha-errada-123")
        senhaErrada.status shouldBe HttpStatusCode.Unauthorized
        senhaErrada.error() shouldBe ErrorCode.NAO_AUTENTICADO
    }

    @Test
    fun `18_7 bloquear nao afeta a sessao ja aberta, so impede entrar em mesa nova`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}")

        // O token que ja existia continua autenticando (consulta normal, sem efeito retroativo).
        client.call(HttpMethod.Get, "/api/lobby/tables", ana.token).status shouldBe HttpStatusCode.OK

        // Mas entrar numa mesa nova e recusado.
        val list = json.decodeFromString<TableListView>(client.call(HttpMethod.Get, "/api/lobby/tables", ana.token).bodyAsText())
        val tableId = list.tables.first { it.state == TableState.ABERTA }.id
        val join = client.call(HttpMethod.Post, "/api/lobby/tables/$tableId/join", ana.token, "{}")
        join.status shouldBe HttpStatusCode.Forbidden
        join.error() shouldBe ErrorCode.CONTA_BLOQUEADA
    }

    @Test
    fun `18_7 encerrar sessoes invalida o token sem bloquear logins futuros`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        val revoke = client.admin("/internal/admin/accounts/${ana.id}/revoke-sessions", body = "{}")
        revoke.status shouldBe HttpStatusCode.OK
        json.decodeFromString<AdminRevokeSessionsResponse>(revoke.bodyAsText()).revoked shouldBe 1

        // O token antigo nao vale mais.
        client.call(HttpMethod.Get, "/api/lobby/tables", ana.token).status shouldBe HttpStatusCode.Unauthorized

        // Repetir (sem sessao viva nenhuma agora) devolve 0, nunca erro.
        val again = client.admin("/internal/admin/accounts/${ana.id}/revoke-sessions", body = "{}")
        json.decodeFromString<AdminRevokeSessionsResponse>(again.bodyAsText()).revoked shouldBe 0

        // Mas a conta NAO esta bloqueada: loga de novo normalmente.
        client.loginRaw("ana@exemplo.com", "segredo123").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `18_7 a ficha da conta mostra o bloqueio`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        json.decodeFromString<AdminAccountDetail>(client.admin("/internal/admin/accounts/${ana.id}").bodyAsText()).blocked shouldBe false
        client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}")
        json.decodeFromString<AdminAccountDetail>(client.admin("/internal/admin/accounts/${ana.id}").bodyAsText()).blocked shouldBe true
    }

    @Test
    fun `18_7 as rotas de escrita exigem o segredo do admin, nunca o servico a servico`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        val semSegredo = client.post("/internal/admin/accounts/${ana.id}/block") { setBody("{}") }
        semSegredo.status shouldBe HttpStatusCode.Unauthorized

        val comSegredoServico = client.post("/internal/admin/accounts/${ana.id}/block") {
            header(INTERNAL_SECRET_HEADER, internalSecret)
            setBody("{}")
        }
        comSegredoServico.status shouldBe HttpStatusCode.Unauthorized

        // O vazamento de verdade: o VALOR do segredo do admin no cabecalho das rotas internas antigas.
        val resolve = client.post("/internal/guests/resolve") { header(INTERNAL_SECRET_HEADER, adminSecret); setBody("{}") }
        resolve.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_7 bloquear e desbloquear sao idempotentes e id invalido da 400`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.register("ana@exemplo.com", "Ana")

        client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}").status shouldBe HttpStatusCode.OK
        client.admin("/internal/admin/accounts/${ana.id}/block", body = "{}").status shouldBe HttpStatusCode.OK
        client.admin("/internal/admin/accounts/${ana.id}/unblock", body = "{}").status shouldBe HttpStatusCode.OK
        client.admin("/internal/admin/accounts/${ana.id}/unblock", body = "{}").status shouldBe HttpStatusCode.OK

        client.admin("/internal/admin/accounts/${"x".repeat(200)}/block", body = "{}").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `18_7 bloqueio sobrevive a reiniciar o servico quando ha banco`() {
        val persistence = InMemoryAccountBlockPersistenceForTest()
        var now2 = now
        val d1 = LobbyDeps(clock = { now2 }, internalSecret = internalSecret, adminSecret = adminSecret, accountBlockPersistence = persistence)
        d1.accountBlocks.block("E_qualquer", "teste")
        // "Reinicia": um LobbyDeps novo, mesma persistencia.
        val d2 = LobbyDeps(clock = { now2 }, internalSecret = internalSecret, adminSecret = adminSecret, accountBlockPersistence = persistence)
        d2.accountBlocks.isBlocked("E_qualquer") shouldBe true
        d2.accountBlocks.unblock("E_qualquer")
        val d3 = LobbyDeps(clock = { now2 }, internalSecret = internalSecret, adminSecret = adminSecret, accountBlockPersistence = persistence)
        d3.accountBlocks.isBlocked("E_qualquer") shouldBe false
    }
}

/** Persistencia de bloqueio em memoria, so para o teste de "sobrevive a reiniciar" (sem banco de verdade). */
private class InMemoryAccountBlockPersistenceForTest : AccountBlockPersistence {
    private val rows = LinkedHashMap<String, AccountBlockPersistence.StoredBlock>()

    override fun loadAll(): List<AccountBlockPersistence.StoredBlock> = rows.values.toList()

    override fun block(block: AccountBlockPersistence.StoredBlock) {
        rows[block.accountId] = block
    }

    override fun unblock(accountId: String) {
        rows.remove(accountId)
    }
}
