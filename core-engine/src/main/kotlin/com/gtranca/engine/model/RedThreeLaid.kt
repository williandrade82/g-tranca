package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/**
 * Uma troca de 3 vermelho, que é **pública** (§3.5, §6.5): todos os jogadores sabem quem baixou cada
 * 3 vermelho e em que momento. A carta recebida como reposição **não** faz parte do registro (é oculta).
 *
 * @property seat assento de quem baixou o 3 vermelho (em duplas, distingue os parceiros).
 * @property card o 3 vermelho baixado (está em [RoundState.redThrees] do lado de [seat]).
 * @property atDeal **obsoleto**: desde que a troca só acontece na vez do jogador (§3.5) é sempre `false`. Mantido só
 *   para o JSON salvo por versões antigas continuar legível (nelas, `true` = troca feita na distribuição).
 * @property atTurnStart `true` se a troca aconteceu no **início da vez** do [seat], antes da compra (§3.5: os 3 vermelhos
 *   da distribuição ou do morto indireto, §9.4, e as reposições em cadeia dessa troca); `false` se durante a jogada
 *   (compra, morto direto ou reposição em cadeia, §6.5 / §9.4 / §10.1). Valor padrão `false` para JSON antigo.
 */
@Serializable
data class RedThreeLaid(
    val seat: Seat,
    val card: Card,
    val atDeal: Boolean = false,
    val atTurnStart: Boolean = false,
)
