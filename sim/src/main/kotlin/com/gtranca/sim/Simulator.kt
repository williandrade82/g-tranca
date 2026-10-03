package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Side
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import kotlin.random.Random

/** Tipos de falha detectados pela simulação. */
enum class FailureKind {
    /** Exceção lançada pelo motor ou por um bot. */
    EXCEPTION,
    /** O bot devolveu uma ação que não está em `legalActions`. */
    ILLEGAL_ACTION,
    /** Partida em andamento sem nenhuma ação legal para o jogador da vez (beco sem saída). */
    NO_LEGAL_ACTIONS,
    /** Invariante violada (ex.: conservação das 104 cartas). */
    INVARIANT,
    /** Partida passou do limite de ações (travamento). */
    ACTION_LIMIT,
    /** Jogo passou do limite de partidas (travamento). */
    ROUND_LIMIT,
}

/** Falha de um jogo, com o necessário para reproduzi-la (semente do jogo, partida e ação). */
data class Failure(
    val kind: FailureKind,
    val gameSeed: Long,
    val round: Int,
    val actionIndex: Int,
    val action: Action?,
    val message: String,
)

/** Resultado de uma partida concluída. */
data class RoundStats(val turns: Int, val actions: Int, val winner: Side?)

/** Resultado de um jogo: concluído (com [winner]) ou interrompido por [failure]. */
data class GameOutcome(
    val gameSeed: Long,
    val winner: Side?,
    val rounds: List<RoundStats>,
    val totals: List<Int>,
    val failure: Failure?,
)

/** Roda jogos completos bot × bot conforme o [config], verificando legalidade e invariantes a cada ação. */
class Simulator(private val config: SimConfig) {

    private class SimFailure(val kind: FailureKind, message: String) : RuntimeException(message)

    /** Joga o jogo de semente [gameSeed]. Nunca lança: falhas vêm em [GameOutcome.failure]. */
    fun runGame(gameSeed: Long): GameOutcome {
        val mode = config.mode
        val bots: List<BotPlayer> = mode.seats.map { seat ->
            createSimBot(config.sides[mode.sideOf(seat).index], Random(botSeed(gameSeed, seat.index)))
        }
        val rounds = mutableListOf<RoundStats>()
        var match: Match? = null
        var actionIndex = 0
        var lastAction: Action? = null
        try {
            match = startMatch(mode, config.target, Random(gameSeed))
            checkRoundInvariants(match.currentRound)
            while (!match!!.isOver) {
                bots.forEach { it.onNewRound() }
                actionIndex = 0
                lastAction = null
                var turns = 0
                while (match!!.currentRound.phase != Phase.FINISHED) {
                    val round = match.currentRound
                    val seat = round.currentSeat
                    val legal = RoundEngine.legalActions(round, seat)
                    if (legal.isEmpty()) throw SimFailure(FailureKind.NO_LEGAL_ACTIONS, "assento ${seat.index} na fase ${round.phase}")
                    val action = bots[seat.index].chooseAction(round.viewFor(seat), legal)
                    lastAction = action
                    if (action !in legal) throw SimFailure(FailureKind.ILLEGAL_ACTION, "assento ${seat.index}: ação fora de legalActions")
                    val takenFromDiscard = if (action is Action.TakeDiscardPile) round.discardPile.dropLast(1) else emptyList()
                    match = match.play(seat, action)
                    if (action == Action.DrawFromStock || action is Action.TakeDiscardPile) turns++
                    actionIndex++
                    checkRoundInvariants(match.currentRound)
                    val event = PublicEvent(seat, action, takenFromDiscard)
                    bots.forEach { it.observe(event) }
                    if (actionIndex >= config.maxActionsPerRound) {
                        throw SimFailure(FailureKind.ACTION_LIMIT, "partida passou de ${config.maxActionsPerRound} ações")
                    }
                }
                val result = match.currentRound.result
                rounds += RoundStats(turns, actionIndex, (result as? RoundResult.GoOut)?.side)
                match = match.finishRound()
                if (!match.isOver) {
                    if (rounds.size >= config.maxRoundsPerGame) {
                        throw SimFailure(FailureKind.ROUND_LIMIT, "jogo passou de ${config.maxRoundsPerGame} partidas")
                    }
                    match = match.startNextRound()
                    checkRoundInvariants(match.currentRound)
                }
            }
            return GameOutcome(gameSeed, match.winner, rounds, match.totals, failure = null)
        } catch (e: SimFailure) {
            return failed(gameSeed, rounds, match, Failure(e.kind, gameSeed, rounds.size + 1, actionIndex, lastAction, e.message.orEmpty()))
        } catch (e: Exception) {
            val message = "${e::class.simpleName}: ${e.message}"
            return failed(gameSeed, rounds, match, Failure(FailureKind.EXCEPTION, gameSeed, rounds.size + 1, actionIndex, lastAction, message))
        }
    }

    private fun failed(gameSeed: Long, rounds: List<RoundStats>, match: Match?, failure: Failure) =
        GameOutcome(gameSeed, winner = null, rounds = rounds, totals = match?.totals.orEmpty(), failure = failure)

    /**
     * Invariantes verificadas após cada ação:
     * - §1 conservação: as 104 cartas, sem repetição nem sumiço, entre mãos, monte, lixo, mortos e mesa;
     * - com [SimConfig.checkInvariants]: §9.1/§10 morto não disponível está vazio; conjuntos na mesa
     *   continuam válidos (§6); 3 vermelhos nunca ficam na mão nem nos conjuntos (§6.5); a partida
     *   encerrada não oferece ações e a em andamento tem jogador com cartas ou recém-pegou o morto.
     */
    private fun checkRoundInvariants(state: RoundState) {
        val all = state.allCards()
        if (all.size != Deck.SIZE) invariant("total de cartas ${all.size} ≠ ${Deck.SIZE}")
        if (all.toSet() != STANDARD_DECK) invariant("cartas repetidas ou faltando")
        if (!config.checkInvariants) return

        state.mortoStatus.forEachIndexed { i, status ->
            if (status != MortoStatus.Available && state.mortos[i].isNotEmpty()) invariant("morto $i $status com cartas")
        }
        state.tables.forEachIndexed { sideIndex, table ->
            for (tableMeld in table.melds) {
                val rebuilt = Meld.create(tableMeld.meld.cards)
                if (rebuilt !is RuleResult.Ok || rebuilt.value.kind != tableMeld.meld.kind) {
                    invariant("conjunto inválido na mesa do lado $sideIndex: ${tableMeld.meld.cards}")
                }
                if (tableMeld.meld.cards.any { it.rank.isThree }) invariant("3 em conjunto: ${tableMeld.meld.cards}")
            }
        }
        if (state.phase != Phase.FINISHED) {
            state.hands.forEachIndexed { seat, hand ->
                if (hand.any { it.isRedThree }) invariant("3 vermelho na mão do assento $seat")
            }
        }
        state.redThrees.forEachIndexed { sideIndex, threes ->
            if (threes.any { !it.isRedThree }) invariant("carta que não é 3 vermelho entre os 3 vermelhos do lado $sideIndex")
        }
        if (state.phase == Phase.FINISHED && RoundEngine.legalActions(state, state.currentSeat).isNotEmpty()) {
            invariant("partida encerrada com ações legais")
        }
    }

    private fun invariant(message: String): Nothing = throw SimFailure(FailureKind.INVARIANT, message)

    companion object {
        private val STANDARD_DECK = Deck.standard().toSet()

        /** Semente do RNG do bot do assento [seatIndex] no jogo [gameSeed]. */
        fun botSeed(gameSeed: Long, seatIndex: Int): Long = gameSeed * 1_000_003L + seatIndex + 1
    }
}
