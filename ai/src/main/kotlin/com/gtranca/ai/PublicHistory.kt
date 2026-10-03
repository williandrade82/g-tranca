package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat

/**
 * Memória do histórico **público** de uma partida, montada só a partir de [PublicEvent]s:
 * - cartas que cada assento levou do lixo para a mão (o lixo é aberto, §5.6; §5.2) e que ainda não
 *   saíram dela (baixadas ou descartadas): são cartas **sabidamente** na mão desse assento;
 * - descartes de cada assento (§8).
 *
 * Não tem acesso a nada oculto. Deve ser zerada a cada partida ([clear]).
 */
class PublicHistory {
    private val known = HashMap<Int, MutableList<Card>>()
    private val discards = HashMap<Int, MutableList<Card>>()

    /** Cartas sabidamente na mão de [seat] (vieram do lixo e ainda não saíram). */
    fun knownHand(seat: Seat): List<Card> = known[seat.index].orEmpty()

    /** Cartas descartadas por [seat] nesta partida, em ordem. */
    fun discardsOf(seat: Seat): List<Card> = discards[seat.index].orEmpty()

    fun clear() {
        known.clear()
        discards.clear()
    }

    fun record(event: PublicEvent) {
        val hand = known.getOrPut(event.seat.index) { mutableListOf() }
        when (val action = event.action) {
            is Action.TakeDiscardPile -> {
                val fromHand = when (val plan = action.plan) {
                    is DiscardPlan.NewMeld -> plan.handCards
                    is DiscardPlan.AddToMeld -> plan.handCards
                }
                fromHand.forEach { hand.removeSameClass(it) }
                hand += event.takenFromDiscard
            }
            is Action.CreateMeld -> action.cards.forEach { hand.removeSameClass(it) }
            is Action.AddToMeld -> action.cards.forEach { hand.removeSameClass(it) }
            is Action.Discard -> {
                hand.removeSameClass(action.card)
                discards.getOrPut(event.seat.index) { mutableListOf() } += action.card
            }
            Action.DrawFromStock, Action.DeclineDraw -> Unit
        }
    }

    /**
     * Remove [card] ou, se a cópia exata não estiver na lista, a outra cópia do mesmo valor e naipe:
     * as ações usam cartas representativas e as duas cópias são equivalentes para as regras.
     */
    private fun MutableList<Card>.removeSameClass(card: Card) {
        if (!remove(card)) {
            val index = indexOfFirst { it.rank == card.rank && it.suit == card.suit }
            if (index >= 0) removeAt(index)
        }
    }
}
