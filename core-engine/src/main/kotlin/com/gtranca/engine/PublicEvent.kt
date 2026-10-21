package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat
import kotlinx.serialization.Serializable

/**
 * Ação pública aplicada pelo jogador [seat].
 *
 * @property takenFromDiscard em [Action.TakeDiscardPile], as cartas do lixo que foram para a mão
 *   desse jogador (o lixo sem o topo, §5.2); é informação pública, pois o lixo é aberto (§5.6).
 *   Vazio para as demais ações.
 * @property takenTop em [Action.TakeDiscardPile], o topo do lixo, que vai à mesa junto do plano (§5.1); `null` nas
 *   demais ações. Também público (o lixo é aberto).
 */
@Serializable
data class PublicEvent(
    val seat: Seat,
    val action: Action,
    val takenFromDiscard: List<Card> = emptyList(),
    val takenTop: Card? = null,
) {
    companion object {
        /**
         * Evento da [action] do [seat], dado o lixo [discardPileBefore] (último = topo) **antes** de aplicar a
         * ação: depois dela o lixo já foi levado. Única forma de montar o evento de uma ação real, usada pelo
         * app, pelo `:sim` e pelas simulações do Difícil.
         */
        fun of(discardPileBefore: List<Card>, seat: Seat, action: Action): PublicEvent = PublicEvent(
            seat = seat,
            action = action,
            takenFromDiscard = if (action is Action.TakeDiscardPile) discardPileBefore.dropLast(1) else emptyList(),
            takenTop = if (action is Action.TakeDiscardPile) discardPileBefore.lastOrNull() else null,
        )
    }
}
