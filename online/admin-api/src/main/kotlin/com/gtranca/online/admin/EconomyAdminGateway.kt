package com.gtranca.online.admin

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminAdjustRequest
import com.gtranca.online.contracts.AdminConfigFieldDto
import com.gtranca.online.contracts.AdminLedgerPage
import com.gtranca.online.contracts.AdminMigrationsPage
import com.gtranca.online.contracts.AdminShopConfigDto
import com.gtranca.online.contracts.AdminShopConfigHistoryPage
import com.gtranca.online.contracts.AdminShopGainAdjustRequest
import com.gtranca.online.contracts.AdminShopOfferAdjustRequest
import com.gtranca.online.contracts.AdminShopOfferConfigDto
import com.gtranca.online.contracts.AdminWalletDto
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** O economy-service nao respondeu ou recusou uma chamada do console por motivo de INFRAESTRUTURA (Sec18.7):
 * fora do ar, timeout, versao incompativel... Nunca um erro de negocio (ver [EconomyAdjustRejected]). */
class EconomyAdminGatewayException(message: String) : RuntimeException(message)

/**
 * O economy-service recusou o AJUSTE por regra de negocio (Sec18.7): [code] e a mesma [EconomyErrorCode] que
 * a rota devolveu (ex. SALDO_INSUFICIENTE se o ajuste deixaria o saldo/quantidade negativos, REQUISICAO_INVALIDA
 * se o booster nao existe). Isso NAO e "economy-service indisponivel": o operador precisa ver o motivo de
 * verdade, nunca um 503 generico.
 */
class EconomyAdjustRejected(val code: EconomyErrorCode, message: String) : RuntimeException(message)

/** Carteira, livro-razao e ajustes de uma conta, mais as migracoes globais, para a tela Economia (Sec18.7). */
interface EconomyAdminGateway {
    /** `null` = sem carteira (conta ainda nao existe no economy-service; nunca a cria, Sec18.7). */
    suspend fun wallet(id: String): AdminWalletDto?

    /** `null` = conta nao encontrada (404 tipado CONTA_INEXISTENTE do economy-service). */
    suspend fun ledger(id: String, limit: Int, offset: Int): AdminLedgerPage?

    /**
     * "Ajustar saldo ou booster" (Sec18.7): idempotente por [AdminAdjustRequest.operationId] (o economy-service
     * mesmo garante isso, com a chave do lancamento - repetir a MESMA chamada nunca ajusta duas vezes).
     * @throws EconomyAdjustRejected em erro de negocio (saldo insuficiente, booster invalido...).
     */
    suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto

    /** Migracoes de carteira local JA feitas, de TODAS as contas (Sec18.6/18.7) - lista global, nao por conta. */
    suspend fun migrations(limit: Int, offset: Int): AdminMigrationsPage

    /** Tela "Loja e configuracao" (Sec17.10/18.7): preco/quantidade atual das 9 ofertas e o ganho diario atual. */
    suspend fun shopConfig(): AdminShopConfigDto

    /** Historico de `config_loja` (TODOS os campos), mais recente primeiro. */
    suspend fun shopConfigHistory(limit: Int, offset: Int): AdminShopConfigHistoryPage

    /**
     * Muda o preco e/ou a quantidade da oferta [index] (so ADMINISTRADOR, ver rota).
     * @throws EconomyAdjustRejected oferta inexistente ou pedido invalido (preco/quantidade <= 0, nenhum dos dois).
     */
    suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest): AdminShopOfferConfigDto

    /**
     * Muda o ganho diario (so ADMINISTRADOR, ver rota).
     * @throws EconomyAdjustRejected valor <= 0.
     */
    suspend fun adjustDailyGain(request: AdminShopGainAdjustRequest): AdminConfigFieldDto
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

    private suspend fun get(path: String): HttpResponse = try {
        client.get("${baseUrl.trimEnd('/')}$path") {
            header(ADMIN_SECRET_HEADER, secret)
            header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EconomyAdminGatewayException("economy-service inacessível: ${e::class.simpleName}")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    override suspend fun wallet(id: String): AdminWalletDto? {
        val r = get("/internal/admin/accounts/${encode(id)}/wallet")
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminWalletDto.serializer(), r.bodyAsText())
    }

    override suspend fun ledger(id: String, limit: Int, offset: Int): AdminLedgerPage? {
        val r = get("/internal/admin/accounts/${encode(id)}/ledger?limit=$limit&offset=$offset")
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminLedgerPage.serializer(), r.bodyAsText())
    }

    override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto {
        val body = Json.encodeToString(AdminAdjustRequest.serializer(), request)
        val r = try {
            client.post("${baseUrl.trimEnd('/')}/internal/admin/accounts/${encode(id)}/adjust") {
                header(ADMIN_SECRET_HEADER, secret)
                header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyAdminGatewayException("economy-service inacessível: ${e::class.simpleName}")
        }
        // 400/409 sao recusa de NEGOCIO (motivo errado, saldo insuficiente, booster invalido): o corpo e um
        // EconomyError de verdade, repassa o code/mensagem pro operador, nunca um 503 generico. Se o corpo nao
        // for um EconomyError valido (resposta inesperada), nao decodificar sem rede - trata como infraestrutura.
        if (r.status.value == 400 || r.status.value == 409) {
            val err = try {
                Json.decodeFromString(EconomyError.serializer(), r.bodyAsText())
            } catch (e: Exception) {
                throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value} com corpo inesperado")
            }
            throw EconomyAdjustRejected(err.code, err.message)
        }
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminWalletDto.serializer(), r.bodyAsText())
    }

    override suspend fun migrations(limit: Int, offset: Int): AdminMigrationsPage {
        val r = get("/internal/admin/migrations?limit=$limit&offset=$offset")
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminMigrationsPage.serializer(), r.bodyAsText())
    }

    override suspend fun shopConfig(): AdminShopConfigDto {
        val r = get("/internal/admin/shop-config")
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminShopConfigDto.serializer(), r.bodyAsText())
    }

    override suspend fun shopConfigHistory(limit: Int, offset: Int): AdminShopConfigHistoryPage {
        val r = get("/internal/admin/shop-config/history?limit=$limit&offset=$offset")
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminShopConfigHistoryPage.serializer(), r.bodyAsText())
    }

    override suspend fun adjustOffer(index: Int, request: AdminShopOfferAdjustRequest): AdminShopOfferConfigDto {
        val body = Json.encodeToString(AdminShopOfferAdjustRequest.serializer(), request)
        val r = try {
            client.post("${baseUrl.trimEnd('/')}/internal/admin/shop-config/offer/$index") {
                header(ADMIN_SECRET_HEADER, secret)
                header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyAdminGatewayException("economy-service inacessível: ${e::class.simpleName}")
        }
        if (r.status.value == 400 || r.status.value == 409) {
            val err = try {
                Json.decodeFromString(EconomyError.serializer(), r.bodyAsText())
            } catch (e: Exception) {
                throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value} com corpo inesperado")
            }
            throw EconomyAdjustRejected(err.code, err.message)
        }
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminShopOfferConfigDto.serializer(), r.bodyAsText())
    }

    override suspend fun adjustDailyGain(request: AdminShopGainAdjustRequest): AdminConfigFieldDto {
        val body = Json.encodeToString(AdminShopGainAdjustRequest.serializer(), request)
        val r = try {
            client.post("${baseUrl.trimEnd('/')}/internal/admin/shop-config/daily-gain") {
                header(ADMIN_SECRET_HEADER, secret)
                header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyAdminGatewayException("economy-service inacessível: ${e::class.simpleName}")
        }
        if (r.status.value == 400 || r.status.value == 409) {
            val err = try {
                Json.decodeFromString(EconomyError.serializer(), r.bodyAsText())
            } catch (e: Exception) {
                throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value} com corpo inesperado")
            }
            throw EconomyAdjustRejected(err.code, err.message)
        }
        if (r.status.value != 200) throw EconomyAdminGatewayException("economy-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminConfigFieldDto.serializer(), r.bodyAsText())
    }
}
