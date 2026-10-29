package com.gtranca.online.game

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.GameLimits
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.VersionCheck
import com.gtranca.online.contracts.checkProtocolVersion
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private class Handshake(val session: MatchSession, val seat: Int, val guestId: String)

private val HandshakeKey = AttributeKey<Handshake>("gtranca.handshake")

private val log = LoggerFactory.getLogger("com.gtranca.online.game.PlayerChannel")

/** Janela fixa por chave, em memoria (so para o handshake). */
internal class FixedWindowLimiter(
    private val limit: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class W(var start: Long, var count: Int)

    private val windows = HashMap<String, W>()

    @Synchronized
    fun allow(key: String): Boolean {
        val t = clock()
        if (windows.size > 10_000) windows.values.removeIf { t - it.start >= windowMs }
        val w = windows.getOrPut(key) { W(t, 0) }
        if (t - w.start >= windowMs) {
            w.start = t
            w.count = 0
        }
        return ++w.count <= limit
    }
}

/** Balde de fichas por conexao: [perSecond] repoe e [burst] e o maximo acumulado. */
internal class TokenBucket(
    private val perSecond: Int,
    private val burst: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    private var tokens = burst.toDouble()
    private var last = clock()

    fun tryTake(): Boolean {
        val t = clock()
        tokens = minOf(burst.toDouble(), tokens + (t - last) / 1_000_000_000.0 * perSecond)
        last = t
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

private fun ApplicationCall.clientIp(trustForwarded: Boolean): String {
    if (trustForwarded) {
        request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.local.remoteAddress
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: ErrorCode, message: String) {
    respond(status, ApiError(code, message))
}

private suspend fun DefaultWebSocketSession.sendMessage(message: ServerMessage) {
    send(Frame.Text(OnlineJson.encodeToString(ServerMessage.serializer(), message)))
}

/**
 * `GET /ws/match/{id}` (WebSocket). Handshake em HTTP, ANTES do upgrade: versao do protocolo (§18.8), token de
 * convidado (validado no lobby) e assento da mesa. O jogador so envia [ClientMessage]; recebe [ServerMessage].
 */
fun Application.playerChannelModule(deps: GameDeps) {
    val cfg = deps.config
    install(WebSockets) {
        pingPeriodMillis = cfg.wsPingMs
        timeoutMillis = cfg.wsTimeoutMs
        maxFrameSize = GameLimits.MAX_CLIENT_MESSAGE_BYTES.toLong()
        masking = false
    }
    val handshakeLimiter = FixedWindowLimiter(cfg.handshakePerMinute)

    routing {
        route("/ws/match/{id}") {
            install(createRouteScopedPlugin("Handshake") { onCall { call ->
                val local = ONLINE_PROTOCOL_VERSION
                if (!handshakeLimiter.allow(call.clientIp(cfg.trustForwardedFor))) {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.refuse(HttpStatusCode.TooManyRequests, ErrorCode.LIMITE_DE_TAXA, "Muitas tentativas. Aguarde um instante.")
                    return@onCall
                }
                // §18.8: a versao e checada primeiro.
                val remote = call.request.headers[PROTOCOL_HEADER]?.toIntOrNull()
                if (remote == null) {
                    call.refuse(HttpStatusCode.UpgradeRequired, ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo")
                    return@onCall
                }
                (checkProtocolVersion(remote, local) as? VersionCheck.Incompatible)?.let {
                    call.refuse(
                        if (remote < local) HttpStatusCode.UpgradeRequired else HttpStatusCode.ServiceUnavailable,
                        ErrorCode.VERSAO_INCOMPATIVEL, it.message,
                    )
                    return@onCall
                }
                val token = call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
                if (token == null) {
                    call.refuse(HttpStatusCode.Unauthorized, ErrorCode.NAO_AUTENTICADO, "Entre novamente.")
                    return@onCall
                }
                val guest = try {
                    deps.resolver.resolve(token)
                } catch (e: LobbyUnavailableException) {
                    log.warn("Introspeccao indisponivel: {}", e.message)
                    call.refuse(HttpStatusCode.ServiceUnavailable, ErrorCode.PARTIDA_INDISPONIVEL, "Servidor indisponível. Tente de novo.")
                    return@onCall
                }
                if (guest == null) {
                    call.refuse(HttpStatusCode.Unauthorized, ErrorCode.NAO_AUTENTICADO, "Entre novamente.")
                    return@onCall
                }
                val session = deps.registry.find(call.parameters["id"].orEmpty())
                if (session == null) {
                    call.refuse(HttpStatusCode.NotFound, ErrorCode.MESA_INEXISTENTE, "Partida não encontrada.")
                    return@onCall
                }
                val seat = session.seatOf(guest.guestId)
                if (seat == null) {
                    call.refuse(HttpStatusCode.Forbidden, ErrorCode.SEM_PERMISSAO, "Você não está nesta mesa.")
                    return@onCall
                }
                call.attributes.put(HandshakeKey, Handshake(session, seat, guest.guestId))
            } })

            // Pedido comum (sem upgrade) na mesma rota: o handshake acima ja o validou; so avisa que e um canal WebSocket.
            get {
                call.respond(HttpStatusCode.UpgradeRequired, ApiError(ErrorCode.REQUISICAO_INVALIDA, "Use uma conexão WebSocket."))
            }

            webSocket {
                val hs = call.attributes[HandshakeKey]
                val link = PlayerLink()
                when (hs.session.attach(hs.guestId, link)) {
                    is AttachResult.Ok -> Unit
                    AttachResult.Abandoned -> {
                        sendMessage(GameError(ErrorCode.ASSENTO_ABANDONADO, "Você desistiu: um robô assumiu o seu lugar."))
                        return@webSocket close(CloseReason(1008, "Assento abandonado"))
                    }
                    AttachResult.Closed -> {
                        sendMessage(GameError(ErrorCode.MESA_INEXISTENTE, "Partida encerrada."))
                        return@webSocket close(CloseReason(1001, "Partida encerrada"))
                    }
                }

                // Escritor: o ator da partida so entrega em [link]; quem escreve no socket e esta coroutine.
                val writer = launch {
                    try {
                        for (message in link.messages) sendMessage(message)
                        link.closeInfo?.let { close(CloseReason(it.code.toShort(), it.reason)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // cliente foi embora no meio do envio: nada a fazer, o laco de leitura encerra a conexao
                    }
                }
                val bucket = TokenBucket(cfg.wsMessagesPerSecond, cfg.wsBurst)
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) {
                            link.offer(GameError(ErrorCode.MENSAGEM_INVALIDA, "Só mensagens de texto são aceitas."))
                            link.close(1003, "Tipo de mensagem invalido")
                            break
                        }
                        if (!bucket.tryTake()) {
                            link.offer(GameError(ErrorCode.LIMITE_DE_TAXA, "Mensagens demais. Aguarde um instante."))
                            link.close(1008, "Limite de taxa")
                            break
                        }
                        val text = frame.readText()
                        val message = if (text.length > GameLimits.MAX_CLIENT_MESSAGE_BYTES) {
                            null
                        } else {
                            try {
                                OnlineJson.decodeFromString(ClientMessage.serializer(), text)
                            } catch (e: Exception) {
                                // Carta/assento invalidos (IllegalArgumentException) tambem caem aqui; nunca derrubam a partida.
                                null
                            }
                        }
                        if (message == null) {
                            link.offer(GameError(ErrorCode.MENSAGEM_INVALIDA, "Mensagem inválida."))
                            link.close(1008, "Mensagem invalida")
                            break
                        }
                        hs.session.submit(hs.seat, link, message)
                    }
                } finally {
                    // Queda da conexao NAO e abandono (§18.4, 1.3b): so libera a conexao do assento.
                    hs.session.detach(hs.seat, link)
                    link.close()
                    // Deixa o escritor entregar o que ficou na fila (ex.: o erro tipado) antes de soltar o socket.
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2_000) { writer.join() }
                        writer.cancelAndJoin()
                    }
                }
            }
        }
    }
}
