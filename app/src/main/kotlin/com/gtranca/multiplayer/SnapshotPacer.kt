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
 * - trocas de 3 vermelho novas: a próxima vista só depois de a tela encená-las ([presentationDone]);
 * - palpite da distribuição certeira resolvido (§3.6): a próxima vista só depois de a tela reconhecer a reação
 *   ([guessRevealDone]) — mesma ideia das trocas de 3 vermelho: sem isso, a vez do próprio jogador (quando
 *   `mortoDealerSeat == firstSeat`, individual) ficaria pronta "atrás" da reação em tela cheia antes do toque.
 *
 * Assim a vista seguinte nunca "pula" a mesa para o fim antes de a anterior ser animada, e a vez do jogador só aparece
 * depois de tudo o que veio antes. Toda espera tem limite ([dealWaitMillis], [presentationWaitMillis],
 * [guessRevealWaitMillis]; 0 = não espera), para a mesa nunca travar se a tela não avisar. Kotlin puro: testável na
 * JVM com tempo virtual.
 */
class SnapshotPacer(
    private val paceMillis: () -> Long,
    private val dealWaitMillis: () -> Long,
    private val presentationWaitMillis: () -> Long,
    /** §3.6 limite da espera pelo reconhecimento da reação do palpite ([guessRevealDone]); 0 = não espera. */
    private val guessRevealWaitMillis: () -> Long = { 0 },
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

    /** §3.6 palpites (partida, mão?) cuja reação já foi reconhecida pela tela ([guessRevealDone]). */
    private val guessAcked = MutableStateFlow<Set<Pair<Int, Boolean>>>(emptySet())

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

    /**
     * §3.6 a tela reconheceu a reação (pílula ou tela cheia) do palpite da partida [roundNumber] ([hand] = mão,
     * `false` = morto): libera a próxima vista da fila que estava esperando por ela. Chamado para qualquer assento
     * (igual ao jogo local): o parceiro e os demais liberam na hora; só quem palpitou de verdade espera o toque.
     */
    fun guessRevealDone(roundNumber: Int, hand: Boolean) {
        guessAcked.update { it + (roundNumber to hand) }
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

    /**
     * §3.6 um palpite ([hand] = mão, `false` = morto) acabou de resolver entre [previous] e [next] (mesma partida,
     * `null` → não-`null`, igual a `GameViewModel.handleDealGuessTransition`): `previous == null` (1ª vista de uma
     * tela nova, reconexão/§16.7) nunca conta — sem "anterior" para comparar, a tela não vai reencenar nem reconhecer
     * nada, e esperar aqui travaria sem motivo.
     */
    private fun guessJustResolved(previous: GameSnapshot?, next: GameSnapshot, hand: Boolean): Boolean {
        if (previous == null || previous.roundNumber != next.roundNumber) return false
        val before = previous.view.dealGuess
        val after = next.view.dealGuess ?: return false
        return if (hand) before?.hand == null && after.hand != null else before?.morto == null && after.morto != null
    }

    /** Depois de mostrar [next]: espera a distribuição (partida nova) e a encenação das trocas novas de 3 vermelho. */
    private suspend fun awaitStaging(previous: GameSnapshot?, next: GameSnapshot) {
        if (next.stage != Stage.PLAYING || next.resigned) return
        val round = next.roundNumber
        // §3.6 antes de qualquer outra espera: se a vista que acabou de ser publicada resolveu um palpite, a
        // PRÓXIMA só aparece depois de a tela reconhecer a reação (pílula sempre; tela cheia no toque) — mesmo
        // princípio das trocas de 3 vermelho, abaixo. Evita a vez do próprio jogador (`mortoDealerSeat == firstSeat`
        // em individual) "furar a fila" atrás da reação ainda aberta.
        listOf(true, false).forEach { hand ->
            if (guessJustResolved(previous, next, hand)) {
                guessRevealWaitMillis().takeIf { it > 0 }?.let { limit ->
                    withTimeoutOrNull(limit) { guessAcked.first { (round to hand) in it } }
                }
            }
        }
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
