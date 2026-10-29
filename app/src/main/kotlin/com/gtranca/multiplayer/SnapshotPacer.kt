package com.gtranca.multiplayer

import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * §16.6 fila das vistas recebidas de quem conduz a mesa (o controlador do anfitrião ou as vistas que chegam ao convidado):
 * cada uma é mostrada **uma de cada vez, em ordem**, no ritmo deste aparelho, como no jogo local contra robôs
 * ([com.gtranca.game.GameController] com a pausa dos robôs e as esperas das encenações):
 * - jogada de outro assento: [paceMillis] antes de mostrá-la (a mesma pausa dos robôs no jogo local);
 * - partida distribuída: a próxima vista só depois de a tela avisar o fim da distribuição ([dealAnimationDone]);
 * - trocas de 3 vermelho novas: a próxima vista só depois de a tela encená-las ([presentationDone]).
 *
 * Assim a vista seguinte nunca "pula" a mesa para o fim antes de a anterior ser animada, e a vez do jogador só aparece
 * depois de tudo o que veio antes. Toda espera tem limite ([dealWaitMillis], [presentationWaitMillis]; 0 = não espera),
 * para a mesa nunca travar se a tela não avisar. Kotlin puro: testável na JVM com tempo virtual.
 */
class SnapshotPacer(
    private val paceMillis: () -> Long,
    private val dealWaitMillis: () -> Long,
    private val presentationWaitMillis: () -> Long,
    /**
     * §17.3 chamado antes de mostrar a distribuição de uma partida em que este aparelho ainda não escolheu os boosters; só
     * volta depois do Seguir (ou do limite). As vistas seguintes esperam na fila: depois o jogador vê as animações e os descartes
     * que já aconteceram.
     */
    private val boostersGate: (suspend (roundNumber: Int) -> Unit)? = null,
    private val publish: (GameSnapshot) -> Unit,
) {
    private val queue = Channel<GameSnapshot>(Channel.UNLIMITED)

    /** Última partida cuja distribuição a tela terminou de animar. */
    private val dealt = MutableStateFlow(0)

    /** Última encenação de 3 vermelho terminada: (partida, tamanho do registro). */
    private val presented = MutableStateFlow(0 to 0)

    /** Põe a vista [snapshot] na fila (nunca bloqueia). */
    fun offer(snapshot: GameSnapshot) {
        queue.trySend(snapshot)
    }

    /** A tela terminou de animar a distribuição da partida [roundNumber]. */
    fun dealAnimationDone(roundNumber: Int) {
        dealt.update { maxOf(it, roundNumber) }
    }

    /** A tela encenou as trocas de 3 vermelho da partida [roundNumber] até o registro ter [logSize] entradas. */
    fun presentationDone(roundNumber: Int, logSize: Int) {
        presented.update { (round, size) -> if (roundNumber > round || (roundNumber == round && logSize > size)) roundNumber to logSize else round to size }
    }

    /** Mostra as vistas da fila, uma por vez, até ser cancelado. [initial] é a vista já na tela. */
    suspend fun run(initial: GameSnapshot) {
        var last: GameSnapshot? = null
        gate(initial)
        awaitStaging(last, initial)
        last = initial
        for (next in queue) {
            if (last != null && othersPlayed(last, next)) paceMillis().takeIf { it > 0 }?.let { delay(it) }
            gate(next)
            publish(next)
            awaitStaging(last, next)
            last = next
        }
    }

    /** §17.3 uma partida em andamento a mostrar: o seletor de boosters vem antes (a tela do jogo só vê a distribuição depois). */
    private suspend fun gate(next: GameSnapshot) {
        if (next.stage == Stage.PLAYING && !next.resigned) boostersGate?.invoke(next.roundNumber)
    }

    /** Depois de mostrar [next]: espera a distribuição (partida nova) e a encenação das trocas novas de 3 vermelho. */
    private suspend fun awaitStaging(previous: GameSnapshot?, next: GameSnapshot) {
        if (next.stage != Stage.PLAYING || next.resigned) return
        val round = next.roundNumber
        val newRound = previous == null || previous.roundNumber != round || previous.stage != Stage.PLAYING
        if (newRound) {
            dealWaitMillis().takeIf { it > 0 }?.let { limit -> withTimeoutOrNull(limit) { dealt.first { it >= round } } }
        }
        val size = next.view.redThreeLog.size
        val grew = if (newRound) size > 0 else size > previous!!.view.redThreeLog.size
        if (grew) {
            presentationWaitMillis().takeIf { it > 0 }?.let { limit ->
                withTimeoutOrNull(limit) { presented.first { (r, s) -> r > round || (r == round && s >= size) } }
            }
        }
    }

    companion object {
        /** Outro assento jogou desde a vista anterior (na mesma partida): é a vez de pausar para acompanhar. */
        fun othersPlayed(previous: GameSnapshot, next: GameSnapshot): Boolean {
            if (previous.stage != Stage.PLAYING || next.stage != Stage.PLAYING || previous.roundNumber != next.roundNumber) return false
            val viewer = next.viewerSeat.index
            return next.turnEvents.indices.any { it != viewer && next.turnEvents[it] != previous.turnEvents.getOrNull(it) }
        }
    }
}
