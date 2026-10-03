package com.gtranca.game

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Suit

/** Classe de uma carta: valor e naipe. As duas cópias (`deck` 0/1) são equivalentes para todas as regras. */
data class CardClass(val rank: Rank, val suit: Suit)

val Card.cardClass: CardClass get() = CardClass(rank, suit)

/** Multiconjunto de classes (valor, naipe) das cartas. */
fun List<Card>.classCounts(): Map<CardClass, Int> = groupingBy { it.cardClass }.eachCount()

/** O que o humano quer fazer com a seleção atual. */
sealed interface PlayIntent {
    /** Comprar do monte. */
    data object Draw : PlayIntent

    /** §10.2 recusar a compra (a interface pede confirmação antes). */
    data object DeclineDraw : PlayIntent

    /** Pegar o lixo levando o topo à mesa com as cartas selecionadas. */
    data object TakeDiscardPile : PlayIntent

    /** Baixar as cartas selecionadas como conjunto novo. */
    data object CreateMeld : PlayIntent

    /** Acrescentar as cartas selecionadas ao conjunto [meldId] do lado. */
    data class AddToMeld(val meldId: MeldId) : PlayIntent

    /** Descartar a carta selecionada. */
    data object Discard : PlayIntent
}

/** Resultado de casar a intenção e a seleção do humano com as ações legais. */
sealed interface Resolution {
    /** Uma única ação de `legal` corresponde: aplicar exatamente esta. */
    data class Play(val action: Action) : Resolution

    /** Mais de um plano para pegar o lixo corresponde à seleção: o humano escolhe. */
    data class ChoosePlan(val options: List<Action.TakeDiscardPile>) : Resolution

    /** Nenhuma ação corresponde; [reason] vem de `RoundEngine.validate` (`null` se o motor não apontou motivo). */
    data class Rejected(val reason: RuleError?) : Resolution

    /** Para descartar, a seleção deve ter exatamente uma carta. */
    data object SelectOneCardToDiscard : Resolution
}

/**
 * Casa a seleção do humano com `RoundEngine.legalActions`, a única fonte de jogadas válidas.
 *
 * `legalActions` lista cada classe de jogada uma vez, com cartas representativas; a seleção casa com uma
 * ação quando o **multiconjunto de (valor, naipe)** das cartas da mão coincide, nunca pela carta física.
 * A ação aplicada é a de `legal`. Sem correspondência, o motivo vem de `explain` (`RoundEngine.validate`
 * aplicado à jogada montada com as cartas físicas selecionadas).
 */
object HumanTurnResolver {

    /** Cartas da mão usadas pela ação (sem o topo do lixo). */
    fun handCardsOf(action: Action): List<Card> = when (action) {
        is Action.CreateMeld -> action.cards
        is Action.AddToMeld -> action.cards
        is Action.TakeDiscardPile -> when (val plan = action.plan) {
            is DiscardPlan.NewMeld -> plan.handCards
            is DiscardPlan.AddToMeld -> plan.handCards
        }
        is Action.Discard -> listOf(action.card)
        Action.DrawFromStock, Action.DeclineDraw -> emptyList()
    }

    fun resolve(
        intent: PlayIntent,
        selection: List<Card>,
        legal: List<Action>,
        explain: (Action) -> RuleError?,
    ): Resolution {
        val wanted = selection.classCounts()
        fun sameCards(action: Action) = handCardsOf(action).classCounts() == wanted
        fun reject(candidate: Action) = Resolution.Rejected(explain(candidate))

        return when (intent) {
            PlayIntent.Draw ->
                if (Action.DrawFromStock in legal) Resolution.Play(Action.DrawFromStock) else reject(Action.DrawFromStock)
            PlayIntent.DeclineDraw ->
                if (Action.DeclineDraw in legal) Resolution.Play(Action.DeclineDraw) else reject(Action.DeclineDraw)
            PlayIntent.TakeDiscardPile -> {
                val options = legal.filterIsInstance<Action.TakeDiscardPile>().filter(::sameCards)
                when (options.size) {
                    0 -> reject(Action.TakeDiscardPile(DiscardPlan.NewMeld(selection)))
                    1 -> Resolution.Play(options.single())
                    else -> Resolution.ChoosePlan(options)
                }
            }
            PlayIntent.CreateMeld ->
                legal.firstOrNull { it is Action.CreateMeld && sameCards(it) }?.let(Resolution::Play)
                    ?: reject(Action.CreateMeld(selection))
            is PlayIntent.AddToMeld ->
                legal.firstOrNull { it is Action.AddToMeld && it.meldId == intent.meldId && sameCards(it) }
                    ?.let(Resolution::Play)
                    ?: reject(Action.AddToMeld(intent.meldId, selection))
            PlayIntent.Discard -> {
                val card = selection.singleOrNull() ?: return Resolution.SelectOneCardToDiscard
                legal.firstOrNull { it is Action.Discard && it.card.cardClass == card.cardClass }
                    ?.let(Resolution::Play)
                    ?: reject(Action.Discard(card))
            }
        }
    }

    /**
     * Classes de cartas a destacar na mão: as que participam de alguma jogada de baixar, acrescentar ou pegar o
     * lixo em `legal` compatível com a [selection] (cujas cartas cabem, com multiplicidade, na jogada). O
     * descarte fica de fora: qualquer carta (exceto 3 vermelho) pode ser descartada e destacaria a mão toda.
     */
    fun highlightedClasses(legal: List<Action>, selection: List<Card>): Set<CardClass> {
        val wanted = selection.classCounts()
        val result = mutableSetOf<CardClass>()
        for (action in legal) {
            if (action !is Action.CreateMeld && action !is Action.AddToMeld && action !is Action.TakeDiscardPile) continue
            val counts = handCardsOf(action).classCounts()
            if (wanted.all { (cls, n) -> (counts[cls] ?: 0) >= n }) result += counts.keys
        }
        return result
    }
}
