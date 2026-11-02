package com.gtranca.online.game

import com.gtranca.online.contracts.BetHoldRequest
import com.gtranca.online.contracts.BetHoldResponse
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.BetSettleRequest
import com.gtranca.online.contracts.BoosterDebitRequest
import com.gtranca.online.contracts.BoosterDebitResponse
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.INTERNAL_SECRET_HEADER
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PayoutKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/** A economia nao respondeu como esperado. [permanent] = recusou o pedido (4xx): repetir nao adianta. */
class EconomyUnavailableException(message: String, val permanent: Boolean = false) : RuntimeException(message)

/** Apostas no economy-service (§18.2 retida ao iniciar; §14.6/§16.13/§18.4 liquidada no fim). Ambas idempotentes por [betId]. */
interface EconomyClient {
    /** @return as contas com aposta retida (quem nao tem saldo fica de fora, §14.6). */
    suspend fun hold(betId: String, amount: Long, accounts: List<String>): Set<String>

    suspend fun settle(betId: String, payouts: List<BetPayout>)

    /**
     * §17.3/§18.3: debita do inventario online de [accountId] cada booster de [boosterIds] (ja sanitizados contra o
     * catalogo, a mesa e b3 por quem chama), no inicio do jogo [matchId] (assento [seat]). Idempotente por (conta,
     * matchId, assento, booster).
     * @return os boosters que realmente foram debitados (o que nao tinha estoque fica de fora, §17.3).
     */
    suspend fun debitBoosters(matchId: String, seat: Int, accountId: String, boosterIds: List<String>): Set<String>
}

class HttpEconomyClient(private val baseUrl: String, private val secret: String?) : EconomyClient {
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 2_000
            }
        }
    }

    override suspend fun hold(betId: String, amount: Long, accounts: List<String>): Set<String> {
        val body = post("/internal/economy/bets/$betId/hold", OnlineJson.encodeToString(BetHoldRequest.serializer(), BetHoldRequest(amount, accounts)))
        return OnlineJson.decodeFromString(BetHoldResponse.serializer(), body).held.toSet()
    }

    override suspend fun settle(betId: String, payouts: List<BetPayout>) {
        post("/internal/economy/bets/$betId/settle", OnlineJson.encodeToString(BetSettleRequest.serializer(), BetSettleRequest(payouts)))
    }

    override suspend fun debitBoosters(matchId: String, seat: Int, accountId: String, boosterIds: List<String>): Set<String> {
        val body = post(
            "/internal/economy/matches/$matchId/seats/$seat/boosters",
            OnlineJson.encodeToString(BoosterDebitRequest.serializer(), BoosterDebitRequest(accountId, boosterIds)),
        )
        return OnlineJson.decodeFromString(BoosterDebitResponse.serializer(), body).applied.toSet()
    }

    private suspend fun post(path: String, body: String): String {
        if (secret.isNullOrBlank()) throw EconomyUnavailableException("segredo interno nao configurado")
        val response = try {
            client.post("${baseUrl.trimEnd('/')}$path") {
                header(INTERNAL_SECRET_HEADER, secret)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EconomyUnavailableException("economia inacessivel: ${e::class.simpleName}")
        }
        val code = response.status.value
        if (code !in 200..299) throw EconomyUnavailableException("economia respondeu $code", permanent = code in 400..499 && code != 408 && code != 429)
        return response.bodyAsText()
    }
}

/**
 * Quanto cada humano com aposta retida ([held]) recebe no fim do jogo. Funcao pura, sem rede.
 * - §18.4 quem abandonou: a punicao ja decidida no [AbandonEvent] (`betRefunded`: devolve; senao perde). Vale em
 *   qualquer fim (o resultado do abandono nao muda depois).
 * - §18.4 mesa encerrada pelo anfitriao: os demais recebem a aposta de volta. Todos desconectados: todos devolvidos.
 * - §14.6/§16.13 fim por pontuacao ou por abandono (1×1, ultimo humano): o pote (aposta × assentos, robos apostam
 *   virtualmente) e dividido igualmente entre os assentos vencedores; o humano vencedor recebe a parte do assento dele;
 *   a parte de robo (de origem ou substituto) se perde. Perdedor: perde a aposta.
 * - Fim por erro do servidor (nao previsto na regra): devolve a todos (nunca se perde por falha nossa).
 */
fun settleBets(end: MatchEnd, held: Set<String>, bet: Long): List<BetPayout> {
    val events = end.abandonments.associateBy { it.guestId }
    val winners = end.winnerSide?.let { w -> end.seatSides.count { it == w } } ?: 0
    val share = if (winners > 0) bet * end.seatSides.size / winners else 0L
    return end.humanSeats.entries.sortedBy { it.value }.filter { it.key in held }.map { (guestId, seat) ->
        val ev = events[guestId]
        val refund = BetPayout(guestId, PayoutKind.DEVOLUCAO, bet)
        val lost = BetPayout(guestId, PayoutKind.PERDA, 0)
        when {
            ev != null -> if (ev.betRefunded) refund else lost
            end.reason == GameEndReason.PONTUACAO || end.reason == GameEndReason.ABANDONO -> when (end.winnerSide) {
                null -> refund
                end.seatSides[seat] -> BetPayout(guestId, PayoutKind.PREMIO, share)
                else -> lost
            }
            else -> refund // ANFITRIAO_ENCERROU (os demais), TODOS_DESCONECTADOS, ERRO, INATIVIDADE
        }
    }
}
