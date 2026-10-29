package com.gtranca.online.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.HardBotConfig
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.RuleSet
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import kotlin.random.Random

/** Configuracao de um jogo so com robos (etapa 1.3a). A semente e injetada: mesma semente = mesmo jogo. */
data class MatchConfig(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.FACIL,
    val rules: RuleSet = RuleSet.DEFAULT,
    /** Pontuacao-alvo da mesa (§13, §14.4); `null` = a do perfil de regras. */
    val targetScore: Int? = null,
    val seed: Long = 42,
    /** Sem teto de tempo por padrao: o Dificil so e reproduzivel assim (ver contrato do BotPlayer). */
    val hardConfig: HardBotConfig = HardBotConfig.DEFAULT.withoutTimeLimit(),
    val maxActionsPerRound: Int = 5_000,
    val maxRoundsPerGame: Int = 200,
)

/** Falha de um jogo (nunca deveria acontecer): o motor, o robo ou um limite violou o contrato. */
data class MatchFailure(val kind: String, val round: Int, val actionIndex: Int, val message: String)

/** Resultado de um jogo. [winnerSide] e o `Side.index` do vencedor (§13); `null` se houve [failure]. */
data class MatchResult(
    val seed: Long,
    val winnerSide: Int?,
    val totals: List<Int>,
    val rounds: Int,
    val actions: Int,
    val failure: MatchFailure? = null,
) {
    val finished: Boolean get() = failure == null && winnerSide != null
}

/**
 * Joga um jogo (`Match`) completo com robos em TODOS os assentos, como o `:sim` e o app: a cada vez,
 * `legalActions` (unica fonte de jogadas), o robo decide a partir do `PlayerView` do assento (nunca o
 * `GameState`/`RoundState`) e a jogada e aplicada por `apply`. Os robos recebem os eventos publicos
 * (`observe`) e o aviso de nova partida (`onNewRound`).
 *
 * Nao ha economia, aposta nem boosters (robos nao usam boosters, §17). [botFactory] e [legalActionsOf]
 * existem para os testes provarem as garantias; o servico usa os padroes.
 */
class MatchRunner internal constructor(
    private val config: MatchConfig,
    private val botFactory: (Seat, Random) -> BotPlayer,
    private val legalActionsOf: (com.gtranca.engine.model.RoundState, Seat) -> List<Action>,
) {
    constructor(config: MatchConfig) : this(
        config,
        botFactory = { _, random -> createBot(config.difficulty, random, config.hardConfig) },
        legalActionsOf = { state, seat -> RoundEngine.legalActions(state, seat, config.rules) },
    )

    private class Failure(val kind: String, message: String) : RuntimeException(message)

    /** Joga o jogo todo. Nunca lanca: falhas vem em [MatchResult.failure]. */
    fun run(): MatchResult {
        val mode = config.mode
        val bots = mode.seats.map { seat -> botFactory(seat, Random(botSeed(config.seed, seat.index))) }
        var match: Match? = null
        var rounds = 0
        var totalActions = 0
        var actionIndex = 0
        try {
            match = startMatch(mode, config.targetScore, Random(config.seed), config.rules)
            checkCards(match)
            while (!match!!.isOver) {
                bots.forEach { it.onNewRound() }
                actionIndex = 0
                while (match!!.currentRound.phase != Phase.FINISHED) {
                    val round = match.currentRound
                    val seat = round.currentSeat
                    val legal = legalActionsOf(round, seat)
                    if (legal.isEmpty()) throw Failure("NO_LEGAL_ACTIONS", "assento ${seat.index} na fase ${round.phase}")
                    // So o PlayerView do assento chega ao robo (§18.3: o servidor nunca expoe o estado completo).
                    val action = bots[seat.index].chooseAction(round.viewFor(seat, config.rules), legal)
                    if (action !in legal) throw Failure("ILLEGAL_ACTION", "assento ${seat.index}: acao fora de legalActions")
                    val event = PublicEvent.of(round.discardPile, seat, action)
                    match = match.play(seat, action)
                    actionIndex++
                    totalActions++
                    checkCards(match)
                    bots.forEach { it.observe(event) }
                    if (actionIndex >= config.maxActionsPerRound) {
                        throw Failure("ACTION_LIMIT", "partida passou de ${config.maxActionsPerRound} acoes")
                    }
                }
                match = match.finishRound() // §12 pontua; §13 verifica o vencedor
                rounds++
                if (!match.isOver) {
                    if (rounds >= config.maxRoundsPerGame) {
                        throw Failure("ROUND_LIMIT", "jogo passou de ${config.maxRoundsPerGame} partidas")
                    }
                    match = match.startNextRound() // §4.1 o jogador seguinte inicia
                    checkCards(match)
                }
            }
            return MatchResult(config.seed, match.winner?.index, match.totals, rounds, totalActions)
        } catch (e: Failure) {
            return failed(match, rounds, totalActions, MatchFailure(e.kind, rounds + 1, actionIndex, e.message.orEmpty()))
        } catch (e: Exception) {
            val failure = MatchFailure("EXCEPTION", rounds + 1, actionIndex, "${e::class.simpleName}: ${e.message}")
            return failed(match, rounds, totalActions, failure)
        }
    }

    private fun failed(match: Match?, rounds: Int, actions: Int, failure: MatchFailure) =
        MatchResult(config.seed, null, match?.totals.orEmpty(), rounds, actions, failure)

    /** §1: as 104 cartas, sem repeticao nem sumico, entre maos, monte, lixo, mortos e mesa. */
    private fun checkCards(match: Match) {
        val all = match.currentRound.allCards()
        if (all.size != Deck.SIZE || all.toSet() != STANDARD_DECK) throw Failure("INVARIANT", "cartas repetidas ou faltando")
    }

    companion object {
        private val STANDARD_DECK = Deck.standard().toSet()

        /** Mesma semente por assento do `:sim`, para os resultados serem comparaveis. */
        fun botSeed(gameSeed: Long, seatIndex: Int): Long = gameSeed * 1_000_003L + seatIndex + 1
    }
}
