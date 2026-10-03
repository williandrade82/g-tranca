package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldKind
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Suit

/**
 * Enumeração COMPLETA, a menos de cartas idênticas, das ações válidas (ver [RoundEngine.legalActions]).
 *
 * As cartas da mão são agrupadas em classes de equivalência (valor + naipe; as duas cópias do baralho
 * são intercambiáveis para todas as regras). Para cada classe de jogada, os candidatos são gerados de
 * forma estrutural, sem percorrer subconjuntos da mão:
 * - grupo: quantas naturais de cada naipe daquele número (0..cópias na mão) + coringa opcional;
 * - sequência: faixa de valores (4..Ás) de um naipe, com no máximo um buraco preenchido por coringa,
 *   ou sem buraco e com coringa opcional (o motor posiciona na ponta, §6.3);
 * - acréscimo/plano de lixo: idem, com o resultado contendo o conjunto existente (e o topo do lixo);
 * - coringa no topo do lixo (§5.4): ele ocupa a vaga do coringa, e a mão entra só com naturais.
 * Cada candidato é então conferido por [RoundEngine.step]; só os aceitos são devolvidos.
 */
internal object LegalActions {

    private val SEQUENCE_RANKS: List<Rank> = Rank.entries.filter { it >= Rank.FOUR }

    fun generate(state: RoundState, seat: Seat, rules: RuleSet): List<Action> {
        if (state.phase == Phase.FINISHED || seat != state.currentSeat) return emptyList()
        val candidates: Sequence<Action> = when (state.phase) {
            Phase.AWAITING_DRAW ->
                sequenceOf(Action.DrawFromStock, Action.DeclineDraw) + discardPileCandidates(state, seat)
            Phase.PLAYING -> createCandidates(state, seat) + addCandidates(state, seat) + discardCandidates(state, seat)
            Phase.FINISHED -> emptySequence()
        }
        return candidates.distinct().filter { isValid(state, seat, it, rules) }.toList()
    }

    /** §10.2 existe algum plano válido para pegar o lixo (verificação exata). */
    fun canTakeDiscardPile(state: RoundState, seat: Seat, rules: RuleSet): Boolean =
        discardPileCandidates(state, seat).any { isValid(state, seat, it, rules) }

    private fun isValid(state: RoundState, seat: Seat, action: Action, rules: RuleSet): Boolean =
        RoundEngine.step(state, seat, action, rules) is RuleResult.Ok

    // ---------- índice da mão ----------

    /** Mão agrupada por classe (valor + naipe); coringas por naipe. */
    private class HandIndex(hand: List<Card>) {
        private val byClass: Map<Pair<Rank, Suit>, List<Card>> =
            hand.filter { !it.isWild }.groupBy { it.rank to it.suit }

        /** Opções de coringa: nenhum, ou um representante de cada naipe de coringa presente. */
        val wildOptions: List<Card?> = listOf<Card?>(null) + hand.filter { it.isWild }.distinctBy { it.suit }

        val realWilds: List<Card> = wildOptions.filterNotNull()

        fun natural(rank: Rank, suit: Suit): Card? = byClass[rank to suit]?.first()

        /** Todas as escolhas de naturais do número [rank] (por naipe, 0..cópias), inclusive a vazia. */
        fun groupChoices(rank: Rank): List<List<Card>> =
            Suit.entries.fold(listOf(emptyList())) { acc, suit ->
                val copies = byClass[rank to suit].orEmpty()
                acc.flatMap { partial -> (0..copies.size).map { n -> partial + copies.take(n) } }
            }

        fun naturalRanks(): Set<Rank> = byClass.keys.map { it.first }.toSet()
    }

    // ---------- sequências: faixas com no máximo um buraco ----------

    /**
     * Faixas de valores (4..Ás) e buraco opcional. Para cada uma, devolve os valores naturais necessários
     * (a faixa sem o buraco) e se o coringa é obrigatório (há buraco).
     */
    private fun ranges(): Sequence<Pair<List<Rank>, Boolean>> = sequence {
        for (low in SEQUENCE_RANKS.indices) {
            for (high in low until SEQUENCE_RANKS.size) {
                val range = SEQUENCE_RANKS.subList(low, high + 1)
                yield(range to false)
                for (gap in range.drop(1).dropLast(1)) yield(range.filter { it != gap } to true)
            }
        }
    }

    /** Naturais do [suit] para os [ranks]; `null` se algum faltar na mão. */
    private fun HandIndex.naturalsFor(ranks: List<Rank>, suit: Suit): List<Card>? =
        ranks.map { natural(it, suit) ?: return null }

    private fun HandIndex.wildsFor(needsWild: Boolean): List<Card?> = if (needsWild) realWilds else wildOptions

    // ---------- pegar o lixo (§5.1) ----------

    private fun discardPileCandidates(state: RoundState, seat: Seat): Sequence<Action> = sequence {
        val top = state.discardTop ?: return@sequence
        if (top.rank.isThree) return@sequence
        val index = HandIndex(state.handOf(seat))
        if (top.isWild) {
            yieldAll(wildTopCandidates(state, seat, index))
            return@sequence
        }

        // conjunto novo: grupo do número do topo
        for (choice in index.groupChoices(top.rank)) {
            for (wild in index.wildOptions) {
                val handCards = choice + listOfNotNull(wild)
                if (handCards.size >= 2) yield(Action.TakeDiscardPile(DiscardPlan.NewMeld(handCards)))
            }
        }
        // conjunto novo: sequência do naipe do topo contendo o topo (natural)
        for ((naturalRanks, needsWild) in ranges()) {
            if (top.rank !in naturalRanks) continue
            val fromHand = index.naturalsFor(naturalRanks - top.rank, top.suit) ?: continue
            for (wild in index.wildsFor(needsWild)) {
                val handCards = fromHand + listOfNotNull(wild)
                if (handCards.size >= 2) yield(Action.TakeDiscardPile(DiscardPlan.NewMeld(handCards)))
            }
        }
        // acréscimo do topo (com ou sem cartas da mão) a conjunto do lado
        for (tableMeld in state.tableOf(state.mode.sideOf(seat)).melds) {
            for (handCards in additions(tableMeld.meld, index, extra = top)) {
                yield(Action.TakeDiscardPile(DiscardPlan.AddToMeld(tableMeld.id, handCards)))
            }
        }
    }

    /**
     * §5.4 coringa no topo: ele é o único coringa do conjunto (§6.3), então as cartas da mão são só
     * naturais. Conjunto novo com ≥2 naturais da mão (grupo ou sequência); acréscimo a conjunto sem coringa.
     */
    private fun wildTopCandidates(state: RoundState, seat: Seat, index: HandIndex): Sequence<Action> = sequence {
        for (rank in index.naturalRanks()) {
            if (rank.isThree) continue
            for (choice in index.groupChoices(rank)) {
                if (choice.size >= 2) yield(Action.TakeDiscardPile(DiscardPlan.NewMeld(choice)))
            }
        }
        for (suit in Suit.entries) {
            for ((naturalRanks, _) in ranges()) {
                val naturals = index.naturalsFor(naturalRanks, suit) ?: continue
                if (naturals.size >= 2) yield(Action.TakeDiscardPile(DiscardPlan.NewMeld(naturals)))
            }
        }
        for (tableMeld in state.tableOf(state.mode.sideOf(seat)).melds) {
            val meld = tableMeld.meld
            if (meld.hasWild) continue
            val handChoices: Sequence<List<Card>> = when (val kind = meld.kind) {
                is MeldKind.Group -> index.groupChoices(kind.rank).asSequence()
                is MeldKind.Sequence -> {
                    val existing = meld.cards.map { it.rank }.toSet()
                    ranges().mapNotNull { (naturalRanks, _) ->
                        if (naturalRanks.containsAll(existing)) index.naturalsFor(naturalRanks - existing, kind.suit) else null
                    }
                }
            }
            for (handCards in handChoices) yield(Action.TakeDiscardPile(DiscardPlan.AddToMeld(tableMeld.id, handCards)))
        }
    }

    // ---------- jogada (§4.3 etapas 2 e 3) ----------

    private fun createCandidates(state: RoundState, seat: Seat): Sequence<Action> = sequence {
        val index = HandIndex(state.handOf(seat))
        for (rank in index.naturalRanks()) {
            if (rank.isWild || rank.isThree) continue
            for (choice in index.groupChoices(rank)) {
                for (wild in index.wildOptions) {
                    val cards = choice + listOfNotNull(wild)
                    if (cards.size >= 3) yield(Action.CreateMeld(cards))
                }
            }
        }
        for (suit in Suit.entries) {
            for ((naturalRanks, needsWild) in ranges()) {
                val naturals = index.naturalsFor(naturalRanks, suit) ?: continue
                for (wild in index.wildsFor(needsWild)) {
                    val cards = naturals + listOfNotNull(wild)
                    if (cards.size >= 3) yield(Action.CreateMeld(cards))
                }
            }
        }
    }

    private fun addCandidates(state: RoundState, seat: Seat): Sequence<Action> = sequence {
        val index = HandIndex(state.handOf(seat))
        for (tableMeld in state.tableOf(state.mode.sideOf(seat)).melds) {
            for (cards in additions(tableMeld.meld, index, extra = null)) {
                if (cards.isNotEmpty()) yield(Action.AddToMeld(tableMeld.id, cards))
            }
        }
    }

    private fun discardCandidates(state: RoundState, seat: Seat): Sequence<Action> =
        state.handOf(seat).distinctBy { it.rank to it.suit }.asSequence().map { Action.Discard(it) }

    /**
     * Cartas da mão que podem ser acrescentadas a [meld], junto com [extra] (o topo do lixo) se houver.
     * Devolve só as cartas da mão (o [extra] é implícito).
     */
    private fun additions(meld: Meld, index: HandIndex, extra: Card?): Sequence<List<Card>> = sequence {
        when (val kind = meld.kind) {
            is MeldKind.Group -> {
                if (extra != null && extra.rank != kind.rank) return@sequence
                for (choice in index.groupChoices(kind.rank)) {
                    for (wild in index.wildOptions) yield(choice + listOfNotNull(wild))
                }
            }
            is MeldKind.Sequence -> {
                if (extra != null && extra.suit != kind.suit) return@sequence
                val existing = meld.cards.filterNot { it.isWild }.map { it.rank }.toSet()
                for ((naturalRanks, needsWild) in ranges()) {
                    if (!naturalRanks.containsAll(existing)) continue
                    val newRanks = naturalRanks - existing
                    if (extra != null && extra.rank !in newRanks) continue
                    val fromHand = index.naturalsFor(newRanks - listOfNotNull(extra?.rank), kind.suit) ?: continue
                    val wildChoices: List<Card?> = when {
                        meld.hasWild -> listOf(null) // o coringa do conjunto cobre o buraco ou corre
                        needsWild -> index.realWilds
                        else -> index.wildOptions
                    }
                    for (wild in wildChoices) yield(fromHand + listOfNotNull(wild))
                }
            }
        }
    }
}
