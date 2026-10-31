package com.gtranca.online.lobby

import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.OnlineJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException

/** A economia nao respondeu: o lobby recusa sentar (falha fechada). */
class EconomyGatewayException(message: String) : RuntimeException(message)

/** Saldo online do convidado (§18.2: para sentar e preciso saldo >= aposta da mesa). */
fun interface EconomyGateway {
    /** Cria a carteira se ainda nao existe (§18.5). Lanca [EconomyGatewayException] se nao deu para saber. */
    suspend fun balance(guestId: String): Long
}

class HttpEconomyGateway(private val baseUrl: String, private val secret: String) : EconomyGateway {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun balance(guestId: String): Long {
        val response = try {
            client.get("${baseUrl.trimEnd('/')}/internal/economy/accounts/$guestId") { header(INTERNAL_SECRET_HEADER, secret) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyGatewayException("economia inacessivel: ${e::class.simpleName}")
        }
        if (response.status.value != 200) throw EconomyGatewayException("economia respondeu ${response.status.value}")
        return OnlineJson.decodeFromString(EconomyStateDto.serializer(), response.bodyAsText()).balance
    }
}
