package com.gtranca.online.game

import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.MatchEndedNotice
import com.gtranca.online.contracts.OnlineJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/** O lobby nao registrou o fim do jogo (fora do ar, segredo errado...). */
class LobbyNotifyException(message: String) : RuntimeException(message)

/** Avisa o lobby que o jogo terminou ou foi encerrado, para a mesa virar ENCERRADA (§18.4). */
fun interface LobbyNotifier {
    /** Lanca [LobbyNotifyException] se nao deu certo (o chamador tenta de novo). Mesa desconhecida (404) conta como ok. */
    suspend fun matchEnded(tableId: String, reason: GameEndReason)

    companion object {
        val None = LobbyNotifier { _, _ -> }
    }
}

/** HTTP interno com o segredo compartilhado (variavel de ambiente/Secret; nunca no codigo). */
class HttpLobbyNotifier(private val baseUrl: String, private val secret: String?) : LobbyNotifier {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun matchEnded(tableId: String, reason: GameEndReason) {
        if (secret.isNullOrBlank()) throw LobbyNotifyException("segredo interno nao configurado")
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/internal/tables/$tableId/ended") {
                header(INTERNAL_SECRET_HEADER, secret)
                contentType(ContentType.Application.Json)
                setBody(OnlineJson.encodeToString(MatchEndedNotice.serializer(), MatchEndedNotice(reason)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LobbyNotifyException("lobby inacessivel: ${e::class.simpleName}")
        }
        val code = response.status.value
        if (code !in 200..299 && code != 404) throw LobbyNotifyException("lobby respondeu $code")
    }
}
