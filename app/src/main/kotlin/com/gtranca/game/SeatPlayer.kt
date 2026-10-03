package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.engine.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Quem ocupa um assento: um jogador virtual ou o humano. */
sealed interface SeatPlayer

/** Assento de um jogador virtual; o [GameController] lhe passa só `viewFor(seat)` e o avisa de cada evento. */
class BotSeatPlayer(val bot: BotPlayer) : SeatPlayer

/**
 * O jogador humano: o [GameController] chama [chooseAction] e fica suspenso até a interface enviar uma
 * escolha com [submit]. Só aceita ações presentes na lista `legal` do pedido atual (vinda de
 * `RoundEngine.legalActions`); a interface nunca valida regras por conta própria.
 */
class HumanPlayer : SeatPlayer {

    private class Request(val legal: List<Action>, val answer: CompletableDeferred<Action>)

    private val request = MutableStateFlow<Request?>(null)
    private val _waiting = MutableStateFlow(false)

    /** O controlador está esperando uma jogada do humano. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /** Suspende até a interface enviar uma ação de [legal] (não vazia). */
    suspend fun chooseAction(legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "Sem ações legais para o humano" }
        val pending = Request(legal, CompletableDeferred())
        request.value = pending
        _waiting.value = true
        try {
            return pending.answer.await()
        } finally {
            // Cancelamento (ex.: tela fechada): descarta o pedido para não aceitar escolhas atrasadas.
            request.compareAndSet(pending, null)
            _waiting.value = false
        }
    }

    /**
     * Envia a escolha do humano. Devolve `false` (e ignora) se não há pedido pendente ou se [action] não está
     * na lista `legal` do pedido: use sempre a ação vinda de `legal` (cartas representativas).
     */
    fun submit(action: Action): Boolean {
        val pending = request.value ?: return false
        if (action !in pending.legal) return false
        if (!request.compareAndSet(pending, null)) return false
        return pending.answer.complete(action)
    }
}
