package com.gtranca.online.game

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CloseMatchRequest
import com.gtranca.online.contracts.CloseMatchResponse
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ResolvedGuest
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.Welcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** §18.4: encerrar a mesa em jogo pela rota interna, e o aviso do fim do jogo ao lobby. */
class MatchCloseHttpTest {
    private val resolver = FakeResolver(mapOf("tok-ga" to ResolvedGuest("ga", "Ana"), "tok-gb" to ResolvedGuest("gb", "Beto")))

    private class RecordingNotifier(var failTimes: Int = 0) : LobbyNotifier {
        val calls = CopyOnWriteArrayList<Pair<String, GameEndReason>>()
        val attempts = AtomicInteger()
        override suspend fun matchEnded(tableId: String, reason: GameEndReason) {
            attempts.incrementAndGet()
            if (attempts.get() <= failTimes) throw LobbyNotifyException("lobby fora do ar")
            calls += tableId to reason
        }
    }

    private fun request(mode: TableMode = TableMode.DUPLAS, seats: List<com.gtranca.online.contracts.SeatSpec> = duplasSeats()) =
        CreateMatchRequest("ABC234", rulesOf(mode), seats, seed = 7)

    private suspend fun HttpClient.create(req: CreateMatchRequest) = post("/internal/matches") {
        header(INTERNAL_SECRET_HEADER, TEST_SECRET)
        contentType(ContentType.Application.Json)
        setBody(OnlineJson.encodeToString(CreateMatchRequest.serializer(), req))
    }

    private suspend fun HttpClient.close(id: String, host: String, secret: String? = TEST_SECRET): HttpResponse =
        post("/internal/matches/$id/close") {
            if (secret != null) header(INTERNAL_SECRET_HEADER, secret)
            contentType(ContentType.Application.Json)
            setBody(OnlineJson.encodeToString(CloseMatchRequest.serializer(), CloseMatchRequest(host)))
        }

    private fun deps(notifier: LobbyNotifier) = GameDeps(
        FAST, MatchRegistry(FAST, notifier = notifier, notifyRetryDelaysMs = listOf(1, 1, 1)), resolver,
    )

    @Test
    fun `18_4 a rota de encerrar exige o segredo e conhece so partidas e jogadores da mesa`() = testApplication {
        application { gameModule(deps(RecordingNotifier())) }
        val client = createClient { }
        client.create(request()).status shouldBe HttpStatusCode.Created
        client.close("ABC234", "ga", secret = null).status shouldBe HttpStatusCode.Unauthorized
        client.close("ABC234", "ga", secret = "errado").status shouldBe HttpStatusCode.Unauthorized
        client.close("ZZZ999", "ga").status shouldBe HttpStatusCode.NotFound
        val intruder = client.close("ABC234", "intruso")
        intruder.status shouldBe HttpStatusCode.BadRequest
        OnlineJson.decodeFromString(ApiError.serializer(), intruder.bodyAsText()).code shouldBe ErrorCode.REQUISICAO_INVALIDA
        val ok = client.close("abc234", "ga") // o id nao diferencia maiusculas
        ok.status shouldBe HttpStatusCode.OK
        OnlineJson.decodeFromString(CloseMatchResponse.serializer(), ok.bodyAsText()).alreadyOver shouldBe false
    }

    @Test
    fun `18_4 encerrar manda a mensagem de mesa encerrada ao jogador conectado e fecha a conexao`() = testApplication {
        val notifier = RecordingNotifier()
        application { gameModule(deps(notifier)) }
        val api = createClient { }
        val ws = createClient { install(WebSockets) }
        api.create(request()).status shouldBe HttpStatusCode.Created
        val seen = CopyOnWriteArrayList<ServerMessage>()
        val connected = CompletableDeferred<Unit>()
        ws.webSocket("/ws/match/ABC234", request = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer tok-ga")
        }) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val m = OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())
                seen += m
                if (m is Welcome && !connected.isCompleted) {
                    connected.complete(Unit)
                    api.close("ABC234", "ga").status shouldBe HttpStatusCode.OK
                }
            }
        }
        seen.last().shouldBeInstanceOf<TableClosed>() // depois dele a conexao foi fechada pelo servidor

        // O lobby e avisado (fora do ator, com tentativas) de que o jogo foi encerrado.
        withTimeout(5_000) { while (notifier.calls.isEmpty()) delay(10) }
        notifier.calls.toList() shouldContainExactly listOf("ABC234" to GameEndReason.ANFITRIAO_ENCERROU)
    }

    @Test
    fun `18_4 o aviso ao lobby tenta de novo quando ele esta fora do ar`() = runBlocking {
        val notifier = RecordingNotifier(failTimes = 2)
        val registry = MatchRegistry(FAST, notifier = notifier, notifyRetryDelaysMs = listOf(1, 1, 1))
        registry.create(request(TableMode.INDIVIDUAL, humans2()))
        val session = registry.find("ABC234")!!
        val a = TestPlayer(session, "ga")
        a.attach()
        a.resign() // 1x1 entre humanos: o outro vence e o jogo acaba
        withTimeout(5_000) { while (notifier.calls.isEmpty()) delay(10) }
        notifier.attempts.get() shouldBe 3 // duas falhas e a tentativa que passou
        notifier.calls.single() shouldBe ("ABC234" to GameEndReason.ABANDONO)
        registry.close()
    }

    @Test
    fun `18_4 encerrar uma partida desconhecida no registro devolve nulo`() = runBlocking {
        val registry = MatchRegistry(FAST)
        registry.closeMatch("ABC234", "ga") shouldBe null
        registry.close()
    }
}
