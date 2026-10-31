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
enum class EconomyErrorCode { SALDO_INSUFICIENTE, JA_RESGATADO, OFERTA_INEXISTENTE, CONTA_INEXISTENTE, REQUISICAO_INVALIDA, APP_NAO_RECONHECIDO }

@Serializable
data class EconomyError(val code: EconomyErrorCode, val message: String)

object EconomyLimits {
    const val MAX_BODY_BYTES = 4 * 1024
    const val MAX_KEY = 64
    const val MAX_GUEST_ID = 64
}
