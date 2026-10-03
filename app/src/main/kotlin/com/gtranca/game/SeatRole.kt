package com.gtranca.game

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat

/**
 * Papel de um assento do ponto de vista de quem joga no assento `viewer` (o humano).
 *
 * Em duplas (§1.1) o parceiro senta em frente (assento oposto). A vez gira no sentido anti-horário (§4.2): o
 * adversário à direita joga logo depois de você (você descarta para ele); o da esquerda, logo antes (você
 * recebe o descarte dele).
 */
enum class SeatRole {
    YOU,

    /** Individual: o único adversário. */
    OPPONENT,

    /** Duplas: parceiro, em frente. */
    PARTNER,

    /** Duplas: adversário que joga logo antes de você (§4.2: você recebe o descarte dele). */
    LEFT_OPPONENT,

    /** Duplas: adversário que joga logo depois de você (§4.2: você descarta para ele). */
    RIGHT_OPPONENT,
    ;

    val isOpponent: Boolean get() = this == OPPONENT || this == LEFT_OPPONENT || this == RIGHT_OPPONENT

    companion object {
        fun of(mode: GameMode, seat: Seat, viewer: Seat): SeatRole {
            mode.requireSeat(seat)
            mode.requireSeat(viewer)
            val offset = (seat.index - viewer.index + mode.seatCount) % mode.seatCount
            return when (mode) {
                GameMode.INDIVIDUAL -> if (offset == 0) YOU else OPPONENT
                GameMode.DUPLAS -> when (offset) {
                    0 -> YOU
                    1 -> RIGHT_OPPONENT
                    2 -> PARTNER
                    else -> LEFT_OPPONENT
                }
            }
        }
    }
}
