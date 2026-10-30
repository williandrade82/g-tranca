package com.gtranca.online.client

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.JoinRequest
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableView
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * Cliente do lobby (HTTP) e da partida (WebSocket) do jogo online (§18). Toda chamada leva `X-Protocol-Version` (§18.8);
 * depois do convidado, `Authorization: Bearer <token>`. O [http] precisa ter o plugin `WebSockets` ([newHttpClient]).
 */
class OnlineClient(
    val endpoint: OnlineEndpoint,
    internal val http: HttpClient,
    private val requestTimeoutMs: Long = 15_000,
) : LobbyApi {

    override suspend fun createGuest(name: String): GuestIdentity = call(
        GuestIdentity.serializer(), null, HttpMethod.Post, "guests",
        OnlineJson.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest(name)),
    )

    override suspend fun createTable(token: String, rules: TableRules): TableView = call(
        TableView.serializer(), token, HttpMethod.Post, "tables",
        OnlineJson.encodeToString(CreateTableRequest.serializer(), CreateTableRequest(rules)),
    )

    override suspend fun table(token: String, tableId: String): TableView =
        call(TableView.serializer(), token, HttpMethod.Get, "tables/${idOf(tableId)}")

    override suspend fun join(token: String, tableId: String, seat: Int?): TableView = call(
        TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/join",
        OnlineJson.encodeToString(JoinRequest.serializer(), JoinRequest(seat)),
    )

    override suspend fun leave(token: String, tableId: String): TableView =
        call(TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/leave", "{}")

    override suspend fun seatRobot(token: String, tableId: String, seat: Int): TableView =
        call(TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/seats/$seat/robot", "{}")

    override suspend fun removeHuman(token: String, tableId: String, seat: Int): TableView =
        call(TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/seats/$seat/remove", "{}")

    override suspend fun start(token: String, tableId: String): TableView =
        call(TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/start", "{}")

    override suspend fun close(token: String, tableId: String): TableView =
        call(TableView.serializer(), token, HttpMethod.Delete, "tables/${idOf(tableId)}")

    /** Conexão reconectável à partida da mesa [tableId] (`/ws/match/{id}`), com o token do convidado. */
    fun matchSession(token: String, tableId: String, policy: ReconnectPolicy = ReconnectPolicy()): MatchSession =
        MatchSession(this, token, idOf(tableId), policy)

    /** Fecha o HttpClient (as conexões abertas caem). */
    fun shutdown() = http.close()

    private fun idOf(tableId: String) = TableId.normalize(tableId) ?: tableId.trim()

    internal fun HttpRequestBuilder.protocol(token: String?) {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun <T> call(
        serializer: KSerializer<T>,
        token: String?,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): T {
        val response = try {
            withTimeout(requestTimeoutMs) {
                http.request("${endpoint.httpBase}/api/lobby/$path") {
                    this.method = method
                    protocol(token)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw OnlineException(OnlineErrorKind.NETWORK, cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineException(OnlineErrorKind.NETWORK, cause = e)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OnlineException(OnlineErrorKind.NETWORK, cause = e)
        }
        if (response.status.value !in 200..299) throw failure(response.status.value, text)
        return try {
            OnlineJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            // Resposta que não é do jogo (ex.: página de um proxy): tratada como rede/servidor errado.
            throw OnlineException(OnlineErrorKind.NETWORK, status = response.status.value, cause = e)
        }
    }

    internal fun failure(status: Int, body: String): OnlineException {
        val api = runCatching { OnlineJson.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return OnlineException.fromResponse(status, api)
    }

    companion object {
        /** HttpClient de produção (OkHttp: respeita a política de tráfego em texto puro do Android) com WebSocket. */
        fun newHttpClient(engine: HttpClientEngine? = null): HttpClient =
            if (engine == null) {
                HttpClient(OkHttp) { install(WebSockets) }
            } else {
                HttpClient(engine) { install(WebSockets) }
            }
    }
}
