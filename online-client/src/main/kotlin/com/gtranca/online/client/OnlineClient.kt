package com.gtranca.online.client

import com.gtranca.online.contracts.AccountLogin
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.BuyRequest
import com.gtranca.online.contracts.DailyClaimRequest
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyLimits
import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.GoogleLoginRequest
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.JoinRequest
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.PassHostRequest
import com.gtranca.online.contracts.GuestPhoto
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableListView
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
    /** Chave do aplicativo (§18.2): assina cada pedido. `null` = sem assinatura (servidor de desenvolvimento). */
    private val appKey: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : LobbyApi, EconomyApi {

    override suspend fun createGuest(name: String): GuestIdentity = call(
        GuestIdentity.serializer(), null, HttpMethod.Post, "guests",
        OnlineJson.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest(name)),
    )

    override suspend fun loginGoogle(idToken: String, guestToken: String?): AccountLogin = call(
        AccountLogin.serializer(), null, HttpMethod.Post, "accounts/google",
        OnlineJson.encodeToString(GoogleLoginRequest.serializer(), GoogleLoginRequest(idToken, guestToken)),
    )

    override suspend fun createTable(token: String, rules: TableRules): TableView = call(
        TableView.serializer(), token, HttpMethod.Post, "tables",
        OnlineJson.encodeToString(CreateTableRequest.serializer(), CreateTableRequest(rules)),
    )

    override suspend fun tables(token: String): TableListView =
        call(TableListView.serializer(), token, HttpMethod.Get, "tables")

    override suspend fun passHost(token: String, tableId: String, seat: Int): TableView = call(
        TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/host",
        OnlineJson.encodeToString(PassHostRequest.serializer(), PassHostRequest(seat)),
    )

    override suspend fun move(token: String, tableId: String, seat: Int): TableView = call(
        TableView.serializer(), token, HttpMethod.Post, "tables/${idOf(tableId)}/move",
        OnlineJson.encodeToString(JoinRequest.serializer(), JoinRequest(seat)),
    )

    override suspend fun setPhoto(token: String, photoBase64: String?) {
        call(com.gtranca.online.contracts.Health.serializer(), token, HttpMethod.Put, "guests/me/photo",
            OnlineJson.encodeToString(GuestPhoto.serializer(), GuestPhoto(photoBase64)))
    }

    override suspend fun photo(token: String, photoUrl: String): String? {
        // So caminhos do proprio lobby (nunca uma URL de fora): /api/lobby/guests/{id}/photo
        val path = photoUrl.substringBefore('?').removePrefix("/api/lobby/")
        require(path.startsWith("guests/") && path.endsWith("/photo") && path.count { it == '/' } == 2) { "caminho de foto invalido" }
        return try {
            call(GuestPhoto.serializer(), token, HttpMethod.Get, path).photoBase64
        } catch (e: OnlineException) {
            if (e.status == 404) null else throw e
        }
    }

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

    // ---------------------------------------------------------------------------------------------- EconomyApi

    override suspend fun economyState(token: String, accountId: String): EconomyStateDto =
        economyCall(EconomyStateDto.serializer(), token, HttpMethod.Get, "economy/${accountIdOf(accountId)}")

    override suspend fun claimDaily(token: String, accountId: String, today: Long): EconomyStateDto = economyCall(
        EconomyStateDto.serializer(), token, HttpMethod.Post, "economy/${accountIdOf(accountId)}/daily",
        OnlineJson.encodeToString(DailyClaimRequest.serializer(), DailyClaimRequest(today)),
    )

    override suspend fun buyOffer(token: String, accountId: String, offerIndex: Int, key: String): EconomyStateDto {
        require(key.isNotBlank() && key.length <= EconomyLimits.MAX_KEY) { "chave de compra invalida" }
        return economyCall(
            EconomyStateDto.serializer(), token, HttpMethod.Post, "economy/${accountIdOf(accountId)}/buy",
            OnlineJson.encodeToString(BuyRequest.serializer(), BuyRequest(offerIndex, key)),
        )
    }

    /** Confere o formato antes de montar o caminho (o mesmo que o economy-service aceita): nunca uma URL. */
    private fun accountIdOf(accountId: String): String {
        require(ACCOUNT_ID.matches(accountId)) { "id de conta invalido" }
        return accountId
    }

    /** Conexão reconectável à partida da mesa [tableId] (`/ws/match/{id}`), com o token do convidado. */
    fun matchSession(token: String, tableId: String, policy: ReconnectPolicy = ReconnectPolicy()): MatchSession =
        MatchSession(this, token, idOf(tableId), policy)

    /** Fecha o HttpClient (as conexões abertas caem). */
    fun shutdown() = http.close()

    private fun idOf(tableId: String) = TableId.normalize(tableId) ?: tableId.trim()

    internal fun HttpRequestBuilder.protocol(token: String?, method: HttpMethod, path: String) {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        val key = appKey ?: return
        val now = clock()
        header(AppSignature.TIME_HEADER, now.toString())
        header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(key, method.value, path, now, token))
    }

    private suspend fun <T> call(
        serializer: KSerializer<T>,
        token: String?,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): T = request(serializer, token, method, "api/lobby/$path", body, ::failure)

    /** [path] já sem o prefixo (ex.: `"economy/g1/buy"`): a economia (§18.5) fica atrás do mesmo host, sem `/api/lobby`. */
    private suspend fun <T> economyCall(
        serializer: KSerializer<T>,
        token: String,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): T = request(serializer, token, method, path, body, ::economyFailure)

    private suspend fun <T> request(
        serializer: KSerializer<T>,
        token: String?,
        method: HttpMethod,
        fullPath: String,
        body: String?,
        decodeFailure: (Int, String) -> OnlineException,
    ): T {
        val response = try {
            withTimeout(requestTimeoutMs) {
                http.request("${endpoint.httpBase}/$fullPath") {
                    this.method = method
                    protocol(token, method, "/$fullPath")
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
        if (response.status.value !in 200..299) throw decodeFailure(response.status.value, text)
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

    /** Corpo das rotas `/economy/...` (§18.5): `EconomyError`, nunca `ApiError` (são serviços diferentes). */
    internal fun economyFailure(status: Int, body: String): OnlineException {
        val error = runCatching { OnlineJson.decodeFromString(EconomyError.serializer(), body) }.getOrNull()
        return OnlineException.fromEconomyResponse(status, error)
    }

    companion object {
        private val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{1,${EconomyLimits.MAX_GUEST_ID}}")

        /** HttpClient de produção (OkHttp: respeita a política de tráfego em texto puro do Android) com WebSocket. */
        fun newHttpClient(engine: HttpClientEngine? = null): HttpClient =
            if (engine == null) {
                HttpClient(OkHttp) { install(WebSockets) }
            } else {
                HttpClient(engine) { install(WebSockets) }
            }
    }
}
