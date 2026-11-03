package com.gtranca.online.economy

import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.AdoptWalletRequest
import com.gtranca.online.contracts.AdoptWalletResponse
import com.gtranca.engine.Loadout
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.BetHoldRequest
import com.gtranca.online.contracts.BetHoldResponse
import com.gtranca.online.contracts.BetSettleRequest
import com.gtranca.online.contracts.BetSettleResponse
import com.gtranca.online.contracts.BoosterDebitRequest
import com.gtranca.online.contracts.BoosterDebitResponse
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.BuyRequest
import com.gtranca.online.contracts.DailyClaimRequest
import com.gtranca.online.contracts.MigrateLocalWalletRequest
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.EconomyLimits
import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.Health
import com.gtranca.online.contracts.ServerInfo
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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlin.system.exitProcess
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SERVICE_NAME = "economy-service"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Tudo por variavel de ambiente (Secret/ConfigMap no cluster); nada de endereco fixo. */
fun main() {
    fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("ERRO: $name nao definido."); exitProcess(1) }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val db = Database.connect(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))
    Database.migrateWithRetry(db)
    val appKey = System.getenv("APP_SIGNING_KEY")?.takeIf { it.isNotBlank() }
        ?: null.also { System.err.println("AVISO: APP_SIGNING_KEY ausente; a economia aceita pedidos sem assinatura do app") }
    val secret = required("INTERNAL_SECRET")
    // §18.7: segredo PROPRIO do console de gerenciamento, nunca o INTERNAL_SECRET (servico a servico, que tambem
    // abre rotas sensiveis entre lobby/game/economy). Igual ao INTERNAL_SECRET = erro de configuracao: as rotas
    // /internal/admin ficam desativadas em vez de aceitar o segredo errado.
    val adminSecretEnv = System.getenv("ADMIN_INTERNAL_SECRET")?.takeIf { it.isNotBlank() }
    val adminSecret = when {
        adminSecretEnv == null -> null.also { System.err.println("AVISO: ADMIN_INTERNAL_SECRET ausente; rotas /internal/admin (console de gerenciamento) desativadas.") }
        adminSecretEnv == secret -> null.also { System.err.println("ERRO: ADMIN_INTERNAL_SECRET nao pode ser igual a INTERNAL_SECRET; rotas /internal/admin desativadas.") }
        else -> adminSecretEnv
    }
    val lobbyUrl = System.getenv("LOBBY_URL")?.takeIf { it.isNotBlank() } ?: "http://lobby-service"
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        economyModule(
            EconomyDeps(
                EconomyStore(db), appKey, internalSecret = secret, adminSecret = adminSecret,
                guests = CachedGuestResolver(HttpGuestResolver(lobbyUrl, secret)),
            ),
        )
    }.start(wait = true)
}

class EconomyDeps(
    val store: EconomyStore,
    /** Chave do aplicativo (§18.2). `null` = sem verificacao (so desenvolvimento). */
    val appKey: String? = null,
    val clock: () -> Long = System::currentTimeMillis,
    /** Segredo servico a servico (rotas internas de economia). `null` = rotas internas FECHADAS. */
    val internalSecret: String? = null,
    /** Dono do token do jogador (o lobby). `null` = rotas publicas FECHADAS (falha fechada). */
    val guests: GuestResolver? = null,
    /** Segredo do console de gerenciamento (§18.7). `null` = rotas `/internal/admin/...` FECHADAS. Nunca igual a [internalSecret]. */
    val adminSecret: String? = null,
)

private class HttpFail(val status: Int, val code: EconomyErrorCode, message: String) : RuntimeException(message)

private suspend fun ApplicationCall.respondError(status: Int, code: EconomyErrorCode, message: String) =
    respondText(json.encodeToString(EconomyError(code, message)), ContentType.Application.Json, HttpStatusCode.fromValue(status))

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T) =
    respondText(json.encodeToString(value), ContentType.Application.Json)

private suspend inline fun <reified T> ApplicationCall.receiveLimited(): T {
    val max = EconomyLimits.MAX_BODY_BYTES
    if ((request.contentLength() ?: 0L) > max) throw HttpFail(413, EconomyErrorCode.REQUISICAO_INVALIDA, "Mensagem grande demais.")
    val bytes = receiveChannel().readRemaining(max + 1L).readByteArray()
    if (bytes.size > max) throw HttpFail(413, EconomyErrorCode.REQUISICAO_INVALIDA, "Mensagem grande demais.")
    return try {
        json.decodeFromString<T>(bytes.decodeToString())
    } catch (e: Exception) {
        throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Mensagem inválida.")
    }
}

private fun EconomyState.toDto() = EconomyStateDto(balance, inventory, lastDailyClaim, migratedLocal)

private fun EconomyException.status(): Int = when (code) {
    EconomyException.Code.SALDO_INSUFICIENTE, EconomyException.Code.JA_RESGATADO -> 409
    EconomyException.Code.CONTA_INEXISTENTE -> 404
    EconomyException.Code.OFERTA_INEXISTENTE, EconomyException.Code.REQUISICAO_INVALIDA -> 400
    EconomyException.Code.APOSTA_INEXISTENTE -> 404
    EconomyException.Code.CONTA_NAO_ELEGIVEL -> 403
}

private val GUEST_ID = Regex("[A-Za-z0-9_-]{1,${EconomyLimits.MAX_GUEST_ID}}")

fun Application.economyModule(deps: EconomyDeps) {
    // §18.2: so o aplicativo oficial nas rotas publicas (/economy/...). /internal/economy/* (2.3) fica sem assinatura.
    install(
        createApplicationPlugin("EconomyAppSignature") {
            onCall { call ->
                val key = deps.appKey ?: return@onCall
                if (!call.request.path().startsWith("/economy")) return@onCall
                val bearer = call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                if (!AppSignature.verify(
                        key, call.request.httpMethod.value, call.request.path(), call.request.headers[AppSignature.TIME_HEADER],
                        call.request.headers[AppSignature.SIGNATURE_HEADER], bearer, deps.clock(),
                    )
                ) {
                    call.respondError(401, EconomyErrorCode.APP_NAO_RECONHECIDO, "Aplicativo não reconhecido. Atualize o Tranca-Bot.")
                }
            }
        },
    )

    suspend fun ApplicationCall.guarded(block: suspend (String) -> EconomyState) {
        try {
            val id = parameters["guestId"].orEmpty()
            if (!GUEST_ID.matches(id)) throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Jogador inválido.")
            // §18.1: so o proprio jogador ve e mexe na propria carteira (o token e o mesmo do lobby).
            val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: throw HttpFail(401, EconomyErrorCode.NAO_AUTENTICADO, "Entre novamente.")
            val resolver = deps.guests ?: throw HttpFail(503, EconomyErrorCode.INDISPONIVEL, "Carteira indisponível agora. Tente de novo.")
            val owner = try {
                resolver.resolve(token)
            } catch (e: LobbyUnavailableException) {
                throw HttpFail(503, EconomyErrorCode.INDISPONIVEL, "Carteira indisponível agora. Tente de novo.")
            } ?: throw HttpFail(401, EconomyErrorCode.NAO_AUTENTICADO, "Entre novamente.")
            if (owner != id) throw HttpFail(403, EconomyErrorCode.SEM_PERMISSAO, "Esta carteira não é sua.")
            respondJson(block(id).toDto())
        } catch (e: HttpFail) {
            respondError(e.status, e.code, e.message.orEmpty())
        } catch (e: EconomyException) {
            respondError(e.status(), EconomyErrorCode.valueOf(e.code.name), e.message.orEmpty())
        }
    }

    val store = deps.store
    routing {
        get("/health") { call.respondJson(Health()) }
        get("/info") { call.respondJson(ServerInfo(SERVICE_NAME)) }

        get("/economy/{guestId}") {
            call.guarded { id -> withContext(Dispatchers.IO) { store.ensureAccount(id) } }
        }
        post("/economy/{guestId}/daily") {
            call.guarded { id ->
                val req = call.receiveLimited<DailyClaimRequest>()
                withContext(Dispatchers.IO) { store.claimDaily(id, req.today) }
            }
        }
        post("/economy/{guestId}/buy") {
            call.guarded { id ->
                val req = call.receiveLimited<BuyRequest>()
                if (req.key.isBlank() || req.key.length > EconomyLimits.MAX_KEY) {
                    throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Pedido inválido.")
                }
                withContext(Dispatchers.IO) { store.buy(id, req.offerIndex, req.key) }
            }
        }
        // §18.6: so conta (e-mail/Google) migra a carteira local, uma unica vez (o store recusa convidado e repetir).
        post("/economy/{guestId}/migrate") {
            call.guarded { id ->
                val req = call.receiveLimited<MigrateLocalWalletRequest>()
                withContext(Dispatchers.IO) { store.migrateLocalWallet(id, req.coins, req.inventory) }
            }
        }

        internalEconomyRoutes(deps)
        adminEconomyRoutes(deps)
    }
}

/**
 * Console de gerenciamento (admin-api), so LEITURA (§18.7): a carteira e o inventario de uma conta, sem criar
 * nada (nunca [EconomyStore.ensureAccount] aqui: um id de teste/erro do operador nao pode nascer com 1.000 moedas).
 * Segredo PROPRIO: ver [adminAllowed].
 */
private fun io.ktor.server.routing.Routing.adminEconomyRoutes(deps: EconomyDeps) {
    val store = deps.store
    get("/internal/admin/accounts/{guestId}/wallet") {
        if (!call.adminAllowed(deps.adminSecret)) return@get
        try {
            val id = call.parameters["guestId"].orEmpty()
            if (!GUEST_ID.matches(id)) throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Jogador inválido.")
            val state = withContext(Dispatchers.IO) { store.state(id) }
            call.respondJson(AdminWalletDto(state.balance, state.inventory, state.migratedLocal))
        } catch (e: HttpFail) {
            call.respondError(e.status, e.code, e.message.orEmpty())
        } catch (e: EconomyException) {
            call.respondError(e.status(), EconomyErrorCode.valueOf(e.code.name), e.message.orEmpty())
        }
    }
}

/** Confere o segredo servico a servico; responde o erro e devolve `false` se nao vale (sem segredo = rotas FECHADAS). */
private suspend fun ApplicationCall.internalAllowed(expected: String?): Boolean {
    val given = request.headers[INTERNAL_SECRET_HEADER] ?: ""
    when {
        expected == null -> respondError(503, EconomyErrorCode.INDISPONIVEL, "Rotas internas desativadas.")
        !MessageDigest.isEqual(expected.toByteArray(), given.toByteArray()) ->
            respondError(401, EconomyErrorCode.NAO_AUTENTICADO, "Não autorizado.")
        else -> return true
    }
    return false
}

/**
 * §18.7: confere o segredo do console de gerenciamento (admin-api). Cabecalho e valor PROPRIOS, diferentes de
 * [internalAllowed]/[INTERNAL_SECRET_HEADER]: nunca aceita o segredo servico a servico aqui, nem o contrario.
 */
private suspend fun ApplicationCall.adminAllowed(expected: String?): Boolean {
    val given = request.headers[ADMIN_SECRET_HEADER] ?: ""
    when {
        expected == null -> respondError(503, EconomyErrorCode.INDISPONIVEL, "Rotas administrativas desativadas.")
        !MessageDigest.isEqual(expected.toByteArray(), given.toByteArray()) ->
            respondError(401, EconomyErrorCode.NAO_AUTENTICADO, "Não autorizado.")
        else -> return true
    }
    return false
}

/**
 * Etapa 2.3, so trafego interno (lobby e game; o ingress expoe apenas /economy): saldo para sentar (§18.2), reter e
 * liquidar apostas (§14.6, §16.13, §18.4). Protegidas pelo segredo servico a servico, sem assinatura do app.
 */
private fun io.ktor.server.routing.Routing.internalEconomyRoutes(deps: EconomyDeps) {
    val store = deps.store
    suspend fun ApplicationCall.internal(block: suspend () -> Unit) {
        if (!internalAllowed(deps.internalSecret)) return
        try {
            block()
        } catch (e: HttpFail) {
            respondError(e.status, e.code, e.message.orEmpty())
        } catch (e: EconomyException) {
            respondError(e.status(), EconomyErrorCode.valueOf(e.code.name), e.message.orEmpty())
        }
    }
    fun ApplicationCall.betId(): String = parameters["betId"].orEmpty().takeIf { BET_ID.matches(it) }
        ?: throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Aposta inválida.")
    fun checkAccounts(ids: Collection<String>) {
        if (ids.isEmpty() || ids.size > 8 || ids.any { !GUEST_ID.matches(it) }) {
            throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Jogador inválido.")
        }
    }

    get("/internal/economy/accounts/{guestId}") {
        call.internal {
            val id = call.parameters["guestId"].orEmpty()
            checkAccounts(listOf(id))
            call.respondJson(withContext(Dispatchers.IO) { store.ensureAccount(id) }.toDto())
        }
    }
    // Plano 2.6 / §18.1 / §18.5: login (Google ou e-mail e senha, pelo lobby). Garante a carteira da conta, adotando a
    // do convidado se ela nao tinha.
    post("/internal/economy/accounts/{accountId}/adopt") {
        call.internal {
            val id = call.parameters["accountId"].orEmpty()
            checkAccounts(listOf(id))
            if (store.kindOf(id) == "CONVIDADO") throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Conta inválida.")
            val req = call.receiveLimited<AdoptWalletRequest>()
            req.guestId?.let { checkAccounts(listOf(it)) }
            val (migrated, state) = withContext(Dispatchers.IO) { store.adoptGuestWallet(id, req.guestId) }
            call.respondJson(AdoptWalletResponse(migrated, state.toDto()))
        }
    }
    post("/internal/economy/bets/{betId}/hold") {
        call.internal {
            val betId = call.betId()
            val req = call.receiveLimited<BetHoldRequest>()
            checkAccounts(req.accounts)
            val held = withContext(Dispatchers.IO) { store.holdBets(betId, req.amount, req.accounts) }
            call.respondJson(BetHoldResponse(req.accounts.filter { it in held }, req.accounts.filter { it !in held }))
        }
    }
    post("/internal/economy/bets/{betId}/settle") {
        call.internal {
            val betId = call.betId()
            val req = call.receiveLimited<BetSettleRequest>()
            checkAccounts(req.payouts.map { it.accountId })
            val (done, before) = withContext(Dispatchers.IO) { store.settleBets(betId, req.payouts) }
            call.respondJson(BetSettleResponse(done, before))
        }
    }
    // §17.3/§18.3: debita do inventario online os boosters escolhidos no inicio do jogo (game-service ja filtrou b3 e
    // skill-nao-permitido pela mesa; aqui so o catalogo e o estoque valem, nunca o que o cliente afirma ter escolhido).
    post("/internal/economy/matches/{matchId}/seats/{seat}/boosters") {
        call.internal {
            val matchId = call.parameters["matchId"].orEmpty().takeIf { BET_ID.matches(it) }
                ?: throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Partida inválida.")
            val seat = call.parameters["seat"]?.toIntOrNull()?.takeIf { it in 0..3 }
                ?: throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Assento inválido.")
            val req = call.receiveLimited<BoosterDebitRequest>()
            checkAccounts(listOf(req.accountId))
            if (req.boosterIds.size > Loadout.MAX) throw HttpFail(400, EconomyErrorCode.REQUISICAO_INVALIDA, "Boosters demais.")
            val applied = withContext(Dispatchers.IO) { store.debitBoosters(matchId, seat, req.accountId, req.boosterIds) }
            call.respondJson(BoosterDebitResponse(applied.toList()))
        }
    }
}

private val BET_ID = Regex("[A-Za-z0-9_-]{1,64}")
