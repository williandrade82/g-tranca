package com.gtranca.online.admin

import com.gtranca.online.contracts.ADMIN_API_VERSION
import com.gtranca.online.contracts.ADMIN_API_VERSION_HEADER
import com.gtranca.online.contracts.ADMIN_SECRET_HEADER
import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.AdminBlockRequest
import com.gtranca.online.contracts.AdminCloseTableRequest
import com.gtranca.online.contracts.AdminRevokeSessionsResponse
import com.gtranca.online.contracts.AdminStatsDto
import com.gtranca.online.contracts.AdminTablesPage
import com.gtranca.online.contracts.TableView
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

    /**
     * "Bloquear conta" (Sec18.7, definition.md decisao de 03/11/2026): impede logar de novo e entrar em mesa
     * nova; NAO ejeta de partida em andamento nem invalida tokens ja emitidos (isso e [revokeSessions], acao
     * independente). Idempotente: bloquear de novo uma conta ja bloqueada nao e erro. [reason] e so pro log do
     * lobby-service; a auditoria de verdade e do admin-api (ver [AuditLog]).
     */
    suspend fun block(id: String, reason: String?)

    /** Reverte [block], sem efeito retroativo. Idempotente. */
    suspend fun unblock(id: String)

    /**
     * "Encerrar sessões": invalida os tokens vivos da conta, SEM bloquear logins futuros (acao independente de
     * [block]/[unblock]). @return quantos tokens foram invalidados agora (0 = a conta nao tinha sessao viva).
     */
    suspend fun revokeSessions(id: String): Int

    /** Tela Mesas (Sec18.7): TODO estado (ao contrario da lista publica do app, que nunca mostra ENCERRADA). */
    suspend fun tables(limit: Int, offset: Int): AdminTablesPage

    /** `null` = mesa nao encontrada (404 tipado MESA_INEXISTENTE do lobby-service). */
    suspend fun tableDetail(id: String): TableView?

    /**
     * "Encerrar mesa travada" (Sec18.7, definition.md Sec18.4): idempotente em qualquer estado. Mesa ABERTA ou
     * ja ENCERRADA e trivial; mesa EM_JOGO devolve a aposta a TODOS os jogadores (ninguem e punido) e avisa os
     * conectados - isso acontece no lobby-service, aqui so repassa o pedido. `null` = mesa nao encontrada.
     */
    suspend fun closeTable(id: String, reason: String?): TableView?
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

    private suspend fun post(path: String, body: String? = null): HttpResponse = try {
        client.post("${baseUrl.trimEnd('/')}$path") {
            header(ADMIN_SECRET_HEADER, secret)
            header(ADMIN_API_VERSION_HEADER, ADMIN_API_VERSION)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
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

    override suspend fun block(id: String, reason: String?) {
        val body = Json.encodeToString(AdminBlockRequest.serializer(), AdminBlockRequest(reason))
        val r = post("/internal/admin/accounts/${encode(id)}/block", body)
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
    }

    override suspend fun unblock(id: String) {
        val r = post("/internal/admin/accounts/${encode(id)}/unblock")
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
    }

    override suspend fun revokeSessions(id: String): Int {
        val r = post("/internal/admin/accounts/${encode(id)}/revoke-sessions")
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminRevokeSessionsResponse.serializer(), r.bodyAsText()).revoked
    }

    override suspend fun tables(limit: Int, offset: Int): AdminTablesPage {
        val r = get("/internal/admin/tables?limit=$limit&offset=$offset")
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(AdminTablesPage.serializer(), r.bodyAsText())
    }

    override suspend fun tableDetail(id: String): TableView? {
        val r = get("/internal/admin/tables/${encode(id)}")
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(TableView.serializer(), r.bodyAsText())
    }

    override suspend fun closeTable(id: String, reason: String?): TableView? {
        val body = Json.encodeToString(AdminCloseTableRequest.serializer(), AdminCloseTableRequest(reason))
        val r = post("/internal/admin/tables/${encode(id)}/close", body)
        if (r.status == HttpStatusCode.NotFound) return null
        if (r.status.value != 200) throw LobbyAdminGatewayException("lobby-service respondeu ${r.status.value}")
        return Json.decodeFromString(TableView.serializer(), r.bodyAsText())
    }
}
