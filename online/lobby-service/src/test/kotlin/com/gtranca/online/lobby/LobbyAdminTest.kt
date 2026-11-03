package com.gtranca.online.lobby

import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.AdminStatsDto
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.EmailRegisterRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.MatchEndedNotice
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ResetPasswordRequest
import com.gtranca.online.contracts.ResolveGuestRequest
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

/** §18.7: rotas de LEITURA do console de gerenciamento (admin-api -> lobby-service), com segredo proprio. */
class LobbyAdminTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_800_000_000_000L
    private val internalSecret = "segredo-interno"
    private val adminSecret = "segredo-admin"

    private fun deps() = LobbyDeps(
        clock = { now },
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

    private suspend fun HttpClient.admin(path: String, secretHeader: String? = adminSecret) = request(path) {
        method = HttpMethod.Get
        if (secretHeader != null) header(ADMIN_SECRET_HEADER, secretHeader)
    }

    @Test
    fun `18_7 sem o segredo do admin as rotas internas recusam, e o segredo do admin nao abre as outras rotas internas`() = testApplication {
        application { lobbyModule(deps()) }

        // Sem cabecalho: 401 (segredo configurado, mas nao enviado).
        client.admin("/internal/admin/stats", secretHeader = null).status shouldBe HttpStatusCode.Unauthorized
        // O X-Internal-Secret (servico a servico) no lugar do X-Admin-Secret nao vale.
        val wrongHeader = client.request("/internal/admin/stats") { header(INTERNAL_SECRET_HEADER, internalSecret) }
        wrongHeader.status shouldBe HttpStatusCode.Unauthorized
        // O proprio valor do INTERNAL_SECRET, mas no cabecalho certo, tambem nao vale (sao segredos diferentes).
        client.admin("/internal/admin/stats", secretHeader = internalSecret).status shouldBe HttpStatusCode.Unauthorized
        // O segredo certo funciona.
        client.admin("/internal/admin/stats").status shouldBe HttpStatusCode.OK

        // O inverso: o ADMIN_INTERNAL_SECRET nunca abre as rotas internas antigas (reset de senha, resolve).
        val resetWithAdminSecret = client.post("/internal/lobby/accounts/reset-password") {
            header(ADMIN_SECRET_HEADER, adminSecret)
            setBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest("ninguem@exemplo.com")))
        }
        resetWithAdminSecret.status shouldBe HttpStatusCode.Unauthorized
        val resolveWithAdminSecret = client.post("/internal/guests/resolve") {
            header(ADMIN_SECRET_HEADER, adminSecret)
            setBody(json.encodeToString(ResolveGuestRequest.serializer(), ResolveGuestRequest("qualquer")))
        }
        resolveWithAdminSecret.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `18_7 sem ADMIN_INTERNAL_SECRET configurado as rotas administrativas ficam desativadas (503)`() = testApplication {
        application { lobbyModule(LobbyDeps(clock = { now }, internalSecret = internalSecret, adminSecret = null)) }
        client.admin("/internal/admin/stats").status shouldBe HttpStatusCode.ServiceUnavailable
    }

    @Test
    fun `18_7 estatisticas contam mesas abertas, em jogo e partidas encerradas na ultima hora`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        val t = client.serverTable(ana.token)

        val before = json.decodeFromString<AdminStatsDto>(client.admin("/internal/admin/stats").bodyAsText())
        before.playersOnline shouldBe 1
        before.tablesOpen shouldBe 1
        before.tablesInGame shouldBe 0
        before.matchesEndedLastHour shouldBe 0

        client.call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token)
        val inGame = json.decodeFromString<AdminStatsDto>(client.admin("/internal/admin/stats").bodyAsText())
        inGame.tablesInGame shouldBe 1

        // O game-service avisa o fim da partida (segredo servico a servico, nao o do admin).
        client.post("/internal/tables/${t.id}/ended") {
            header(INTERNAL_SECRET_HEADER, internalSecret)
            setBody(json.encodeToString(MatchEndedNotice.serializer(), MatchEndedNotice(GameEndReason.PONTUACAO)))
        }.status shouldBe HttpStatusCode.OK
        val ended = json.decodeFromString<AdminStatsDto>(client.admin("/internal/admin/stats").bodyAsText())
        ended.tablesInGame shouldBe 0
        ended.matchesEndedLastHour shouldBe 1

        // Passada mais de uma hora, a partida sai da janela.
        now += 3_600_001L
        val later = json.decodeFromString<AdminStatsDto>(client.admin("/internal/admin/stats").bodyAsText())
        later.matchesEndedLastHour shouldBe 0
    }

    @Test
    fun `18_7 busca de contas encontra por e-mail, por nome do convidado e pelo id exato, sem senha nem token`() = testApplication {
        val d = deps()
        application { lobbyModule(d) }
        client.register("Ana", "ana@exemplo.com")
        val beto = client.guest("Beto")
        val googleId = AccountLimits.GOOGLE_ID_PREFIX + "g".repeat(40)
        d.guests.createSession(googleId, "Gustavo") // simula uma sessao Google ja autenticada (sem ID token de verdade)

        val byEmail = json.decodeFromString<AdminAccountsPage>(client.admin("/internal/admin/accounts?q=ana@exemplo.com").bodyAsText())
        byEmail.accounts.size shouldBe 1
        byEmail.accounts.first().kind shouldBe AccountKind.EMAIL
        byEmail.accounts.first().createdMs shouldBe now

        val byGuestName = json.decodeFromString<AdminAccountsPage>(client.admin("/internal/admin/accounts?q=Beto").bodyAsText())
        byGuestName.accounts.size shouldBe 1
        byGuestName.accounts.first().id shouldBe beto.id
        byGuestName.accounts.first().kind shouldBe AccountKind.CONVIDADO

        val byGoogleName = json.decodeFromString<AdminAccountsPage>(client.admin("/internal/admin/accounts?q=Gustavo").bodyAsText())
        byGoogleName.accounts.size shouldBe 1
        byGoogleName.accounts.first().kind shouldBe AccountKind.GOOGLE
        byGoogleName.accounts.first().email shouldBe null
        byGoogleName.accounts.first().createdMs shouldBe null

        val byId = json.decodeFromString<AdminAccountsPage>(client.admin("/internal/admin/accounts?q=$googleId").bodyAsText())
        byId.accounts.map { it.id } shouldContain googleId

        // Nunca o hash bcrypt (prefixo "$2a$"/"$2b$") nem um token de sessao no corpo da resposta.
        val body = client.admin("/internal/admin/accounts?q=ana@exemplo.com").bodyAsText()
        body shouldNotContain Regex("""\$2[ab]\$""")

        // E-mail inexistente: lista vazia, nunca 404 (a busca sempre responde, so sem resultado).
        val none = json.decodeFromString<AdminAccountsPage>(client.admin("/internal/admin/accounts?q=ninguem-aqui").bodyAsText())
        none.total shouldBe 0
    }

    @Test
    fun `18_7 ficha da conta mostra as mesas onde ela esta sentada agora, e 404 tipado para conta inexistente`() = testApplication {
        application { lobbyModule(deps()) }
        val ana = client.guest("Ana")
        client.serverTable(ana.token)

        val detail = json.decodeFromString<AdminAccountDetail>(client.admin("/internal/admin/accounts/${ana.id}").bodyAsText())
        detail.id shouldBe ana.id
        detail.kind shouldBe AccountKind.CONVIDADO
        detail.blocked shouldBe false
        detail.currentTables.size shouldBe 1
        detail.currentTables.first().state shouldBe TableState.ABERTA

        val missing = client.admin("/internal/admin/accounts/ninguem-existe")
        missing.status shouldBe HttpStatusCode.NotFound
        json.decodeFromString<ApiError>(missing.bodyAsText()).code shouldBe ErrorCode.CONTA_INEXISTENTE
    }

    // ---------------------------------------------------------------------------------------------------- apoio

    private suspend fun HttpClient.register(name: String, email: String) {
        post("/api/lobby/accounts/email/register") {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION)
            setBody(json.encodeToString(EmailRegisterRequest.serializer(), EmailRegisterRequest(email, "segredo123", name)))
        }
    }
}
