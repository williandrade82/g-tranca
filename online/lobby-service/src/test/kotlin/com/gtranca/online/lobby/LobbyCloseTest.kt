package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.MatchEndedNotice
import com.gtranca.online.contracts.PresenceNotice
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

/** §18.4: encerrar a mesa em jogo (o lobby manda o game-service) e o aviso de que o jogo acabou. */
class LobbyCloseTest {
    private val json = OnlineJson
    private val secret = "segredo-de-teste"

    private class Gateway(var failClose: GameGatewayException? = null) : GameGateway {
        val created = mutableListOf<CreateMatchRequest>()
        val closed = mutableListOf<Pair<String, String>>()
        override suspend fun createMatch(request: CreateMatchRequest) {
            created += request
        }

        override suspend fun closeMatch(tableId: String, hostGuestId: String) {
            failClose?.let { throw it }
            closed += tableId to hostGuestId
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

    private suspend fun HttpClient.view(token: String, id: String): TableView =
        json.decodeFromString(call(HttpMethod.Get, "/api/lobby/tables/$id", token).bodyAsText())

    /** §18.2: o jogador entra na mesa aberta do servidor (nao cria mesa). */
    private suspend fun HttpClient.serverTable(token: String): TableView {
        val list = json.decodeFromString<TableListView>(call(HttpMethod.Get, "/api/lobby/tables", token).bodyAsText())
        val id = list.tables.first { it.state == TableState.ABERTA && it.seats.any { s -> s.kind == SeatKind.VAGO } }.id
        return json.decodeFromString(call(HttpMethod.Post, "/api/lobby/tables/$id/join", token, "{}").bodyAsText())
    }

    private suspend fun HttpResponse.error(): ErrorCode = json.decodeFromString<ApiError>(bodyAsText()).code

    /** Mesa do servidor com Ana (anfitria) e Beto, ja EM_JOGO. */
    private suspend fun HttpClient.inGame(): Triple<GuestIdentity, GuestIdentity, TableView> {
        val ana = guest("Ana")
        val beto = guest("Beto")
        val t = serverTable(ana.token)
        call(HttpMethod.Post, "/api/lobby/tables/${t.id}/join", beto.token, "{}")
        call(HttpMethod.Post, "/api/lobby/tables/${t.id}/start", ana.token).status shouldBe HttpStatusCode.OK
        view(ana.token, t.id).state shouldBe TableState.EM_JOGO
        return Triple(ana, beto, t)
    }

    private fun withApp(gateway: Gateway, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { lobbyModule(LobbyDeps(gateway = gateway, internalSecret = secret)) }
        block()
    }

    @Test
    fun `18_4 o anfitriao encerra a mesa em jogo, o lobby manda o game-service e so entao marca ENCERRADA`() {
        val gw = Gateway()
        withApp(gw) {
            val (ana, beto, t) = client.inGame()
            // So o anfitriao encerra.
            client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", beto.token).error() shouldBe ErrorCode.SEM_PERMISSAO
            gw.closed.size shouldBe 0
            val r = client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token)
            r.status shouldBe HttpStatusCode.OK
            json.decodeFromString<TableView>(r.bodyAsText()).state shouldBe TableState.ENCERRADA
            gw.closed.single() shouldBe (t.id to ana.id) // o lobby diz ao game-service QUEM e o anfitriao
        }
    }

    @Test
    fun `18_4 se o game-service nao responde a mesa segue EM_JOGO e da para tentar de novo`() {
        val gw = Gateway(failClose = GameGatewayException(null, "fora do ar"))
        withApp(gw) {
            val (ana, _, t) = client.inGame()
            val r = client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token)
            r.status shouldBe HttpStatusCode.ServiceUnavailable
            r.error() shouldBe ErrorCode.PARTIDA_INDISPONIVEL
            client.view(ana.token, t.id).state shouldBe TableState.EM_JOGO // nunca "encerrada" com o jogo rodando
            gw.failClose = null
            client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token).status shouldBe HttpStatusCode.OK
            client.view(ana.token, t.id).state shouldBe TableState.ENCERRADA
        }
    }

    @Test
    fun `18_2 mesa ainda aberta nao e encerrada e o game-service nao e chamado`() {
        val gw = Gateway(failClose = GameGatewayException(null, "nao deveria ser chamado"))
        withApp(gw) {
            val ana = client.guest("Ana")
            val t = client.serverTable(ana.token)
            client.call(HttpMethod.Delete, "/api/lobby/tables/${t.id}", ana.token).error() shouldBe ErrorCode.SEM_PERMISSAO
            client.view(ana.token, t.id).state shouldBe TableState.ABERTA
            gw.closed.size shouldBe 0
        }
    }

    @Test
    fun `18_2 o game-service informa a presenca e o anfitriao que caiu passa o cargo`() {
        withApp(Gateway()) {
            val (ana, beto, t) = client.inGame()
            val body = json.encodeToString(PresenceNotice(connected = listOf(beto.id)))
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/presence", body = body).status shouldBe HttpStatusCode.Unauthorized
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/presence", body = body, secretHeader = secret).status shouldBe HttpStatusCode.OK
            client.view(ana.token, t.id).hostId shouldBe beto.id
        }
    }

    @Test
    fun `18_4 o game-service avisa o fim do jogo e a mesa EM_JOGO vira ENCERRADA com segredo e idempotente`() {
        val gw = Gateway()
        withApp(gw) {
            val (ana, _, t) = client.inGame()
            val body = json.encodeToString(MatchEndedNotice(GameEndReason.PONTUACAO))
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/ended", body = body).status shouldBe HttpStatusCode.Unauthorized
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/ended", body = body, secretHeader = "errado").status shouldBe
                HttpStatusCode.Unauthorized
            client.view(ana.token, t.id).state shouldBe TableState.EM_JOGO

            client.call(HttpMethod.Post, "/internal/tables/${t.id}/ended", body = body, secretHeader = secret).status shouldBe HttpStatusCode.OK
            client.view(ana.token, t.id).state shouldBe TableState.ENCERRADA
            // Repetir o aviso nao muda nada; mesa desconhecida e 404 (o game-service trata como entregue).
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/ended", body = body, secretHeader = secret).status shouldBe HttpStatusCode.OK
            client.call(HttpMethod.Post, "/internal/tables/ZZZ999/ended", body = body, secretHeader = secret).status shouldBe
                HttpStatusCode.NotFound
        }
    }

    @Test
    fun `18_4 aviso de fim de jogo nao fecha uma mesa que ainda esta aberta`() {
        withApp(Gateway()) {
            val ana = client.guest("Ana")
            val t = client.serverTable(ana.token)
            val body = json.encodeToString(MatchEndedNotice(GameEndReason.ABANDONO))
            client.call(HttpMethod.Post, "/internal/tables/${t.id}/ended", body = body, secretHeader = secret).status shouldBe HttpStatusCode.OK
            client.view(ana.token, t.id).state shouldBe TableState.ABERTA
        }
    }
}
