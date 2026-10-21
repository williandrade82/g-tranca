package com.gtranca.multiplayer

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.CountingRandom
import com.gtranca.game.GameController
import com.gtranca.game.botSeed
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
import com.gtranca.net.RoundOver
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
    private val gameSeed: Long,
    remoteSeats: Set<Seat>,
    private val botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
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

    /** §16.9 assentos cujo humano desistiu (a aposta fica retida no robô; o repasse do prêmio é da etapa 4). */
    private val _resignedSeats = ConcurrentHashMap.newKeySet<Seat>()
    val resignedSeats: Set<Seat> get() = _resignedSeats.toSet()

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
        val outboxSeat = Channel<Pair<Seat, Payload>>(Channel.UNLIMITED)
        val relay = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.takeWhile { event ->
                if (event is GameEvent.RoundScored) {
                    // §16.11 pacote de fim de partida, por assento (a mesa final traz a mão de quem recebe).
                    remote.forEach { seat -> roundOver(seat, event)?.let { outboxSeat.trySend(seat to it) } }
                } else {
                    toPayload(event)?.let { outbox.trySend(it) }
                }
                event !is GameEvent.GameFinished
            }.collect()
        }
        val drain = launch { for (payload in outbox) session.broadcast(payload) }
        val drainSeat = launch { for ((seat, payload) in outboxSeat) session.sendTo(seat, payload) }
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
        outboxSeat.close()
        drain.join()
        drainSeat.join()
    }

    /** §16.11 pacote de fim da partida para o convidado do [seat]: só o que é visível a ele (§16.4). */
    private fun roundOver(seat: Seat, event: GameEvent.RoundScored): RoundOver? {
        val view = controller.roundEndViewOf(seat, event.roundNumber) ?: return null
        val history = controller.currentMatch.history.takeWhile { it.number <= event.roundNumber }
        return RoundOver(
            roundNumber = event.roundNumber,
            record = event.record,
            totals = event.totals,
            gameWinner = event.gameWinner,
            view = SeatView.of(
                view = view,
                roundNumber = event.roundNumber,
                stage = if (event.gameWinner != null) TableStage.GAME_OVER else TableStage.ROUND_OVER,
                totals = event.totals,
                winner = event.gameWinner,
                history = history,
                turnEvents = emptyList(),
                turnRedThrees = emptyList(),
                thinkingSeat = null,
                legal = emptyList(),
                requestId = 0,
            ),
        )
    }

    /**
     * §16.11 o anfitrião terminou as telas de resultado: distribui a próxima partida. Não espera nenhum convidado (cada um
     * conduz as próprias telas e guarda a partida nova até avançar).
     */
    fun continueRound(): Boolean = controller.continueToNextRound()

    /**
     * §16.9 desistência do convidado do [seat], a qualquer momento: na mesa de 4, um robô assume na hora (a decisão em
     * aberto do humano é descartada) e o jogo segue; no 1×1 entre dois humanos vale §13.1 (o outro vence). A desistência
     * do anfitrião (passar o cargo, sucessão) é da etapa 4.
     */
    override fun resign(seat: Seat): Boolean {
        if (seat !in remote || seat in _resignedSeats) return false
        val accepted = if (config.mode.seatCount == 2) {
            controller.resign(seat)
        } else {
            val random = CountingRandom(botSeed(gameSeed, seat.index))
            controller.replaceWithBot(seat, botFactory(seat, random), random)
        }
        if (accepted) _resignedSeats += seat
        return accepted
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
        is GameEvent.RoundScored -> null // pacote por assento, ver [roundOver]
        is GameEvent.GameFinished -> GameFinished(event.winner, event.resigned)
        is GameEvent.SeatReplaced -> null // aviso enviado pela sessão (SeatTakenByRobot)
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
