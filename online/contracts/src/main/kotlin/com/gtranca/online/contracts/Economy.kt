package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/** §18.5 economia online: contratos JSON do economy-service (rotas publicas /economy/...). */

/** Carteira e inventario da conta. [lastDailyClaim] em dias desde a epoca (nulo = nunca resgatou). */
@Serializable
data class EconomyStateDto(val balance: Long, val inventory: Map<String, Int>, val lastDailyClaim: Long? = null)

/** POST /economy/{guestId}/daily: o dia de hoje no fuso do jogador, em dias desde a epoca (§14.6). */
@Serializable
data class DailyClaimRequest(val today: Long)

/** POST /economy/{guestId}/buy: oferta da loja (§17.10) e chave do pedido (repetir a chave nao cobra de novo). */
@Serializable
data class BuyRequest(val offerIndex: Int, val key: String)

@Serializable
enum class EconomyErrorCode {
    SALDO_INSUFICIENTE, JA_RESGATADO, OFERTA_INEXISTENTE, CONTA_INEXISTENTE, REQUISICAO_INVALIDA, APP_NAO_RECONHECIDO,

    /** Sem token do jogador, token invalido (401) ou de outro jogador (403). */
    NAO_AUTENTICADO, SEM_PERMISSAO,

    /** Nao foi possivel conferir o token agora (lobby fora do ar). */
    INDISPONIVEL,

    /** Liquidacao de uma aposta que nao foi retida (§14.6: nunca se ganha sem apostar). */
    APOSTA_INEXISTENTE,
}

@Serializable
data class EconomyError(val code: EconomyErrorCode, val message: String)

object EconomyLimits {
    const val MAX_BODY_BYTES = 4 * 1024
    const val MAX_KEY = 64
    const val MAX_GUEST_ID = 64
}

// ------------------------------------------------------------------------------------------------ interno (2.3)

/**
 * game -> economy, POST /internal/economy/bets/{betId}/hold: retem [amount] de cada conta (§18.2, aposta retida ao
 * iniciar). [betId] e unico por jogo (o id da mesa se repete). Idempotente: repetir nao retem de novo.
 */
@Serializable
data class BetHoldRequest(val amount: Long, val accounts: List<String>)

/** Quem teve a aposta retida e quem nao tinha saldo (§14.6: esse joga sem aposta, nada debitado nem pago). */
@Serializable
data class BetHoldResponse(val held: List<String>, val notHeld: List<String> = emptyList())

@Serializable
enum class PayoutKind { PREMIO, DEVOLUCAO, PERDA }

/** O que cada conta recebe ao liquidar: [amount] = parte do premio, a aposta devolvida ou 0 (perdeu a aposta). */
@Serializable
data class BetPayout(val accountId: String, val kind: PayoutKind, val amount: Long)

/** game -> economy, POST /internal/economy/bets/{betId}/settle. Idempotente: aposta ja LIQUIDADA nao muda. */
@Serializable
data class BetSettleRequest(val payouts: List<BetPayout>)

@Serializable
data class BetSettleResponse(val settled: Int, val alreadySettled: Int)
