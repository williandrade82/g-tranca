package com.gtranca.game

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RuleResult

/**
 * Ordem das sugestões ao pegar o descarte (só apresentação: quem escolhe é sempre o jogador).
 *
 * 1. Planos sem coringa antes dos com coringa.
 * 2. Dentro de cada grupo, o que mais pontuará no fim: o conjunto resultante estendido com as cartas que ficarão
 *    na mão (a mão restante mais o resto do descarte), contando pontos das cartas e a canastra que fecharia.
 * 3. Empate: o que mais pontua já ao baixar; depois, o que usa menos cartas da mão.
 */
object PlanRanking {

    fun rank(options: List<Action.TakeDiscardPile>, view: PlayerView, rules: RuleSet = RuleSet.DEFAULT): List<Action.TakeDiscardPile> {
        val top = view.discardPile.lastOrNull() ?: return options
        val rest = view.discardPile.dropLast(1)
        val scored = options.map { option ->
            val meld = resultingMeld(option.plan, top, view, rules)
            val handCards = HumanTurnResolver.handCardsOf(option)
            val remaining = view.hand.minusEach(handCards) + rest
            val potential = meld?.let { meldPoints(extend(it, remaining, rules), rules) } ?: Int.MIN_VALUE
            val now = meld?.let { meldPoints(it, rules) } ?: Int.MIN_VALUE
            Scored(option, meld?.hasWild ?: true, potential, now, handCards.size)
        }
        return scored.sortedWith(
            compareBy<Scored> { it.wild }
                .thenByDescending { it.potential }
                .thenByDescending { it.now }
                .thenBy { it.handCards },
        ).map { it.option }
    }

    private data class Scored(val option: Action.TakeDiscardPile, val wild: Boolean, val potential: Int, val now: Int, val handCards: Int)

    private fun resultingMeld(plan: DiscardPlan, top: Card, view: PlayerView, rules: RuleSet): Meld? = when (plan) {
        is DiscardPlan.NewMeld -> Meld.create(plan.handCards + top, rules).okOrNull()
        is DiscardPlan.AddToMeld -> view.tables[view.side.index].meld(plan.meldId)?.add(plan.handCards + top, rules)?.okOrNull()
    }

    /** Acrescenta, uma a uma, as cartas naturais de [cards] que cabem no conjunto (sem coringas: mantém limpo). */
    internal fun extend(meld: Meld, cards: List<Card>, rules: RuleSet): Meld {
        var current = meld
        val pool = cards.filter { !it.isWild && !it.rank.isThree }.toMutableList()
        var grew = true
        while (grew) {
            grew = false
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val next = current.add(listOf(iterator.next()), rules).okOrNull() ?: continue
                current = next
                iterator.remove()
                grew = true
            }
        }
        return current
    }

    /** §12.1 pontos de um conjunto: cartas mais a canastra (limpa ou suja), se tiver. */
    internal fun meldPoints(meld: Meld, rules: RuleSet): Int {
        val cards = meld.cards.sumOf { card ->
            when {
                card.isWild -> rules.tableWildPoints
                card.rank in Rank.FOUR..Rank.TEN -> rules.tableFourToTenPoints
                else -> rules.tableFaceCardOrAcePoints
            }
        }
        val canasta = when {
            meld.isCleanCanasta(rules) -> rules.cleanCanastaPoints
            meld.isDirtyCanasta(rules) -> rules.dirtyCanastaPoints
            else -> 0
        }
        return cards + canasta
    }

    private fun <T> RuleResult<T>.okOrNull(): T? = (this as? RuleResult.Ok)?.value

    private fun List<Card>.minusEach(remove: List<Card>): List<Card> {
        val left = toMutableList()
        remove.forEach { left.remove(it) }
        return left
    }
}
