package com.gtranca.online.game

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

/** O lobby nao respondeu como esperado (fora do ar, segredo errado...). */
class LobbyUnavailableException(message: String) : RuntimeException(message)

/** Descobre de quem e um token de convidado (o lobby e o dono dos convidados). */
fun interface GuestResolver {
    /** `null` = token invalido/expirado. Lanca [LobbyUnavailableException] se o lobby nao responde. */
    suspend fun resolve(token: String): ResolvedGuest?
}

/** Introspeccao via HTTP INTERNO do cluster, protegida pelo segredo compartilhado. */
class HttpGuestResolver(private val baseUrl: String, private val secret: String?) : GuestResolver {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun resolve(token: String): ResolvedGuest? {
        if (secret.isNullOrBlank()) throw LobbyUnavailableException("segredo interno nao configurado")
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/internal/guests/resolve") {
                header(INTERNAL_SECRET_HEADER, secret)
                contentType(ContentType.Application.Json)
                setBody(OnlineJson.encodeToString(ResolveGuestRequest.serializer(), ResolveGuestRequest(token)))
            }
        } catch (e: Exception) {
            throw LobbyUnavailableException("lobby inacessivel: ${e::class.simpleName}")
        }
        return when (response.status) {
            HttpStatusCode.OK -> OnlineJson.decodeFromString(ResolvedGuest.serializer(), response.bodyAsText())
            HttpStatusCode.NotFound -> null
            else -> throw LobbyUnavailableException("lobby respondeu ${response.status.value}")
        }
    }
}
