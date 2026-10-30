package com.gtranca.ui.online

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.RuleError
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.TableDriver
import com.gtranca.multiplayer.SnapshotPacer
import com.gtranca.online.client.MatchEvent
import com.gtranca.online.client.MatchSession
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.Replaced
import com.gtranca.online.contracts.SeatInfo
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.game.TableAnimations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Por que a partida online acabou para este aparelho sem passar pelo fim de jogo normal (a tela avisa e volta). */
sealed interface OnlineGameEnd {
    /** A mesa foi encerrada ou destruída (§18.4). */
    data class Closed(val message: String) : OnlineGameEnd

    /** Outra conexão assumiu este assento (o mesmo jogador noutro aparelho). */
    data class Replaced(val message: String) : OnlineGameEnd

    /** Recusa definitiva do servidor (versão, acesso, partida inexistente...). */
    data class Failed(val problem: OnlineProblem) : OnlineGameEnd
}

/**
 * A partida online para a tela do jogo (§18.3): a vista do assento chega do servidor (que valida tudo) e vira o
 * [GameSnapshot] de sempre; a jogada escolhida vai ao servidor. Nunca valida regra: as jogadas são as legais recebidas.
 *
 * Ritmo (§18.3, como §16.6): as jogadas dos outros chegam em rajada; cada vista entra no [SnapshotPacer] e é mostrada uma
 * de cada vez, com a pausa antes de cada jogada de outro assento e as esperas da distribuição. O número do pedido da vez
 * é a versão da vista (o servidor não numera pedidos): uma jogada por versão.
 */
class OnlineTableDriver private constructor(
    private val session: MatchSession,
    private val scope: CoroutineScope,
    val welcome: Welcome,
    first: OnlineSeatView,
    private val events: kotlinx.coroutines.channels.ReceiveChannel<MatchEvent>,
    paceMillis: () -> Long,
) : TableDriver {
    val config: GameConfig = configOf(welcome.table, welcome.ruleSet)

    private val _seats = MutableStateFlow(welcome.seats)

    /** Os assentos como o servidor os mostra (nomes, robôs, conexão, desistência). */
    val seats: StateFlow<List<SeatInfo>> = _seats.asStateFlow()

    private val _ended = MutableStateFlow<OnlineGameEnd?>(null)
    val ended: StateFlow<OnlineGameEnd?> = _ended.asStateFlow()

    private val _turnClock = MutableStateFlow<com.gtranca.ui.game.TurnDeadline?>(null)

    /** §18.4 relógio da vez pela vista mais recente do servidor (não pela mostrada: o tempo corre no servidor). */
    val turnClock: StateFlow<com.gtranca.ui.game.TurnDeadline?> = _turnClock.asStateFlow()

    private fun clockFrom(view: OnlineSeatView) {
        _turnClock.value = view.turn?.let { com.gtranca.ui.game.TurnDeadline(it.seat.index, android.os.SystemClock.elapsedRealtime() + it.leftMs, it.limitMs) }
    }

    private val _reconnecting = MutableStateFlow(false)

    /** A conexão caiu e o cliente tenta voltar (a mesa segue no servidor). */
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    @Volatile
    private var shown: GameSnapshot = first.toSnapshot(config, welcome)

    private val _state = MutableStateFlow(shown)
    override val state: StateFlow<GameSnapshot> = _state.asStateFlow()

    /** Versão da vista já respondida (não reenviar por toque duplo); uma recusa a libera. */
    private val answered = MutableStateFlow(0L)

    @Volatile
    private var resigned = false

    private val _rejections = MutableSharedFlow<RuleError?>(extraBufferCapacity = 16)
    override val rejections: Flow<RuleError?> = _rejections

    override var dealDelayScale: Double = 1.0

    private val lock = Any()

    /**
     * §12 a pontuação da partida fica na tela até o Continuar: o servidor já distribui a seguinte (§18.4, poucos segundos
     * depois), então as vistas de partidas mais novas esperam aqui e só entram na fila depois do Continuar.
     */
    private var holdingRound: Int? = null
    private val held = ArrayDeque<GameSnapshot>()

    private fun offer(snapshot: GameSnapshot) {
        synchronized(lock) {
            val hold = holdingRound
            if (hold != null && snapshot.roundNumber > hold) {
                held.addLast(snapshot)
                return
            }
            if (snapshot.stage == Stage.ROUND_OVER) holdingRound = snapshot.roundNumber
        }
        pacer.offer(snapshot)
    }

    private val pacer = SnapshotPacer(
        paceMillis = paceMillis,
        dealWaitMillis = { if (paceMillis() > 0) (DEAL_MILLIS * dealDelayScale).toLong() + WAIT_GRACE_MILLIS else 0 },
        presentationWaitMillis = { if (paceMillis() > 0) PRESENTATION_WAIT_MILLIS else 0 },
        publish = { snapshot ->
            shown = snapshot
            refresh()
        },
    )

    private fun refresh() {
        synchronized(lock) {
            var snapshot = shown
            val done = answered.value
            if (done != 0L && snapshot.humanRequestId == done) snapshot = snapshot.copy(humanLegal = emptyList())
            if (resigned) snapshot = asResigned(snapshot)
            if (snapshot != _state.value) _state.value = snapshot
        }
    }

    init {
        clockFrom(first)
        if (shown.stage == Stage.ROUND_OVER) holdingRound = shown.roundNumber
        scope.launch { pacer.run(shown) }
        scope.launch { answered.collect { refresh() } }
        scope.launch {
            for (event in events) handle(event)
        }
    }

    private suspend fun handle(event: MatchEvent) {
        when (event) {
            is MatchEvent.Reconnecting -> _reconnecting.value = true
            is MatchEvent.Failed -> if (_ended.value == null && !resigned) _ended.value = OnlineGameEnd.Failed(event.error.toProblem())
            is MatchEvent.Message -> {
                _reconnecting.value = false
                when (val m = event.message) {
                    // Reconexão: a vista atual chega logo depois; o pedido respondido antes da queda vale de novo.
                    is Welcome -> { _seats.value = m.seats; answered.value = 0 }
                    is SeatUpdate -> {
                        clockFrom(m.view)
                        offer(m.view.toSnapshot(config, welcome))
                    }
                    is SeatsChanged -> _seats.value = m.seats
                    is Rejected -> {
                        answered.value = 0
                        // ILLEGAL traz o erro do motor: o mesmo texto em pt-BR do jogo local; as demais só devolvem a vez.
                        if (m.reason == com.gtranca.online.contracts.RejectReason.ILLEGAL) {
                            _rejections.emit(com.gtranca.multiplayer.MultiplayerDriver.ruleErrorOf(m.ruleError))
                        }
                    }
                    is YouResigned -> { resigned = true; refresh() }
                    is TableClosed -> _ended.value = OnlineGameEnd.Closed(m.message)
                    is Replaced -> _ended.value = OnlineGameEnd.Replaced(m.message)
                    is GameError -> _ended.value = OnlineGameEnd.Failed(OnlineProblem.UNEXPECTED)
                    else -> Unit
                }
            }
        }
    }

    /** §18.4 quem desistiu vai ao fim de jogo como derrota (no 1x1 o servidor já encerra; numa mesa maior o jogo dos outros segue). */
    private fun asResigned(snapshot: GameSnapshot): GameSnapshot = snapshot.copy(
        stage = Stage.GAME_OVER,
        resigned = true,
        winner = snapshot.view.mode.sides.first { it != snapshot.viewerSide },
        humanLegal = emptyList(),
        humanRequestId = 0,
    )

    override fun awaitingHuman(): Boolean {
        val snapshot = _state.value
        return snapshot.humanRequestId > 0 && snapshot.humanLegal.isNotEmpty() && snapshot.humanRequestId != answered.value
    }

    override fun submit(action: Action, requestId: Long): Boolean {
        if (requestId <= 0L || requestId == answered.value || resigned) return false
        answered.value = requestId
        refresh()
        scope.launch {
            // A versão da vista na tela: o servidor recusa (STALE_STATE) se ela já mudou, e a vez volta ao jogador.
            if (!session.play(action, requestId)) answered.value = 0
        }
        return true
    }

    /** O motivo só o servidor sabe; a recusa chega por [rejections]. */
    override fun explain(action: Action, requestId: Long): RuleError? = null

    override fun presentationDone(roundNumber: Int, logSize: Int) = pacer.presentationDone(roundNumber, logSize)

    override fun dealAnimationDone(roundNumber: Int) = pacer.dealAnimationDone(roundNumber)

    /**
     * O jogador terminou as telas de resultado: a próxima partida já começou no servidor (§18.4); as vistas guardadas
     * entram na fila e a tela as mostra no ritmo de sempre até alcançar a mesa.
     */
    override fun continueToNextRound() {
        val pending = synchronized(lock) {
            holdingRound = null
            val list = held.toList()
            held.clear()
            list
        }
        pending.forEach(::offer)
    }

    override fun resign() {
        scope.launch { session.resign() }
    }

    override suspend fun run() = awaitCancellation()

    companion object {
        private const val DEAL_MILLIS: Long = (4 * 11 + 2) * TableAnimations.DEAL_STAGGER_MILLIS + GameViewModel.DEFAULT_ANIMATION_MILLIS
        private const val WAIT_GRACE_MILLIS: Long = 4_000
        private const val PRESENTATION_WAIT_MILLIS: Long = 20_000

        /**
         * Abre a conexão e espera o [Welcome] e a primeira vista. `null` se a conexão acabou antes (o motivo vai em
         * [onFailure]). A coleta dos eventos segue no [scope] enquanto a tela existir.
         */
        suspend fun connect(
            session: MatchSession,
            scope: CoroutineScope,
            onFailure: (OnlineGameEnd) -> Unit,
            paceMillis: () -> Long = { GameController.DEFAULT_BOT_DELAY_MILLIS },
        ): OnlineTableDriver? {
            val channel = kotlinx.coroutines.channels.Channel<MatchEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            scope.launch {
                session.events().collect { channel.send(it) }
                channel.close()
            }
            var welcome: Welcome? = null
            for (event in channel) {
                when (event) {
                    is MatchEvent.Message -> when (val m = event.message) {
                        is Welcome -> welcome = m
                        is SeatUpdate -> welcome?.let { w -> return OnlineTableDriver(session, scope, w, m.view, channel, paceMillis) }
                        is TableClosed -> { onFailure(OnlineGameEnd.Closed(m.message)); return null }
                        is Replaced -> { onFailure(OnlineGameEnd.Replaced(m.message)); return null }
                        is YouResigned -> { onFailure(OnlineGameEnd.Closed(m.message)); return null }
                        is GameError -> { onFailure(OnlineGameEnd.Failed(OnlineProblem.UNEXPECTED)); return null }
                        else -> Unit
                    }
                    is MatchEvent.Failed -> { onFailure(OnlineGameEnd.Failed(event.error.toProblem())); return null }
                    is MatchEvent.Reconnecting -> Unit
                }
            }
            onFailure(OnlineGameEnd.Failed(OnlineProblem.NETWORK))
            return null
        }

        fun configOf(table: TableRules, rules: RuleSet): GameConfig = GameConfig(
            mode = if (table.mode == com.gtranca.online.contracts.TableMode.DUPLAS) com.gtranca.engine.model.GameMode.DUPLAS else com.gtranca.engine.model.GameMode.INDIVIDUAL,
            difficulty = when (table.robotLevel) {
                BotLevel.FACIL -> Difficulty.FACIL
                BotLevel.MEDIO -> Difficulty.MEDIO
                BotLevel.DIFICIL -> Difficulty.DIFICIL
            },
            targetScore = table.targetScore,
            rules = rules,
            stake = table.bet,
            skillBoosters = table.skillBoostersAllowed,
        )
    }
}

/** §18.3 a vista do servidor no formato da tela (a mesma do jogo local); o pedido da vez é a versão da vista. */
fun OnlineSeatView.toSnapshot(config: GameConfig, welcome: Welcome): GameSnapshot = GameSnapshot(
    config = config,
    viewerSeat = seat,
    view = toPlayerView(welcome.ruleSet),
    roundNumber = roundNumber,
    totals = totals,
    history = history.orEmpty(),
    stage = when (stage) {
        TableStage.PLAYING -> Stage.PLAYING
        TableStage.ROUND_OVER -> Stage.ROUND_OVER
        TableStage.GAME_OVER -> Stage.GAME_OVER
    },
    winner = winner,
    humanLegal = legal,
    thinkingSeat = thinkingSeat,
    turnEvents = List(mode.seatCount) { turnEvents.getOrNull(it).orEmpty() },
    humanRequestId = if (legal.isNotEmpty()) version else 0,
)
