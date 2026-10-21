package com.gtranca.multiplayer

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameEvent
import com.gtranca.game.GameSnapshot
import com.gtranca.game.HumanPlayer
import com.gtranca.game.PacingPolicy
import com.gtranca.game.Stage
import com.gtranca.net.ActionRejected
import com.gtranca.net.GameFinished
import com.gtranca.net.HostGamePort
import com.gtranca.net.HostSession
import com.gtranca.net.Payload
import com.gtranca.net.Played
import com.gtranca.net.RejectReason
import com.gtranca.net.RoundScored
import com.gtranca.net.RoundStarted
import com.gtranca.net.SeatView
import com.gtranca.net.TableStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Orquestração do **anfitrião** (§16.1, §16.6): liga o [GameController] (a autoridade, com todas as cartas) à
 * [HostSession]. Os convidados só propõem jogadas, validadas pelo pedido em aberto e pelas jogadas legais do motor; cada
 * jogada processada sai a todos como [Played] (só informação pública) e cada convidado recebe a vista **do seu
 * assento** ([SeatView], a mesma informação que um robô recebe, §16.4). O controlador roda em [PacingPolicy.FREE]: só
 * pára para a decisão de um humano; cada aparelho anima no seu ritmo. Nada vai para o disco (§16.12: `onSave = null`).
 *
 * O anfitrião joga no [HostSession.HOST_SEAT] pela vista [GameController.stateOf] desse assento.
 *
 * @param remoteSeats assentos dos convidados humanos (o retorno de [HostSession.startGame]).
 */
class MultiplayerHost(
    private val session: HostSession,
    val config: GameConfig,
    gameSeed: Long,
    remoteSeats: Set<Seat>,
    botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HostGamePort {

    val hostSeat: Seat = HostSession.HOST_SEAT
    private val remote: Set<Seat> = remoteSeats - hostSeat
    private val humanSeats: Set<Seat> = remote + hostSeat

    /** O jogo (autoridade). Só o anfitrião o tem; nunca é enviado a um convidado. */
    val controller: GameController = GameController(
        config = config,
        gameSeed = gameSeed,
        players = GameController.playersForTable(config, gameSeed, humanSeats.associateWith { HumanPlayer() }, botFactory),
        viewerSeat = hostSeat,
        computeDispatcher = computeDispatcher,
        botDelayMillis = 0,
        onSave = null,
        pacing = PacingPolicy.FREE,
    )

    /** Último pedido já respondido por assento: uma 2ª resposta ao mesmo pedido é recusada como repetida. */
    private val answered = ConcurrentHashMap<Int, Long>()

    /** §16.11 quem já tocou "Continuar", por partida. */
    private val ready = ConcurrentHashMap<Int, MutableSet<Seat>>()

    init {
        // Uma só fonte das regras da mesa: as que os convidados receberam (§16.2) são as que o anfitrião aplica.
        session.rules.let {
            require(it.mode == config.mode && it.rules == config.rules && it.targetScore == config.targetScore &&
                it.difficulty == config.difficulty.id && it.stake == config.stake) { "Regras da mesa diferentes das do jogo" }
        }
        session.game = this
    }

    /** Roda o jogo até o fim (§13), retransmitindo cada jogada e a vista de cada convidado. */
    suspend fun run() = coroutineScope {
        // Coleta antes de o jogo começar (o fluxo de eventos não guarda o passado) e sem nunca segurar o controlador.
        val outbox = Channel<Payload>(Channel.UNLIMITED)
        val relay = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.takeWhile { event ->
                toPayload(event)?.let { outbox.trySend(it) }
                event !is GameEvent.GameFinished
            }.collect()
        }
        val drain = launch { for (payload in outbox) session.broadcast(payload) }
        val sentHistory = ConcurrentHashMap<Int, List<RoundRecord>>()
        suspend fun sendView(seat: Seat, snapshot: GameSnapshot) {
            val history = snapshot.history.takeIf { it != sentHistory[seat.index] }
            sentHistory[seat.index] = snapshot.history
            session.sendView(seat, snapshot.toSeatView(history))
        }
        val viewJobs = remote.map { seat ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                controller.stateOf(seat).collect { sendView(seat, it) }
            }
        }
        controller.run()
        // Fim do jogo: encerra as vistas, envia a final de cada convidado e entrega os eventos que faltam.
        viewJobs.forEach { it.cancel() }
        remote.forEach { seat -> sendView(seat, controller.stateOf(seat).value) }
        relay.join()
        outbox.close()
        drain.join()
    }

    /** §16.11 o anfitrião tocou "Continuar". */
    fun continueRound() = continueRound(hostSeat, controller.stateOf(hostSeat).value.roundNumber)

    override fun continueRound(seat: Seat, roundNumber: Int) {
        val snapshot = controller.stateOf(hostSeat).value
        if (snapshot.stage != Stage.ROUND_OVER || snapshot.roundNumber != roundNumber || seat !in humanSeats) return
        val seats = ready.getOrPut(roundNumber) { ConcurrentHashMap.newKeySet() }
        seats += seat
        // Avança quando todos os humanos tocaram (desconectados e robôs não seguram: etapa 4).
        if (seats.containsAll(humanSeats)) controller.continueToNextRound()
    }

    override fun propose(seat: Seat, requestId: Long, action: Action): ActionRejected? {
        val open = controller.stateOf(seat).value.humanRequestId
        if (open == 0L) return ActionRejected(requestId, RejectReason.NOT_YOUR_TURN)
        if (requestId <= (answered[seat.index] ?: 0L)) return ActionRejected(requestId, RejectReason.STALE_REQUEST)
        if (requestId != open) return ActionRejected(requestId, RejectReason.STALE_REQUEST)
        controller.explain(seat, action, requestId)?.let {
            return ActionRejected(requestId, RejectReason.ILLEGAL, (it as? Enum<*>)?.name)
        }
        if (!controller.submit(seat, action, requestId)) return ActionRejected(requestId, RejectReason.ILLEGAL)
        answered[seat.index] = requestId
        return null
    }

    private fun toPayload(event: GameEvent): Payload? = when (event) {
        is GameEvent.RoundStarted -> RoundStarted(event.roundNumber)
        is GameEvent.ActionApplied -> Played(event.roundNumber, event.event, event.redThrees)
        is GameEvent.RoundScored -> RoundScored(event.roundNumber)
        is GameEvent.GameFinished -> GameFinished(event.winner, event.resigned)
        is GameEvent.SeatReplaced -> null // etapa 4
    }
}

/** §16.4 a vista de um assento para a rede: a do controlador, sem as regras e sem a configuração do aparelho. */
internal fun GameSnapshot.toSeatView(history: List<RoundRecord>?): SeatView = SeatView.of(
    view = view,
    roundNumber = roundNumber,
    stage = when (stage) {
        Stage.PLAYING -> TableStage.PLAYING
        Stage.ROUND_OVER -> TableStage.ROUND_OVER
        Stage.GAME_OVER -> TableStage.GAME_OVER
    },
    totals = totals,
    winner = winner,
    history = history,
    turnEvents = turnEvents,
    turnRedThrees = turnRedThrees,
    thinkingSeat = thinkingSeat,
    legal = humanLegal,
    requestId = humanRequestId,
)
