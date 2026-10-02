package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/** Assento/jogador. A ordem horária (§4.2) é a de índice crescente. */
@Serializable
@JvmInline
value class Seat(val index: Int) {
    init {
        require(index >= 0) { "Assento inválido: $index" }
    }
}

/** Lado (§1.1): o jogador no modo individual ou a dupla no modo duplas. */
@Serializable
@JvmInline
value class Side(val index: Int) {
    init {
        require(index >= 0) { "Lado inválido: $index" }
    }
}

/**
 * Modo de jogo (§1.1, §14).
 * - [INDIVIDUAL]: 2 assentos, cada um é um lado.
 * - [DUPLAS]: 4 assentos; parceiros em posições opostas (assentos 0 e 2 = lado 0; 1 e 3 = lado 1).
 */
@Serializable
enum class GameMode(val seatCount: Int) {
    INDIVIDUAL(2),
    DUPLAS(4);

    /** Sempre há 2 lados. */
    val sideCount: Int get() = 2

    val seats: List<Seat> get() = (0 until seatCount).map(::Seat)

    val sides: List<Side> get() = (0 until sideCount).map(::Side)

    /** §1.1 lado a que pertence o assento (em duplas, parceiros ficam em posições opostas). */
    fun sideOf(seat: Seat): Side {
        requireSeat(seat)
        return Side(seat.index % sideCount)
    }

    fun seatsOf(side: Side): List<Seat> {
        require(side.index < sideCount) { "Lado $side fora do modo $this" }
        return seats.filter { sideOf(it) == side }
    }

    /** §4.2 próximo assento no sentido horário. */
    fun nextSeat(seat: Seat): Seat {
        requireSeat(seat)
        return Seat((seat.index + 1) % seatCount)
    }

    /** Todos os assentos na ordem de jogada, começando por [first]. */
    fun seatsInPlayOrder(first: Seat): List<Seat> =
        generateSequence(first) { nextSeat(it) }.take(seatCount).toList().also { requireSeat(first) }

    fun requireSeat(seat: Seat) {
        require(seat.index < seatCount) { "Assento ${seat.index} fora do modo $this" }
    }
}
