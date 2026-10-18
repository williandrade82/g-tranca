package com.gtranca.ui.game

import com.gtranca.ai.PublicEvent
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
        listOfNotNull(
            RecordEntry(ActionKind.TAKE, takenFromDiscard),
            (handCards + listOfNotNull(takenTop)).takeIf { it.isNotEmpty() }?.let { RecordEntry(kind, it) },
        )
    }
}

fun List<PublicEvent>.record(): List<RecordEntry> = flatMap { it.record() }
