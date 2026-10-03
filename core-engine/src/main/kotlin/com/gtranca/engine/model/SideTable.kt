package com.gtranca.engine.model

import com.gtranca.engine.RuleSet
import kotlinx.serialization.Serializable

/** Identificador estável de um conjunto na mesa de um lado (§6.4: o jogador indica o destino). */
@Serializable
@JvmInline
value class MeldId(val value: Int)

/** Conjunto baixado na mesa, com seu identificador. */
@Serializable
data class TableMeld(val id: MeldId, val meld: Meld)

/**
 * Conjuntos de um lado na mesa (§6.4). Imutável. Os identificadores nunca são reutilizados e
 * os conjuntos nunca se unem nem são removidos.
 */
@Serializable
data class SideTable(
    val melds: List<TableMeld> = emptyList(),
    val nextMeldId: Int = 0,
) {
    fun meld(id: MeldId): Meld? = melds.firstOrNull { it.id == id }?.meld

    fun allCards(): List<Card> = melds.flatMap { it.meld.cards }

    /**
     * Baixa um conjunto novo (§6.1–§6.3), aplicando as regras de mesa de §6.4:
     * - grupos do mesmo número podem se repetir;
     * - uma sequência nova não pode ser continuação de outra do mesmo naipe do lado, isto é,
     *   suas cartas não podem caber, de uma vez, numa sequência existente (§6.2, §6.3), salvo se a nova
     *   tiver coringa e a existente for canastra limpa, ou se caber exigiria mudar a situação do coringa da
     *   existente (exceções de §6.4).
     *
     * O novo conjunto entra no fim de [melds] com id [nextMeldId].
     */
    fun createMeld(cards: List<Card>, rules: RuleSet = RuleSet.DEFAULT): RuleResult<SideTable> {
        val meld = when (val created = Meld.create(cards, rules)) {
            is RuleResult.Failure -> return created
            is RuleResult.Ok -> created.value
        }
        if (meld.kind is MeldKind.Sequence &&
            melds.any { it.meld.kind == meld.kind && isContinuation(meld, it.meld, rules) }
        ) {
            return RuleResult.Failure(MeldError.CONTIGUOUS_SEQUENCE)
        }
        return RuleResult.Ok(copy(melds = melds + TableMeld(MeldId(nextMeldId), meld), nextMeldId = nextMeldId + 1))
    }

    /** Acrescenta [cards] ao conjunto [id] (§4.3, §6.4). Sequências nunca se unem. */
    fun addToMeld(id: MeldId, cards: List<Card>, rules: RuleSet = RuleSet.DEFAULT): RuleResult<SideTable> {
        val target = melds.firstOrNull { it.id == id } ?: return RuleResult.Failure(MeldError.MELD_NOT_FOUND)
        return when (val added = target.meld.add(cards, rules)) {
            is RuleResult.Failure -> added
            is RuleResult.Ok -> RuleResult.Ok(
                copy(melds = melds.map { if (it.id == id) it.copy(meld = added.value) else it }),
            )
        }
    }

    private companion object {
        /**
         * §6.4 todas as cartas da nova sequência poderiam ser acrescentadas, de uma vez, à existente.
         * Exceções: não se considera sujar uma canastra limpa com o coringa da nova sequência, nem
         * mudar a situação do coringa da existente (travá-lo ou mudar seu valor, §6.3).
         */
        fun isContinuation(new: Meld, existing: Meld, rules: RuleSet): Boolean {
            if (new.hasWild && existing.isCleanCanasta(rules)) return false
            val joined = when (val added = existing.add(new.cards, rules)) {
                is RuleResult.Failure -> return false
                is RuleResult.Ok -> added.value
            }
            return !existing.hasWild || joined.wildState == existing.wildState
        }
    }
}
