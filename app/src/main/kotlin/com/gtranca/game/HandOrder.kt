package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit

/** Critério de ordem da mão na tela (só apresentação; não valida regra nenhuma). */
enum class HandSort { BY_RANK, BY_SUIT }

/**
 * Preferência de ordem da mão: o critério e se os 3 pretos e coringas ficam separados numa coluna à esquerda
 * (o "separar"). Gravada como texto: `BY_SUIT` ou `BY_SUIT:SPECIAL`.
 */
data class HandPrefs(val sort: HandSort = HandSort.BY_SUIT, val separateSpecial: Boolean = true) {
    val id: String get() = sort.name + if (separateSpecial) ":$SPECIAL" else ""

    companion object {
        private const val SPECIAL = "SPECIAL"

        /** Lê o texto gravado; o antigo `CUSTOM` (ordem personalizada) vira o padrão, e o ilegível também. */
        fun parse(id: String?): HandPrefs {
            if (id == null) return HandPrefs()
            val parts = id.split(':')
            val sort = HandSort.entries.firstOrNull { it.name == parts[0] } ?: return HandPrefs()
            return HandPrefs(sort, separateSpecial = SPECIAL in parts.drop(1))
        }
    }
}

/** Ordem dos naipes na mão: cores alternadas. */
private val SUIT_ORDER = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)

private val bySuitThenRank: Comparator<Card> =
    compareBy<Card> { SUIT_ORDER.indexOf(it.suit) }.thenBy { it.rank.ordinal }.thenBy { it.deck }
private val byRankThenSuit: Comparator<Card> =
    compareBy<Card> { it.rank.ordinal }.thenBy { SUIT_ORDER.indexOf(it.suit) }.thenBy { it.deck }

/**
 * Mão com a coluna separada: [special] são os 3 pretos e os coringas (2), mostrados numa coluna fixa à esquerda;
 * [rest] são as demais cartas, na ordem do critério escolhido.
 */
data class CustomHand(val special: List<Card>, val rest: List<Card>) {
    val all: List<Card> get() = special + rest
}

object HandOrder {

    private fun comparator(sort: HandSort) = if (sort == HandSort.BY_RANK) byRankThenSuit else bySuitThenRank

    /** Mão toda no critério [sort], sem separar nada. */
    fun sort(hand: List<Card>, sort: HandSort): List<Card> = hand.sortedWith(comparator(sort))

    /**
     * Mão com os 3 pretos e coringas separados. Dentro da coluna: 3 pretos e depois coringas, por naipe/valor; o
     * resto (inclui 3 vermelho, se houver) no critério [sort].
     */
    fun split(hand: List<Card>, sort: HandSort): CustomHand {
        val special = hand.filter { it.isBlackThree }.sortedWith(bySuitThenRank) + hand.filter { it.isWild }.sortedWith(bySuitThenRank)
        val rest = hand.filter { !it.isBlackThree && !it.isWild }.sortedWith(comparator(sort))
        return CustomHand(special, rest)
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
