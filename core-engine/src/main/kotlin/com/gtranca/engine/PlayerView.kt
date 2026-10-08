package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.hasBegunFirstTurn
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
 * @property redThreeLog registro público (§3.5, §6.5) de quem baixou cada 3 vermelho, em ordem cronológica, igual
 *   ao [RoundState.redThreeLog]. Não contém as cartas de reposição (ocultas). Vazio por padrão (JSON antigo).
 * @property turnsBegun quantos inícios de vez já ocorreram na partida (§3.5), igual ao [RoundState.turnsBegun]; com [firstSeat] e
 *   o modo, dá para saber quais assentos já tiveram o início da vez ([hasBegunFirstTurn]). A mão de um assento que ainda
 *   não começou a primeira vez pode ter 3 vermelho (só trocado na vez do jogador). Padrão: todos já começaram (JSON antigo).
 * @property unsettledMortoSeats assentos que pegaram o morto indireto (§9.3) e ainda não tiveram o início da vez seguinte:
 *   a mão deles pode ter 3 vermelho do morto (§9.4). Igual ao [RoundState.unsettledMortoSeats] (público).
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
    val redThreeLog: List<RedThreeLaid> = emptyList(),
    val turnsBegun: Int = mode.seatCount,
    val unsettledMortoSeats: List<Seat> = emptyList(),
) {
    /** §3.5 o [seat] já teve o início da sua primeira vez nesta partida. */
    fun hasBegunFirstTurn(seat: Seat): Boolean = hasBegunFirstTurn(mode, firstSeat, turnsBegun, seat)

    /** Topo do lixo (último elemento), ou `null` se vazio. */
    val discardTop: Card? get() = discardPile.lastOrNull()
}

/**
 * Projeção do estado para o jogador em [seat]: só a informação visível a ele na mesa.
 *
 * As listas são cópias defensivas (inclusive as aninhadas): quem recebe a vista não consegue
 * alterar o [RoundState] nem com cast para `MutableList`.
 */
fun RoundState.viewFor(seat: Seat): PlayerView = PlayerView(
    mode = mode,
    seat = seat,
    side = mode.sideOf(seat),
    currentSeat = currentSeat,
    firstSeat = firstSeat,
    phase = phase,
    result = result,
    hand = handOf(seat).toList(),
    handSizes = hands.map { it.size },
    discardPile = discardPile.toList(),
    stockSize = stock.size,
    mortoStatus = mortoStatus.toList(),
    mortoSizes = mortos.map { it.size },
    redThrees = redThrees.map { it.toList() },
    tables = tables.map { it.defensiveCopy() },
    redThreeLog = redThreeLog.toList(),
    turnsBegun = turnsBegun,
    unsettledMortoSeats = unsettledMortoSeats.toList(),
)

/** Cópia de [SideTable] sem compartilhar nenhuma lista com o original. */
internal fun SideTable.defensiveCopy(): SideTable =
    copy(melds = melds.map { it.copy(meld = it.meld.copy(cards = it.meld.cards.toList())) })
