package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.hard.HardBot
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
import com.gtranca.engine.model.Seat
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

/** Resultado de uma partida concluída; [scores] = pontos de cada lado na partida (§12), por `Side.index`. */
data class RoundStats(val turns: Int, val actions: Int, val winner: Side?, val scores: List<Int> = emptyList())

/**
 * Tempo gasto pelos bots de um lado em `chooseAction`, só nas decisões com mais de uma ação legal (as
 * demais são triviais).
 */
data class DecisionTiming(val decisions: Int = 0, val totalNanos: Long = 0, val maxNanos: Long = 0) {
    operator fun plus(nanos: Long) = DecisionTiming(decisions + 1, totalNanos + nanos, maxOf(maxNanos, nanos))
    operator fun plus(other: DecisionTiming) =
        DecisionTiming(decisions + other.decisions, totalNanos + other.totalNanos, maxOf(maxNanos, other.maxNanos))
}

/** Resultado de um jogo: concluído (com [winner]) ou interrompido por [failure]. */
data class GameOutcome(
    val gameSeed: Long,
    val winner: Side?,
    val rounds: List<RoundStats>,
    val totals: List<Int>,
    val failure: Failure?,
    /** Tempo de decisão por lado (indexado por `Side.index`). */
    val timing: List<DecisionTiming> = emptyList(),
    /** Por lado: decisões do Difícil em que o teto de tempo foi atingido (jogada não reproduzível). */
    val hardTimeLimitHits: List<Int> = emptyList(),
    /** Por lado: decisões do Difícil em que a busca falhou e ele jogou como o Médio. */
    val hardSearchFailures: List<Int> = emptyList(),
)

/**
 * Roda jogos completos bot × bot conforme o [config], verificando legalidade e invariantes a cada ação.
 *
 * O construtor interno permite aos testes injetar bots ([botFactory]), a fonte de ações legais
 * ([legalActionsOf]) e a transição ([playAction]) para provocar as falhas que o motor e os bots
 * corretos não produzem.
 */
class Simulator internal constructor(
    private val config: SimConfig,
    private val botFactory: (seat: Seat, random: Random) -> BotPlayer,
    private val legalActionsOf: (RoundState, Seat) -> List<Action>,
    private val playAction: (Match, Seat, Action) -> Match,
) {

    constructor(config: SimConfig) : this(
        config,
        botFactory = { seat, random -> createSimBot(config.sides[config.mode.sideOf(seat).index], random, config.hardConfig) },
        legalActionsOf = { state, seat -> RoundEngine.legalActions(state, seat) },
        playAction = { match, seat, action -> match.play(seat, action) },
    )

    private class SimFailure(val kind: FailureKind, message: String) : RuntimeException(message)

    /** Joga o jogo de semente [gameSeed]. Nunca lança: falhas vêm em [GameOutcome.failure]. */
    fun runGame(gameSeed: Long): GameOutcome {
        val mode = config.mode
        val bots: List<BotPlayer> = mode.seats.map { seat -> botFactory(seat, Random(botSeed(gameSeed, seat.index))) }
        val rounds = mutableListOf<RoundStats>()
        var match: Match? = null
        var actionIndex = 0
        var lastAction: Action? = null
        val timing = MutableList(mode.sideCount) { DecisionTiming() }
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
                    val legal = legalActionsOf(round, seat)
                    if (legal.isEmpty()) throw SimFailure(FailureKind.NO_LEGAL_ACTIONS, "assento ${seat.index} na fase ${round.phase}")
                    val started = System.nanoTime()
                    val action = bots[seat.index].chooseAction(round.viewFor(seat), legal)
                    if (legal.size > 1) {
                        val side = mode.sideOf(seat).index
                        timing[side] = timing[side] + (System.nanoTime() - started)
                    }
                    lastAction = action
                    if (action !in legal) throw SimFailure(FailureKind.ILLEGAL_ACTION, "assento ${seat.index}: ação fora de legalActions")
                    val takenFromDiscard = if (action is Action.TakeDiscardPile) round.discardPile.dropLast(1) else emptyList()
                    match = playAction(match, seat, action)
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
                match = match.finishRound()
                rounds += RoundStats(turns, actionIndex, (result as? RoundResult.GoOut)?.side, match.history.last().scores.map { it.total })
                if (!match.isOver) {
                    if (rounds.size >= config.maxRoundsPerGame) {
                        throw SimFailure(FailureKind.ROUND_LIMIT, "jogo passou de ${config.maxRoundsPerGame} partidas")
                    }
                    match = match.startNextRound()
                    checkRoundInvariants(match.currentRound)
                }
            }
            fun perSide(metric: (HardBot) -> Int): List<Int> = mode.sides.map { side ->
                mode.seatsOf(side).sumOf { seat -> (bots[seat.index] as? HardBot)?.let(metric) ?: 0 }
            }
            return GameOutcome(
                gameSeed, match.winner, rounds, match.totals, failure = null, timing = timing,
                hardTimeLimitHits = perSide { it.timeLimitHits },
                hardSearchFailures = perSide { it.searchFailures },
            )
        } catch (e: SimFailure) {
            return failed(gameSeed, rounds, match, Failure(e.kind, gameSeed, rounds.size + 1, actionIndex, lastAction, e.message.orEmpty()))
        } catch (e: Exception) {
            val message = "${e::class.simpleName}: ${e.message}"
            return failed(gameSeed, rounds, match, Failure(FailureKind.EXCEPTION, gameSeed, rounds.size + 1, actionIndex, lastAction, message))
        }
    }

    private fun failed(gameSeed: Long, rounds: List<RoundStats>, match: Match?, failure: Failure) =
        GameOutcome(gameSeed, winner = null, rounds = rounds, totals = match?.totals.orEmpty(), failure = failure)

    private fun checkRoundInvariants(state: RoundState) {
        invariantViolation(state)?.let { throw SimFailure(FailureKind.INVARIANT, it) }
    }

    /**
     * Primeira invariante violada em [state] (descrição), ou `null`. Verificada após cada ação:
     * - §1 conservação: as 104 cartas, sem repetição nem sumiço, entre mãos, monte, lixo, mortos e mesa;
     * - com [SimConfig.checkInvariants]: §9.1/§10 morto não disponível está vazio; conjuntos na mesa
     *   continuam válidos (§6); 3 vermelhos nunca ficam na mão nem nos conjuntos (§6.5); a partida
     *   encerrada não oferece ações; e, na partida em andamento, **todo jogador tem cartas na mão**.
     *
     * Esta última vem de §8/§9/§11: ficar sem cartas só é permitido se resultar em pegar o morto ou em
     * batida. A batida encerra a partida; ao pegar o morto (direto, §9.2, ou indireto, §9.3) o motor põe
     * o morto na mão no mesmo passo, e um morto (11 cartas) nunca é só de 3 vermelhos (há 4 no baralho,
     * §9.4). A mão também não esvazia por 3 vermelho sem reposição (§8: fica ao menos a carta que já
     * estava nela).
     *
     * O motor garante o mesmo em `RoundSimulationPropertyTest` (§8 mão vazia só para quem bateu): se o
     * motor passar a guardar o morto fora da mão, aquele teste falha primeiro e esta checagem deve ser
     * ajustada junto.
     */
    internal fun invariantViolation(state: RoundState): String? {
        val all = state.allCards()
        if (all.size != Deck.SIZE) return "total de cartas ${all.size} ≠ ${Deck.SIZE}"
        if (all.toSet() != STANDARD_DECK) return "cartas repetidas ou faltando"
        if (!config.checkInvariants) return null

        state.mortoStatus.forEachIndexed { i, status ->
            if (status != MortoStatus.Available && state.mortos[i].isNotEmpty()) return "morto $i $status com cartas"
        }
        state.tables.forEachIndexed { sideIndex, table ->
            for (tableMeld in table.melds) {
                val rebuilt = Meld.create(tableMeld.meld.cards)
                if (rebuilt !is RuleResult.Ok || rebuilt.value.kind != tableMeld.meld.kind) {
                    return "conjunto inválido na mesa do lado $sideIndex: ${tableMeld.meld.cards}"
                }
                if (tableMeld.meld.cards.any { it.rank.isThree }) return "3 em conjunto: ${tableMeld.meld.cards}"
            }
        }
        if (state.phase != Phase.FINISHED) {
            state.hands.forEachIndexed { seat, hand ->
                if (hand.isEmpty()) return "assento $seat sem cartas com a partida em andamento"
                if (hand.any { it.isRedThree }) return "3 vermelho na mão do assento $seat"
            }
        }
        state.redThrees.forEachIndexed { sideIndex, threes ->
            if (threes.any { !it.isRedThree }) return "carta que não é 3 vermelho entre os 3 vermelhos do lado $sideIndex"
        }
        if (state.phase == Phase.FINISHED && RoundEngine.legalActions(state, state.currentSeat).isNotEmpty()) {
            return "partida encerrada com ações legais"
        }
        return null
    }

    companion object {
        private val STANDARD_DECK = Deck.standard().toSet()

        /** Semente do RNG do bot do assento [seatIndex] no jogo [gameSeed]. */
        fun botSeed(gameSeed: Long, seatIndex: Int): Long = gameSeed * 1_000_003L + seatIndex + 1
    }
}
