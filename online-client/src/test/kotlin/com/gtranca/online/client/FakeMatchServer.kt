package com.gtranca.online.client

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ServerMessage
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Servidor falso de `/ws/match/{id}` (Ktor de teste): confere o handshake antes do upgrade e roda um roteiro por conexão. */
class FakeMatchServer {
    /** Roteiro de cada conexão aceita, por ordem; a última repete. */
    var scripts: List<suspend DefaultWebSocketServerSession.() -> Unit> = listOf({})

    /** Se não for nulo, o handshake é recusado com esta resposta (status e [ApiError]). */
    @Volatile
    var refuse: Pair<Int, ApiError?>? = null

    val connections = AtomicInteger()
    val handshakeVersions = CopyOnWriteArrayList<String?>()
    val handshakeAuth = CopyOnWriteArrayList<String?>()
    val handshakeUris = CopyOnWriteArrayList<String>()
    val received = CopyOnWriteArrayList<ClientMessage>()

    suspend fun DefaultWebSocketServerSession.send(m: ServerMessage) =
        send(Frame.Text(OnlineJson.encodeToString(ServerMessage.serializer(), m)))

    /** Lê as mensagens do cliente até a conexão fechar, guardando-as em [received]. */
    suspend fun DefaultWebSocketServerSession.collectClientMessages() {
        for (f in incoming) if (f is Frame.Text) received += OnlineJson.decodeFromString(ClientMessage.serializer(), f.readText())
    }

    /** Roda [block] com um [OnlineClient] ligado a este servidor (tempo real, fora do relógio virtual do teste). */
    fun run(block: suspend CoroutineScope.(OnlineClient) -> Unit) = testApplication {
        application {
            install(ServerWebSockets)
            routing {
                route("/ws/match/{id}") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        handshakeVersions += call.request.header(PROTOCOL_HEADER)
                        handshakeAuth += call.request.header(HttpHeaders.Authorization)
                        handshakeUris += call.request.uri
                        val r = refuse
                        if (r != null) {
                            val body = r.second?.let { OnlineJson.encodeToString(ApiError.serializer(), it) } ?: ""
                            call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(r.first))
                            finish()
                        } else if (call.request.header(PROTOCOL_HEADER) != "2") {
                            val body = OnlineJson.encodeToString(ApiError.serializer(), ApiError(ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo"))
                            call.respondText(body, ContentType.Application.Json, HttpStatusCode.UpgradeRequired)
                            finish()
                        }
                    }
                    webSocket {
                        val i = connections.getAndIncrement()
                        scripts[minOf(i, scripts.lastIndex)].invoke(this)
                    }
                }
            }
        }
        val http = createClient { install(WebSockets) }
        val client = OnlineClient(OnlineEndpoint.parse("localhost", cleartext = true)!!, http)
        withContext(Dispatchers.Default) { block(client) }
    }
}
