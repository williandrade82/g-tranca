package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.dealRound
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.viewFor
import kotlin.random.Random

fun cards(text: String): List<Card> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(Card::parse)

/** Cenário fixo no modo individual, com o assento 0 da vez. Não completa as 104 cartas. */
fun scenario(
    hand: String,
    phase: Phase = Phase.AWAITING_DRAW,
    discard: String = "",
    ownMelds: List<String> = emptyList(),
    opponentHand: String = "9C 9C' TC",
    stock: String = "5S 6S 7S 8S",
): RoundState {
    val ownTable = ownMelds.fold(SideTable()) { table, meld -> table.createMeld(cards(meld)).getOrThrow() }
    return RoundState(
        mode = GameMode.INDIVIDUAL,
        hands = listOf(cards(hand), cards(opponentHand)),
        stock = cards(stock),
        discardPile = cards(discard),
        mortos = listOf(cards("4D' 5D' 6D'"), cards("4C' 5C' 6C'")),
        redThrees = listOf(emptyList(), emptyList()),
        tables = listOf(ownTable, SideTable()),
        firstSeat = Seat(0),
        currentSeat = Seat(0),
        phase = phase,
        mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available),
    )
}

/** Escolha do [bot] para o assento da vez em [state], a partir da vista e das ações legais do motor. */
fun BotPlayer.decide(state: RoundState): Action {
    val seat = state.currentSeat
    return chooseAction(state.viewFor(seat), RoundEngine.legalActions(state, seat))
}

/**
 * Joga uma partida inteira com [bots] (um por assento), conferindo a cada passo que a ação escolhida
 * está entre as legais. Devolve as ações na ordem em que foram aplicadas.
 */
fun playRound(mode: GameMode, dealSeed: Long, bots: List<BotPlayer>, maxActions: Int = 5_000): List<Action> {
    var state = dealRound(mode, Random(dealSeed))
    val actions = mutableListOf<Action>()
    while (state.phase != Phase.FINISHED) {
        val seat = state.currentSeat
        val legal = RoundEngine.legalActions(state, seat)
        check(legal.isNotEmpty()) { "semente $dealSeed: sem ações legais" }
        val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
        check(action in legal) { "semente $dealSeed: ação fora das legais: $action" }
        val takenFromDiscard = if (action is Action.TakeDiscardPile) state.discardPile.dropLast(1) else emptyList()
        state = RoundEngine.apply(state, seat, action)
        bots.forEach { it.observe(PublicEvent(seat, action, takenFromDiscard)) }
        actions += action
        check(actions.size < maxActions) { "semente $dealSeed: limite de ações" }
    }
    return actions
}
