package com.gtranca.ai.hard

import com.gtranca.ai.cards
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.SideTable
import kotlin.random.Random

/**
 * Cenário fixo COMPLETO (as 104 cartas, §1), como a determinização exige, no modo individual, com o
 * assento 0 da vez. As cartas não citadas vão, embaralhadas com [seed], para a mão do adversário (até
 * [opponentHandSize], sem 3 vermelhos), os mortos disponíveis (11 cada, §3.2) e o monte.
 */
fun fullScenario(
    hand: String,
    phase: Phase = Phase.PLAYING,
    discard: String = "",
    ownMelds: List<String> = emptyList(),
    opponentMelds: List<String> = emptyList(),
    opponentHand: String = "",
    opponentHandSize: Int = 11,
    redThrees: List<String> = listOf("", ""),
    mortoStatus: List<MortoStatus> = listOf(MortoStatus.Available, MortoStatus.Available),
    seed: Long = 1,
): RoundState {
    val ownTable = table(ownMelds)
    val opponentTable = table(opponentMelds)
    val fixedOpponent = cards(opponentHand)
    val fixed = cards(hand) + cards(discard) + fixedOpponent + redThrees.flatMap(::cards) +
        ownTable.allCards() + opponentTable.allCards()
    require(fixed.toSet().size == fixed.size) { "carta repetida no cenário: $fixed" }
    val rest = (Deck.standard() - fixed.toSet()).shuffled(Random(seed)).toMutableList()

    val opponentFill = rest.filterNot { it.isRedThree }.take((opponentHandSize - fixedOpponent.size).coerceAtLeast(0))
    rest.removeAll(opponentFill.toSet())
    val mortos = mortoStatus.map { status ->
        if (status == MortoStatus.Available) List(11) { rest.removeAt(0) } else emptyList<Card>()
    }
    return RoundState(
        mode = GameMode.INDIVIDUAL,
        hands = listOf(cards(hand), fixedOpponent + opponentFill),
        stock = rest.toList(),
        discardPile = cards(discard),
        mortos = mortos,
        redThrees = redThrees.map(::cards),
        tables = listOf(ownTable, opponentTable),
        firstSeat = Seat(0),
        currentSeat = Seat(0),
        phase = phase,
        mortoStatus = mortoStatus,
    )
}

private fun table(melds: List<String>): SideTable =
    melds.fold(SideTable()) { table, meld -> table.createMeld(cards(meld)).getOrThrow() }
