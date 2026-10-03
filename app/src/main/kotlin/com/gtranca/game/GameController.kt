package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Etapa do jogo, do ponto de vista de quem acompanha. */
enum class Stage {
    /** Partida em andamento. */
    PLAYING,

    /** Partida encerrada e pontuada (§12); aguarda a confirmação para a próxima (§13: inclui o empate). */
    ROUND_OVER,

    /** §13 jogo terminado com vencedor. */
    GAME_OVER,
}

/**
 * Estado publicado pelo [GameController]. Tudo o que a interface precisa, **sem** o estado completo da
 * partida: a mesa vem de `viewFor(viewerSeat)` (mãos dos outros, monte e mortos só como tamanhos).
 *
 * @property view vista do assento [viewerSeat] (o humano).
 * @property history partidas encerradas, com o detalhamento §12 ([RoundRecord.scores]).
 * @property humanLegal ações legais do humano quando o controlador espera a jogada dele; vazia caso contrário.
 * @property thinkingSeat bot que está decidindo (inclui a pausa entre jogadas), ou `null`.
 * @property turnEvents por assento ([Seat.index]), as ações públicas da vez mais recente (ou atual) dele
 *   nesta partida.
 */
data class GameSnapshot(
    val config: GameConfig,
    val viewerSeat: Seat,
    val view: PlayerView,
    val roundNumber: Int,
    val totals: List<Int>,
    val history: List<RoundRecord>,
    val stage: Stage,
    val winner: Side?,
    val humanLegal: List<Action>,
    val thinkingSeat: Seat?,
    val turnEvents: List<List<PublicEvent>>,
) {
    val viewerSide: Side get() = view.side

    /** É a vez do humano e o controlador espera a escolha dele. */
    val isHumanTurn: Boolean get() = humanLegal.isNotEmpty()
}

/**
 * Laço do jogo (§15): pergunta a ação ao jogador da vez, confere que está em `legalActions`, monta o
 * [PublicEvent] **antes** de aplicar (depois o lixo já foi levado), aplica no motor, avisa todos os bots e
 * publica o novo [state]. A cada partida distribuída chama `onNewRound()` em todos os bots. Ao fim de cada
 * partida pontua (`finishRound`) e, se houver humano, espera [continueToNextRound] antes de distribuir a
 * próxima.
 *
 * Mesmo procedimento de `Simulator.runGame` (`:sim`): o jogo é `startMatch(mode, alvo, Random(gameSeed))` e
 * cada bot recebe `Random(botSeed(gameSeed, assento))`. Com os mesmos bots e semente, o resultado é idêntico,
 * desde que nenhuma decisão dependa do relógio: o Difícil do app tem teto de 800 ms (`HardBotConfig.DEFAULT`) e,
 * quando o atinge, a escolha passa a depender da máquina. Por isso a partida salva guarda o `Match` e os eventos,
 * e nunca é refeita a partir da semente.
 *
 * Kotlin puro (sem Android): testável na JVM. Todo o processamento (motor e decisões dos bots, inclusive a
 * busca de CPU do Difícil) roda em [computeDispatcher], nunca na thread principal.
 *
 * @param players quem ocupa cada assento, por [Seat.index]; no máximo um [HumanPlayer].
 * @param viewerSeat assento cuja vista é publicada (o humano; num jogo só de bots, qualquer um).
 * @param botDelayMillis pausa antes de cada jogada de bot, para o humano acompanhar (0 nos testes).
 */
class GameController(
    val config: GameConfig,
    /**
     * Semente do jogo: determina **todas** as cartas (mãos, monte e mortos de todas as partidas). Só para
     * persistência e testes; nunca a exiba nem a coloque no estado da interface.
     */
    val gameSeed: Long,
    private val players: List<SeatPlayer>,
    private val viewerSeat: Seat = Seat(0),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val botDelayMillis: Long = DEFAULT_BOT_DELAY_MILLIS,
) {
    init {
        require(players.size == config.mode.seatCount) { "Um jogador por assento: ${config.mode.seatCount}" }
        require(players.count { it is HumanPlayer } <= 1) { "No máximo um humano" }
        config.mode.requireSeat(viewerSeat)
        require(botDelayMillis >= 0) { "Pausa negativa" }
    }

    private val bots: List<BotPlayer> = players.filterIsInstance<BotSeatPlayer>().map { it.bot }
    private val hasHuman: Boolean = players.any { it is HumanPlayer }
    private val started = AtomicBoolean(false)
    private val nextRoundSignal = Channel<Unit>(Channel.CONFLATED)
    private val awaitingNextRound = AtomicBoolean(false)

    @Volatile
    private var match: Match = startMatch(config.mode, config.targetScore, Random(gameSeed))
    private var turnEvents: List<List<PublicEvent>> = List(config.mode.seatCount) { emptyList() }

    private val _state = MutableStateFlow(snapshot(Stage.PLAYING, humanLegal = emptyList(), thinkingSeat = null))

    /** Estado atual para a interface. */
    val state: StateFlow<GameSnapshot> = _state.asStateFlow()

    /**
     * Jogo atual completo, para persistência e testes. **Não** use para desenhar a mesa (contém as mãos de
     * todos, o monte e os mortos): a interface usa [state].
     */
    val currentMatch: Match get() = match

    /**
     * Roda o jogo até o fim (§13). Só pode ser chamado uma vez. Cancelável: ao cancelar, a espera pelo
     * humano ou pela confirmação de nova partida é interrompida.
     */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "O jogo já foi iniciado" }
        withContext(computeDispatcher) { loop() }
    }

    private suspend fun loop() {
        while (!match.isOver) {
            bots.forEach { it.onNewRound() }
            turnEvents = List(config.mode.seatCount) { emptyList() }
            publish(Stage.PLAYING)
            while (match.currentRound.phase != Phase.FINISHED) {
                val round = match.currentRound
                val seat = round.currentSeat
                val legal = RoundEngine.legalActions(round, seat)
                check(legal.isNotEmpty()) { "Assento ${seat.index} sem ações legais na fase ${round.phase}" }
                val action = when (val player = players[seat.index]) {
                    is BotSeatPlayer -> {
                        publish(Stage.PLAYING, thinkingSeat = seat)
                        if (botDelayMillis > 0) delay(botDelayMillis)
                        player.bot.chooseAction(round.viewFor(seat), legal)
                    }
                    is HumanPlayer -> {
                        publish(Stage.PLAYING, humanLegal = legal)
                        player.chooseAction(legal)
                    }
                }
                check(action in legal) { "Assento ${seat.index}: ação fora de legalActions: $action" }
                val event = PublicEvent.of(round.discardPile, seat, action)
                match = match.play(seat, action)
                recordEvent(event)
                bots.forEach { it.observe(event) }
                publish(Stage.PLAYING)
            }
            match = match.finishRound()
            if (match.isOver) {
                publish(Stage.GAME_OVER)
            } else {
                if (hasHuman) awaitingNextRound.set(true)
                publish(Stage.ROUND_OVER)
                if (hasHuman) nextRoundSignal.receive()
                match = match.startNextRound()
            }
        }
    }

    /**
     * Confirmação do humano para distribuir a próxima partida (tela de fim de partida). Ignorada fora da
     * etapa [Stage.ROUND_OVER]; devolve se foi aceita.
     */
    fun continueToNextRound(): Boolean {
        if (!awaitingNextRound.compareAndSet(true, false)) return false
        return nextRoundSignal.trySend(Unit).isSuccess
    }

    /**
     * Motivo pelo qual [action] do humano seria recusada na situação atual (`RoundEngine.validate`), ou `null`
     * se ela é válida. Só devolve o motivo, nunca o estado oculto.
     */
    fun explain(action: Action): RuleError? =
        (RoundEngine.validate(match.currentRound, viewerSeat, action) as? RuleResult.Failure)?.error

    private fun recordEvent(event: PublicEvent) {
        val index = event.seat.index
        val startsTurn = event.action == Action.DrawFromStock ||
            event.action == Action.DeclineDraw ||
            event.action is Action.TakeDiscardPile
        turnEvents = turnEvents.mapIndexed { i, events ->
            when {
                i != index -> events
                startsTurn -> listOf(event)
                else -> events + event
            }
        }
    }

    private fun publish(stage: Stage, humanLegal: List<Action> = emptyList(), thinkingSeat: Seat? = null) {
        _state.value = snapshot(stage, humanLegal, thinkingSeat)
    }

    private fun snapshot(stage: Stage, humanLegal: List<Action>, thinkingSeat: Seat?): GameSnapshot {
        val current = match
        return GameSnapshot(
            config = config,
            viewerSeat = viewerSeat,
            view = current.currentRound.viewFor(viewerSeat),
            roundNumber = current.roundNumber,
            totals = current.totals,
            history = current.history,
            stage = stage,
            winner = current.winner,
            humanLegal = humanLegal,
            thinkingSeat = thinkingSeat,
            turnEvents = turnEvents,
        )
    }

    companion object {
        /** Pausa padrão antes de cada jogada de bot. */
        const val DEFAULT_BOT_DELAY_MILLIS: Long = 700

        /**
         * Jogadores de um jogo do app: o [human] no [humanSeat] e bots da dificuldade da [config] nos demais
         * assentos, cada um com `Random(botSeed(gameSeed, assento))`. [botFactory] permite trocar os bots nos
         * testes.
         */
        fun playersFor(
            config: GameConfig,
            gameSeed: Long,
            human: HumanPlayer,
            humanSeat: Seat = Seat(0),
            botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
        ): List<SeatPlayer> = config.mode.seats.map { seat ->
            if (seat == humanSeat) human else BotSeatPlayer(botFactory(seat, Random(botSeed(gameSeed, seat.index))))
        }
    }
}
