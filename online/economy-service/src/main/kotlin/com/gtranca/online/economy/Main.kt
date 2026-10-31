package com.gtranca.online.economy

import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.BuyRequest
import com.gtranca.online.contracts.DailyClaimRequest
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
    Database.migrate(db)
    val appKey = System.getenv("APP_SIGNING_KEY")?.takeIf { it.isNotBlank() }
        ?: null.also { System.err.println("AVISO: APP_SIGNING_KEY ausente; a economia aceita pedidos sem assinatura do app") }
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        economyModule(EconomyDeps(EconomyStore(db), appKey))
    }.start(wait = true)
}

class EconomyDeps(
    val store: EconomyStore,
    /** Chave do aplicativo (§18.2). `null` = sem verificacao (so desenvolvimento). */
    val appKey: String? = null,
    val clock: () -> Long = System::currentTimeMillis,
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

private fun EconomyState.toDto() = EconomyStateDto(balance, inventory, lastDailyClaim)

private fun EconomyException.status(): Int = when (code) {
    EconomyException.Code.SALDO_INSUFICIENTE, EconomyException.Code.JA_RESGATADO -> 409
    EconomyException.Code.CONTA_INEXISTENTE -> 404
    EconomyException.Code.OFERTA_INEXISTENTE -> 400
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

        // Etapa 2.3: /internal/economy/* (reter e liquidar apostas, §14.6), so trafego interno do game-service.
    }
}
