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
 * @property firstSeat jogador inicial (§4.1): sorteado na 1ª partida; nas seguintes, o próximo depois
 *   de quem iniciou a partida anterior.
 * @property currentSeat assento da vez.
 * @property phase fase da jogada do assento da vez, ou partida encerrada.
 * @property mortoStatus situação de cada morto (§9.1, §10). Um morto que não está
 *   [MortoStatus.Available] tem a lista correspondente em [mortos] vazia.
 * @property result resultado, presente somente quando [phase] é [Phase.FINISHED].
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
    val phase: Phase = Phase.AWAITING_DRAW,
    val mortoStatus: List<MortoStatus> = List(2) { MortoStatus.Available },
    val result: RoundResult? = null,
) {
    init {
        require(mortoStatus.size == mortos.size) { "Uma situação por morto" }
        require((phase == Phase.FINISHED) == (result != null)) { "Resultado existe só com a partida encerrada" }
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

    /** §9.1 o lado já pegou um morto. */
    fun hasTakenMorto(side: Side): Boolean = mortoStatus.any { it == MortoStatus.Taken(side) }

    /** §9.1 índice do primeiro morto disponível, ou `null`. */
    fun firstAvailableMorto(): Int? = mortoStatus.indexOfFirst { it == MortoStatus.Available }.takeIf { it >= 0 }

    /** Topo do lixo (último elemento), ou `null` se vazio. */
    val discardTop: Card? get() = discardPile.lastOrNull()

    /** Todas as cartas do estado (mãos, monte, lixo, mortos, 3 vermelhos e conjuntos na mesa). */
    fun allCards(): List<Card> =
        hands.flatten() + stock + discardPile + mortos.flatten() + redThrees.flatten() +
            tables.flatMap { it.allCards() }
}
