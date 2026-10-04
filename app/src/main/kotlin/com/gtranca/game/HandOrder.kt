package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit

/** Ordem da mão na tela (só apresentação; não valida regra nenhuma). */
enum class HandSort { BY_RANK, BY_SUIT, CUSTOM }

/** Ordem dos naipes na mão: cores alternadas. */
private val SUIT_ORDER = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)

private val bySuitThenRank: Comparator<Card> =
    compareBy<Card> { SUIT_ORDER.indexOf(it.suit) }.thenBy { it.rank.ordinal }.thenBy { it.deck }
private val byRankThenSuit: Comparator<Card> =
    compareBy<Card> { it.rank.ordinal }.thenBy { SUIT_ORDER.indexOf(it.suit) }.thenBy { it.deck }

/**
 * Mão na ordem Personalizada, em blocos:
 * - [special]: (a) 3 pretos e (b) coringas (2), mostrados numa coluna fixa à esquerda;
 * - [rest]: (c) cartas soltas, (d) sequências e (e) cartas de mesmo valor, nessa ordem.
 */
data class CustomHand(val special: List<Card>, val rest: List<Card>) {
    val all: List<Card> get() = special + rest
}

object HandOrder {

    /** Valores que podem formar sequência de naturais (4..A; sem 2 e 3). */
    private val SEQUENCE_RANKS: List<Rank> = Rank.entries.filter { it >= Rank.FOUR }

    fun sort(hand: List<Card>, sort: HandSort): List<Card> = when (sort) {
        HandSort.BY_SUIT -> hand.sortedWith(bySuitThenRank)
        HandSort.BY_RANK -> hand.sortedWith(byRankThenSuit)
        HandSort.CUSTOM -> custom(hand).all
    }

    /**
     * Ordem Personalizada. Definições (apresentação, não regra):
     * - sequência: 2+ cartas naturais (4..A) do mesmo naipe com valores consecutivos, uma cópia por valor;
     * - mesmo valor: 2+ naturais do mesmo número que não entraram numa sequência;
     * - forma-se primeiro as sequências (a mais longa antes; empate pela ordem naipe/valor), depois os grupos de
     *   mesmo valor com o que sobrou; o resto é solto.
     * Dentro de cada bloco, ordem estável por naipe/valor.
     */
    fun custom(hand: List<Card>): CustomHand {
        val blackThrees = hand.filter { it.isBlackThree }.sortedWith(bySuitThenRank)
        val wilds = hand.filter { it.isWild }.sortedWith(bySuitThenRank)
        val pool = hand.filter { it.rank in SEQUENCE_RANKS }.sortedWith(bySuitThenRank).toMutableList()
        val others = hand.filter { !it.isBlackThree && !it.isWild && it.rank !in SEQUENCE_RANKS } // ex.: 3 vermelho

        val sequences = mutableListOf<List<Card>>()
        while (true) {
            val best = longestRun(pool) ?: break
            sequences += best
            best.forEach { pool.remove(it) }
        }
        val groups = pool.groupBy { it.rank }.filterValues { it.size >= 2 }
            .toSortedMap(compareBy { it.ordinal })
            .values.map { it.sortedWith(bySuitThenRank) }
        groups.forEach { group -> group.forEach { pool.remove(it) } }
        val loose = (pool + others).sortedWith(bySuitThenRank)

        val orderedSequences = sequences.sortedWith(
            compareBy<List<Card>> { SUIT_ORDER.indexOf(it.first().suit) }.thenBy { it.first().rank.ordinal },
        )
        return CustomHand(
            special = blackThrees + wilds,
            rest = loose + orderedSequences.flatten() + groups.flatten(),
        )
    }

    /** Maior sequência (≥ 2) disponível em [pool], uma cópia por valor; empate: a primeira por naipe/valor. */
    private fun longestRun(pool: List<Card>): List<Card>? {
        var best: List<Card>? = null
        for (suit in SUIT_ORDER) {
            val byRank = pool.filter { it.suit == suit }.groupBy { it.rank }
            var run = mutableListOf<Card>()
            for (rank in SEQUENCE_RANKS) {
                val card = byRank[rank]?.minByOrNull { it.deck }
                if (card != null) {
                    run.add(card)
                } else {
                    run = mutableListOf()
                }
                if (run.size >= 2 && run.size > (best?.size ?: 1)) best = run.toList()
            }
        }
        return best
    }

    /**
     * Quantas cartas de largura tem a coluna fixa de 3 pretos e coringas: a menor quantidade que, com as linhas
     * ocupadas pelo resto da mão, acomoda todas ([perRow] = cartas por linha do fluxo, dado o número de colunas
     * da coluna fixa). Uma coluna, no mínimo, se houver alguma; zero se não houver.
     */
    fun specialColumns(specialCount: Int, restCount: Int, maxColumns: Int = 3, perRow: (columns: Int) -> Int): Int {
        if (specialCount == 0) return 0
        for (columns in 1..maxColumns) {
            val flow = perRow(columns).coerceAtLeast(1)
            val rows = maxOf(1, (restCount + flow - 1) / flow)
            if (columns * rows >= specialCount) return columns
        }
        return maxColumns
    }
}
