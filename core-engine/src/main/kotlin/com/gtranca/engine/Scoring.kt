package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.HandPenalty
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.SideScore

/**
 * Pontuação de uma partida encerrada (§12), com detalhamento por lado (indexado por `Side.index`).
 * - §12.1 3 vermelhos, canastras limpas e sujas, batida (só o lado vencedor; nunca sem vencedor);
 *   cartas em conjuntos não têm valor próprio.
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
        val handCards = state.mode.seatsOf(side).flatMap { state.handOf(it) }
        SideScore(
            side = side,
            redThrees = ScoreLine(redThrees, redThrees * rules.redThreePoints),
            cleanCanastas = ScoreLine(clean, clean * rules.cleanCanastaPoints),
            dirtyCanastas = ScoreLine(dirty, dirty * rules.dirtyCanastaPoints),
            goOut = if (side == winner) rules.goOutPoints else 0,
            mortoNotTaken = if (state.hasTakenMorto(side)) 0 else rules.mortoNotTakenPoints,
            hand = handPenalty(handCards, rules),
        )
    }
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
