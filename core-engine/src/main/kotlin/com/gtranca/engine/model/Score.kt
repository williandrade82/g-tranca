package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/** Um item do detalhamento: quantidade e pontos (positivos ou negativos). */
@Serializable
data class ScoreLine(val count: Int, val points: Int) {
    companion object {
        val ZERO = ScoreLine(0, 0)
    }
}

/** §12.2 penalidade das cartas na mão de todos os jogadores do lado, por categoria. */
@Serializable
data class HandPenalty(
    /** 3 vermelho: −5. */
    val redThrees: ScoreLine,
    /** 3 preto: −5. */
    val blackThrees: ScoreLine,
    /** 4 a 10: −8. */
    val fourToTen: ScoreLine,
    /** J, Q, K, A: −10. */
    val faceCardsAndAces: ScoreLine,
    /** Coringa (2): −10. */
    val wilds: ScoreLine,
) {
    val points: Int get() = redThrees.points + blackThrees.points + fourToTen.points + faceCardsAndAces.points + wilds.points
}

/** Detalhamento da pontuação de um lado em uma partida (§12), item a item. */
@Serializable
data class SideScore(
    val side: Side,
    /** §12.1 3 vermelhos na mesa. */
    val redThrees: ScoreLine,
    /** §12.1 canastras limpas. */
    val cleanCanastas: ScoreLine,
    /** §12.1 canastras sujas. */
    val dirtyCanastas: ScoreLine,
    /** §12.1 batida (só o lado vencedor). */
    val goOut: Int,
    /** §12.2 morto não pego (0 ou negativo). */
    val mortoNotTaken: Int,
    /** §12.2 cartas na mão. */
    val hand: HandPenalty,
) {
    val total: Int
        get() = redThrees.points + cleanCanastas.points + dirtyCanastas.points + goOut + mortoNotTaken + hand.points
}
