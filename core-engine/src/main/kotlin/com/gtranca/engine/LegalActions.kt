package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldKind
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Suit

/**
 * Gera candidatos de ação agrupando as cartas por naipe/valor (sem força bruta sobre subconjuntos)
 * e mantém só os aceitos por [RoundEngine.step]. Ver [RoundEngine.legalActions].
 */
internal object LegalActions {

    private val SEQUENCE_RANKS: List<Rank> = Rank.entries.filter { it >= Rank.FOUR }

    fun generate(state: RoundState, seat: Seat, rules: RuleSet): List<Action> {
        if (state.phase == Phase.FINISHED || seat != state.currentSeat) return emptyList()
        val candidates: List<Action> = when (state.phase) {
            Phase.AWAITING_DRAW -> listOf(Action.DrawFromStock, Action.DeclineDraw) + discardPileCandidates(state, seat, rules)
            Phase.PLAYING -> createCandidates(state, seat, rules) + addCandidates(state, seat) + discardCandidates(state, seat)
            Phase.FINISHED -> emptyList()
        }
        return candidates.distinct().filter { isValid(state, seat, it, rules) }
    }

    /** Planos válidos para pegar o lixo (usado também na verificação de fim sem vencedor, §10.2). */
    fun discardPileOptions(state: RoundState, seat: Seat, rules: RuleSet): List<Action> =
        discardPileCandidates(state, seat, rules).distinct().filter { isValid(state, seat, it, rules) }

    private fun isValid(state: RoundState, seat: Seat, action: Action, rules: RuleSet): Boolean =
        RoundEngine.step(state, seat, action, rules) is RuleResult.Ok

    // ---------- pegar o lixo (§5.1) ----------

    private fun discardPileCandidates(state: RoundState, seat: Seat, rules: RuleSet): List<Action> {
        val top = state.discardTop ?: return emptyList()
        if (top.isWild || top.rank.isThree) return emptyList()
        val hand = state.handOf(seat)
        val wild = hand.firstOrNull { it.isWild }
        val result = mutableListOf<Action>()

        // acréscimo do topo a conjunto do lado: sozinho ou preenchendo o intervalo até a sequência
        for (tableMeld in state.tableOf(state.mode.sideOf(seat)).melds) {
            val id = tableMeld.id
            result += Action.TakeDiscardPile(DiscardPlan.AddToMeld(id))
            val kind = tableMeld.meld.kind
            if (kind is MeldKind.Sequence && kind.suit == top.suit) {
                val meld = tableMeld.meld
                val gap = when {
                    top.rank > meld.highRank -> Rank.entries.filter { it > meld.highRank && it < top.rank }
                    top.rank < meld.lowRank -> Rank.entries.filter { it > top.rank && it < meld.lowRank }
                    else -> emptyList()
                }
                if (gap.isNotEmpty()) {
                    val found = gap.mapNotNull { r -> hand.firstOrNull { !it.isWild && it.suit == top.suit && it.rank == r } }
                    val missing = gap.size - found.size
                    if (missing == 0) result += Action.TakeDiscardPile(DiscardPlan.AddToMeld(id, found))
                    if (missing == 1 && wild != null) result += Action.TakeDiscardPile(DiscardPlan.AddToMeld(id, found + wild))
                }
            }
        }

        // grupo novo: topo + 2 naturais do mesmo número, ou topo + 1 natural + coringa
        val sameRank = hand.filter { !it.isWild && it.rank == top.rank }
        if (sameRank.size >= 2) result += Action.TakeDiscardPile(DiscardPlan.NewMeld(sameRank.take(2)))
        if (sameRank.isNotEmpty() && wild != null) {
            result += Action.TakeDiscardPile(DiscardPlan.NewMeld(listOf(sameRank.first(), wild)))
        }

        // sequência nova contendo o topo: a de menor tamanho válida
        val bySuit = representativesBySuit(hand, top.suit)
        val runs = sequenceRuns(bySuit + (top.rank to top), wild, mustContain = top.rank)
        for (length in runs.keys.sorted()) {
            val valid = runs.getValue(length)
                .map { cards -> Action.TakeDiscardPile(DiscardPlan.NewMeld(cards - top)) }
                .filter { isValid(state, seat, it, rules) }
            if (valid.isNotEmpty()) {
                result += valid
                break
            }
        }
        return result
    }

    // ---------- jogada (§4.3 etapa 2 e 3) ----------

    private fun createCandidates(state: RoundState, seat: Seat, rules: RuleSet): List<Action> {
        val hand = state.handOf(seat)
        val wild = hand.firstOrNull { it.isWild }
        val result = mutableListOf<Action>()
        val naturals = hand.filter { !it.isWild && !it.rank.isThree }
        for ((_, cards) in naturals.groupBy { it.rank }) {
            if (cards.size >= 3) result += Action.CreateMeld(cards.take(3))
            if (cards.size >= 2 && wild != null) result += Action.CreateMeld(cards.take(2) + wild)
        }
        for (suit in Suit.entries) {
            val runs = sequenceRuns(representativesBySuit(hand, suit), wild, mustContain = null)
            for (length in runs.keys.sorted()) {
                val valid = runs.getValue(length).map { Action.CreateMeld(it) }.filter { isValid(state, seat, it, rules) }
                if (valid.isNotEmpty()) {
                    result += valid
                    break
                }
            }
        }
        return result
    }

    private fun addCandidates(state: RoundState, seat: Seat): List<Action> {
        val hand = state.handOf(seat)
        return state.tableOf(state.mode.sideOf(seat)).melds.flatMap { tableMeld ->
            hand.filter { card ->
                card.isWild || when (val kind = tableMeld.meld.kind) {
                    is MeldKind.Sequence -> card.suit == kind.suit
                    is MeldKind.Group -> card.rank == kind.rank
                }
            }.map { Action.AddToMeld(tableMeld.id, listOf(it)) }
        }
    }

    private fun discardCandidates(state: RoundState, seat: Seat): List<Action> =
        state.handOf(seat).map { Action.Discard(it) }

    // ---------- sequências ----------

    /** Uma carta natural representativa por valor de sequência (4..Ás) do [suit]. */
    private fun representativesBySuit(hand: List<Card>, suit: Suit): Map<Rank, Card> =
        hand.filter { !it.isWild && it.suit == suit && it.rank >= Rank.FOUR }
            .groupBy { it.rank }
            .mapValues { (_, cards) -> cards.first() }

    /**
     * Faixas consecutivas (4..Ás) de 3 ou mais valores formáveis com [available] e, no máximo,
     * um [wild] cobrindo um valor ausente; agrupadas por tamanho. Se [mustContain], a faixa o inclui.
     */
    private fun sequenceRuns(available: Map<Rank, Card>, wild: Card?, mustContain: Rank?): Map<Int, List<List<Card>>> {
        val runs = mutableMapOf<Int, MutableList<List<Card>>>()
        for (lowIndex in SEQUENCE_RANKS.indices) {
            for (highIndex in lowIndex + 2 until SEQUENCE_RANKS.size) {
                val range = SEQUENCE_RANKS.subList(lowIndex, highIndex + 1)
                if (mustContain != null && mustContain !in range) continue
                val missing = range.filter { it !in available }
                if (missing.size > 1 || (missing.size == 1 && wild == null)) continue
                val cards = range.mapNotNull { available[it] } + listOfNotNull(wild.takeIf { missing.size == 1 })
                runs.getOrPut(range.size) { mutableListOf() } += cards
            }
        }
        return runs
    }
}
