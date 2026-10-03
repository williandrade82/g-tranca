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
    opponentMelds: List<String> = emptyList(),
    mortoStatus: List<MortoStatus> = listOf(MortoStatus.Available, MortoStatus.Available),
): RoundState {
    val ownTable = table(ownMelds)
    return RoundState(
        mode = GameMode.INDIVIDUAL,
        hands = listOf(cards(hand), cards(opponentHand)),
        stock = cards(stock),
        discardPile = cards(discard),
        mortos = listOf(cards("4D' 5D' 6D'"), cards("4C' 5C' 6C'")),
        redThrees = listOf(emptyList(), emptyList()),
        tables = listOf(ownTable, table(opponentMelds)),
        firstSeat = Seat(0),
        currentSeat = Seat(0),
        phase = phase,
        mortoStatus = mortoStatus,
    ).withEmptyTakenMortos()
}

/**
 * Cenário fixo no modo duplas, com o assento 0 da vez; o parceiro é o assento 2 (§1.1) e os conjuntos
 * do lado 0 ([ownMelds]) são compartilhados por ele (§6.4). Não completa as 104 cartas.
 */
fun duplasScenario(
    hand: String,
    phase: Phase = Phase.PLAYING,
    discard: String = "",
    ownMelds: List<String> = emptyList(),
    opponentMelds: List<String> = emptyList(),
    otherHands: List<String> = listOf("9C 9C' TC", "JD JD' QD", "KC KC' AC"),
    stock: String = "5S 6S 7S 8S",
    mortoStatus: List<MortoStatus> = listOf(MortoStatus.Available, MortoStatus.Available),
): RoundState = RoundState(
    mode = GameMode.DUPLAS,
    hands = listOf(cards(hand)) + otherHands.map(::cards),
    stock = cards(stock),
    discardPile = cards(discard),
    mortos = listOf(cards("4D' 5D' 6D'"), cards("4C' 5C' 6C'")),
    redThrees = listOf(emptyList(), emptyList()),
    tables = listOf(table(ownMelds), table(opponentMelds)),
    firstSeat = Seat(0),
    currentSeat = Seat(0),
    phase = phase,
    mortoStatus = mortoStatus,
).withEmptyTakenMortos()

private fun table(melds: List<String>): SideTable =
    melds.fold(SideTable()) { table, meld -> table.createMeld(cards(meld)).getOrThrow() }

/** Morto que não está disponível fica vazio (invariante de [RoundState.mortoStatus]). */
private fun RoundState.withEmptyTakenMortos(): RoundState =
    copy(mortos = mortos.mapIndexed { i, morto -> if (mortoStatus[i] == MortoStatus.Available) morto else emptyList() })

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
