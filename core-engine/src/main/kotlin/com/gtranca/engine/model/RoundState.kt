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
 * @property redThreeLog registro público (§3.5, §6.5) de quem baixou cada 3 vermelho, em ordem cronológica:
 *   um item por 3 vermelho baixado, com `atTurnStart` indicando se a troca foi no início da vez ou durante a jogada.
 *   Para cada lado, as cartas do registro baixadas pelos assentos do lado são as de [redThrees] do lado, na mesma
 *   ordem. Vazio por padrão para que o JSON salvo antes do registro existir continue legível (nesse caso o registro só
 *   tem as trocas posteriores à leitura).
 * @property dealReplacements **obsoleto e inerte** (sempre vazio): na regra antiga guardava as reposições feitas na
 *   distribuição. Hoje nada é trocado na distribuição (§3.5); o campo só existe para o JSON salvo antes da mudança
 *   continuar legível.
 * @property turnsBegun quantos inícios de vez já ocorreram nesta partida (§3.5), contando o do primeiro jogador: 0 logo
 *   após a distribuição pura, 1 depois de o primeiro jogador ter começado a vez. **Público.** Os assentos que já tiveram
 *   o início da vez são os primeiros [turnsBegun] da ordem de jogada a partir de [firstSeat] (ver [hasBegunFirstTurn]); a mão
 *   de quem ainda não começou pode ter 3 vermelho. O padrão (a quantidade de assentos) trata JSON antigo como "todos já
 *   começaram", pois nele a troca era feita na distribuição.
 * @property unsettledMortoSeats **público**: assentos que pegaram o morto de forma **indireta** (§9.3) e ainda não tiveram
 *   o início da vez seguinte; o morto na mão deles pode ter 3 vermelho, trocado só nesse início de vez (§9.4). Independe
 *   do conteúdo do morto (para não vazar informação).
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
    val redThreeLog: List<RedThreeLaid> = emptyList(),
    val dealReplacements: List<List<Card>> = List(mode.seatCount) { emptyList() },
    val turnsBegun: Int = mode.seatCount,
    val unsettledMortoSeats: List<Seat> = emptyList(),
) {
    init {
        require(dealReplacements.size == mode.seatCount) {
            "Esperadas ${mode.seatCount} listas de reposições da distribuição, recebidas ${dealReplacements.size}"
        }
        require(turnsBegun >= 0) { "turnsBegun negativo: $turnsBegun" }
        unsettledMortoSeats.forEach { mode.requireSeat(it) }
        require(mortoStatus.size == mortos.size) { "Uma situação por morto" }
        require((phase == Phase.FINISHED) == (result != null)) { "Resultado existe só com a partida encerrada" }
        require(hands.size == mode.seatCount) { "Esperadas ${mode.seatCount} mãos, recebidas ${hands.size}" }
        require(redThrees.size == mode.sideCount) { "Esperados ${mode.sideCount} lados de 3 vermelhos" }
        require(tables.size == mode.sideCount) { "Esperadas ${mode.sideCount} mesas de lado" }
        require(mortos.size == 2) { "Esperados 2 mortos, recebidos ${mortos.size}" }
        mode.requireSeat(firstSeat)
        mode.requireSeat(currentSeat)
        redThreeLog.forEach { mode.requireSeat(it.seat) }
    }

    fun handOf(seat: Seat): List<Card> {
        mode.requireSeat(seat)
        return hands[seat.index]
    }

    fun redThreesOf(side: Side): List<Card> = redThrees[side.index]

    /** §3.5 o [seat] já teve o início da sua primeira vez nesta partida (ver [turnsBegun]). */
    fun hasBegunFirstTurn(seat: Seat): Boolean = hasBegunFirstTurn(mode, firstSeat, turnsBegun, seat)

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

/**
 * §3.5 o [seat] já teve o início da sua primeira vez, dados o modo, o primeiro jogador e [turnsBegun]: os assentos que
 * já começaram são os primeiros [turnsBegun] da ordem de jogada a partir de [firstSeat].
 */
fun hasBegunFirstTurn(mode: GameMode, firstSeat: Seat, turnsBegun: Int, seat: Seat): Boolean =
    mode.seatsInPlayOrder(firstSeat).indexOf(seat.also { mode.requireSeat(it) }) < turnsBegun
