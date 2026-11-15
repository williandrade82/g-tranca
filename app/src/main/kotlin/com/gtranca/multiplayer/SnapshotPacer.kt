package com.gtranca.multiplayer

import com.gtranca.engine.model.Phase
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
 *
 * **§3.6 o palpite da distribuição certeira não tem um portão equivalente aqui** (decisão de 14/11/2026, depois de
 * uma versão revertida): o `GameViewModel` observa `[com.gtranca.game.TableDriver.state]`, um `StateFlow`
 * **conflado** — ele garante o último valor publicado, não cada valor intermediário. Numa virada de partida em que
 * o palpite resolve rápido (robô, `PacingPolicy.FREE`, sem atraso), a tela pode nunca ver a vista exata em que um
 * palpite "acabou de resolver": só a seguinte, já adiantada. Um portão que espera `guessRevealDone` pelo
 * reconhecimento dessa vista nunca vista travaria pelo limite de segurança inteiro a cada virada de partida — pior
 * que o problema que resolveria. Ver KDoc de [com.gtranca.game.TableDriver.guessRevealDone] e
 * `docs/plano-distribuicao-certeira.md`.
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

    /** §3.6 [snapshot] está numa fase de palpite da distribuição certeira: a distribuição de verdade ainda não comecou. */
    private fun inGuessPhase(snapshot: GameSnapshot): Boolean =
        snapshot.view.phase == Phase.DEAL_GUESS_HAND || snapshot.view.phase == Phase.DEAL_GUESS_MORTO

    /** Depois de mostrar [next]: espera a distribuição (partida nova) e a encenação das trocas novas de 3 vermelho. */
    private suspend fun awaitStaging(previous: GameSnapshot?, next: GameSnapshot) {
        if (next.stage != Stage.PLAYING || next.resigned) return
        val round = next.roundNumber
        val newRound = previous == null || previous.roundNumber != round || previous.stage != Stage.PLAYING
        // §3.6 com a distribuição certeira ligada, a partida nova chega primeiro numa fase de palpite
        // ([Phase.DEAL_GUESS_HAND]/[Phase.DEAL_GUESS_MORTO]): a distribuição de verdade só é animada na tela depois
        // dos dois palpites resolvidos (`GameViewModel.absorb`, `dealing`/`leavingGuessPhases`) — esperar aqui, só
        // porque o número da partida mudou, travaria pelo [dealWaitMillis] inteiro sem motivo (nada para animar
        // ainda), a cada partida. Só espera ao SAIR da fase de palpite (`leavingGuess`), que é quando a distribuição
        // de verdade começa; sem a regra (nunca em fase de palpite), o comportamento é o de sempre.
        val leavingGuess = previous != null && inGuessPhase(previous) && !inGuessPhase(next)
        if ((newRound || leavingGuess) && !inGuessPhase(next)) {
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
