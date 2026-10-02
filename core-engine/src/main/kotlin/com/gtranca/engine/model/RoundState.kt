package com.gtranca.engine.model

import kotlinx.serialization.Serializable

/**
 * Estado imutável de uma partida (`Round`).
 *
 * Convenções de ordem das pilhas:
 * - [stock] (monte): **índice 0 é o topo**, ou seja, a próxima carta a ser comprada.
 * - [discardPile] (lixo): **último elemento é o topo** (a última carta descartada).
 *
 * @property hands mão de cada assento, indexada por [Seat.index].
 * @property mortos os 2 mortos (§3.2), cada um com suas cartas viradas para baixo.
 *   A disponibilidade de cada morto (§9.1, §10) será modelada quando essas regras forem implementadas.
 * @property redThrees 3 vermelhos baixados na mesa (§6.5), indexados por [Side.index].
 * @property tables conjuntos na mesa de cada lado (§6.4), indexados por [Side.index].
 * @property firstSeat jogador inicial sorteado (§4.1).
 * @property currentSeat assento da vez.
 */
@Serializable
data class RoundState(
    val mode: GameMode,
    val hands: List<List<Card>>,
    val stock: List<Card>,
    val discardPile: List<Card>,
    val mortos: List<List<Card>>,
    val redThrees: List<List<Card>>,
    val tables: List<SideTable>,
    val firstSeat: Seat,
    val currentSeat: Seat,
) {
    init {
        require(hands.size == mode.seatCount) { "Esperadas ${mode.seatCount} mãos, recebidas ${hands.size}" }
        require(redThrees.size == mode.sideCount) { "Esperados ${mode.sideCount} lados de 3 vermelhos" }
        require(tables.size == mode.sideCount) { "Esperadas ${mode.sideCount} mesas de lado" }
        require(mortos.size == 2) { "Esperados 2 mortos, recebidos ${mortos.size}" }
        mode.requireSeat(firstSeat)
        mode.requireSeat(currentSeat)
    }

    fun handOf(seat: Seat): List<Card> {
        mode.requireSeat(seat)
        return hands[seat.index]
    }

    fun redThreesOf(side: Side): List<Card> = redThrees[side.index]

    fun tableOf(side: Side): SideTable = tables[side.index]

    /** Todas as cartas do estado (mãos, monte, lixo, mortos, 3 vermelhos e conjuntos na mesa). */
    fun allCards(): List<Card> =
        hands.flatten() + stock + discardPile + mortos.flatten() + redThrees.flatten() +
            tables.flatMap { it.allCards() }
}
