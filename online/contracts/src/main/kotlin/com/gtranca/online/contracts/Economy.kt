package com.gtranca.online.contracts

import kotlinx.serialization.Serializable

/** §18.5 economia online: contratos JSON do economy-service (rotas publicas /economy/...). */

/**
 * Carteira e inventario da conta. [lastDailyClaim] em dias desde a epoca (nulo = nunca resgatou). [migratedLocal] =
 * a conta ja trouxe a carteira do aparelho (§18.6): o app usa para esconder o botao "Trazer minha carteira" para
 * sempre depois da 1a vez (convidado sempre false, a migracao nao vale para ele).
 */
@Serializable
data class EconomyStateDto(
    val balance: Long,
    val inventory: Map<String, Int>,
    val lastDailyClaim: Long? = null,
    val migratedLocal: Boolean = false,
)

/**
 * POST /economy/{guestId}/daily: o dia de hoje no fuso do jogador, em dias desde a epoca (§14.6). So informativo: o
 * SERVIDOR decide pelo proprio relogio (UTC) se o resgate e liberado, nunca por este valor (adiantar o relogio do
 * aparelho nao libera o resgate de novo).
 */
@Serializable
data class DailyClaimRequest(val today: Long)

/** POST /economy/{guestId}/buy: oferta da loja (§17.10) e chave do pedido (repetir a chave nao cobra de novo). */
@Serializable
data class BuyRequest(val offerIndex: Int, val key: String)

/**
 * POST /economy/{guestId}/migrate (§18.6): o saldo e o inventario (so boosters com quantidade > 0) da carteira LOCAL
 * do aparelho, para somar a carteira online uma unica vez por conta. O servidor nao confia em nada do aparelho alem
 * do que vem aqui (o save local nao tem autoridade nenhuma): so confere limites defensivos (negativo, absurdo) e
 * confia no valor informado, como a regra pede ("sem teto").
 */
@Serializable
data class MigrateLocalWalletRequest(val coins: Long, val inventory: Map<String, Int> = emptyMap())

@Serializable
enum class EconomyErrorCode {
    SALDO_INSUFICIENTE, JA_RESGATADO, OFERTA_INEXISTENTE, CONTA_INEXISTENTE, REQUISICAO_INVALIDA, APP_NAO_RECONHECIDO,

    /** Sem token do jogador, token invalido (401) ou de outro jogador (403). */
    NAO_AUTENTICADO, SEM_PERMISSAO,

    /** Nao foi possivel conferir o token agora (lobby fora do ar). */
    INDISPONIVEL,

    /** Liquidacao de uma aposta que nao foi retida (§14.6: nunca se ganha sem apostar). */
    APOSTA_INEXISTENTE,

    /** §18.6: so conta (e-mail/Google) migra a carteira local; convidado nao e elegivel. */
    CONTA_NAO_ELEGIVEL,
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

/**
 * game -> economy, POST /internal/economy/matches/{matchId}/seats/{seat}/boosters: debita 1 de cada booster de
 * [boosterIds] (ate 3, §17.2) do inventario online de [accountId], no inicio do jogo [matchId] (§17.3/§18.3). Cada
 * booster e independente: o que nao tiver estoque fica de fora, nunca interrompe os demais. Idempotente por
 * (contaId, matchId, assento, boosterId): repetir a chamada (rede falhou depois de debitar) nao debita de novo.
 */
@Serializable
data class BoosterDebitRequest(val accountId: String, val boosterIds: List<String>)

/** Os boosters realmente debitados (os demais ficaram de fora por falta de estoque, §17.3). */
@Serializable
data class BoosterDebitResponse(val applied: List<String>)
