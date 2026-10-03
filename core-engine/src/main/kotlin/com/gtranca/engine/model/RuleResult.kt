package com.gtranca.engine.model

/** Resultado de uma operação de regra: sucesso com valor ou falha com o motivo. */
sealed interface RuleResult<out T> {
    data class Ok<out T>(val value: T) : RuleResult<T>
    data class Failure(val error: RuleError) : RuleResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): RuleError? = (this as? Failure)?.error
    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Failure -> throw IllegalArgumentException("Jogada inválida: $error")
    }
}

/** Motivo de recusa: [MeldError] (conjuntos, §6–§7) ou [ActionError] (jogadas da partida). */
sealed interface RuleError

/** Motivos pelos quais uma ação da partida é recusada (§4, §5, §8–§11). */
enum class ActionError : RuleError {
    /** §11 a partida já terminou. */
    ROUND_FINISHED,
    /** §4.2 não é a vez deste jogador. */
    NOT_YOUR_TURN,
    /** §4.3 é preciso comprar (ou pegar o lixo) antes de baixar ou descartar. */
    MUST_DRAW_FIRST,
    /** §4.3 a compra (ou o lixo) já foi feita nesta jogada. */
    ALREADY_DREW,
    /** §10.2 monte vazio e nenhum morto disponível para virar monte. */
    STOCK_EXHAUSTED,
    /** §10.2 recusar a compra só é possível com monte vazio e sem morto disponível. */
    DECLINE_NOT_ALLOWED,
    /** §5.5 lixo vazio. */
    DISCARD_PILE_EMPTY,
    /** §5.3 3 preto no topo trava o lixo. */
    DISCARD_PILE_LOCKED,
    /** §5.1 conjunto novo com o topo exige pelo menos 2 cartas da mão. */
    DISCARD_TOP_NEEDS_TWO_HAND_CARDS,
    /** A carta indicada não está na mão do jogador. */
    CARD_NOT_IN_HAND,
    /** §6.5 / §8 3 vermelho não pode ser descartado. */
    CANNOT_DISCARD_RED_THREE,
    /** §8 / §9.5 ficaria sem cartas, mas o lado não tem morto e não há morto disponível. */
    NO_MORTO_AVAILABLE,
    /** §8 / §11.1 ficaria sem cartas com morto do lado, mas sem canastra: não pode bater. */
    NO_CANASTA_TO_GO_OUT,
    /**
     * §8 manter cartas para descartar: quando ficar sem cartas não é permitido, baixar, acrescentar
     * ou pegar o lixo deve deixar pelo menos 2 cartas na mão.
     */
    MUST_KEEP_CARD_TO_DISCARD,
}

/** Motivos pelos quais um conjunto não pode ser criado ou receber cartas (§6, §7). */
enum class MeldError : RuleError {
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
    /** §6.1 carta de outro naipe acrescentada a uma sequência. */
    WRONG_SUIT,
    /** §6.1 carta de outro número acrescentada a um grupo. */
    WRONG_RANK,
    /** §6.4 nova sequência seria continuação de outra do mesmo naipe do lado. */
    CONTIGUOUS_SEQUENCE,
    /** §6.4 conjunto de destino não existe na mesa do lado. */
    MELD_NOT_FOUND,
}
