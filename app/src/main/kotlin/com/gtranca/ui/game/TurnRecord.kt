package com.gtranca.ui.game

import com.gtranca.engine.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card

/**
 * Uma linha do registro da vez de um assento (pílula e diálogo do jogador): o tipo da jogada e as cartas dela.
 * Em [ActionKind.TAKE], [cards] são as cartas do lixo que foram para a mão.
 */
data class RecordEntry(val kind: ActionKind, val cards: List<Card> = emptyList())

/**
 * Entradas do registro de uma ação pública. Pegar o lixo (§5.1) são duas jogadas: o que foi para a mão e o que foi
 * à mesa (as cartas da mão do plano mais o topo do lixo, num jogo novo ou acrescentado a um da mesa).
 */
fun PublicEvent.record(): List<RecordEntry> = when (val a = action) {
    Action.DrawFromStock -> listOf(RecordEntry(ActionKind.DRAW))
    Action.DeclineDraw -> listOf(RecordEntry(ActionKind.DECLINE))
    is Action.CreateMeld -> listOf(RecordEntry(ActionKind.MELD, a.cards))
    is Action.AddToMeld -> listOf(RecordEntry(ActionKind.ADD, a.cards))
    is Action.Discard -> listOf(RecordEntry(ActionKind.DISCARD, listOf(a.card)))
    is Action.TakeDiscardPile -> {
        val (kind, handCards) = when (val plan = a.plan) {
            is DiscardPlan.NewMeld -> ActionKind.MELD to plan.handCards
            is DiscardPlan.AddToMeld -> ActionKind.ADD to plan.handCards
        }
        // Na ordem das animações (§5.1): primeiro o topo e as cartas da mão vão ao jogo; depois o resto do lixo vai à mão.
        listOfNotNull(
            (handCards + listOfNotNull(takenTop)).takeIf { it.isNotEmpty() }?.let { RecordEntry(kind, it) },
            RecordEntry(ActionKind.TAKE, takenFromDiscard),
        )
    }
}

fun List<PublicEvent>.record(): List<RecordEntry> = flatMap { it.record() }

/** Um item da vez em ordem cronológica: 3 vermelhos baixados naquele ponto ou uma linha de uma ação. */
sealed interface TurnItem {
    data class RedThrees(val cards: List<Card>) : TurnItem

    data class Entry(val event: PublicEvent, val entry: RecordEntry) : TurnItem
}

/**
 * §3.5, §6.5 a vez de um assento na ordem real, a mesma das animações: os 3 vermelhos entram depois das ações que já
 * tinham acontecido quando foram baixados ([redThreeAfter]: 0 = início da vez; 1 = depois da compra...). Sem essa
 * informação (vista antiga), os 3 vermelhos vêm no início.
 */
fun turnTimeline(events: List<PublicEvent>, redThrees: List<Card>, redThreeAfter: List<Int>): List<TurnItem> {
    val after = if (redThreeAfter.size == redThrees.size) redThreeAfter else List(redThrees.size) { 0 }
    val items = mutableListOf<TurnItem>()
    for (i in 0..events.size) {
        val laid = redThrees.filterIndexed { k, _ -> after[k].coerceIn(0, events.size) == i }
        if (laid.isNotEmpty()) items += TurnItem.RedThrees(laid)
        if (i < events.size) events[i].record().forEach { items += TurnItem.Entry(events[i], it) }
    }
    return items
}
