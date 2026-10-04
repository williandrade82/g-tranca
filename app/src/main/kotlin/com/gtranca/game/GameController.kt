package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RoundState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Etapa do jogo, do ponto de vista de quem acompanha. */
enum class Stage {
    /** Partida em andamento. */
    PLAYING,

    /** Partida encerrada e pontuada (§12); aguarda a confirmação para a próxima (§13: inclui o empate). */
    ROUND_OVER,

    /** §13 jogo terminado com vencedor (inclusive por desistência, §13.1). */
    GAME_OVER,
}


/**
 * Estado publicado pelo [GameController]. Tudo o que a interface precisa, **sem** o estado completo da
 * partida: a mesa vem de `viewFor(viewerSeat)` (mãos dos outros, monte e mortos só como tamanhos).
 *
 * @property view vista do assento [viewerSeat] (o humano).
 * @property history partidas encerradas, com o detalhamento §12 ([RoundRecord.scores]).
 * @property humanLegal ações legais do humano quando o controlador espera a jogada dele; vazia caso contrário.
 * @property humanRequestId identificador do pedido ao humano a que [humanLegal] se refere (0 sem pedido); a
 *   interface o devolve em [GameController.submit] e [GameController.explain].
 * @property thinkingSeat bot que está decidindo (inclui a pausa entre jogadas), ou `null`.
 * @property turnEvents por assento ([Seat.index]), as ações públicas da vez mais recente (ou atual) dele
 *   nesta partida.
 * @property resigned §13.1 o humano desistiu: [winner] é o outro lado e a partida em andamento não foi pontuada.
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
    val humanRequestId: Long = 0,
    val resigned: Boolean = false,
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
 * Os 3 vermelhos (§6.5) continuam automáticos no motor; quem baixou cada um está no registro público
 * `PlayerView.redThreeLog`, que a interface usa para encenar as trocas.
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
    private val human: HumanPlayer? = players.filterIsInstance<HumanPlayer>().singleOrNull()
    private val started = AtomicBoolean(false)
    private val nextRoundSignal = Channel<Unit>(Channel.CONFLATED)
    private val awaitingNextRound = AtomicBoolean(false)
    private val requestCounter = AtomicLong(0)
    private val dealShownSignal = Channel<Unit>(Channel.CONFLATED)

    /** Partida (número) cuja encenação das trocas da distribuição o controlador espera; 0 se nenhuma. */
    private val awaitingDealRound = AtomicLong(0)

    @Volatile
    private var match: Match = startMatch(config.mode, config.targetScore, Random(gameSeed))
    private var turnEvents: List<List<PublicEvent>> = List(config.mode.seatCount) { emptyList() }
    init {
        // §3.5 com humano, a 1ª jogada espera a encenação das trocas da distribuição (ver [dealPresentationDone]).
        if (human != null && hasDealSwaps()) awaitingDealRound.set(1)
    }

    /** Partida e identificador do pedido em aberto ao humano; `null` fora da espera pelo humano. */
    @Volatile
    private var humanRequest: Pair<Long, RoundState>? = null

    /** §13.1 o humano desistiu: nada mais é aplicado nem publicado além do fim de jogo. */
    @Volatile
    private var resignedWinner: Side? = null

    @Volatile
    private var loopJob: Job? = null

    private val _state = MutableStateFlow(snapshot(Stage.PLAYING, humanLegal = emptyList(), thinkingSeat = null))

    /** Estado atual para a interface. */
    val state: StateFlow<GameSnapshot> = _state.asStateFlow()

    /**
     * Jogo atual completo, para persistência e testes. **Não** use para desenhar a mesa (contém as mãos de
     * todos, o monte e os mortos): a interface usa [state].
     */
    val currentMatch: Match get() = match

    private val resigned: Boolean get() = resignedWinner != null

    /**
     * Roda o jogo até o fim (§13) ou até a desistência (§13.1). Só pode ser chamado uma vez. Cancelável: ao
     * cancelar, a espera pelo humano ou pela confirmação de nova partida é interrompida.
     */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "O jogo já foi iniciado" }
        coroutineScope {
            val job = launch(computeDispatcher) { loop() }
            loopJob = job
            if (resigned) job.cancel()
        }
    }

    private suspend fun loop() {
        while (!match.isOver && !resigned) {
            bots.forEach { it.onNewRound() }
            turnEvents = List(config.mode.seatCount) { emptyList() }
            // Com humano, a 1ª jogada só acontece depois de a interface encenar as trocas de 3 vermelho da
            // distribuição (§3.5), na ordem. Sem humano (bots, :sim), não há espera.
            val waitDeal = human != null && hasDealSwaps()
            // Na 1ª partida a espera já foi armada na construção (o 1º snapshot pode ser igual ao inicial).
            if (waitDeal && match.roundNumber > 1) awaitingDealRound.set(match.roundNumber.toLong())
            publish(Stage.PLAYING)
            if (waitDeal) dealShownSignal.receive()
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
                        val requestId = requestCounter.incrementAndGet()
                        humanRequest = requestId to round
                        publish(Stage.PLAYING, humanLegal = legal, requestId = requestId)
                        try {
                            player.chooseAction(legal, requestId)
                        } finally {
                            humanRequest = null
                        }
                    }
                }
                // §13.1 desistência durante a decisão (ex.: bot pensando): nada mais é aplicado.
                if (resigned) return
                check(action in legal) { "Assento ${seat.index}: ação fora de legalActions: $action" }
                val event = PublicEvent.of(round.discardPile, seat, action)
                match = match.play(seat, action)
                recordEvent(event)
                bots.forEach { it.observe(event) }
                publish(Stage.PLAYING)
            }
            if (resigned) return
            match = match.finishRound()
            if (match.isOver) {
                publish(Stage.GAME_OVER)
            } else {
                if (human != null) awaitingNextRound.set(true)
                publish(Stage.ROUND_OVER)
                if (human != null) nextRoundSignal.receive()
                match = match.startNextRound()
            }
        }
    }

    /**
     * Confirmação do humano para distribuir a próxima partida (tela de fim de partida). Ignorada fora da
     * etapa [Stage.ROUND_OVER]; devolve se foi aceita.
     */
    fun continueToNextRound(): Boolean {
        if (resigned) return false
        if (!awaitingNextRound.compareAndSet(true, false)) return false
        return nextRoundSignal.trySend(Unit).isSuccess
    }

    /**
     * A interface terminou de encenar as trocas de 3 vermelho da distribuição da partida [roundNumber]: libera a 1ª
     * jogada. Ignorada se o controlador não espera essa partida; devolve se foi aceita.
     */
    fun dealPresentationDone(roundNumber: Int): Boolean {
        if (!awaitingDealRound.compareAndSet(roundNumber.toLong(), 0)) return false
        return dealShownSignal.trySend(Unit).isSuccess
    }

    /**
     * Envia a jogada do humano para o pedido [requestId] (o de [GameSnapshot.humanRequestId] em que ela foi
     * resolvida). Recusada se o pedido não é mais o em aberto ou se a ação não está em `legal`.
     */
    fun submit(action: Action, requestId: Long): Boolean {
        if (resigned) return false
        return human?.submit(action, requestId) ?: false
    }

    /**
     * Motivo pelo qual [action] do humano seria recusada (`RoundEngine.validate`), ou `null` se ela é válida ou se
     * o pedido [requestId] não é mais o em aberto. Valida contra a partida desse pedido, a mesma das ações
     * publicadas em [GameSnapshot.humanLegal]: um toque atrasado nunca recebe o motivo de outra situação.
     * Só devolve o motivo, nunca o estado oculto.
     */
    fun explain(action: Action, requestId: Long): RuleError? {
        val (id, round) = humanRequest ?: return null
        if (id != requestId || resigned) return null
        return (RoundEngine.validate(round, viewerSeat, action) as? RuleResult.Failure)?.error
    }

    /**
     * §13.1 desistência do humano: o jogo termina imediatamente, o outro lado vence e a partida em andamento não
     * é pontuada (os totais ficam como estavam). Publica [Stage.GAME_OVER] e encerra o laço, inclusive se um bot
     * estiver pensando (a decisão dele é descartada). Devolve `false` se o jogo já tinha terminado.
     */
    fun resign(): Boolean {
        if (match.isOver || resigned) return false
        val winner = config.mode.sides.first { it != config.mode.sideOf(viewerSeat) }
        resignedWinner = winner
        awaitingNextRound.set(false)
        awaitingDealRound.set(0)
        loopJob?.cancel()
        _state.value = snapshot(Stage.GAME_OVER, humanLegal = emptyList(), thinkingSeat = null)
        return true
    }

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

    /** §3.5 houve troca de 3 vermelho na distribuição da partida atual (registro público do motor). */
    private fun hasDealSwaps(): Boolean = match.currentRound.redThreeLog.any { it.atDeal }

    private fun publish(
        stage: Stage,
        humanLegal: List<Action> = emptyList(),
        thinkingSeat: Seat? = null,
        requestId: Long = 0,
    ) {
        if (resigned) return
        _state.value = snapshot(stage, humanLegal, thinkingSeat, requestId)
    }

    private fun snapshot(stage: Stage, humanLegal: List<Action>, thinkingSeat: Seat?, requestId: Long = 0): GameSnapshot {
        val current = match
        return GameSnapshot(
            config = config,
            viewerSeat = viewerSeat,
            view = current.currentRound.viewFor(viewerSeat),
            roundNumber = current.roundNumber,
            totals = current.totals,
            history = current.history,
            stage = stage,
            winner = resignedWinner ?: current.winner,
            humanLegal = humanLegal,
            thinkingSeat = thinkingSeat,
            turnEvents = turnEvents,
            humanRequestId = requestId,
            resigned = resignedWinner != null,
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
