package com.gtranca.online.lobby

import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
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

/** O game-service nao criou a partida. [status] e o HTTP devolvido (`null` = nao respondeu: rede, tempo, segredo). */
class GameGatewayException(val status: Int?, message: String) : RuntimeException(message)

/** Passagem da mesa ao game-service (§18.3), pela rede INTERNA do cluster. */
fun interface GameGateway {
    /** Cria (ou confirma, se ja existe) a partida. Lanca [GameGatewayException] se nao deu certo. */
    suspend fun createMatch(request: CreateMatchRequest)
}

/** HTTP interno com o segredo compartilhado (variavel de ambiente/Secret; nunca no codigo). */
class HttpGameGateway(private val baseUrl: String, private val secret: String) : GameGateway {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun createMatch(request: CreateMatchRequest) {
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/internal/matches") {
                header(INTERNAL_SECRET_HEADER, secret)
                contentType(ContentType.Application.Json)
                setBody(OnlineJson.encodeToString(CreateMatchRequest.serializer(), request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GameGatewayException(null, "game-service inacessivel: ${e::class.simpleName}")
        }
        if (response.status.value !in 200..299) throw GameGatewayException(response.status.value, "game-service respondeu ${response.status.value}")
    }
}
