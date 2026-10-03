package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.cards

/** Carta pela notação curta. */
fun c(text: String): Card = Card.parse(text)

/**
 * Builder de cenários de teste: monta mãos, monte, lixo, mortos e mesa exatos, sem embaralhar.
 * Não completa as 104 cartas (os cenários são pequenos); só a simulação verifica essa invariante.
 */
class RoundStateBuilder(private val mode: GameMode) {
    private val hands = MutableList(mode.seatCount) { emptyList<Card>() }
    private val mortos = mutableListOf(
        cards("4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' AC'"),
        cards("4D' 5D' 6D' 7D' 8D' 9D' TD' JD' QD' KD' AD'"),
    )
    private val mortoStatus = MutableList<MortoStatus>(2) { MortoStatus.Available }
    private val tables = MutableList(mode.sideCount) { SideTable() }
    private val redThrees = MutableList(mode.sideCount) { emptyList<Card>() }

    /** Monte: primeira carta = topo. */
    var stock: List<Card> = cards("9S' 8S' 7S' 6S' 5S'")
    /** Lixo: última carta = topo. */
    var discardPile: List<Card> = emptyList()
    var current: Int = 0
    var phase: Phase = Phase.AWAITING_DRAW
    /** Se definido, a partida está encerrada ([Phase.FINISHED]) com este resultado. */
    var result: RoundResult? = null
    /** §3.5 / §6.5 registro público de quem baixou cada 3 vermelho (vazio por padrão). */
    var redThreeLog: List<RedThreeLaid> = emptyList()

    fun hand(seat: Int, text: String) { hands[seat] = cards(text) }
    fun stock(text: String) { stock = cards(text) }
    fun discard(text: String) { discardPile = cards(text) }
    fun morto(index: Int, text: String) { mortos[index] = cards(text) }
    fun mortoTaken(index: Int, side: Int) {
        mortos[index] = emptyList()
        mortoStatus[index] = MortoStatus.Taken(Side(side))
    }
    fun mortoBecameStock(index: Int) {
        mortos[index] = emptyList()
        mortoStatus[index] = MortoStatus.BecameStock
    }
    fun redThrees(side: Int, text: String) { redThrees[side] = cards(text) }

    /** Baixa um conjunto válido na mesa do lado (ids sequenciais a partir de 0). */
    fun meld(side: Int, text: String) {
        tables[side] = tables[side].createMeld(cards(text)).getOrThrow()
    }

    fun build(): RoundState = RoundState(
        mode = mode,
        hands = hands.toList(),
        stock = stock,
        discardPile = discardPile,
        mortos = mortos.toList(),
        redThrees = redThrees.toList(),
        tables = tables.toList(),
        firstSeat = Seat(0),
        currentSeat = Seat(current),
        phase = if (result != null) Phase.FINISHED else phase,
        result = result,
        mortoStatus = mortoStatus.toList(),
        redThreeLog = redThreeLog,
    )
}

fun round(mode: GameMode = GameMode.INDIVIDUAL, block: RoundStateBuilder.() -> Unit): RoundState =
    RoundStateBuilder(mode).apply(block).build()

fun RoundState.act(seat: Int, action: Action): RoundState = RoundEngine.apply(this, Seat(seat), action)

fun RoundState.check(seat: Int, action: Action): RuleResult<Unit> = RoundEngine.validate(this, Seat(seat), action)

fun RoundState.legalActions(seat: Int): List<Action> = RoundEngine.legalActions(this, Seat(seat))

fun RoundState.hand(seat: Int): List<Card> = handOf(Seat(seat))

fun RoundState.table(side: Int): SideTable = tableOf(Side(side))

fun create(text: String) = Action.CreateMeld(cards(text))
fun addTo(id: Int, text: String) = Action.AddToMeld(MeldId(id), cards(text))
fun discardCard(text: String) = Action.Discard(c(text))
fun takeNew(text: String) = Action.TakeDiscardPile(DiscardPlan.NewMeld(cards(text)))
fun takeAdd(id: Int, text: String = "") = Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(id), cards(text)))

/** Conjuntos de cartas da mão usados nos planos de "pegar o lixo com conjunto novo" listados. */
fun List<Action>.takeNewPlans(): List<Set<Card>> =
    filterIsInstance<Action.TakeDiscardPile>().mapNotNull { (it.plan as? DiscardPlan.NewMeld)?.handCards?.toSet() }

/** Planos de "pegar o lixo acrescentando a um conjunto": (id, cartas da mão). */
fun List<Action>.takeAddPlans(): List<Pair<Int, Set<Card>>> =
    filterIsInstance<Action.TakeDiscardPile>().mapNotNull { a ->
        (a.plan as? DiscardPlan.AddToMeld)?.let { it.meldId.value to it.handCards.toSet() }
    }

/** Conjuntos novos listados (cartas como conjunto). */
fun List<Action>.createPlans(): List<Set<Card>> = filterIsInstance<Action.CreateMeld>().map { it.cards.toSet() }

/** Acréscimos listados: (id, cartas). */
fun List<Action>.addPlans(): List<Pair<Int, Set<Card>>> =
    filterIsInstance<Action.AddToMeld>().map { it.meldId.value to it.cards.toSet() }

/** Entrada do registro de 3 vermelhos (§3.5 / §6.5). */
fun laid(seat: Int, card: String, atDeal: Boolean = false) = RedThreeLaid(Seat(seat), c(card), atDeal)

/**
 * §3.5 / §6.5 invariante do registro público de 3 vermelhos: para cada lado, as cartas do registro baixadas
 * pelos assentos do lado são exatamente os 3 vermelhos do lado, na mesma ordem; e as trocas da distribuição
 * (§3.5) vêm todas antes das trocas durante a jogada.
 */
fun RoundState.redThreeLogViolation(): String? {
    for (side in mode.sides) {
        val logged = redThreeLog.filter { mode.sideOf(it.seat) == side }.map { it.card }
        if (logged != redThreesOf(side)) return "lado ${side.index}: registro $logged != 3 vermelhos ${redThreesOf(side)}"
    }
    val firstInPlay = redThreeLog.indexOfFirst { !it.atDeal }
    if (firstInPlay >= 0 && redThreeLog.drop(firstInPlay).any { it.atDeal }) {
        return "troca da distribuição depois de troca durante a jogada: $redThreeLog"
    }
    return null
}
