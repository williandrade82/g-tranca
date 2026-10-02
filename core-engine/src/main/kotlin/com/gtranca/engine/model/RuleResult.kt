package com.gtranca.engine.model

/** Resultado de uma operação de regra: sucesso com valor ou falha com o motivo. */
sealed interface RuleResult<out T> {
    data class Ok<out T>(val value: T) : RuleResult<T>
    data class Failure(val error: MeldError) : RuleResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): MeldError? = (this as? Failure)?.error
    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Failure -> throw IllegalArgumentException("Jogada inválida: $error")
    }
}

/** Motivos pelos quais um conjunto não pode ser criado ou receber cartas (§6, §7). */
enum class MeldError {
    /** §6.2 menos de 3 cartas. */
    TOO_FEW_CARDS,
    /** Acréscimo sem cartas. */
    NO_CARDS,
    /** A mesma carta física aparece duas vezes (ou já está no conjunto). */
    DUPLICATE_CARD,
    /** §6.2 nenhum 3 pode compor conjuntos. */
    CONTAINS_THREE,
    /** §6.3 mais de 1 coringa no conjunto (inclui "grupo de 2"). */
    TOO_MANY_WILDS,
    /** §6.1 as naturais não são do mesmo naipe nem do mesmo número. */
    NOT_A_SEQUENCE_OR_GROUP,
    /** §6.1 sequência com número repetido. */
    REPEATED_RANK,
    /** §6.1/§6.2 sequência não consecutiva (inclui tentativa de passar do Ás para baixo). */
    NOT_CONSECUTIVE,
    /** §6.2/§6.3 o coringa não cabe em nenhuma posição de 4 a Ás. */
    WILD_DOES_NOT_FIT,
    /** §6.1 carta de outro naipe acrescentada a uma sequência. */
    WRONG_SUIT,
    /** §6.1 carta de outro número acrescentada a um grupo. */
    WRONG_RANK,
    /** §6.3/§7.3 coringa acrescentado a canastra limpa. */
    WILD_IN_CLEAN_CANASTA,
    /** §6.4 o lado já tem um grupo desse número. */
    DUPLICATE_GROUP,
    /** §6.4 nova sequência seria continuação de outra do mesmo naipe do lado. */
    CONTIGUOUS_SEQUENCE,
    /** §6.4 conjunto de destino não existe na mesa do lado. */
    MELD_NOT_FOUND,
}
