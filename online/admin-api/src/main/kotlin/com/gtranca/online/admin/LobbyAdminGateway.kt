package com.gtranca.online.admin

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.AdminStatsDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** O lobby-service nao respondeu ou recusou uma leitura do console (Sec18.7): a tela nunca inventa dado. */
class LobbyAdminGatewayException(message: String) : RuntimeException(message)

/**
 * Visao de LEITURA do lobby-service para o console (Sec18.7, Painel e Contas): estatisticas, busca e ficha de
 * conta. Interface testavel sem rede (mesmo padrao do [EconomyGateway] no lobby-service e do [HealthGateway]
 * deste modulo); so [HttpLobbyAdminGateway] fala HTTP de verdade.
 */
interface LobbyAdminGateway {
    suspend fun stats(): AdminStatsDto
    suspend fun searchAccounts(query: String, limit: Int, offset: Int): AdminAccountsPage

    /** `null` = conta nao encontrada (404 tipado do lobby-service, Sec18.7: "nunca cria nem inventa"). */
    suspend fun accountDetail(id: String): AdminAccountDetail?
}

/**
 * Chama `GET /internal/admin/...` do lobby-service com o segredo e a versao PROPRIOS do console (Sec18.7):
 * [ADMIN_SECRET_HEADER], nunca o [com.gtranca.online.contracts.INTERNAL_SECRET_HEADER] servico a servico (esse
 * tambem abre rotas de escrita sensiveis, como o reset de senha); e [ADMIN_API_VERSION_HEADER], proprio deste
 * contrato, nunca o [com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION] do app.
 */
class HttpLobbyAdminGateway(
    private val baseUrl: String,
    private val secret: String,
    private val timeoutMs: Long = 5_000L,
) : LobbyAdminGateway {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }

    private suspend fun get(path: String): HttpResponse = try {
        client.get("${baseUrl.trimEnd('/')}$path") {
            header(ADMIN_SECRET_HEADER, secret)
            header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LobbyAdminGatewayException("lobby-service inacessível: ${e::class.simpleName}")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    override suspend fun stats(): AdminStatsDto {
        val r = get("/internal/admin/stats")
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminStatsDto.serializer(), r.bodyAsText())
    }

    override suspend fun searchAccounts(query: String, limit: Int, offset: Int): AdminAccountsPage {
        val r = get("/internal/admin/accounts?q=${encode(query)}&limit=$limit&offset=$offset")
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminAccountsPage.serializer(), r.bodyAsText())
    }

    override suspend fun accountDetail(id: String): AdminAccountDetail? {
        val r = get("/internal/admin/accounts/${encode(id)}")
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminAccountDetail.serializer(), r.bodyAsText())
    }
}
