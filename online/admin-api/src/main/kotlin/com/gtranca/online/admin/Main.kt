package com.gtranca.online.admin

import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ServerInfo
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
) {
    val ipLimiter = RateLimiter(ipLimitPerMinute, clock = clock)
    val loginAttempts = FailedLoginLimiter(loginAttemptLimit, loginLockoutMinutes * 60_000L, clock)
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
                // ErrorCode.PARTIDA_INDISPONIVEL emprestado: o contrato compartilhado nao tem um codigo generico
                // de "servico indisponivel" (sugestao pro online-backend-dev no relatorio desta etapa). A
                // mensagem em pt-BR mostrada ao operador e clara de qualquer forma.
                val gateway = deps.lobbyAdmin
                    ?: adminError(ErrorCode.PARTIDA_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                val q = call.request.queryParameters["q"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, AdminLimits.MAX_PAGE_SIZE)
                    ?: AdminLimits.DEFAULT_PAGE_SIZE
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val page = try {
                    withContext(Dispatchers.IO) { gateway.searchAccounts(q, limit, offset) }
                } catch (e: LobbyAdminGatewayException) {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/accounts): {}", e.message)
                    adminError(ErrorCode.PARTIDA_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
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
                // ErrorCode.PARTIDA_INDISPONIVEL emprestado, ver comentario em GET /admin/accounts acima.
                val lobby = deps.lobbyAdmin
                    ?: adminError(ErrorCode.PARTIDA_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                val economy = deps.economyAdmin
                val (detailResult, walletOutcome) = coroutineScope {
                    val detailDeferred = async(Dispatchers.IO) { runCatching { lobby.accountDetail(id) } }
                    val walletDeferred = async(Dispatchers.IO) { economy?.let { gw -> runCatching { gw.wallet(id) } } }
                    detailDeferred.await() to walletDeferred.await()
                }
                val account = detailResult.getOrElse {
                    gatewayLog.warn("lobby-service indisponivel (GET /admin/accounts/{{id}}): {}", it.message)
                    adminError(ErrorCode.PARTIDA_INDISPONIVEL, "Contas indisponíveis agora. Tente de novo.", 503)
                } ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Conta não encontrada.", 404)
                walletOutcome?.exceptionOrNull()?.let {
                    gatewayLog.warn("economy-service indisponivel (GET /admin/accounts/{{id}}/wallet): {}", it.message)
                }
                val wallet = walletOutcome?.getOrNull()
                val walletUnavailable = economy == null || walletOutcome?.isFailure == true
                call.respondJson(AccountDetailResponse(account, wallet, walletUnavailable))
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
