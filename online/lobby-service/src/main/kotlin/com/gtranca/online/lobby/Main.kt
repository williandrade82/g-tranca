package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.EmailLoginRequest
import com.gtranca.online.contracts.EmailRegisterRequest
import com.gtranca.online.contracts.GoogleLoginRequest
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestPhoto
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.MatchEndedNotice
import com.gtranca.online.contracts.PassHostRequest
import com.gtranca.online.contracts.PresenceNotice
import com.gtranca.online.contracts.ResetPasswordRequest
import com.gtranca.online.contracts.ResetPasswordResponse
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
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    // Plano 2.4: com DB_URL, convidados, fotos e mesas ficam no Postgres (schema `lobby`); sem, tudo em memoria.
    val dbUrl = System.getenv("DB_URL")?.takeIf { it.isNotBlank() }
    val db = dbUrl?.let {
        LobbyDatabase.connect(it, System.getenv("DB_USER").orEmpty(), System.getenv("DB_PASSWORD").orEmpty())
            .also(LobbyDatabase::migrateWithRetry)
    } ?: null.also { System.err.println("AVISO: DB_URL ausente; convidados e mesas ficam so na memoria (perdidos ao reiniciar)") }
    val deps = LobbyDeps(
        store = db?.let { PersistentTableStore(PostgresTablePersistence(it)) } ?: InMemoryTableStore(),
        guestPersistence = db?.let(::PostgresGuestPersistence),
        trustForwardedFor = System.getenv("TRUST_FORWARDED_FOR") == "true",
        config = LobbyConfig(
            openTtlMs = (System.getenv("TABLE_OPEN_TTL_MINUTES")?.toLongOrNull() ?: 30L) * 60_000L,
        ),
        ipLimitPerMinute = System.getenv("RATE_LIMIT_PER_MINUTE")?.toIntOrNull() ?: 120,
        appKey = System.getenv("APP_SIGNING_KEY")?.takeIf { it.isNotBlank() }
            ?: null.also { System.err.println("AVISO: APP_SIGNING_KEY ausente; o lobby aceita pedidos sem assinatura do app") },
        guestCreationPerMinute = System.getenv("GUEST_CREATION_PER_MINUTE")?.toIntOrNull() ?: 10,
        gateway = HttpGameGateway(System.getenv("GAME_SERVICE_URL")?.takeIf { it.isNotBlank() } ?: "http://game-service", secret),
        internalSecret = secret,
        economy = System.getenv("ECONOMY_URL")?.takeIf { it.isNotBlank() }?.let { HttpEconomyGateway(it, secret) }
            ?: null.also { System.err.println("AVISO: ECONOMY_URL ausente; o lobby nao confere o saldo ao sentar") },
        // Plano 2.6: login Google. Sem GOOGLE_CLIENT_ID a rota responde "indisponivel".
        google = System.getenv("GOOGLE_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let {
            GoogleIdTokenVerifier(it, HttpJwksSource(System.getenv("GOOGLE_JWKS_URL")?.takeIf { u -> u.isNotBlank() } ?: "https://www.googleapis.com/oauth2/v3/certs"))
        } ?: null.also { System.err.println("AVISO: GOOGLE_CLIENT_ID ausente; login Google desligado") },
        walletLinker = System.getenv("ECONOMY_URL")?.takeIf { it.isNotBlank() }?.let { HttpAccountWalletLinker(it, secret) },
        // §18.1 (mudanca de 02/11/2026): contas por e-mail e senha. Custo do bcrypt fixo em produção (12).
        emailAccountPersistence = db?.let(::PostgresEmailAccountPersistence),
        loginAttemptLimit = System.getenv("LOGIN_ATTEMPT_LIMIT")?.toIntOrNull() ?: 5,
        loginLockoutMinutes = System.getenv("LOGIN_LOCKOUT_MINUTES")?.toLongOrNull() ?: 15L,
        // §18.7: segredo PROPRIO do console de gerenciamento (admin-api), nunca o INTERNAL_SECRET (servico a
        // servico entre lobby/game/economy, que tambem abre o reset de senha). Igual ao INTERNAL_SECRET = erro de
        // configuracao: as rotas /internal/admin ficam desativadas em vez de aceitar o segredo errado.
        adminSecret = System.getenv("ADMIN_INTERNAL_SECRET")?.takeIf { it.isNotBlank() }?.let { candidate ->
            if (candidate == secret) {
                System.err.println("ERRO: ADMIN_INTERNAL_SECRET nao pode ser igual a INTERNAL_SECRET; rotas /internal/admin desativadas.")
                null
            } else {
                candidate
            }
        } ?: null.also { System.err.println("AVISO: ADMIN_INTERNAL_SECRET ausente; rotas /internal/admin (console de gerenciamento) desativadas.") },
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
    /** A cada quanto o servidor roda os prazos das mesas (abrir a proxima, §18.2). 0 = so nas chamadas (testes). */
    val maintainEveryMs: Long = 5_000L,
    /** Chave do aplicativo (§18.2). `null` = sem verificacao (desenvolvimento e testes antigos). */
    val appKey: String? = null,
    /** Saldo para sentar (§18.2). `null` = nao confere (desenvolvimento e testes antigos). */
    val economy: EconomyGateway? = null,
    /** Convidados e fotos no banco (plano 2.4). `null` = memoria. */
    val guestPersistence: GuestPersistence? = null,
    /** Verificador do ID token do Google (plano 2.6). `null` = login Google desligado. */
    val google: GoogleIdTokenVerifier? = null,
    /** Carteira da conta no login (§18.5). `null` = sem economia (desenvolvimento): a conta entra sem migrar nada. */
    val walletLinker: AccountWalletLinker? = null,
    /** Contas por e-mail e senha no banco (§18.1). `null` = so memoria (desenvolvimento e testes). */
    val emailAccountPersistence: EmailAccountPersistence? = null,
    /** Custo do bcrypt (§18.1). Baixo nos testes (bcrypt e lento de proposito); 12 em produção. */
    val emailPasswordCost: Int = 12,
    /** Tentativas erradas de login seguidas, pelo mesmo e-mail, antes de bloquear (§18.1, forca bruta). */
    val loginAttemptLimit: Int = 5,
    val loginLockoutMinutes: Long = 15L,
    /** Segredo do console de gerenciamento (§18.7). `null` = rotas `/internal/admin/...` FECHADAS. Nunca igual a [internalSecret]. */
    val adminSecret: String? = null,
) {
    val guests = GuestRegistry(clock, persistence = guestPersistence)
    val emailAccounts = EmailAccountRegistry(emailAccountPersistence, emailPasswordCost, clock)
    val loginAttempts = FailedLoginLimiter(loginAttemptLimit, loginLockoutMinutes * 60_000L, clock)
    val service = LobbyService(store, clock, config = config, photoVersion = guests::photoVersion)
    val ipLimiter = RateLimiter(ipLimitPerMinute, clock = clock)
    val tokenLimiter = RateLimiter(tokenLimitPerMinute, clock = clock)
    val guestLimiter = RateLimiter(guestCreationPerMinute, clock = clock)
    val adminAccounts = AdminAccountDirectory(guests, emailAccounts)
}

private val StartedAt = io.ktor.util.AttributeKey<Long>("lobby.startedAt")

private fun ApplicationCall.clientIp(trustForwarded: Boolean): String {
    if (trustForwarded) {
        // O proxy (Traefik) acrescenta o IP real ao FIM da lista; entradas anteriores podem ser forjadas.
        request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.local.remoteAddress
}

/** Confere o segredo servico a servico; responde o erro e devolve `false` se nao vale. */
private suspend fun ApplicationCall.internalAllowed(expected: String?): Boolean {
    when {
        expected == null -> respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Rotas internas desativadas.", 503))
        !MessageDigest.isEqual(expected.toByteArray(), (request.headers[INTERNAL_SECRET_HEADER] ?: "").toByteArray()) ->
            respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Não autorizado.", 401))
        else -> return true
    }
    return false
}

/**
 * §18.7: confere o segredo do console de gerenciamento (admin-api). Cabecalho e valor PROPRIOS, diferentes de
 * [internalAllowed]/[INTERNAL_SECRET_HEADER]: nunca aceita o segredo servico a servico aqui, nem o contrario.
 */
private suspend fun ApplicationCall.adminAllowed(expected: String?): Boolean {
    when {
        expected == null -> respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Rotas administrativas desativadas.", 503))
        !MessageDigest.isEqual(expected.toByteArray(), (request.headers[ADMIN_SECRET_HEADER] ?: "").toByteArray()) ->
            respondError(LobbyException(ErrorCode.NAO_AUTENTICADO, "Não autorizado.", 401))
        else -> return true
    }
    return false
}

/** Jogador "online" (§18.2): usou o token neste intervalo (a lista e a sala consultam a cada poucos segundos). */
private const val ONLINE_WINDOW_MS = 60_000L

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

/** Nome da conta a partir do Google: sem controles, ate o limite do nome; vazio vira "Jogador". */
internal fun accountName(raw: String?): String =
    raw.orEmpty().filterNot { it.isISOControl() }.trim().take(LobbyLimits.MAX_NAME).trim().ifEmpty { "Jogador" }

fun Application.lobbyModule(deps: LobbyDeps = LobbyDeps()) {
    // Log de acesso: metodo, caminho (sem query), status, IP usado no limite de taxa e o X-Forwarded-For bruto. Nunca o token.
    val accessLog = LoggerFactory.getLogger("lobby.access")
    install(
        createApplicationPlugin("LobbyAccessLog") {
            onCall { it.attributes.put(StartedAt, System.nanoTime()) }
            on(ResponseSent) { call ->
                if (call.request.path() == "/health") return@on
                val ms = call.attributes.getOrNull(StartedAt)?.let { (System.nanoTime() - it) / 1_000_000 }
                accessLog.info(
                    "{} {} {} ip={} xff={} {}ms",
                    call.request.httpMethod.value, call.request.path(), call.response.status()?.value,
                    call.clientIp(deps.trustForwardedFor), call.request.headers["X-Forwarded-For"] ?: "-", ms,
                )
            }
        },
    )
    val limiter = createApplicationPlugin("LobbyRateLimit") {
        onCall { call ->
            val path = call.request.uri
            if (path.startsWith("/api/lobby") || path.startsWith("/m/")) {
                val ip = call.clientIp(deps.trustForwardedFor)
                var ok = deps.ipLimiter.allow("ip:$ip")
                if (ok && (path.startsWith("/api/lobby/guests") || path.startsWith("/api/lobby/accounts"))) ok = deps.guestLimiter.allow("guest:$ip")
                call.bearer()?.let { if (ok) ok = deps.tokenLimiter.allow("tok:" + GuestRegistry.hash(it)) }
                if (!ok) {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.respondError(LobbyException(ErrorCode.LIMITE_DE_TAXA, "Muitas requisições. Aguarde um instante.", 429))
                    return@onCall
                }
                // §18.2: so o aplicativo oficial. Versao diferente segue para a recusa "Atualize o aplicativo" (§18.8).
                val key = deps.appKey
                val sameVersion = call.request.headers[PROTOCOL_HEADER]?.toIntOrNull() == ONLINE_PROTOCOL_VERSION
                if (key != null && sameVersion && path.startsWith("/api/lobby") &&
                    !AppSignature.verify(
                        key, call.request.httpMethod.value, call.request.path(), call.request.headers[AppSignature.TIME_HEADER],
                        call.request.headers[AppSignature.SIGNATURE_HEADER], call.bearer(), deps.clock(),
                    )
                ) {
                    call.respondError(LobbyException(ErrorCode.APP_NAO_RECONHECIDO, "Aplicativo não reconhecido. Atualize o Tranca-Bot.", 401))
                }
            }
        }
    }
    install(limiter)

    if (deps.maintainEveryMs > 0) {
        launch {
            while (isActive) {
                delay(deps.maintainEveryMs)
                runCatching { deps.service.maintain() }
            }
        }
    }

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

        // Pagina do convite /m/<id>: desligada nesta fase (§18.2, sem convite); InvitePage fica para a mesa do jogador.

        post("/api/lobby/guests") {
            call.guarded(authenticated = false) {
                val req = call.receiveLimited<CreateGuestRequest>()
                call.respondJson(deps.guests.create(req.name), HttpStatusCode.Created)
            }
        }
        // §18.1 / plano 2.6: entrar com o Google. O ID token e verificado aqui; a carteira do convidado deste aparelho passa
        // para a conta se ela ainda nao tem carteira (§18.5). A resposta tem o token de sessao, no mesmo esquema do convidado.
        post("/api/lobby/accounts/google") {
            call.guarded(authenticated = false) {
                val req = call.receiveLimited<GoogleLoginRequest>()
                val verifier = deps.google
                    ?: lobbyError(ErrorCode.LOGIN_INDISPONIVEL, "Entrar com o Google não está disponível neste servidor.", 503)
                val user = try {
                    verifier.verify(req.idToken)
                } catch (e: InvalidIdTokenException) {
                    LoggerFactory.getLogger("lobby.login").info("ID token recusado: {}", e.message)
                    lobbyError(ErrorCode.NAO_AUTENTICADO, "Não foi possível confirmar a sua conta Google. Entre de novo.", 401)
                } catch (e: JwksUnavailableException) {
                    lobbyError(ErrorCode.LOGIN_INDISPONIVEL, "Não foi possível falar com o Google agora. Tente de novo.", 503)
                }
                val guestId = req.guestToken?.takeIf { it.isNotBlank() }?.let(deps.guests::authenticate)?.id
                    ?.takeIf { !AccountLimits.isAccountId(it) }
                val migrated = deps.walletLinker?.let { linker ->
                    try {
                        linker.adopt(user.accountId, guestId)
                    } catch (e: EconomyGatewayException) {
                        lobbyError(ErrorCode.LOGIN_INDISPONIVEL, "Não foi possível abrir a sua carteira agora. Tente de novo.", 503)
                    }
                } ?: false
                val session = deps.guests.createSession(user.accountId, accountName(user.name))
                call.respondJson(
                    AccountLogin(session.id, session.name, session.token, pictureUrl = user.picture, walletFromGuest = migrated),
                )
            }
        }
        // §18.1 (mudanca de 02/11/2026): cadastro por e-mail e senha. Se a carteira nao puder ser aberta agora
        // (economia fora do ar), a conta ja existe com a senha escolhida: o jogador entra com "Entrar" depois,
        // que tenta a mesma adocao da carteira do convidado (idempotente, §18.5).
        post("/api/lobby/accounts/email/register") {
            call.guarded(authenticated = false) {
                val req = call.receiveLimited<EmailRegisterRequest>()
                val guest = req.guestToken?.takeIf { it.isNotBlank() }?.let(deps.guests::authenticate)
                    ?.takeIf { !AccountLimits.isAccountId(it.id) }
                // O bcrypt e CPU pesada de proposito: nao roda na thread que processa a chamada HTTP.
                val (id, name) = withContext(Dispatchers.Default) { deps.emailAccounts.register(req.email, req.password, req.name ?: guest?.name) }
                val migrated = deps.walletLinker?.let { linker ->
                    try {
                        linker.adopt(id, guest?.id)
                    } catch (e: EconomyGatewayException) {
                        lobbyError(ErrorCode.LOGIN_INDISPONIVEL, "Não foi possível abrir a sua carteira agora. Tente de novo entrando com a conta.", 503)
                    }
                } ?: false
                val session = deps.guests.createSession(id, name)
                call.respondJson(
                    AccountLogin(session.id, session.name, session.token, kind = AccountKind.EMAIL, walletFromGuest = migrated),
                    HttpStatusCode.Created,
                )
            }
        }
        // §18.1: entrar com e-mail e senha ja cadastrados. Forca bruta: tentativas erradas seguidas pelo mesmo
        // e-mail bloqueiam a chave por um tempo, alem do limite de taxa por IP/token (LobbyRateLimit acima).
        post("/api/lobby/accounts/email/login") {
            call.guarded(authenticated = false) {
                val req = call.receiveLimited<EmailLoginRequest>()
                val attemptKey = req.email.trim().lowercase(Locale.ROOT)
                deps.loginAttempts.checkAllowed(attemptKey)
                val (id, name) = try {
                    // O bcrypt e CPU pesada de proposito: nao roda na thread que processa a chamada HTTP.
                    withContext(Dispatchers.Default) { deps.emailAccounts.login(req.email, req.password) }
                } catch (e: LobbyException) {
                    if (e.code == ErrorCode.NAO_AUTENTICADO) deps.loginAttempts.recordFailure(attemptKey)
                    throw e
                }
                deps.loginAttempts.recordSuccess(attemptKey)
                val guestId = req.guestToken?.takeIf { it.isNotBlank() }?.let(deps.guests::authenticate)?.id
                    ?.takeIf { !AccountLimits.isAccountId(it) }
                val migrated = deps.walletLinker?.let { linker ->
                    try {
                        linker.adopt(id, guestId)
                    } catch (e: EconomyGatewayException) {
                        lobbyError(ErrorCode.LOGIN_INDISPONIVEL, "Não foi possível abrir a sua carteira agora. Tente de novo.", 503)
                    }
                } ?: false
                val session = deps.guests.createSession(id, name)
                call.respondJson(
                    AccountLogin(session.id, session.name, session.token, kind = AccountKind.EMAIL, walletFromGuest = migrated),
                )
            }
        }
        // §18.2: a foto do proprio jogador (reduzida no app) e a foto de outro jogador, so para quem tem token.
        put("/api/lobby/guests/me/photo") {
            call.guarded { guest ->
                deps.guests.setPhoto(guest.id, call.receiveLimited<GuestPhoto>().photoBase64)
                call.respondJson(Health())
            }
        }
        get("/api/lobby/guests/{id}/photo") {
            call.guarded { _ ->
                val photo = deps.guests.photo(call.parameters["id"].orEmpty())
                    ?: lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Sem foto.", 404)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                call.respondJson(GuestPhoto(photo))
            }
        }
        post("/api/lobby/tables") {
            call.guarded { guest ->
                val req = call.receiveLimited<CreateTableRequest>()
                call.respondJson(service.createTable(guest, req.rules), HttpStatusCode.Created)
            }
        }
        // §18.2: todas as mesas do servidor e quantos jogadores estao online.
        get("/api/lobby/tables") {
            call.guarded { _ ->
                val online = deps.guests.activeIds(ONLINE_WINDOW_MS) + service.connectedInGame()
                call.respondJson(service.list(online.size))
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
                val id = call.parameters["id"].orEmpty()
                deps.economy?.let { eco ->
                    // §18.2: so ocupa um assento quem tem saldo >= aposta da mesa. Conferido fora da trava do lobby.
                    val table = service.get(guest, id)
                    val bet = table.rules.bet
                    if (bet > 0 && table.seats.none { it.guestId == guest.id }) {
                        val balance = try {
                            eco.balance(guest.id)
                        } catch (e: EconomyGatewayException) {
                            lobbyError(ErrorCode.PARTIDA_INDISPONIVEL, "Não foi possível conferir o seu saldo agora. Tente de novo.", 503)
                        }
                        if (balance < bet) {
                            lobbyError(ErrorCode.SALDO_INSUFICIENTE, "Saldo insuficiente: a aposta desta mesa é de $bet moedas.", 409)
                        }
                    }
                }
                call.respondJson(service.join(guest, id, req.seat))
            }
        }
        post("/api/lobby/tables/{id}/move") {
            call.guarded { guest ->
                val seat = call.receiveLimited<JoinRequest>().seat
                    ?: lobbyError(ErrorCode.LUGAR_INVALIDO, "Escolha um lugar.", 400)
                call.respondJson(service.move(guest, call.parameters["id"].orEmpty(), seat))
            }
        }
        post("/api/lobby/tables/{id}/leave") {
            call.guarded { guest -> call.respondJson(service.leave(guest, call.parameters["id"].orEmpty())) }
        }
        post("/api/lobby/tables/{id}/host") {
            call.guarded { guest ->
                val req = call.receiveLimited<PassHostRequest>()
                call.respondJson(service.passHost(guest, call.parameters["id"].orEmpty(), req.seat))
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
                    service.beginStart(guest, id)
                    call.respondJson(service.commitStart(id))
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

        // Servico a servico (game-service): quem esta conectado/abandonou; base da sucessao do anfitriao (§18.2).
        post("/internal/tables/{id}/presence") {
            if (!call.internalAllowed(deps.internalSecret)) return@post
            try {
                val notice = call.receiveLimited<PresenceNotice>()
                service.presence(call.parameters["id"].orEmpty(), notice.connected.toSet(), notice.abandoned.toSet())
                call.respondJson(Health())
            } catch (e: LobbyException) {
                call.respondError(e)
            }
        }

        // Suporte, a mao (§18.1): sem recuperacao de senha pelo proprio jogador nesta fase. Gera uma senha
        // temporaria nova para a conta do [email] informado; a antiga deixa de valer. Sem tela nem API publica.
        post("/internal/lobby/accounts/reset-password") {
            if (!call.internalAllowed(deps.internalSecret)) return@post
            try {
                val req = call.receiveLimited<ResetPasswordRequest>()
                val temp = withContext(Dispatchers.Default) { deps.emailAccounts.resetPassword(req.email) }
                    ?: return@post call.respondError(LobbyException(ErrorCode.REQUISICAO_INVALIDA, "Conta não encontrada.", 404))
                call.respondJson(ResetPasswordResponse(temp))
            } catch (e: LobbyException) {
                call.respondError(e)
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

        // Console de gerenciamento (admin-api), so LEITURA (§18.7). Segredo PROPRIO: ver [adminAllowed].
        get("/internal/admin/stats") {
            if (!call.adminAllowed(deps.adminSecret)) return@get
            val online = deps.guests.activeIds(ONLINE_WINDOW_MS) + service.connectedInGame()
            call.respondJson(service.stats(online.size))
        }
        get("/internal/admin/accounts") {
            if (!call.adminAllowed(deps.adminSecret)) return@get
            val q = call.request.queryParameters["q"].orEmpty().take(AccountLimits.MAX_EMAIL)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val (total, page) = deps.adminAccounts.search(q, limit, offset)
            call.respondJson(AdminAccountsPage(total, page))
        }
        get("/internal/admin/accounts/{id}") {
            if (!call.adminAllowed(deps.adminSecret)) return@get
            val id = call.parameters["id"].orEmpty()
            val summary = deps.adminAccounts.detail(id)
                ?: return@get call.respondError(LobbyException(ErrorCode.CONTA_INEXISTENTE, "Conta não encontrada.", 404))
            val tables = service.currentTablesOf(id)
            call.respondJson(
                AdminAccountDetail(
                    summary.id, summary.kind, summary.name, summary.email, summary.createdMs, summary.lastSeenMs,
                    summary.blocked, tables,
                ),
            )
        }
    }
}
