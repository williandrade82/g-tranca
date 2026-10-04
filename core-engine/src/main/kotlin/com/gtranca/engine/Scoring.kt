package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.HandPenalty
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideScore
import com.gtranca.engine.model.TableCards

/**
 * Pontuação de uma partida encerrada (§12), com detalhamento por lado (indexado por `Side.index`).
 * - §12.1 3 vermelhos (+100 cada se o lado tiver canastra, limpa ou suja; senão −100 cada, com ou sem
 *   vencedor), canastras limpas e sujas, batida (só o lado vencedor; nunca sem vencedor); depois, as cartas
 *   na mesa ([tableCardPoints]): cada carta de todos os conjuntos do lado e cada 3 vermelho baixado soma o seu
 *   valor (3 vermelho +5, 4 a 10 +8, J/Q/K/A +10, coringa +10), sempre.
 * - §12.2 morto não pego (inclusive o que virou monte) e cartas na mão de todos os jogadores do lado.
 * - §12.3 em duplas, os pontos dos parceiros são somados.
 *
 * Todos os valores vêm do [rules]. Lança [IllegalArgumentException] se a partida não terminou.
 */
fun scoreRound(state: RoundState, rules: RuleSet = RuleSet.DEFAULT): List<SideScore> {
    require(state.phase == Phase.FINISHED) { "Só se pontua partida encerrada" }
    val winner = (state.result as? RoundResult.GoOut)?.side
    return state.mode.sides.map { side ->
        val melds = state.tableOf(side).melds.map { it.meld }
        val clean = melds.count { it.isCleanCanasta(rules) }
        val dirty = melds.count { it.isDirtyCanasta(rules) }
        val redThrees = state.redThreesOf(side).size
        val redThreePoints = if (clean + dirty > 0) rules.redThreePoints else rules.redThreeWithoutCanastaPoints
        val handCards = state.mode.seatsOf(side).flatMap { state.handOf(it) }
        SideScore(
            side = side,
            redThrees = ScoreLine(redThrees, redThrees * redThreePoints),
            cleanCanastas = ScoreLine(clean, clean * rules.cleanCanastaPoints),
            dirtyCanastas = ScoreLine(dirty, dirty * rules.dirtyCanastaPoints),
            goOut = if (side == winner) rules.goOutPoints else 0,
            mortoNotTaken = if (state.hasTakenMorto(side)) 0 else rules.mortoNotTakenPoints,
            hand = handPenalty(handCards, rules),
            tableCards = tableCardPoints(state, side, rules),
        )
    }
}

/**
 * §12.1 valor das cartas na mesa do lado [side], por categoria: todas as cartas de todos os conjuntos do lado
 * (canastras ou não) e os 3 vermelhos baixados, com os valores positivos do [rules] (3 vermelho +5, 4 a 10 +8,
 * J/Q/K/A +10, coringa +10). Vale em qualquer momento da partida (com ou sem vencedor, com ou sem canastra);
 * conjuntos nunca têm 3 (§6.2), então não há 3 preto na mesa.
 */
fun tableCardPoints(state: RoundState, side: Side, rules: RuleSet = RuleSet.DEFAULT): TableCards {
    fun line(count: Int, points: Int) = ScoreLine(count, count * points)
    val meldCards = state.tableOf(side).melds.flatMap { it.meld.cards }
    return TableCards(
        redThrees = line(state.redThreesOf(side).size, rules.tableRedThreePoints),
        fourToTen = line(meldCards.count { it.rank in Rank.FOUR..Rank.TEN }, rules.tableFourToTenPoints),
        faceCardsAndAces = line(meldCards.count { it.rank in Rank.JACK..Rank.ACE }, rules.tableFaceCardOrAcePoints),
        wilds = line(meldCards.count { it.isWild }, rules.tableWildPoints),
    )
}

/** §12.2 penalidade das cartas na mão, por categoria. */
private fun handPenalty(cards: List<Card>, rules: RuleSet): HandPenalty {
    fun line(count: Int, points: Int) = ScoreLine(count, count * points)
    return HandPenalty(
        redThrees = line(cards.count { it.isRedThree }, rules.handRedThreePoints),
        blackThrees = line(cards.count { it.isBlackThree }, rules.handBlackThreePoints),
        fourToTen = line(cards.count { it.rank in Rank.FOUR..Rank.TEN }, rules.handFourToTenPoints),
        faceCardsAndAces = line(cards.count { it.rank in Rank.JACK..Rank.ACE }, rules.handFaceCardOrAcePoints),
        wilds = line(cards.count { it.isWild }, rules.handWildPoints),
    )
}
