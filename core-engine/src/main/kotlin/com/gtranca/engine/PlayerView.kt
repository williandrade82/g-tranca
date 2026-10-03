package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import kotlinx.serialization.Serializable

/**
 * O que o jogador sentado em [seat] enxerga da partida. É o que os bots recebem, nunca o
 * [RoundState] completo. Imutável.
 *
 * Fica de fora tudo o que é oculto: as mãos dos outros assentos (inclusive do parceiro, em duplas),
 * as cartas do monte (§3.3) e as dos mortos (§3.2). Deles só se conhecem os tamanhos.
 *
 * @property hand só a mão do próprio [seat].
 * @property handSizes tamanho da mão de cada assento, indexado por [Seat.index].
 * @property discardPile lixo completo, aberto a todos (§5.6); o último elemento é o topo.
 * @property stockSize número de cartas no monte.
 * @property mortoStatus situação de cada morto (§9.1, §10).
 * @property mortoSizes número de cartas de cada morto (0 se pego ou se virou monte).
 * @property redThrees 3 vermelhos baixados na mesa (§6.5), indexados por [Side.index].
 * @property tables conjuntos na mesa de cada lado (§6.4), indexados por [Side.index].
 */
@Serializable
data class PlayerView(
    val mode: GameMode,
    val seat: Seat,
    val side: Side,
    val currentSeat: Seat,
    val firstSeat: Seat,
    val phase: Phase,
    val result: RoundResult?,
    val hand: List<Card>,
    val handSizes: List<Int>,
    val discardPile: List<Card>,
    val stockSize: Int,
    val mortoStatus: List<MortoStatus>,
    val mortoSizes: List<Int>,
    val redThrees: List<List<Card>>,
    val tables: List<SideTable>,
) {
    /** Topo do lixo (último elemento), ou `null` se vazio. */
    val discardTop: Card? get() = discardPile.lastOrNull()
}

/** Projeção do estado para o jogador em [seat]: só a informação visível a ele na mesa. */
fun RoundState.viewFor(seat: Seat): PlayerView = PlayerView(
    mode = mode,
    seat = seat,
    side = mode.sideOf(seat),
    currentSeat = currentSeat,
    firstSeat = firstSeat,
    phase = phase,
    result = result,
    hand = handOf(seat),
    handSizes = hands.map { it.size },
    discardPile = discardPile,
    stockSize = stock.size,
    mortoStatus = mortoStatus,
    mortoSizes = mortos.map { it.size },
    redThrees = redThrees,
    tables = tables,
)
