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

    private class Request(val id: Long, val legal: List<Action>, val answer: CompletableDeferred<Action>)

    private val request = MutableStateFlow<Request?>(null)
    private val _waiting = MutableStateFlow(false)

    /** O controlador está esperando uma jogada do humano. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /** Identificador do pedido em aberto, ou `null`. */
    val pendingRequestId: Long? get() = request.value?.id

    /**
     * Suspende até a interface enviar uma ação de [legal] (não vazia). [requestId] identifica o pedido (o
     * controlador o publica no snapshot); a resposta a um pedido anterior é recusada em [submit].
     */
    suspend fun chooseAction(legal: List<Action>, requestId: Long = 0): Action {
        require(legal.isNotEmpty()) { "Sem ações legais para o humano" }
        val pending = Request(requestId, legal, CompletableDeferred())
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
     * Envia a escolha do humano. Devolve `false` (e ignora) se não há pedido pendente, se [requestId] (quando
     * informado) não é o do pedido em aberto, ou se [action] não está na lista `legal` do pedido: use sempre a
     * ação vinda de `legal` (cartas representativas), resolvida contra o snapshot do mesmo pedido.
     */
    fun submit(action: Action, requestId: Long? = null): Boolean {
        val pending = request.value ?: return false
        if (requestId != null && requestId != pending.id) return false
        if (action !in pending.legal) return false
        if (!request.compareAndSet(pending, null)) return false
        // Já não espera: um segundo toque, antes de o controlador retomar, é ignorado pela interface.
        _waiting.value = false
        return pending.answer.complete(action)
    }
}
