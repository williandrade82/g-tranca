package com.gtranca.ai.hard

import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.scoreRound

/*
 * Utilitários sobre estados SORTEADOS (determinizações). Todo RoundState deste pacote vem de
 * `PlayerView.determinize` ou de `RoundEngine.apply` sobre um estado assim: nunca é o estado real da partida.
 */

/**
 * Vista do assento [seat] num estado sorteado: o que esse jogador enxergaria naquele mundo hipotético
 * (sua mão sorteada, o lixo, as mesas e os tamanhos). Cada política da simulação recebe só a sua vista,
 * então nenhuma delas usa as cartas ocultas das outras.
 *
 * Sem cópias defensivas, de propósito: (1) o estado é um mundo sorteado, descartado ao fim da iteração, e
 * nunca o estado real da partida; (2) as listas do [RoundState] são imutáveis (o motor só cria estados novos)
 * e quem recebe esta vista são só as políticas internas da simulação (o Médio), que não fazem cast para
 * listas mutáveis; (3) esta função roda a cada ação simulada, e copiar mãos, lixo e mesas ali teria custo
 * sem benefício. A vista real entregue aos bots continua vindo do motor, com cópias defensivas.
 */
internal fun RoundState.sampledView(seat: Seat): PlayerView = PlayerView(
    mode = mode,
    seat = seat,
    side = mode.sideOf(seat),
    currentSeat = currentSeat,
    firstSeat = firstSeat,
    phase = phase,
    result = result,
    hand = handOf(seat),
    handSizes = hands.map { it.size },
    discardPile = discardPile,
    stockSize = stock.size,
    mortoStatus = mortoStatus,
    mortoSizes = mortos.map { it.size },
    redThrees = redThrees,
    tables = tables,
)

/**
 * Avaliação de um estado sorteado do ponto de vista de um lado: recompensa em [0, 1] baseada na diferença
 * de pontuação da partida (§12) entre o lado e o adversário.
 *
 * - Partida encerrada: a pontuação exata ([scoreRound], §12.1/§12.2).
 * - Em andamento (corte de profundidade): **heurística, não é regra do §12.** Estima o placar se a partida
 *   acabasse agora, com um pouco de potencial: 3 vermelhos e canastras com os valores de §12.1; morto não
 *   pego, −100 (§12.2); cartas na mão por uma fração da penalidade de §12.2 (ainda podem ser baixadas). Os
 *   demais termos são só estimativa de potencial e NÃO existem na pontuação: conjuntos ainda não canastra
 *   valem pelo tamanho (pelo §12.1 cartas em conjuntos não têm valor próprio) e o lado que já pode bater
 *   (morto e canastra, §11.1) ganha um bônus.
 */
internal object Evaluation {
    private val RULES = RuleSet.DEFAULT
    private const val CLEAN_MELD_PROGRESS = 25.0
    private const val DIRTY_MELD_PROGRESS = 15.0
    private const val HAND_PENALTY_WEIGHT = 0.6
    private const val READY_TO_GO_OUT_BONUS = 60.0

    /** `0,5 + diferença / (2 × scale)`, limitada a [0, 1]: linear, para a média estimar a diferença esperada. */
    fun reward(state: RoundState, side: Side, scale: Double): Double =
        (0.5 + scoreDiff(state, side) / (2 * scale)).coerceIn(0.0, 1.0)

    /** Diferença de pontos (lado [side] menos o adversário), exata ou estimada. */
    fun scoreDiff(state: RoundState, side: Side): Double {
        val opponent = Side(1 - side.index)
        if (state.phase == Phase.FINISHED) {
            val scores = scoreRound(state, RULES)
            return (scores[side.index].total - scores[opponent.index].total).toDouble()
        }
        return estimate(state, side) - estimate(state, opponent)
    }

    private fun estimate(state: RoundState, side: Side): Double {
        var value = 0.0
        var hasCanasta = false
        for (tableMeld in state.tableOf(side).melds) {
            val meld = tableMeld.meld
            when {
                meld.isCleanCanasta(RULES) -> { value += RULES.cleanCanastaPoints; hasCanasta = true }
                meld.isDirtyCanasta(RULES) -> { value += RULES.dirtyCanastaPoints; hasCanasta = true }
                else -> value += (meld.cards.size - 2) * if (meld.hasWild) DIRTY_MELD_PROGRESS else CLEAN_MELD_PROGRESS
            }
        }
        // §12.1 o 3 vermelho vale +100 só com canastra do lado; sem ela, −100.
        val redThreePoints = if (hasCanasta) RULES.redThreePoints else RULES.redThreeWithoutCanastaPoints
        value += state.redThreesOf(side).size * redThreePoints
        val hasMorto = state.hasTakenMorto(side)
        if (!hasMorto) value += RULES.mortoNotTakenPoints
        if (hasMorto && hasCanasta) value += READY_TO_GO_OUT_BONUS
        val handPenalty = state.mode.seatsOf(side).sumOf { seat -> state.handOf(seat).sumOf(::cardPenalty) }
        return value + HAND_PENALTY_WEIGHT * handPenalty
    }

    /** §12.2 penalidade de uma carta na mão (negativa). */
    private fun cardPenalty(card: Card): Int = when {
        card.rank.isThree -> if (card.isRedThree) RULES.handRedThreePoints else RULES.handBlackThreePoints
        card.isWild -> RULES.handWildPoints
        card.rank in Rank.FOUR..Rank.TEN -> RULES.handFourToTenPoints
        else -> RULES.handFaceCardOrAcePoints
    }
}
