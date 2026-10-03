package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/**
 * Uma troca de 3 vermelho, que é **pública** (§3.5, §6.5): todos os jogadores sabem quem baixou cada
 * 3 vermelho e em que momento. A carta recebida como reposição **não** faz parte do registro (é oculta).
 *
 * @property seat assento de quem baixou o 3 vermelho (em duplas, distingue os parceiros).
 * @property card o 3 vermelho baixado (está em [RoundState.redThrees] do lado de [seat]).
 * @property atDeal `true` se a troca aconteceu na distribuição (§3.5), antes da primeira jogada; `false` se
 *   durante a jogada (compra, morto ou reposição em cadeia, §6.5 / §9.4 / §10.1).
 */
@Serializable
data class RedThreeLaid(val seat: Seat, val card: Card, val atDeal: Boolean)
