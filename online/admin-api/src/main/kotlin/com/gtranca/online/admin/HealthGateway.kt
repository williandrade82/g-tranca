package com.gtranca.online.admin

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException

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
 * Chama o `/health` do servico por HTTP simples (sem segredo servico-a-servico: `/health` e publico em todo
 * servico do online, ver lobby/game-service Main.kt). Verde = respondeu 200 dentro do limiar; amarelo = respondeu
 * mas lento ou com um corpo inesperado; vermelho = erro ou expirou o tempo. Limiares provisorios, a confirmar com
 * o usuario (ver relatorio da etapa).
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
            val status = when {
                response.status.value != 200 -> ServiceStatusLevel.VERMELHO
                latencyMs > yellowAboveMs -> ServiceStatusLevel.AMARELO
                else -> ServiceStatusLevel.VERDE
            }
            ServiceHealthInfo(target.name, status, latencyMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServiceHealthInfo(target.name, ServiceStatusLevel.VERMELHO, null)
        }
    }
}
