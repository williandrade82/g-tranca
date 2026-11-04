package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminAdjustRequest
import com.gtranca.online.contracts.AdminRevokeSessionsResponse
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ServerInfo
import com.gtranca.online.contracts.TableState
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
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

const val SERVICE_NAME = "admin-api"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Falhas ao chamar lobby/economy-service pelas rotas de leitura (Sec18.7): nunca o segredo, so a mensagem. */
private val gatewayLog = LoggerFactory.getLogger("admin.gateway")

/**
 * Console de gerenciamento (Sec18.7): login da equipe, CRUD da equipe (so administrador cadastra), auditoria,
 * Painel (saude dos servicos + numeros do lobby-service) e Contas (busca e ficha, so LEITURA: lobby-service para
 * a conta e as mesas atuais, economy-service para a carteira). Mesas, Economia (escrita) e Loja ficam para as
 * proximas etapas.
 *
 * Nada de endereco fixo: tudo vem do ambiente (Secret/ConfigMap no cluster).
 */
fun main() {
    fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("ERRO: $name nao definido."); exitProcess(1) }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // DB_URL e obrigatorio (nao opcional como no lobby-service): sem banco a auditoria so em memoria violaria
    // "o registro nao se altera" (ele simplesmente desapareceria a cada reinicio do pod).
    val db = Database.connect(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))
    Database.migrateWithRetry(db)

    val passwordCost = System.getenv("ADMIN_PASSWORD_COST")?.toIntOrNull() ?: 12
    val store = TeamStore(db, passwordCost = passwordCost)
    val sessions = SessionStore(
        db,
        idleTtlMs = (System.getenv("ADMIN_SESSION_IDLE_MINUTES")?.toLongOrNull() ?: 30L) * 60_000L,
        absoluteTtlMs = (System.getenv("ADMIN_SESSION_MAX_HOURS")?.toLongOrNull() ?: 8L) * 3_600_000L,
    )

    // Semente do primeiro administrador (Sec18.7): so roda se a equipe estiver vazia; variaveis lidas uma unica vez
    // no arranque, para o k8s-ops injetar via Secret. Sem administrador nenhum, o console fica trancado para
    // sempre (ninguem cadastra ninguem) - por isso falhar alto (exitProcess) se as variaveis vierem invalidas.
    val seedEmail = System.getenv("ADMIN_SEED_EMAIL")?.takeIf { it.isNotBlank() }
    val seedPassword = System.getenv("ADMIN_SEED_PASSWORD")?.takeIf { it.isNotBlank() }
    when {
        seedEmail != null && seedPassword != null -> try {
            if (store.seedFirstAdmin(seedEmail, seedPassword)) {
                System.err.println("INFO: primeiro administrador semeado ($seedEmail). Troque a senha pelo console e remova ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD do Secret.")
            } else {
                System.err.println("INFO: ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD ignorados: a equipe ja tem gente.")
            }
        } catch (e: AdminException) {
            System.err.println("ERRO: ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD invalidos: ${e.message}")
            exitProcess(1)
        }
        else -> System.err.println("AVISO: ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD ausentes; sem eles o console so funciona se a equipe ja tiver alguem.")
    }

    // Painel (Sec18.7): servicos a sondar, so os configurados (nome -> variavel com o /health dele). Links de
    // metricas/logs tambem por variavel (nunca VITE_*: o mesmo bundle do admin-web roda no cluster local e na
    // nuvem depois, so a configuracao do admin-api muda).
    val healthTargets = listOfNotNull(
        System.getenv("LOBBY_SERVICE_HEALTH_URL")?.takeIf { it.isNotBlank() }?.let { HealthTarget("lobby-service", it) },
        System.getenv("GAME_SERVICE_HEALTH_URL")?.takeIf { it.isNotBlank() }?.let { HealthTarget("game-service", it) },
        System.getenv("ECONOMY_SERVICE_HEALTH_URL")?.takeIf { it.isNotBlank() }?.let { HealthTarget("economy-service", it) },
    )

    // Tela Contas e numeros do Painel (Sec18.7): rotas /internal/admin/... de LEITURA do lobby/economy-service,
    // segredo PROPRIO (nunca o INTERNAL_SECRET servico a servico). Sem as duas variaveis, as rotas ficam
    // desativadas (nulo) em vez de tentar com um segredo errado.
    val adminInternalSecret = System.getenv("ADMIN_INTERNAL_SECRET")?.takeIf { it.isNotBlank() }
    val lobbyServiceUrl = System.getenv("LOBBY_SERVICE_URL")?.takeIf { it.isNotBlank() }
    val economyServiceUrl = System.getenv("ECONOMY_SERVICE_URL")?.takeIf { it.isNotBlank() }
    val lobbyAdmin = if (adminInternalSecret != null && lobbyServiceUrl != null) {
        HttpLobbyAdminGateway(lobbyServiceUrl, adminInternalSecret)
    } else {
        System.err.println("AVISO: LOBBY_SERVICE_URL/ADMIN_INTERNAL_SECRET ausentes; Painel sem numeros e tela Contas desativada.")
        null
    }
    val economyAdmin = if (adminInternalSecret != null && economyServiceUrl != null) {
        HttpEconomyAdminGateway(economyServiceUrl, adminInternalSecret)
    } else {
        System.err.println("AVISO: ECONOMY_SERVICE_URL/ADMIN_INTERNAL_SECRET ausentes; carteira na tela Contas desativada.")
        null
    }

    val deps = AdminDeps(
        store = store,
        sessions = sessions,
        trustForwardedFor = System.getenv("TRUST_FORWARDED_FOR") == "true",
        ipLimitPerMinute = System.getenv("RATE_LIMIT_PER_MINUTE")?.toIntOrNull() ?: 60,
        loginAttemptLimit = System.getenv("LOGIN_ATTEMPT_LIMIT")?.toIntOrNull() ?: 5,
        loginLockoutMinutes = System.getenv("LOGIN_LOCKOUT_MINUTES")?.toLongOrNull() ?: 15L,
        healthTargets = healthTargets,
        healthGateway = HttpHealthGateway(
            timeoutMs = System.getenv("ADMIN_HEALTH_TIMEOUT_MS")?.toLongOrNull() ?: 2_000L,
            yellowAboveMs = System.getenv("ADMIN_HEALTH_YELLOW_MS")?.toLongOrNull() ?: 500L,
        ),
        grafanaUrl = System.getenv("GRAFANA_URL")?.takeIf { it.isNotBlank() },
        lokiUrl = System.getenv("LOKI_URL")?.takeIf { it.isNotBlank() },
        lobbyAdmin = lobbyAdmin,
        economyAdmin = economyAdmin,
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") { adminModule(deps) }.start(wait = true)
}

class AdminDeps(
    val store: TeamStore,
    val sessions: SessionStore,
    val clock: () -> Long = System::currentTimeMillis,
    val trustForwardedFor: Boolean = false,
    val ipLimitPerMinute: Int = 60,
    val loginAttemptLimit: Int = 5,
    val loginLockoutMinutes: Long = 15L,
    /** Painel (Sec18.7): servicos a sondar. Vazio = a tela so mostra o que nao depende de rede nenhuma. */
    val healthTargets: List<HealthTarget> = emptyList(),
    val healthGateway: HealthGateway = HttpHealthGateway(),
    val grafanaUrl: String? = null,
    val lokiUrl: String? = null,
    /** Tela Contas e numeros do Painel (Sec18.7). `null` = rotas Contas desativadas, nulo no Painel (nunca inventa). */
    val lobbyAdmin: LobbyAdminGateway? = null,
    /** Carteira na tela Contas (Sec18.7). `null` = wallet sempre nulo na ficha (nunca inventa saldo). */
    val economyAdmin: EconomyAdminGateway? = null,
    /** Auditoria das acoes sobre conta (bloquear/desbloquear/encerrar sessoes): mesmo banco da Equipe, ver [AuditLog]. */
    val auditLog: AuditLog = AuditLog(store.db, clock),
) {
    val ipLimiter = RateLimiter(ipLimitPerMinute, clock = clock)
    val loginAttempts = FailedLoginLimiter(loginAttemptLimit, loginLockoutMinutes * 60_000L, clock)
}

/**
 * Grava a auditoria de uma acao de escrita sobre conta ou mesa (Sec18.7), depois que a chamada remota ao
 * lobby-service confirmou. Se o INSERT falhar (banco indisponivel no meio exato entre a mutacao remota e a
 * auditoria local - ver pendencia no relatorio da etapa), a acao JA ACONTECEU no lobby-service sem registro:
 * log ERROR antes de propagar, nunca silencioso.
 */
private suspend fun AdminDeps.recordAudit(
    operationId: String?, actor: Actor, action: String, targetId: String, reason: String,
    resultado: String? = null, detail: String? = null,
) {
    try {
        withContext(Dispatchers.IO) { auditLog.record(operationId, actor, action, targetId, null, reason, resultado, detail) }
    } catch (e: Exception) {
        gatewayLog.error("auditoria NAO gravada (acao={}, alvo={}, ator={}): {}", action, targetId, actor.email, e.message)
        throw e
    }
}

private val StartedAt = io.ktor.util.AttributeKey<Long>("admin.startedAt")

private fun ApplicationCall.clientIp(trustForwarded: Boolean): String {
    if (trustForwarded) {
        // O proxy (ingress) acrescenta o IP real ao FIM da lista; entradas anteriores podem ser forjadas.
        request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.local.remoteAddress
}

private suspend fun ApplicationCall.respondError(e: AdminException) {
    respondText(json.encodeToString(e.toApiError()), ContentType.Application.Json, HttpStatusCode.fromValue(e.status))
}

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(json.encodeToString(value), ContentType.Application.Json, status)
}

/** Le o corpo com teto de tamanho (mesmo sem Content-Length) e decodifica. */
private suspend inline fun <reified T> ApplicationCall.receiveLimited(): T {
    val max = AdminLimits.MAX_BODY_BYTES
    if ((request.contentLength() ?: 0L) > max) adminError(ErrorCode.CORPO_GRANDE_DEMAIS, "Mensagem grande demais.", 413)
    val bytes = receiveChannel().readRemaining(max + 1L).readByteArray()
    if (bytes.size > max) adminError(ErrorCode.CORPO_GRANDE_DEMAIS, "Mensagem grande demais.", 413)
    return try {
        json.decodeFromString<T>(if (bytes.isEmpty()) "{}" else bytes.decodeToString())
    } catch (e: Exception) {
        adminError(ErrorCode.REQUISICAO_INVALIDA, "Mensagem inválida.", 400)
    }
}

private fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

fun Application.adminModule(deps: AdminDeps) {
    // Log de acesso: metodo, caminho, status, IP. NUNCA o cabecalho Authorization (nem o token, nem parte dele).
    val accessLog = LoggerFactory.getLogger("admin.access")
    install(
        createApplicationPlugin("AdminAccessLog") {
            onCall { it.attributes.put(StartedAt, System.nanoTime()) }
            on(ResponseSent) { call ->
                if (call.request.path() == "/health") return@on
                val ms = call.attributes.getOrNull(StartedAt)?.let { (System.nanoTime() - it) / 1_000_000 }
                accessLog.info(
                    "{} {} {} ip={} {}ms",
                    call.request.httpMethod.value, call.request.path(), call.response.status()?.value,
                    call.clientIp(deps.trustForwardedFor), ms,
                )
            }
        },
    )

    // Cabecalhos de seguranca em toda resposta: nada fica em cache (console administrativo) e o navegador nao
    // tenta "adivinhar" o tipo do conteudo.
    install(
        createApplicationPlugin("AdminSecurityHeaders") {
            onCall { call ->
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Content-Type-Options", "nosniff")
            }
        },
    )

    // Limite de taxa por IP em /admin/... (sem AppSignature nem PROTOCOL_HEADER aqui: isso e do aplicativo movel;
    // uma chave assim no navegador do console seria um segredo exposto. O token Bearer no cabecalho ja cobre CSRF;
    // same-origin fica a cargo do NGINX que serve o admin-web na frente do admin-api).
    install(
        createApplicationPlugin("AdminRateLimit") {
            onCall { call ->
                if (call.request.path().startsWith("/admin")) {
                    val ip = call.clientIp(deps.trustForwardedFor)
                    if (!deps.ipLimiter.allow("ip:$ip")) {
                        call.response.header(HttpHeaders.RetryAfter, "60")
                        call.respondError(AdminException(ErrorCode.LIMITE_DE_TAXA, "Muitas requisições. Aguarde um instante.", 429))
                    }
                }
            }
        },
    )

    suspend fun ApplicationCall.authenticated(minRole: Role, block: suspend (SessionMember) -> Unit) {
        try {
            val token = bearer() ?: adminError(ErrorCode.NAO_AUTENTICADO, "Entre novamente.", 401)
            val member = deps.sessions.authenticate(token) ?: adminError(ErrorCode.NAO_AUTENTICADO, "Entre novamente.", 401)
            if (!member.role.atLeast(minRole)) adminError(ErrorCode.SEM_PERMISSAO, "Seu papel não permite esta ação.", 403)
            block(member)
        } catch (e: AdminException) {
            respondError(e)
        }
    }

    routing {
        get("/health") { call.respondJson(Health()) }
        get("/info") { call.respondJson(ServerInfo(SERVICE_NAME)) }

        // Sec18.7: entra-se com e-mail e senha; erro de login nunca revela se o e-mail existe.
        post("/admin/login") {
            try {
                val req = call.receiveLimited<LoginRequest>()
                val attemptKey = req.email.trim().lowercase()
                deps.loginAttempts.checkAllowed(attemptKey)
                val result = try {
                    // bcrypt e CPU pesada de proposito: nao roda na thread que processa a chamada HTTP.
                    withContext(Dispatchers.Default) { deps.store.login(req.email, req.password) }
                } catch (e: AdminException) {
                    if (e.code == ErrorCode.NAO_AUTENTICADO) deps.loginAttempts.recordFailure(attemptKey)
                    throw e
                }
                deps.loginAttempts.recordSuccess(attemptKey)
                val token = deps.sessions.create(result.id)
                call.respondJson(LoginResponse(token, result.id, result.email, result.role))
            } catch (e: AdminException) {
                call.respondError(e)
            }
        }

        // Sec18.7: revoga a sessao no servidor ("Sair"). Sem token ou token ja invalido: mesma resposta (idempotente).
        post("/admin/logout") {
            try {
                val token = call.bearer() ?: adminError(ErrorCode.NAO_AUTENTICADO, "Entre novamente.", 401)
                withContext(Dispatchers.IO) { deps.sessions.revoke(token) }
                call.respondJson(Health())
            } catch (e: AdminException) {
                call.respondError(e)
            }
        }

        // Sec18.7 Painel (docs/telas-admin-web.md): saude de cada servico (verde/amarelo/vermelho), numeros do
        // lobby-service (GET /internal/admin/stats) e links de metricas/logs. Sem LOBBY_SERVICE_URL/
        // ADMIN_INTERNAL_SECRET configurados, ou se o lobby-service nao responder, os 4 numeros ficam nulos (ver
        // DashboardResponse): o admin-web mostra "indisponivel", nunca inventa.
        get("/admin/dashboard") {
            call.authenticated(Role.LEITOR) { _ ->
                val (services, stats) = coroutineScope {
                    val servicesDeferred = deps.healthTargets.map { target -> async { deps.healthGateway.check(target) } }
                    val statsDeferred = async {
                        deps.lobbyAdmin?.let { gateway ->
                            runCatching { withContext(Dispatchers.IO) { gateway.stats() } }
                                .onFailure { gatewayLog.warn("lobby-service indisponivel (GET /admin/dashboard): {}", it.message) }
                                .getOrNull()
                        }
                    }
                    servicesDeferred.awaitAll() to statsDeferred.await()
                }
                call.respondJson(
                    DashboardResponse(
                        services = services,
                        playersOnline = stats?.playersOnline,
                        tablesOpen = stats?.tablesOpen,
                        tablesInGame = stats?.tablesInGame,
                        matchesEndedLastHour = stats?.matchesEndedLastHour,
                        grafanaUrl = deps.grafanaUrl,
                        lokiUrl = deps.lokiUrl,
                    ),
                )
            }
        }

        // Sec18.7 tela Contas (docs/telas-admin-web.md): busca por nome/e-mail/id, agregada do lobby-service.
        // Sem LOBBY_SERVICE_URL/ADMIN_INTERNAL_SECRET configurados, ou se o lobby-service nao responder, a rota
        // recusa (503): uma listagem de contas nao tem como degradar para "indisponivel" num campo so.
        get("/admin/accounts") {
            call.authenticated(Role.LEITOR) { _ ->
                val gateway = deps.lobbyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                val q = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = try {
                    withContext(Dispatchers.IO) { gateway.searchAccounts(q, limit, offset) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/accounts): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                }
                call.respondJson(AccountsPageResponse(page.accounts, page.total, limit, offset))
            }
        }

        // Sec18.7 tela Contas: a ficha (lobby-service) e a carteira (economy-service) em paralelo. A ficha nao
        // tem como degradar (sem ela nao ha tela), por isso 503 se o lobby nao responder. A carteira tem 3
        // desfechos distintos (ver AccountDetailResponse.walletUnavailable): tem carteira, a conta genuinamente
        // nao tem carteira online ainda (wallet=null, walletUnavailable=false, "sem carteira" na tela) ou o
        // economy-service/a configuracao nao deixou saber (wallet=null, walletUnavailable=true, "indisponivel").
        get("/admin/accounts/{id}") {
            call.authenticated(Role.LEITOR) { _ ->
                val id = call.parameters["id"].orEmpty()
                val lobby = deps.lobbyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                val economy = deps.economyAdmin
                val (detailResult, walletOutcome) = coroutineScope {
                    val detailDeferred = async(Dispatchers.IO) { runCatching { lobby.accountDetail(id) } }
                    val walletDeferred = async(Dispatchers.IO) { economy?.let { gw -> runCatching { gw.wallet(id) } } }
                    detailDeferred.await() to walletDeferred.await()
                }
                val account = detailResult.getOrElse {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/accounts/{{id}}): {}", it.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                } ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Conta não encontrada.", 404)
                walletOutcome?.exceptionOrNull()?.let {
                    gatewayLog.warn("economy-service indisponivel (GET /admin/accounts/{{id}}/wallet): {}", it.message)
                }
                val wallet = walletOutcome?.getOrNull()
                val walletUnavailable = economy == null || walletOutcome?.isFailure == true
                call.respondJson(AccountDetailResponse(account, wallet, walletUnavailable))
            }
        }

        // Sec18.7 "Bloquear conta" (definition.md, decisao de 03/11/2026): operador+, motivo obrigatorio (acao
        // destrutiva). Impede logar de novo e entrar em mesa nova; NAO ejeta de partida em andamento nem
        // invalida tokens ja emitidos (isso e /revoke-sessions, acao independente, logo abaixo). Idempotente no
        // lobby-service; aqui so evita duplicar a linha de auditoria quando o mesmo operationId repete.
        post("/admin/accounts/{id}/block") {
            call.authenticated(Role.OPERADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<AccountActionRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                // Confere ANTES de chamar o lobby-service: repetir o mesmo operationId nao chama de novo (uma
                // 2a chamada bloquear/desbloquear/bloquear com o mesmo operationId nao pode reverter a 1a).
                val jaFeito = req.operationId != null && withContext(Dispatchers.IO) { deps.auditLog.hasOperation(req.operationId) }
                if (!jaFeito) {
                    val gateway = deps.lobbyAdmin
                        ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    try {
                        withContext(Dispatchers.IO) { gateway.block(id, req.reason) }
                    } catch (e: LobbyAdminGatewayException) {
                        gatewayLog.warn("lobby-service indisponivel (POST /admin/accounts/{{id}}/block): {}", e.message)
                        adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    }
                    deps.recordAudit(req.operationId, Actor(actor.id, actor.email), "BLOQUEAR_CONTA", id, req.reason)
                }
                call.respondJson(Health())
            }
        }
        // Reverte o bloqueio acima, sem efeito retroativo. Mesmas regras (operador+, motivo obrigatorio).
        post("/admin/accounts/{id}/unblock") {
            call.authenticated(Role.OPERADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<AccountActionRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                val jaFeito = req.operationId != null && withContext(Dispatchers.IO) { deps.auditLog.hasOperation(req.operationId) }
                if (!jaFeito) {
                    val gateway = deps.lobbyAdmin
                        ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    try {
                        withContext(Dispatchers.IO) { gateway.unblock(id) }
                    } catch (e: LobbyAdminGatewayException) {
                        gatewayLog.warn("lobby-service indisponivel (POST /admin/accounts/{{id}}/unblock): {}", e.message)
                        adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    }
                    deps.recordAudit(req.operationId, Actor(actor.id, actor.email), "DESBLOQUEAR_CONTA", id, req.reason)
                }
                call.respondJson(Health())
            }
        }
        // Sec18.7 "Encerrar sessões": acao INDEPENDENTE do bloqueio - invalida os tokens vivos, sem impedir
        // logins futuros. Motivo obrigatorio, como as duas de cima. Idempotencia COM resposta (como
        // TeamStore.findAuditResult): repetir o mesmo operationId devolve o MESMO "revoked" da 1a vez, sem
        // encerrar sessoes criadas depois da 1a chamada.
        post("/admin/accounts/{id}/revoke-sessions") {
            call.authenticated(Role.OPERADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<AccountActionRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                val cached = req.operationId?.let { opId -> withContext(Dispatchers.IO) { deps.auditLog.findResult(opId) } }
                val revoked = if (cached != null) {
                    json.decodeFromString(AdminRevokeSessionsResponse.serializer(), cached).revoked
                } else {
                    val gateway = deps.lobbyAdmin
                        ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    val result = try {
                        withContext(Dispatchers.IO) { gateway.revokeSessions(id) }
                    } catch (e: LobbyAdminGatewayException) {
                        gatewayLog.warn("lobby-service indisponivel (POST /admin/accounts/{{id}}/revoke-sessions): {}", e.message)
                        adminError(ErrorCode.SERVICO_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                    }
                    deps.recordAudit(
                        req.operationId, Actor(actor.id, actor.email), "ENCERRAR_SESSOES_CONTA", id, req.reason,
                        resultado = json.encodeToString(AdminRevokeSessionsResponse.serializer(), AdminRevokeSessionsResponse(result)),
                        detail = "sessões encerradas: $result",
                    )
                    result
                }
                call.respondJson(AdminRevokeSessionsResponse(revoked))
            }
        }

        // Sec18.7 tela Mesas (docs/telas-admin-web.md): lista TODO estado (ao contrario da lista publica do
        // app, que nunca mostra ENCERRADA), agregada do lobby-service.
        get("/admin/tables") {
            call.authenticated(Role.LEITOR) { _ ->
                val gateway = deps.lobbyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = try {
                    withContext(Dispatchers.IO) { gateway.tables(limit, offset) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/tables): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                }
                call.respondJson(TablesPageResponse(page.tables, page.total, limit, offset))
            }
        }
        // A ficha da mesa (assentos, regras): mesmo TableView da lista publica do app, so que em QUALQUER
        // estado (console, Sec18.7).
        get("/admin/tables/{id}") {
            call.authenticated(Role.LEITOR) { _ ->
                val id = call.parameters["id"].orEmpty()
                val gateway = deps.lobbyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                val table = try {
                    withContext(Dispatchers.IO) { gateway.tableDetail(id) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/tables/{{id}}): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                } ?: adminError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada.", 404)
                call.respondJson(table)
            }
        }
        // Sec18.7 "Encerrar mesa travada": operador+, motivo obrigatorio. Idempotente em qualquer estado no
        // lobby-service (fechar de novo so devolve o mesmo estado, nunca ejeta/penaliza duas vezes) - por isso,
        // ao contrario de bloquear/desbloquear conta, NAO precisa conferir o operationId antes de chamar o
        // gateway: nao ha "desfazer" que uma repeticao possa reverter. O operationId so evita duplicar a linha
        // de auditoria (deps.recordAudit/AuditLog.record ja fazem isso).
        //
        // Busca o estado ATUAL antes de fechar, por dois motivos: (1) o 404 sai antes de tentar fechar; (2) o
        // aviso que o operador confirmou na tela pode estar desatualizado (ele abriu a ficha, a mesa estava
        // ABERTA, o anfitriao apertou Iniciar, so entao o operador confirma "isso vai encerrar esta mesa" sem
        // saber que agora e uma partida em andamento). Se [CloseTableRequest.expectedState] vier preenchido E a
        // mesa estiver EM_JOGO (a UNICA direcao perigosa: mesa ja ENCERRADA continua sem efeito) e diferente do
        // esperado, recusa com 409 para o operador conferir o aviso de novo (mesmo ErrorCode/status que
        // TeamStore usa pro ultimo administrador).
        post("/admin/tables/{id}/close") {
            call.authenticated(Role.OPERADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<CloseTableRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                val gateway = deps.lobbyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                val before = try {
                    withContext(Dispatchers.IO) { gateway.tableDetail(id) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (POST /admin/tables/{{id}}/close, consulta previa): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Mesas indisponíveis agora. Tente de novo.", 503)
                } ?: adminError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada.", 404)
                if (req.expectedState != null && before.state == TableState.EM_JOGO && req.expectedState != before.state) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "A mesa mudou de estado; confira o aviso de novo.", 409)
                }
                val table = try {
                    withContext(Dispatchers.IO) { gateway.closeTable(id, req.reason) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (POST /admin/tables/{{id}}/close): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Não foi possível encerrar a mesa agora (ninguém foi penalizado). Tente de novo.", 503)
                } ?: adminError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada.", 404)
                val detail = when (before.state) {
                    TableState.EM_JOGO -> "estado anterior: EM_JOGO, partida encerrada, aposta devolvida a todos"
                    TableState.ABERTA -> "estado anterior: ABERTA"
                    TableState.ENCERRADA -> "já estava ENCERRADA (sem efeito)"
                }
                deps.recordAudit(req.operationId, Actor(actor.id, actor.email), "ENCERRAR_MESA", id, req.reason, detail = detail)
                call.respondJson(table)
            }
        }

        // Sec18.7 tela Economia (docs/telas-admin-web.md): livro-razao de uma conta, so LEITURA.
        get("/admin/accounts/{id}/ledger") {
            call.authenticated(Role.LEITOR) { _ ->
                val id = call.parameters["id"].orEmpty()
                val economy = deps.economyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = try {
                    withContext(Dispatchers.IO) { economy.ledger(id, limit, offset) }
                } catch (e: EconomyAdminGatewayException) {
                    gatewayLog.warn("economy-service indisponivel (GET /admin/accounts/{{id}}/ledger): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                } ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Conta não encontrada.", 404)
                call.respondJson(LedgerPageResponse(page.entries, page.total, limit, offset))
            }
        }
        // "Ajustar saldo ou booster": operador+, motivo e chave de idempotencia obrigatorios (a chave e gerada
        // pelo admin-web, como nas outras acoes de escrita). Validacoes de FORMATO (motivo, pelo menos um ajuste
        // real, booster e quantidade sempre juntos) sao conferidas aqui, pra nao gastar uma chamada de rede com
        // um pedido obviamente invalido; a validacao de NEGOCIO (saldo/quantidade nunca negativos) so o
        // economy-service sabe responder, por isso nao e repetida aqui (ver EconomyAdjustRejected).
        post("/admin/accounts/{id}/adjust") {
            call.authenticated(Role.OPERADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<AdminAdjustRequest>()
                validateReason(req.reason)
                if (req.operationId.isBlank() || req.operationId.length > AdminLimits.MAX_OPERATION_ID) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Identificador de operação inválido.", 400)
                }
                if ((req.boosterId == null) != (req.quantityDelta == null)) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Informe o booster e a quantidade juntos.", 400)
                }
                val coinsDelta = req.coinsDelta ?: 0L
                val quantityDelta = req.quantityDelta ?: 0
                if (coinsDelta == 0L && (req.boosterId == null || quantityDelta == 0)) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Informe ao menos um ajuste de moedas ou de booster.", 400)
                }
                val economy = deps.economyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                val wallet = try {
                    withContext(Dispatchers.IO) { economy.adjust(id, req) }
                } catch (e: EconomyAdjustRejected) {
                    gatewayLog.warn("ajuste recusado pelo economy-service (conta {}): {} {}", id, e.code, e.message)
                    val msg = e.message ?: "O economy-service recusou o ajuste."
                    if (e.code == EconomyErrorCode.SALDO_INSUFICIENTE) {
                        adminError(ErrorCode.SALDO_INSUFICIENTE, msg, 409)
                    } else {
                        adminError(ErrorCode.REQUISICAO_INVALIDA, msg, 400)
                    }
                } catch (e: EconomyAdminGatewayException) {
                    gatewayLog.warn("economy-service indisponivel (POST /admin/accounts/{{id}}/adjust): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                }
                val detail = buildString {
                    if (coinsDelta != 0L) append("moedas: ${if (coinsDelta > 0) "+" else ""}$coinsDelta")
                    if (req.boosterId != null && quantityDelta != 0) {
                        if (isNotEmpty()) append("; ")
                        append("${req.boosterId}: ${if (quantityDelta > 0) "+" else ""}$quantityDelta")
                    }
                }
                deps.recordAudit(
                    req.operationId, Actor(actor.id, actor.email), "AJUSTAR_CARTEIRA", id, req.reason,
                    resultado = json.encodeToString(AdminWalletDto.serializer(), wallet), detail = detail,
                )
                call.respondJson(wallet)
            }
        }
        // Migracoes de carteira local JA feitas (Sec18.6), de TODAS as contas - lista GLOBAL, nao de uma conta so.
        get("/admin/migrations") {
            call.authenticated(Role.LEITOR) { _ ->
                val economy = deps.economyAdmin
                    ?: adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = try {
                    withContext(Dispatchers.IO) { economy.migrations(limit, offset) }
                } catch (e: EconomyAdminGatewayException) {
                    gatewayLog.warn("economy-service indisponivel (GET /admin/migrations): {}", e.message)
                    adminError(ErrorCode.SERVICO_INDISPONIVEL, "Economia indisponível agora. Tente de novo.", 503)
                }
                call.respondJson(MigrationsPageResponse(page.migrations, page.total, limit, offset))
            }
        }

        // Sec18.7 "Equipe": leitor consulta; so administrador cadastra, altera e remove (sem autocadastro).
        get("/admin/team") {
            call.authenticated(Role.LEITOR) { _ ->
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val (items, total) = withContext(Dispatchers.IO) { deps.store.list(limit, offset) }
                call.respondJson(TeamListResponse(items, total, limit, offset))
            }
        }
        post("/admin/team") {
            call.authenticated(Role.ADMINISTRADOR) { actor ->
                val req = call.receiveLimited<CreateMemberRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                val member = withContext(Dispatchers.Default) {
                    deps.store.create(req.email, req.password, req.role, Actor(actor.id, actor.email), req.reason, req.operationId)
                }
                call.respondJson(member, HttpStatusCode.Created)
            }
        }
        patch("/admin/team/{id}") {
            call.authenticated(Role.ADMINISTRADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<UpdateMemberRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                if (req.role == null && req.password == null) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Informe o novo papel ou a nova senha.", 400)
                }
                val member = withContext(Dispatchers.Default) {
                    deps.store.update(id, req.role, req.password, Actor(actor.id, actor.email), req.reason, req.operationId)
                }
                call.respondJson(member)
            }
        }
        // Motivo no CORPO JSON (nao em query string): mesmo formato de POST/PATCH, sem motivo visivel em log de acesso/URL.
        delete("/admin/team/{id}") {
            call.authenticated(Role.ADMINISTRADOR) { actor ->
                val id = call.parameters["id"].orEmpty()
                val req = call.receiveLimited<RemoveMemberRequest>()
                validateReason(req.reason)
                validateOperationId(req.operationId)
                withContext(Dispatchers.IO) { deps.store.remove(id, Actor(actor.id, actor.email), req.reason, req.operationId) }
                call.respondJson(Health())
            }
        }
    }
}
