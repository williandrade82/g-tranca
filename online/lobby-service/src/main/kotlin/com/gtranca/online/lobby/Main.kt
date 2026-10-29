package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.MatchEndedNotice
import com.gtranca.online.contracts.ResolveGuestRequest
import com.gtranca.online.contracts.ResolvedGuest
import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.JoinRequest
import com.gtranca.online.contracts.LobbyLimits
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.ServerInfo
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.VersionCheck
import com.gtranca.online.contracts.checkProtocolVersion
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SERVICE_NAME = "lobby-service"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Tudo configuravel por variavel de ambiente; nada de endereco fixo. */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val secret = System.getenv("INTERNAL_SECRET")?.takeIf { it.isNotBlank() }
        ?: run {
            System.err.println("ERRO: INTERNAL_SECRET nao definido (Secret do Kubernetes). Sem ele o lobby nao inicia partidas.")
            exitProcess(1)
        }
    val deps = LobbyDeps(
        trustForwardedFor = System.getenv("TRUST_FORWARDED_FOR") == "true",
        config = LobbyConfig(
            openTtlMs = (System.getenv("TABLE_OPEN_TTL_MINUTES")?.toLongOrNull() ?: 30L) * 60_000L,
        ),
        ipLimitPerMinute = System.getenv("RATE_LIMIT_PER_MINUTE")?.toIntOrNull() ?: 120,
        gateway = HttpGameGateway(System.getenv("GAME_SERVICE_URL")?.takeIf { it.isNotBlank() } ?: "http://game-service", secret),
        internalSecret = secret,
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") { lobbyModule(deps) }.start(wait = true)
}

class LobbyDeps(
    val clock: () -> Long = System::currentTimeMillis,
    val store: TableStore = InMemoryTableStore(),
    val config: LobbyConfig = LobbyConfig(),
    val trustForwardedFor: Boolean = false,
    val ipLimitPerMinute: Int = 120,
    val tokenLimitPerMinute: Int = 120,
    val guestCreationPerMinute: Int = 10,
    /** Passagem ao game-service ao iniciar (§18.3). `null` = so marca EM_JOGO (testes antigos; main exige o gateway). */
    val gateway: GameGateway? = null,
    /** Segredo servico a servico. Vazio/nulo = rotas `/internal/...` FECHADAS. */
    val internalSecret: String? = null,
) {
    val guests = GuestRegistry(clock)
    val service = LobbyService(store, clock, config = config)
    val ipLimiter = RateLimiter(ipLimitPerMinute, clock = clock)
    val tokenLimiter = RateLimiter(tokenLimitPerMinute, clock = clock)
    val guestLimiter = RateLimiter(guestCreationPerMinute, clock = clock)
}

private fun ApplicationCall.clientIp(trustForwarded: Boolean): String {
    if (trustForwarded) {
        // O proxy (Traefik) acrescenta o IP real ao FIM da lista; entradas anteriores podem ser forjadas.
        request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.local.remoteAddress
}

private suspend fun ApplicationCall.respondError(e: LobbyException) {
    respondText(json.encodeToString(e.toApiError()), ContentType.Application.Json, HttpStatusCode.fromValue(e.status))
}

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(json.encodeToString(value), ContentType.Application.Json, status)
}

/** Le o corpo com teto de tamanho (mesmo sem Content-Length) e decodifica. */
private suspend inline fun <reified T> ApplicationCall.receiveLimited(): T {
    val max = LobbyLimits.MAX_BODY_BYTES
    if ((request.contentLength() ?: 0L) > max) {
        lobbyError(ErrorCode.CORPO_GRANDE_DEMAIS, "Mensagem grande demais.", 413)
    }
    val bytes = receiveChannel().readRemaining(max + 1L).readByteArray()
    if (bytes.size > max) lobbyError(ErrorCode.CORPO_GRANDE_DEMAIS, "Mensagem grande demais.", 413)
    return try {
        json.decodeFromString<T>(if (bytes.isEmpty()) "{}" else bytes.decodeToString())
    } catch (e: Exception) {
        lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Mensagem inválida.", 400)
    }
}

/** §18.8: toda chamada de /api/lobby declara a versao do protocolo. */
private fun ApplicationCall.checkVersion() {
    val remote = request.headers[PROTOCOL_HEADER]?.toIntOrNull()
        ?: lobbyError(ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo", 426)
    when (val r = checkProtocolVersion(remote)) {
        VersionCheck.Compatible -> Unit
        is VersionCheck.Incompatible ->
            lobbyError(ErrorCode.VERSAO_INCOMPATIVEL, r.message, if (remote < ONLINE_PROTOCOL_VERSION) 426 else 503)
    }
}

private fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

private fun ApplicationCall.seatParam(): Int =
    parameters["n"]?.toIntOrNull() ?: lobbyError(ErrorCode.LUGAR_INVALIDO, "Lugar inválido.", 400)

fun Application.lobbyModule(deps: LobbyDeps = LobbyDeps()) {
    val limiter = createApplicationPlugin("LobbyRateLimit") {
        onCall { call ->
            val path = call.request.uri
            if (path.startsWith("/api/lobby") || path.startsWith("/m/")) {
                val ip = call.clientIp(deps.trustForwardedFor)
                var ok = deps.ipLimiter.allow("ip:$ip")
                if (ok && path.startsWith("/api/lobby/guests")) ok = deps.guestLimiter.allow("guest:$ip")
                call.bearer()?.let { if (ok) ok = deps.tokenLimiter.allow("tok:" + GuestRegistry.hash(it)) }
                if (!ok) {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.respondError(LobbyException(ErrorCode.LIMITE_DE_TAXA, "Muitas requisições. Aguarde um instante.", 429))
                }
            }
        }
    }
    install(limiter)

    suspend fun ApplicationCall.guarded(authenticated: Boolean = true, block: suspend (Guest) -> Unit) {
        try {
            checkVersion()
            val guest = if (authenticated) {
                (bearer()?.let(deps.guests::authenticate))
                    ?: lobbyError(ErrorCode.NAO_AUTENTICADO, "Entre novamente.", 401)
            } else {
                Guest("", "")
            }
            block(guest)
        } catch (e: LobbyException) {
            respondError(e)
        }
    }

    val service = deps.service

    routing {
        get("/health") { call.respondJson(Health()) }
        get("/info") { call.respondJson(ServerInfo(SERVICE_NAME)) }

        // Pagina do convite (§18.2). Sem token: o id da mesa e o segredo; nada da mesa e revelado aqui.
        get("/m/{id}") {
            val (status, html) = InvitePage.render(call.parameters["id"].orEmpty(), call.request.headers[HttpHeaders.Host])
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
            )
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.fromValue(status))
        }

        post("/api/lobby/guests") {
            call.guarded(authenticated = false) {
                val req = call.receiveLimited<CreateGuestRequest>()
                call.respondJson(deps.guests.create(req.name), HttpStatusCode.Created)
            }
        }
        post("/api/lobby/tables") {
            call.guarded { guest ->
                val req = call.receiveLimited<CreateTableRequest>()
                call.respondJson(service.createTable(guest, req.rules), HttpStatusCode.Created)
            }
        }
        get("/api/lobby/tables/{id}") {
            call.guarded { guest -> call.respondJson(service.get(guest, call.parameters["id"].orEmpty())) }
        }
        delete("/api/lobby/tables/{id}") {
            call.guarded { guest ->
                val id = call.parameters["id"].orEmpty()
                // §18.4: encerrar uma mesa EM_JOGO manda o game-service encerrar a partida ANTES de marcar ENCERRADA; se ele
                // nao responde, a mesa segue EM_JOGO (o lobby nunca diz "encerrada" com o jogo rodando).
                val gateway = deps.gateway
                if (gateway != null && service.closeNeedsGame(guest, id)) {
                    try {
                        gateway.closeMatch(TableId.normalize(id) ?: id, guest.id)
                    } catch (e: GameGatewayException) {
                        lobbyError(ErrorCode.PARTIDA_INDISPONIVEL, "Não foi possível encerrar a partida agora. Tente de novo.", 503)
                    }
                }
                call.respondJson(service.close(guest, id))
            }
        }
        post("/api/lobby/tables/{id}/join") {
            call.guarded { guest ->
                val req = call.receiveLimited<JoinRequest>()
                call.respondJson(service.join(guest, call.parameters["id"].orEmpty(), req.seat))
            }
        }
        post("/api/lobby/tables/{id}/leave") {
            call.guarded { guest -> call.respondJson(service.leave(guest, call.parameters["id"].orEmpty())) }
        }
        post("/api/lobby/tables/{id}/seats/{n}/robot") {
            call.guarded { guest ->
                call.respondJson(service.seatRobot(guest, call.parameters["id"].orEmpty(), call.seatParam()))
            }
        }
        post("/api/lobby/tables/{id}/seats/{n}/remove") {
            call.guarded { guest ->
                call.respondJson(service.removeHuman(guest, call.parameters["id"].orEmpty(), call.seatParam()))
            }
        }
        post("/api/lobby/tables/{id}/start") {
            call.guarded { guest ->
                val id = call.parameters["id"].orEmpty()
                val gateway = deps.gateway
                if (gateway == null) {
                    call.respondJson(service.start(guest, id))
                } else {
                    // §18.3: pede a partida ao game-service (rede interna) ANTES de marcar EM_JOGO. Falhou = mesa ABERTA.
                    val plan = service.beginStart(guest, id)
                    var committed = false
                    try {
                        try {
                            gateway.createMatch(plan)
                        } catch (e: GameGatewayException) {
                            if (e.status != null && e.status in 400..499) {
                                lobbyError(ErrorCode.REQUISICAO_INVALIDA, "O servidor de jogo não aceitou as regras desta mesa.", 400)
                            }
                            lobbyError(ErrorCode.PARTIDA_INDISPONIVEL, "Não foi possível iniciar a partida agora. A mesa continua aberta; tente de novo.", 503)
                        }
                        val view = service.commitStart(id)
                        committed = true
                        call.respondJson(view)
                    } finally {
                        if (!committed) service.abortStart(id)
                    }
                }
            }
        }

        // Servico a servico (game-service): o jogo terminou/foi encerrado; a mesa EM_JOGO vira ENCERRADA (§18.4).
        post("/internal/tables/{id}/ended") {
            val expected = deps.internalSecret
            when {
                expected == null -> call.respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Rotas internas desativadas.", 503))
                !MessageDigest.isEqual(expected.toByteArray(), (call.request.headers[INTERNAL_SECRET_HEADER] ?: "").toByteArray()) ->
                    call.respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Não autorizado.", 401))
                else -> try {
                    call.receiveLimited<MatchEndedNotice>()
                    service.matchEnded(call.parameters["id"].orEmpty())
                    call.respondJson(Health())
                } catch (e: LobbyException) {
                    call.respondError(e)
                }
            }
        }

        // Servico a servico (game-service): de quem e este token? Protegido pelo segredo; o ingress nao expoe /internal.
        post("/internal/guests/resolve") {
            val expected = deps.internalSecret
            when {
                expected == null -> call.respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Rotas internas desativadas.", 503))
                !MessageDigest.isEqual(expected.toByteArray(), (call.request.headers[INTERNAL_SECRET_HEADER] ?: "").toByteArray()) ->
                    call.respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Não autorizado.", 401))
                else -> {
                    val guest = try {
                        call.receiveLimited<ResolveGuestRequest>().token.let(deps.guests::authenticate)
                    } catch (e: LobbyException) {
                        return@post call.respondError(e)
                    }
                    if (guest == null) {
                        call.respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Token inválido.", 404))
                    } else {
                        call.respondJson(ResolvedGuest(guest.id, guest.name))
                    }
                }
            }
        }
    }
}
