package com.gtranca.online.admin

import com.gtranca.online.contracts.Health
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Um servico a sondar no Painel (Sec18.7): [name] e o rotulo na tela, [url] e o `/health` dele. */
data class HealthTarget(val name: String, val url: String)

/**
 * Sonda [target] e classifica o estado para o Painel. Interface (como `EconomyGateway` no lobby-service) para
 * testar sem rede de verdade; so a implementacao HTTP de fato chama outro servico.
 */
fun interface HealthGateway {
    suspend fun check(target: HealthTarget): ServiceHealthInfo
}

/**
 * Classifica o estado de um servico que respondeu (sem exception/timeout, isso e sempre VERMELHO em [HttpHealthGateway]):
 * vermelho se o HTTP nao foi 200; amarelo se o corpo nao disse "UP" OU a latencia passou do limiar; verde senao.
 * Funcao pura (sem rede) para poder testar as quatro combinacoes sem HTTP de verdade.
 */
fun classifyHealth(httpStatus: Int, bodyUp: Boolean, latencyMs: Long, yellowAboveMs: Long): ServiceStatusLevel = when {
    httpStatus != 200 -> ServiceStatusLevel.VERMELHO
    !bodyUp || latencyMs > yellowAboveMs -> ServiceStatusLevel.AMARELO
    else -> ServiceStatusLevel.VERDE
}

/**
 * Chama o `/health` do servico por HTTP simples (sem segredo servico-a-servico: `/health` e publico em todo
 * servico do online, ver lobby/game-service Main.kt). `timeoutMs`/`yellowAboveMs` vem de
 * ADMIN_HEALTH_TIMEOUT_MS/ADMIN_HEALTH_YELLOW_MS em Main.kt (padrao 2000/500 se ausentes).
 */
class HttpHealthGateway(
    private val timeoutMs: Long = 2_000L,
    private val yellowAboveMs: Long = 500L,
) : HealthGateway {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }

    override suspend fun check(target: HealthTarget): ServiceHealthInfo {
        val startNanos = System.nanoTime()
        return try {
            val response = client.get(target.url)
            val latencyMs = (System.nanoTime() - startNanos) / 1_000_000
            val bodyUp = try {
                Json.decodeFromString(Health.serializer(), response.bodyAsText()).status == "UP"
            } catch (e: Exception) {
                false
            }
            ServiceHealthInfo(target.name, classifyHealth(response.status.value, bodyUp, latencyMs, yellowAboveMs), latencyMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServiceHealthInfo(target.name, ServiceStatusLevel.VERMELHO, null)
        }
    }
}
