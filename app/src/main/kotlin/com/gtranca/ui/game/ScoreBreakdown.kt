package com.gtranca.ui.game

import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.SideScore

/** Seções do detalhamento de §12 na tela de pontos. */
enum class ScoreSection {
    /** §12.1 pontos especiais (3 vermelhos ±100, canastras, batida) e §12.2 morto não pego. */
    SPECIAL,

    /** §12.1 cartas na mesa (valor positivo de cada carta baixada). */
    TABLE,

    /** §12.2 cartas na mão (valor negativo). */
    HAND,
}

/** Itens da tela de pontos (o texto de cada um fica em `strings.xml`). */
enum class ScoreItem(val section: ScoreSection) {
    RED_THREES(ScoreSection.SPECIAL),
    CLEAN_CANASTAS(ScoreSection.SPECIAL),
    DIRTY_CANASTAS(ScoreSection.SPECIAL),
    GO_OUT(ScoreSection.SPECIAL),
    MORTO_NOT_TAKEN(ScoreSection.SPECIAL),
    TABLE_RED_THREES(ScoreSection.TABLE),
    TABLE_FOUR_TO_TEN(ScoreSection.TABLE),
    TABLE_FACES(ScoreSection.TABLE),
    TABLE_WILDS(ScoreSection.TABLE),
    HAND_RED_THREES(ScoreSection.HAND),
    HAND_BLACK_THREES(ScoreSection.HAND),
    HAND_FOUR_TO_TEN(ScoreSection.HAND),
    HAND_FACES(ScoreSection.HAND),
    HAND_WILDS(ScoreSection.HAND),
}

/**
 * Detalhamento §12 de um lado, linha a linha, na ordem da tela. Fonte única do que a tela mostra: a soma dos
 * pontos de todas as linhas é sempre [SideScore.total] (testado), e cada subtotal de seção é a soma das suas linhas.
 */
object ScoreBreakdown {

    /** Linha: quantidade (`null` quando não se aplica, ex.: batida) e pontos. */
    data class Line(val count: Int?, val points: Int)

    fun lines(score: SideScore): Map<ScoreItem, Line> = linkedMapOf(
        ScoreItem.RED_THREES to score.redThrees.line(),
        ScoreItem.CLEAN_CANASTAS to score.cleanCanastas.line(),
        ScoreItem.DIRTY_CANASTAS to score.dirtyCanastas.line(),
        ScoreItem.GO_OUT to Line(null, score.goOut),
        ScoreItem.MORTO_NOT_TAKEN to Line(null, score.mortoNotTaken),
        ScoreItem.TABLE_RED_THREES to score.tableCards.redThrees.line(),
        ScoreItem.TABLE_FOUR_TO_TEN to score.tableCards.fourToTen.line(),
        ScoreItem.TABLE_FACES to score.tableCards.faceCardsAndAces.line(),
        ScoreItem.TABLE_WILDS to score.tableCards.wilds.line(),
        ScoreItem.HAND_RED_THREES to score.hand.redThrees.line(),
        ScoreItem.HAND_BLACK_THREES to score.hand.blackThrees.line(),
        ScoreItem.HAND_FOUR_TO_TEN to score.hand.fourToTen.line(),
        ScoreItem.HAND_FACES to score.hand.faceCardsAndAces.line(),
        ScoreItem.HAND_WILDS to score.hand.wilds.line(),
    )

    /** Subtotal de uma seção: soma das linhas exibidas nela. */
    fun subtotal(score: SideScore, section: ScoreSection): Int =
        lines(score).filterKeys { it.section == section }.values.sumOf { it.points }

    /** Soma de todas as linhas exibidas (= "Total da partida"). */
    fun sum(score: SideScore): Int = lines(score).values.sumOf { it.points }

    private fun ScoreLine.line() = Line(count, points)
}
