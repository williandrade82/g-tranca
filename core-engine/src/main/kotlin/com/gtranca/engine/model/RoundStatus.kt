package com.gtranca.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Fase da partida (§4.3). */
@Serializable
enum class Phase {
    /** Etapa 1: o jogador da vez deve comprar do monte ou pegar o lixo. */
    AWAITING_DRAW,

    /** Etapas 2 e 3: baixar/acrescentar à vontade e descartar. */
    PLAYING,

    /** Partida encerrada (§11): ver [RoundState.result]. */
    FINISHED,
}

/** Resultado da partida (§11). */
@Serializable
sealed interface RoundResult {
    /** §11.1 batida do [seat], vitória do [side]. */
    @Serializable
    @SerialName("goOut")
    data class GoOut(val side: Side, val seat: Seat) : RoundResult

    /** §11.2 / §10.2 fim sem vencedor. */
    @Serializable
    @SerialName("noWinner")
    data object NoWinner : RoundResult
}

/** Situação de um morto (§9.1, §10). */
@Serializable
sealed interface MortoStatus {
    /** Disponível: não foi pego nem virou monte. */
    @Serializable
    @SerialName("available")
    data object Available : MortoStatus

    /** Pego pelo [side]. */
    @Serializable
    @SerialName("taken")
    data class Taken(val side: Side) : MortoStatus

    /** §10.1 virou o monte. */
    @Serializable
    @SerialName("becameStock")
    data object BecameStock : MortoStatus
}
