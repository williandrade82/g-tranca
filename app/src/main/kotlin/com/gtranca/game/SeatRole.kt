package com.gtranca.game

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat

/**
 * Papel de um assento do ponto de vista de quem joga no assento `viewer` (o humano).
 *
 * Em duplas (§1.1) o parceiro senta em frente (assento oposto). Esquerda e direita seguem o sentido horário
 * (§4.2): o adversário à esquerda joga logo depois de você; o da direita, logo antes.
 */
enum class SeatRole {
    YOU,

    /** Individual: o único adversário. */
    OPPONENT,

    /** Duplas: parceiro, em frente. */
    PARTNER,

    /** Duplas: adversário que joga logo depois de você. */
    LEFT_OPPONENT,

    /** Duplas: adversário que joga logo antes de você. */
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
                    1 -> LEFT_OPPONENT
                    2 -> PARTNER
                    else -> RIGHT_OPPONENT
                }
            }
        }
    }
}
