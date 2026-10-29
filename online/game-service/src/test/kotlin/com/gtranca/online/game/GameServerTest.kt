package com.gtranca.online.game

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.ResolvedGuest
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.engine.Action
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class GameServerTest {
    private val resolver = FakeResolver(
        mapOf(
            "tok-ga" to ResolvedGuest("ga", "Ana"),
            "tok-gb" to ResolvedGuest("gb", "Beto"),
            "tok-gx" to ResolvedGuest("gx", "Intruso"),
        ),
    )

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private suspend fun HttpClient.createMatch(
        req: CreateMatchRequest,
        secret: String? = TEST_SECRET,
    ): HttpResponse = post("/internal/matches") {
        if (secret != null) header(INTERNAL_SECRET_HEADER, secret)
        contentType(ContentType.Application.Json)
        setBody(OnlineJson.encodeToString(CreateMatchRequest.serializer(), req))
    }

    private fun duplasRequest(target: Int = 300, seed: Long? = 7, json: String = "{}") = CreateMatchRequest(
        tableId = "ABC234",
        rules = rulesOf(TableMode.DUPLAS, target).copy(rulesJson = json),
        seats = duplasSeats(),
        seed = seed,
    )

    /** Pedido de handshake sem o upgrade (o cliente de teste nao pode enviar Upgrade): o mesmo plugin valida. */
    private suspend fun HttpClient.upgrade(
        token: String?,
        version: String? = ONLINE_PROTOCOL_VERSION.toString(),
        id: String = "ABC234",
    ) = get("/ws/match/$id") {
        if (version != null) header(PROTOCOL_HEADER, version)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpResponse.apiError(): ApiError = OnlineJson.decodeFromString(ApiError.serializer(), bodyAsText())

    private suspend fun DefaultClientWebSocketSession.nextMessage(): ServerMessage? {
        for (frame in incoming) if (frame is Frame.Text) return OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())
        return null
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(m: ClientMessage) =
        send(Frame.Text(OnlineJson.encodeToString(ClientMessage.serializer(), m)))

    private suspend fun DefaultClientWebSocketSession.playOver(brain: ClientBrain) {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val act = brain.absorb(OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText()))
            if (act != null) {
                brain.actionsSent++
                sendMessage(PlayAction(++brain.seq, brain.view!!.version, act))
            }
            if (brain.view?.stage == TableStage.GAME_OVER) break
        }
    }

    // ------------------------------------------------------------------------------ segredo servico a servico

    @Test
    fun `18_3 rotas internas exigem o segredo compartilhado`() = testApplication {
        application { gameModule(testDeps(resolver = resolver)) }
        val client = createClient { }
        client.createMatch(duplasRequest(), secret = null).status shouldBe HttpStatusCode.Unauthorized
        client.createMatch(duplasRequest(), secret = "errado").status shouldBe HttpStatusCode.Unauthorized
        client.post("/internal/sim") { contentType(ContentType.Application.Json); setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized
        client.createMatch(duplasRequest()).status shouldBe HttpStatusCode.Created
        // Idempotente por mesa: repetir devolve a mesma partida.
        client.createMatch(duplasRequest()).status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `18_3 sem segredo configurado as rotas internas ficam fechadas`() = testApplication {
        val cfg = GameConfig(internalSecret = null)
        application { gameModule(testDeps(cfg, resolver)) }
        val client = createClient { }
        client.createMatch(duplasRequest(), secret = "qualquer").status shouldBe HttpStatusCode.ServiceUnavailable
        client.upgrade("tok-ga").status shouldBe HttpStatusCode.NotFound // sem partida nenhuma
    }

    @Test
    fun `18_3 criacao recusa regras invalidas, assentos errados e excesso de partidas`() = testApplication {
        val cfg = GameConfig(internalSecret = TEST_SECRET, maxMatches = 1)
        application { gameModule(testDeps(cfg, resolver)) }
        val client = createClient { }
        client.createMatch(duplasRequest(json = "{nao e json")).status shouldBe HttpStatusCode.BadRequest
        client.createMatch(duplasRequest().copy(seats = humansOnly2())).status shouldBe HttpStatusCode.BadRequest
        client.createMatch(duplasRequest().copy(tableId = "0O1I!!")).status shouldBe HttpStatusCode.BadRequest
        client.createMatch(
            duplasRequest().copy(seats = List(4) { SeatSpec.Robot(BotProfile.JOVEM, BotLevel.FACIL) }),
        ).status shouldBe HttpStatusCode.BadRequest
        client.createMatch(duplasRequest()).status shouldBe HttpStatusCode.Created
        client.createMatch(duplasRequest().copy(tableId = "ZZZ234")).status shouldBe HttpStatusCode.ServiceUnavailable
    }

    private fun humansOnly2() = humans2()

    // ------------------------------------------------------------------------------ handshake

    @Test
    fun `18_8 versao incompativel e recusada com a mensagem do protocolo, antes do upgrade`() = testApplication {
        application { gameModule(testDeps(resolver = resolver)) }
        val client = createClient { }
        client.createMatch(duplasRequest())

        client.upgrade("tok-ga", version = "0").let {
            it.status shouldBe HttpStatusCode.UpgradeRequired
            it.apiError() shouldBe ApiError(ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo")
        }
        client.upgrade("tok-ga", version = (ONLINE_PROTOCOL_VERSION + 1).toString()).let {
            it.status shouldBe HttpStatusCode.ServiceUnavailable
            it.apiError() shouldBe ApiError(ErrorCode.VERSAO_INCOMPATIVEL, "Servidor em manutenção")
        }
        client.upgrade("tok-ga", version = null).status shouldBe HttpStatusCode.UpgradeRequired
    }

    @Test
    fun `18_3 token invalido, mesa inexistente e jogador de fora sao recusados`() = testApplication {
        val r = FakeResolver(
            mapOf("tok-ga" to ResolvedGuest("ga", "Ana"), "tok-gx" to ResolvedGuest("gx", "Intruso")),
        )
        application { gameModule(testDeps(resolver = r)) }
        val client = createClient { }
        client.createMatch(duplasRequest())

        client.upgrade(null).status shouldBe HttpStatusCode.Unauthorized
        client.upgrade("tok-invalido").status shouldBe HttpStatusCode.Unauthorized
        client.upgrade("tok-ga", id = "ZZZ234").status shouldBe HttpStatusCode.NotFound
        client.upgrade("tok-gx").let {
            it.status shouldBe HttpStatusCode.Forbidden
            it.apiError().code shouldBe ErrorCode.SEM_PERMISSAO
        }
        r.down = true
        client.upgrade("tok-ga").let {
            it.status shouldBe HttpStatusCode.ServiceUnavailable
            it.apiError().code shouldBe ErrorCode.PARTIDA_INDISPONIVEL
        }
    }

    // ------------------------------------------------------------------------------ jogo por WebSocket

    @Test
    fun `18_3 jogo completo por WebSocket, 2 humanos e 2 robos em duplas`() = testApplication {
        application { gameModule(testDeps(GameConfig(internalSecret = TEST_SECRET, botDelayMs = 0, roundPauseMs = 0, wsMessagesPerSecond = 1000, wsBurst = 1000), resolver)) }
        val http = createClient { }
        http.createMatch(duplasRequest(target = 600)).status shouldBe HttpStatusCode.Created
        val ws = wsClient()
        val a = ClientBrain("ga")
        val b = ClientBrain("gb")
        try {
            withTimeout(50_000) {
            listOf(
                async {
                    ws.webSocket("/ws/match/ABC234", request = {
                        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
                        header(HttpHeaders.Authorization, "Bearer tok-ga")
                    }) { playOver(a) }
                },
                async {
                    ws.webSocket("/ws/match/abc234", request = { // id sem diferenciar maiusculas (§18.2)
                        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
                        header(HttpHeaders.Authorization, "Bearer tok-gb")
                    }) { playOver(b) }
                },
            ).awaitAll()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("travou: a=${a.view?.version}/${a.view?.stage}/vez=${a.view?.currentSeat} rej=${a.rejections} err=${a.errors} b=${b.view?.version} rej=${b.rejections} acoes=${a.actionsSent}/${b.actionsSent}")
        }
        a.view!!.stage shouldBe TableStage.GAME_OVER
        b.view!!.winner shouldBe a.view!!.winner
        a.view!!.totals shouldBe b.view!!.totals
        a.rejections.shouldBeEmpty()
        b.rejections.shouldBeEmpty()
        a.welcome!!.you.index shouldBe 0
        b.welcome!!.you.index shouldBe 2
        a.actionsSent shouldBeGreaterThan 20
    }

    @Test
    fun `18_4 queda e volta pelo WebSocket devolvem a vista atual com o ultimo seq`() = testApplication {
        val cfg = GameConfig(internalSecret = TEST_SECRET, botDelayMs = 0, roundPauseMs = 0)
        application { gameModule(testDeps(cfg, resolver)) }
        val http = createClient { }
        http.createMatch(duplasRequest())
        val ws = wsClient()
        val req: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer tok-ga")
        }
        var firstView: com.gtranca.online.contracts.OnlineSeatView? = null
        var sent = 0L
        ws.webSocket("/ws/match/ABC234", request = req) {
            val w = nextMessage().shouldBeInstanceOf<Welcome>()
            w.lastSeq shouldBe 0
            var u = nextMessage().shouldBeInstanceOf<SeatUpdate>()
            // Espera a vez de A (os robos jogam antes, se for o caso) e joga uma compra.
            while (u.view.legal.isEmpty()) u = withTimeout(10_000) { nextMessageUpdate() }
            firstView = u.view
            sendMessage(PlayAction(++sent, u.view.version, Action.DrawFromStock))
            withTimeout(10_000) { while (nextMessageUpdate().view.version <= u.view.version) Unit }
            close(CloseReason(CloseReason.Codes.GOING_AWAY, "queda"))
        }
        delay(200)
        ws.webSocket("/ws/match/ABC234", request = req) {
            val w = nextMessage().shouldBeInstanceOf<Welcome>()
            w.lastSeq shouldBe sent
            val u = nextMessage().shouldBeInstanceOf<SeatUpdate>()
            u.view.version shouldBeGreaterThan firstView!!.version
            u.view.hand.size shouldBe firstView!!.hand.size + 1 // a compra continua valendo
            u.view.history shouldBe emptyList() // incluido na reentrada (aqui ainda sem partidas pontuadas)
            // Uma duplicada com o seq ja usado e descartada; seguir com seq novo funciona.
            sendMessage(PlayAction(sent, u.view.version, Action.DrawFromStock))
            close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitMessage(pred: (ServerMessage) -> Boolean): ServerMessage? {
        while (true) {
            val m = nextMessage() ?: return null
            if (pred(m)) return m
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextMessageUpdate(): SeatUpdate {
        while (true) {
            val m = nextMessage() ?: error("canal fechado")
            if (m is SeatUpdate) return m
        }
    }

    // ------------------------------------------------------------------------------ limites

    @Test
    fun `18_3 mensagem invalida, binaria ou grande demais fecha a conexao com erro tipado`() = testApplication {
        application { gameModule(testDeps(resolver = resolver)) }
        val http = createClient { }
        http.createMatch(duplasRequest(seed = 3))
        val ws = wsClient()
        val req: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer tok-ga")
        }
        // JSON invalido e carta invalida (IllegalArgumentException do motor) nao derrubam a partida.
        for (bad in listOf("{nao e json", """{"type":"play","seq":1,"stateVersion":1,"action":{"type":"discard","card":"ZZ"}}""")) {
            ws.webSocket("/ws/match/ABC234", request = req) {
                nextMessage().shouldBeInstanceOf<Welcome>()
                send(Frame.Text(bad))
                val err = withTimeout(10_000) { awaitMessage { it is GameError } } as GameError
                err.code shouldBe ErrorCode.MENSAGEM_INVALIDA
                (closeReason.await()?.code ?: 0).toInt() shouldBe 1008
            }
        }
        ws.webSocket("/ws/match/ABC234", request = req) {
            nextMessage().shouldBeInstanceOf<Welcome>()
            send(Frame.Text("x".repeat(20 * 1024)))
            withTimeout(10_000) { closeReason.await() }
        }
        // A partida segue de pe: o jogador ainda entra.
        ws.webSocket("/ws/match/ABC234", request = req) {
            nextMessage().shouldBeInstanceOf<Welcome>()
            close()
        }
    }

    @Test
    fun `18_3 limite de taxa por conexao`() = testApplication {
        val cfg = GameConfig(internalSecret = TEST_SECRET, botDelayMs = 0, roundPauseMs = 0, wsMessagesPerSecond = 1, wsBurst = 3)
        application { gameModule(testDeps(cfg, resolver)) }
        val http = createClient { }
        http.createMatch(duplasRequest(seed = 3))
        val ws = wsClient()
        ws.webSocket("/ws/match/ABC234", request = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer tok-ga")
        }) {
            nextMessage().shouldBeInstanceOf<Welcome>()
            repeat(10) { sendMessage(PlayAction(it + 1L, 1, Action.DrawFromStock)) }
            val err = withTimeout(10_000) { awaitMessage { it is GameError } } as GameError
            err.code shouldBe ErrorCode.LIMITE_DE_TAXA
            (withTimeout(10_000) { closeReason.await() }?.code ?: 0).toInt() shouldBe 1008
        }
    }

    @Test
    fun `18_4 segunda conexao do mesmo jogador assume e a primeira e fechada`() = testApplication {
        application { gameModule(testDeps(resolver = resolver)) }
        val http = createClient { }
        http.createMatch(duplasRequest(seed = 3))
        val ws = wsClient()
        val req: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer tok-ga")
        }
        ws.webSocket("/ws/match/ABC234", request = req) {
            nextMessage().shouldBeInstanceOf<Welcome>()
            ws.webSocket("/ws/match/ABC234", request = req) {
                nextMessage().shouldBeInstanceOf<Welcome>()
            }
            withTimeout(10_000) { awaitMessage { it is com.gtranca.online.contracts.Replaced } }.shouldBeInstanceOf<com.gtranca.online.contracts.Replaced>()
            withTimeout(10_000) { closeReason.await() }?.code?.toInt() shouldBe MatchSession.CLOSE_REPLACED
        }
    }
}
