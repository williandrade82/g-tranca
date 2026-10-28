package com.gtranca.game

import com.gtranca.engine.Action
import com.gtranca.engine.model.RuleError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * O que a tela do jogo precisa de quem conduz a mesa: o jogo local ([LocalTableDriver], o [GameController] de sempre) ou
 * a mesa multijogador (§16: o anfitrião, com o controlador, ou o convidado, que só recebe a vista do seu assento e propõe
 * jogadas). A tela nunca valida regra: as jogadas vêm de [GameSnapshot.humanLegal].
 */
interface TableDriver {
    /** Vista do jogador deste aparelho. */
    val state: StateFlow<GameSnapshot>

    /** O jogo espera mesmo a jogada deste aparelho agora (toque atrasado depois de enviar é ignorado). */
    fun awaitingHuman(): Boolean

    /** Envia a jogada [action] (de `humanLegal`) para o pedido [requestId]; `false` se o pedido já não vale. */
    fun submit(action: Action, requestId: Long): Boolean

    /** Motivo de recusa de [action], quando dá para saber aqui (o convidado não tem o jogo: `null`). */
    fun explain(action: Action, requestId: Long): RuleError?

    /** §16.1 recusas que chegam depois do envio (só no convidado): o motivo do motor, se houver. */
    val rejections: Flow<RuleError?> get() = emptyFlow()

    fun presentationDone(roundNumber: Int, logSize: Int) {}

    fun dealAnimationDone(roundNumber: Int) {}

    /** Escala da pausa da distribuição (só o jogo local espera a animação). */
    var dealDelayScale: Double

    /** O jogador terminou as telas de resultado da partida. */
    fun continueToNextRound()

    /** §13.1 / §16.9 desistência confirmada pelo jogador. */
    fun resign()

    /** Roda a mesa enquanto a tela existir. */
    suspend fun run()

    /**
     * §17.3 a partida que está esperando o seletor de boosters deste aparelho (a distribuição só aparece depois do Seguir);
     * `null` = nada pendente. Só a mesa multijogador usa; o jogo contra robôs tem o seu próprio fluxo.
     */
    val boosterRound: StateFlow<Int?> get() = NO_BOOSTER_ROUND

    /** §17.4 o estado dos boosters do assento deste aparelho (multijogador); `null` = a tela guarda o dela. */
    val seatBoosters: StateFlow<com.gtranca.engine.SeatBoosters>? get() = null

    /** §17.8 reações enviadas por qualquer assento (multijogador). */
    val seatReactions: kotlinx.coroutines.flow.Flow<SeatReaction> get() = kotlinx.coroutines.flow.emptyFlow()

    /** §17.3 o seletor da partida [round] foi confirmado com [boosters] (já debitados): registra no assento. */
    fun confirmBoosters(round: Int, boosters: List<String>) {}

    /** §17.8 envia uma reação na partida [round]. */
    fun sendReaction(round: Int, reaction: com.gtranca.engine.Reaction) {}

    companion object {
        private val NO_BOOSTER_ROUND: StateFlow<Int?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    }
}

/** §17.8 a reação [reaction] enviada pelo assento [seat] (índice). */
data class SeatReaction(val seat: Int, val reaction: com.gtranca.engine.Reaction)

/** O jogo local contra robôs (§15): exatamente o [GameController] de sempre, com o humano [human]. */
class LocalTableDriver(val controller: GameController, private val human: HumanPlayer) : TableDriver {
    override val state: StateFlow<GameSnapshot> get() = controller.state

    override fun awaitingHuman(): Boolean = human.waiting.value

    override fun submit(action: Action, requestId: Long): Boolean = controller.submit(action, requestId)

    override fun explain(action: Action, requestId: Long): RuleError? = controller.explain(action, requestId)

    override fun presentationDone(roundNumber: Int, logSize: Int) {
        controller.presentationDone(roundNumber, logSize)
    }

    override fun dealAnimationDone(roundNumber: Int) = controller.dealAnimationDone(roundNumber)

    override var dealDelayScale: Double
        get() = controller.dealDelayScale
        set(value) {
            controller.dealDelayScale = value
        }

    override fun continueToNextRound() {
        controller.continueToNextRound()
    }

    override fun resign() {
        controller.resign()
    }

    override suspend fun run() = controller.run()
}
