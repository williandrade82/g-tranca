package com.gtranca.engine.model

import com.gtranca.engine.RuleSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tipo de conjunto (§6.1). */
@Serializable
sealed interface MeldKind {
    /** Sequência de cartas consecutivas do naipe [suit]. */
    @Serializable
    @SerialName("sequence")
    data class Sequence(val suit: Suit) : MeldKind

    /** Grupo de cartas do número [rank]. */
    @Serializable
    @SerialName("group")
    data class Group(val rank: Rank) : MeldKind
}

/**
 * Situação do coringa no conjunto (§6.3). Com no máximo 1 coringa, ela é determinada só pelas
 * cartas naturais; o jogador nunca escolhe.
 */
sealed interface WildState {
    /** Sem coringa. */
    data object None : WildState

    /** Coringa com valor fixo: o buraco entre as naturais da sequência, ou o número do grupo. */
    data class Locked(val rank: Rank) : WildState

    /** Sequência sem buraco: o coringa fica numa ponta, sem valor fixo, e serve dos dois lados. */
    data object Loose : WildState

    /** Sequência completa 4..Ás: o coringa permanece no conjunto sem representar nenhuma carta. */
    data object Unplaced : WildState
}

/**
 * Conjunto válido (§6, §7). Imutável; só é obtido por [Meld.create] ou [Meld.add], que garantem
 * as regras de §6.1–§6.3.
 *
 * [cards] está em ordem canônica: na sequência, as naturais do menor para o maior valor, com o
 * coringa no buraco que ocupa ([WildState.Locked]) ou numa ponta (de cima, se couber; senão de
 * baixo); no grupo, as naturais (por naipe e baralho) e o coringa por último.
 */
@Serializable
@ConsistentCopyVisibility
data class Meld internal constructor(
    val kind: MeldKind,
    val cards: List<Card>,
) {
    val hasWild: Boolean get() = cards.any { it.isWild }

    /** Sem coringa. Uma canastra limpa é `isCanasta() && isClean` (§7.2). */
    val isClean: Boolean get() = !hasWild

    /** §6.3 situação do coringa, derivada das cartas naturais. */
    val wildState: WildState
        get() = when {
            !hasWild -> WildState.None
            kind is MeldKind.Group -> WildState.Locked(kind.rank)
            else -> sequenceWildState(naturalRanks())
        }

    /** Valor que o coringa representa quando travado; `null` se não há coringa ou ele não tem valor fixo. */
    val wildRank: Rank? get() = (wildState as? WildState.Locked)?.rank

    /** Menor valor natural (no grupo, o próprio número). */
    val lowRank: Rank
        get() = when (kind) {
            is MeldKind.Group -> kind.rank
            is MeldKind.Sequence -> naturalRanks().min()
        }

    /** Maior valor natural (no grupo, o próprio número). */
    val highRank: Rank
        get() = when (kind) {
            is MeldKind.Group -> kind.rank
            is MeldKind.Sequence -> naturalRanks().max()
        }

    private fun naturalRanks(): List<Rank> = cards.filterNot { it.isWild }.map { it.rank }

    /** §7.1 canastra: [RuleSet.minCanastaSize] (6) ou mais cartas. */
    fun isCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = cards.size >= rules.minCanastaSize

    /** §7.2 canastra limpa: sem coringa. */
    fun isCleanCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = isCanasta(rules) && isClean

    /** §7.2 canastra suja: com coringa. */
    fun isDirtyCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = isCanasta(rules) && hasWild

    /**
     * Acrescenta [newCards] ao conjunto (§4.3), de uma vez. O tipo do conjunto é mantido.
     * - §6.1 sequência: mesmo naipe, consecutiva, sem repetição; grupo: mesmo número.
     * - §6.3 máx. 1 coringa; a situação do coringa é recalculada pelas naturais (a natural do
     *   buraco libera o coringa travado, que fica solto ou, na sequência completa, sem posição).
     */
    fun add(newCards: List<Card>, rules: RuleSet = RuleSet.DEFAULT): RuleResult<Meld> {
        if (newCards.isEmpty()) return fail(MeldError.NO_CARDS)
        val all = cards + newCards
        if (all.toSet().size != all.size) return fail(MeldError.DUPLICATE_CARD)
        if (newCards.any { it.rank.isThree }) return fail(MeldError.CONTAINS_THREE)
        if (all.count { it.isWild } > rules.maxWildsPerMeld) return fail(MeldError.TOO_MANY_WILDS)
        val newNaturals = newCards.filterNot { it.isWild }
        return when (kind) {
            is MeldKind.Group ->
                if (newNaturals.any { it.rank != kind.rank }) fail(MeldError.WRONG_RANK) else buildGroup(kind.rank, all)
            is MeldKind.Sequence ->
                if (newNaturals.any { it.suit != kind.suit }) fail(MeldError.WRONG_SUIT) else buildSequence(kind.suit, all)
        }
    }

    companion object {
        /**
         * Cria um conjunto a partir de [cards] (§6.1–§6.3). A ordem de entrada não importa.
         * Devolve o motivo da recusa em [RuleResult.Failure].
         */
        fun create(cards: List<Card>, rules: RuleSet = RuleSet.DEFAULT): RuleResult<Meld> {
            if (cards.size < rules.minMeldSize) return fail(MeldError.TOO_FEW_CARDS)
            if (cards.toSet().size != cards.size) return fail(MeldError.DUPLICATE_CARD)
            if (cards.any { it.rank.isThree }) return fail(MeldError.CONTAINS_THREE)
            if (cards.count { it.isWild } > rules.maxWildsPerMeld) return fail(MeldError.TOO_MANY_WILDS)
            // Com ≥ 3 cartas e no máximo 1 coringa, há pelo menos 2 naturais (§6.3).
            val naturals = cards.filterNot { it.isWild }
            return when {
                naturals.all { it.rank == naturals.first().rank } -> buildGroup(naturals.first().rank, cards)
                naturals.all { it.suit == naturals.first().suit } -> buildSequence(naturals.first().suit, cards)
                else -> fail(MeldError.NOT_A_SEQUENCE_OR_GROUP)
            }
        }

        private fun fail(error: MeldError): RuleResult<Nothing> = RuleResult.Failure(error)

        /** Grupo (§6.1): naturais já conferidas como do número [rank]. */
        private fun buildGroup(rank: Rank, cards: List<Card>): RuleResult<Meld> {
            val naturals = cards.filterNot { it.isWild }.sortedWith(compareBy({ it.suit.ordinal }, { it.deck }))
            val wild = cards.firstOrNull { it.isWild }
            return RuleResult.Ok(Meld(MeldKind.Group(rank), naturals + listOfNotNull(wild)))
        }

        /** §6.3 situação do coringa de uma sequência com naturais de valores [ranks] (distintos). */
        private fun sequenceWildState(ranks: List<Rank>): WildState {
            val low = ranks.min()
            val high = ranks.max()
            val gaps = (high.ordinal - low.ordinal + 1) - ranks.size
            return when {
                gaps == 1 -> WildState.Locked(Rank.entries.first { it in low..high && it !in ranks })
                low == Rank.FOUR && high == Rank.ACE -> WildState.Unplaced
                else -> WildState.Loose
            }
        }

        /**
         * Sequência (§6.1, §6.2, §6.3): naturais já conferidas como do naipe [suit].
         * Faixa permitida 4..Ás (Ás só alto; o 2 nunca é natural e nenhum 3 entra). As naturais podem
         * ter no máximo um buraco, e só se houver coringa para ocupá-lo.
         */
        private fun buildSequence(suit: Suit, cards: List<Card>): RuleResult<Meld> {
            val naturals = cards.filterNot { it.isWild }.sortedBy { it.rank }
            val wild = cards.firstOrNull { it.isWild }
            val ranks = naturals.map { it.rank }
            if (ranks.toSet().size != ranks.size) return fail(MeldError.REPEATED_RANK)
            val gaps = (ranks.last().ordinal - ranks.first().ordinal + 1) - ranks.size
            if (gaps > (if (wild == null) 0 else 1)) return fail(MeldError.NOT_CONSECUTIVE)
            val ordered = when {
                wild == null -> naturals
                gaps == 1 -> {
                    val hole = (sequenceWildState(ranks) as WildState.Locked).rank
                    naturals.filter { it.rank < hole } + wild + naturals.filter { it.rank > hole }
                }
                ranks.last() == Rank.ACE && ranks.first() > Rank.FOUR -> listOf(wild) + naturals // solto, só cabe embaixo
                else -> naturals + wild // solto na ponta de cima, ou sem posição (4..Ás completa)
            }
            return RuleResult.Ok(Meld(MeldKind.Sequence(suit), ordered))
        }
    }
}
