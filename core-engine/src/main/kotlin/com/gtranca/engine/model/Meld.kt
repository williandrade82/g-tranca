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
 * Conjunto válido (§6, §7). Imutável; só é obtido por [Meld.create] ou [Meld.add], que garantem
 * as regras de §6.1–§6.3.
 *
 * - [cards] está em ordem canônica: na sequência, do menor para o maior valor, com o coringa na
 *   posição que representa; no grupo, as naturais (por naipe e baralho) e o coringa por último.
 * - [wildRank] é o valor que o coringa representa (`null` se não houver coringa). Na sequência a
 *   posição é definida pelo motor (§6.3): buraco entre naturais; senão ponta de cima; se não couber
 *   acima do Ás, ponta de baixo.
 */
@Serializable
@ConsistentCopyVisibility
data class Meld internal constructor(
    val kind: MeldKind,
    val cards: List<Card>,
    val wildRank: Rank?,
) {
    val hasWild: Boolean get() = wildRank != null

    /** Sem coringa. Uma canastra limpa é `isCanasta() && isClean` (§7.2). */
    val isClean: Boolean get() = !hasWild

    /** Menor valor representado (no grupo, o próprio número). */
    val lowRank: Rank
        get() = when (kind) {
            is MeldKind.Group -> kind.rank
            is MeldKind.Sequence -> representedRanks().min()
        }

    /** Maior valor representado (no grupo, o próprio número). */
    val highRank: Rank
        get() = when (kind) {
            is MeldKind.Group -> kind.rank
            is MeldKind.Sequence -> representedRanks().max()
        }

    private fun representedRanks(): List<Rank> = cards.filterNot { it.isWild }.map { it.rank } + listOfNotNull(wildRank)

    /** §7.1 canastra: [RuleSet.minCanastaSize] (6) ou mais cartas. */
    fun isCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = cards.size >= rules.minCanastaSize

    /** §7.2 canastra limpa: sem coringa. */
    fun isCleanCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = isCanasta(rules) && isClean

    /** §7.2 canastra suja: com coringa. */
    fun isDirtyCanasta(rules: RuleSet = RuleSet.DEFAULT): Boolean = isCanasta(rules) && hasWild

    /**
     * Acrescenta [newCards] ao conjunto (§4.3), de uma vez. O tipo do conjunto é mantido.
     * - §6.1 sequência: mesmo naipe, consecutiva, sem repetição; grupo: mesmo número.
     * - §6.3 máx. 1 coringa; o coringa corre conforme a mesma preferência da criação; natural é
     *   rejeitada se o coringa não couber ([MeldError.WILD_DOES_NOT_FIT]).
     * - §6.3/§7.3 coringa não entra em canastra limpa (avaliado sobre o conjunto antes do acréscimo).
     */
    fun add(newCards: List<Card>, rules: RuleSet = RuleSet.DEFAULT): RuleResult<Meld> {
        if (newCards.isEmpty()) return fail(MeldError.NO_CARDS)
        val all = cards + newCards
        if (all.toSet().size != all.size) return fail(MeldError.DUPLICATE_CARD)
        if (newCards.any { it.rank.isThree }) return fail(MeldError.CONTAINS_THREE)
        if (all.count { it.isWild } > rules.maxWildsPerMeld) return fail(MeldError.TOO_MANY_WILDS)
        if (newCards.any { it.isWild } && isCleanCanasta(rules)) return fail(MeldError.WILD_IN_CLEAN_CANASTA)
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
            return RuleResult.Ok(Meld(MeldKind.Group(rank), naturals + listOfNotNull(wild), wild?.let { rank }))
        }

        /**
         * Sequência (§6.1, §6.2, §6.3): naturais já conferidas como do naipe [suit].
         * Faixa permitida 4..Ás (Ás só alto; o 2 nunca é natural e nenhum 3 entra).
         */
        private fun buildSequence(suit: Suit, cards: List<Card>): RuleResult<Meld> {
            val naturals = cards.filterNot { it.isWild }.sortedBy { it.rank }
            val wild = cards.firstOrNull { it.isWild }
            val ords = naturals.map { it.rank.ordinal }
            if (ords.toSet().size != ords.size) return fail(MeldError.REPEATED_RANK)
            val low = ords.first()
            val high = ords.last()
            val gaps = (high - low + 1) - ords.size
            val wildOrd: Int? = when {
                wild == null -> if (gaps == 0) null else return fail(MeldError.NOT_CONSECUTIVE)
                gaps == 1 -> (low..high).first { it !in ords } // §6.3 buraco
                gaps > 1 -> return fail(MeldError.NOT_CONSECUTIVE)
                high < Rank.ACE.ordinal -> high + 1 // §6.3 ponta de cima
                low > Rank.FOUR.ordinal -> low - 1 // §6.3 ponta de baixo
                else -> return fail(MeldError.WILD_DOES_NOT_FIT)
            }
            val ordered = if (wild == null || wildOrd == null) {
                naturals
            } else {
                val index = naturals.count { it.rank.ordinal < wildOrd }
                naturals.subList(0, index) + wild + naturals.subList(index, naturals.size)
            }
            return RuleResult.Ok(Meld(MeldKind.Sequence(suit), ordered, wildOrd?.let { Rank.entries[it] }))
        }
    }
}
