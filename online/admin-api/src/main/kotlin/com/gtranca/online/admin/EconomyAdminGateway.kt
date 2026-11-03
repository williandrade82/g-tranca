package com.gtranca.online.admin

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminWalletDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** O economy-service nao respondeu ou recusou uma leitura do console (Sec18.7). */
class EconomyAdminGatewayException(message: String) : RuntimeException(message)

/** Carteira e inventario de uma conta, so LEITURA, para a tela Contas (Sec18.7). */
fun interface EconomyAdminGateway {
    /** `null` = sem carteira (conta ainda nao existe no economy-service; nunca a cria, Sec18.7). */
    suspend fun wallet(id: String): AdminWalletDto?
}

/** Mesmo esquema de segredo/versao do [HttpLobbyAdminGateway] (segredo e versao PROPRIOS do console). */
class HttpEconomyAdminGateway(
    private val baseUrl: String,
    private val secret: String,
    private val timeoutMs: Long = 5_000L,
) : EconomyAdminGateway {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }

    override suspend fun wallet(id: String): AdminWalletDto? {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val r = try {
            client.get("${baseUrl.trimEnd('/')}/internal/admin/accounts/$encodedId/wallet") {
                header(ADMIN_SECRET_HEADER, secret)
                header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyAdminGatewayException("economy-service inacessível: ${e::class.simpleName}")
        }
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminWalletDto.serializer(), r.bodyAsText())
    }
}
