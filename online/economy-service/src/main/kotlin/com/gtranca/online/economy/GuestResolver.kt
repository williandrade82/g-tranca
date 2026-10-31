package com.gtranca.online.economy

import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.ResolveGuestRequest
import com.gtranca.online.contracts.ResolvedGuest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** O lobby nao respondeu como esperado: a economia recusa (falha fechada). */
class LobbyUnavailableException(message: String) : RuntimeException(message)

/** De quem e o token do jogador? O lobby e o dono dos convidados; a economia nao guarda segredo de token. */
fun interface GuestResolver {
    /** Id do convidado, ou `null` se o token e invalido/expirado. Lanca [LobbyUnavailableException] se o lobby nao responde. */
    suspend fun resolve(token: String): String?
}

/** `/internal/guests/resolve` do lobby, com o segredo servico a servico (o mesmo Secret do lobby e do game). */
class HttpGuestResolver(private val baseUrl: String, private val secret: String) : GuestResolver {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun resolve(token: String): String? {
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/internal/guests/resolve") {
                header(INTERNAL_SECRET_HEADER, secret)
                contentType(ContentType.Application.Json)
                setBody(OnlineJson.encodeToString(ResolveGuestRequest.serializer(), ResolveGuestRequest(token)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LobbyUnavailableException("lobby inacessivel: ${e::class.simpleName}")
        }
        return when (response.status) {
            HttpStatusCode.OK -> OnlineJson.decodeFromString(ResolvedGuest.serializer(), response.bodyAsText()).guestId
            HttpStatusCode.NotFound -> null
            else -> throw LobbyUnavailableException("lobby respondeu ${response.status.value}")
        }
    }
}

/** Guarda por pouco tempo o dono de cada token (pelo hash; o token nunca fica em claro), para nao consultar o lobby a cada pedido. */
class CachedGuestResolver(
    private val inner: GuestResolver,
    private val ttlMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : GuestResolver {
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    override suspend fun resolve(token: String): String? {
        val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        val now = clock()
        cache[key]?.let { (id, at) -> if (now - at < ttlMs) return id }
        val id = inner.resolve(token) ?: return null.also { cache.remove(key) }
        if (cache.size > 10_000) cache.clear()
        cache[key] = id to now
        return id
    }
}
