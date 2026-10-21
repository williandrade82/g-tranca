package com.gtranca.multiplayer

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.GroupBetLedger
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.Seat
import com.gtranca.game.CountingRandom
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameEvent
import com.gtranca.game.GameSnapshot
import com.gtranca.game.HumanPlayer
import com.gtranca.game.PacingPolicy
import com.gtranca.game.RestoredGame
import com.gtranca.game.SaveSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.botSeed
import com.gtranca.net.ActionRejected
import com.gtranca.net.GameFinished
import com.gtranca.net.HostGamePort
import com.gtranca.net.HostSession
import com.gtranca.net.NoticeKind
import com.gtranca.net.PauseReason
import com.gtranca.net.Paused
import com.gtranca.net.Payload
import com.gtranca.net.Payout
import com.gtranca.net.Played
import com.gtranca.net.RejectReason
import com.gtranca.net.Resumed
import com.gtranca.net.RoundOver
import com.gtranca.net.RoundStarted
import com.gtranca.net.SeatView
import com.gtranca.net.StakeDebit
import com.gtranca.net.SuccessorState
import com.gtranca.net.TableNotice
import com.gtranca.net.TableStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Decisão pendente do anfitrião (§16.8, §16.9): chegou a vez do [seat] e o jogador não está (caiu ou desistiu). [choice] é
 * o que ele já escolheu ([NoticeKind.WAITING] ou [NoticeKind.SEAT_OPENED]; `null` = ainda não escolheu). As três ações
 * ficam sempre disponíveis: [MultiplayerHost.waitForPlayer], [MultiplayerHost.replaceWithRobot] e
 * [MultiplayerHost.openSeatForRecruit].
 */
data class HostDecision(val seat: Seat, val playerId: Int?, val reason: PauseReason, val choice: NoticeKind? = null)

/**
 * Orquestração do **anfitrião** (§16.1, §16.6): liga o [GameController] (a autoridade, com todas as cartas) à
 * [HostSession]. Os convidados só propõem jogadas, validadas pelo pedido em aberto e pelas jogadas legais do motor; cada
 * jogada processada sai a todos como [Played] (só informação pública) e cada convidado recebe a vista **do seu
 * assento** ([SeatView], §16.4). O controlador roda em [PacingPolicy.FREE]: só pára para a decisão de um humano.
 *
 * Etapa 4: queda e reingresso (§16.5, §16.8), pausa na vez de quem não está com a decisão do anfitrião ([decision]),
 * robô, recrutamento com aprovação (§16.7), desistência com o assento "VAGO" (§16.9), apostas em grupo
 * ([GroupBetLedger], §16.13) e sucessão (§16.10): a cada jogada o estado completo vai **só** ao 1º e ao 2º sucessores, e
 * a jogada só é transmitida aos demais depois da confirmação do 1º (ou do tempo limite, que o marca indisponível).
 *
 * @param remoteSeats assentos dos convidados humanos (o retorno de [HostSession.startGame] ou, no sucessor, os demais
 *   humanos da sala).
 * @param restored estado recebido como sucessor (§16.10), ou `null` para um jogo novo.
 */
class MultiplayerHost(
    private val session: HostSession,
    val config: GameConfig,
    private val gameSeed: Long,
    remoteSeats: Set<Seat>,
    private val botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    restored: SuccessorState? = null,
    /** Personagens disponíveis para substituir (§16.8: o primeiro ainda não usado na mesa). */
    private val personaPool: List<String> = com.gtranca.game.BotProfile.entries.map { it.code },
    private val successorAckTimeoutMillis: Long = SUCCESSOR_ACK_TIMEOUT_MILLIS,
) : HostGamePort {

    val hostSeat: Seat = session.hostSeat

    /** A sessão desta mesa (sala, pedidos, vagas), para a interface do anfitrião; inclusive depois de assumir (§16.10). */
    val hostSession: HostSession get() = session
    private val remote: MutableSet<Seat> = ConcurrentHashMap.newKeySet<Seat>().also { it += remoteSeats - hostSeat }

    /** O jogo (autoridade). Só o anfitrião (e, em memória, os sucessores) o tem; nunca vai a um convidado comum. */
    val controller: GameController = GameController(
        config = config,
        gameSeed = gameSeed,
        players = GameController.playersForTable(
            config, gameSeed, (remote + hostSeat).associateWith { HumanPlayer() }, botFactory,
            randomCalls = restored?.botRandomCalls ?: emptyList(),
        ),
        viewerSeat = hostSeat,
        computeDispatcher = computeDispatcher,
        botDelayMillis = 0,
        restored = restored?.let { RestoredGame(it.match, it.events, it.botRandomCalls) },
        onSave = ::onSave,
        pacing = PacingPolicy.FREE,
    )

    /** Último pedido já respondido por assento: uma 2ª resposta ao mesmo pedido é recusada como repetida. */
    private val answered = ConcurrentHashMap<Int, Long>()

    private val _ledger = MutableStateFlow(
        restored?.ledger ?: GroupBetLedger.start(
            config.stake, config.mode.seatCount, session.roster.value.filter { it.seat != null }.associate { it.seat!!.index to it.id },
        ),
    )

    /** §16.13 apostas em grupo (retidas, herdadas, perdidas). */
    val ledger: StateFlow<GroupBetLedger> = _ledger.asStateFlow()

    private val _robotPersonas = MutableStateFlow(
        restored?.robotPersonas ?: config.mode.seats.filter { it !in remote && it != hostSeat }
            .zip(session.rules.robots).associate { (seat, persona) -> seat.index to persona },
    )

    /** §16.8 personagem de cada assento de robô (o substituto é o primeiro do [personaPool] ainda não usado). */
    val robotPersonas: StateFlow<Map<Int, String>> = _robotPersonas.asStateFlow()

    private val _decision = MutableStateFlow<HostDecision?>(null)

    /** §16.8, §16.9 decisão pendente do anfitrião (a pausa), ou `null` com o jogo seguindo. */
    val decision: StateFlow<HostDecision?> = _decision.asStateFlow()

    private val _payouts = MutableStateFlow<Map<Int, Int>?>(null)

    /** §16.13 o que cada humano recebe (por id) no fim do jogo; `null` antes. */
    val payouts: StateFlow<Map<Int, Int>?> = _payouts.asStateFlow()

    private val _noSuccessor = MutableStateFlow(false)

    /** §16.10 aviso ao anfitrião: sem sucessor disponível, uma queda dele encerra a mesa. */
    val noSuccessor: StateFlow<Boolean> = _noSuccessor.asStateFlow()

    /**
     * Os pedidos na rede nunca se repetem na mesa: o anfitrião que assume (§16.10) numera os dele depois dos do anterior
     * (o controlador novo recomeça do 1).
     */
    private val requestOffset: Long = restored?.requestBase ?: 0

    @Volatile
    private var maxWireRequest: Long = requestOffset

    private val pauseLock = Mutex()
    private val stateSeq = AtomicLong(restored?.stateSeq ?: 0)
    private val latestState = MutableStateFlow<SuccessorState?>(null)
    private val acks = MutableStateFlow<Map<Int, Long>>(emptyMap())
    private val sentHistory = ConcurrentHashMap<Int, List<RoundRecord>>()
    private val viewJobs = ConcurrentHashMap<Int, Job>()
    private var scope: CoroutineScope? = null
    private var runJob: Job? = null

    /** §16.11 último pacote de fim de partida de cada assento (reenviado a quem volta sem tê-lo recebido). */
    private val lastRoundOver = ConcurrentHashMap<Int, RoundOver>()

    /** §16.10 durante a passagem do cargo as vistas não esperam a confirmação do sucessor. */
    @Volatile
    private var handingOver = false

    /** §16.10 escolha da pausa herdada do anfitrião anterior (aplicada quando a mesma pausa reabrir). */
    @Volatile
    private var restoredDecision: Pair<Seat, NoticeKind>? =
        restored?.let { r -> r.decisionSeat?.let { seat -> r.decisionChoice?.let { seat to it } } }

    /** §16.10 outro anfitrião da mesma mesa prevaleceu: este parou e o aparelho volta como convidado. */
    val superseded: StateFlow<Boolean> get() = session.superseded

    /** §16.10 confirmado como anfitrião (o provisório só retoma o jogo depois disto). */
    val committed: StateFlow<Boolean> get() = session.committed

    /**
     * §16.10, §16.15 provisório com outros humanos sem reingresso depois de 30 s: a tela "Aguardando os outros jogadores"
     * mostra **Assumir mesmo assim** ([forceCommit]). (Provisório, revisar com produto.)
     */
    val canForceCommit: StateFlow<Boolean> get() = session.canForceCommit

    /** §16.10 "Assumir mesmo assim": confirma o cargo e o jogo retoma. Devolve se confirmou. */
    suspend fun forceCommit(): Boolean = session.forceCommit()

    /** §16.11 depois do fim, ainda há humano fora sem o resultado (a mesa segue anunciada para a entrega). */
    val resultsPending: StateFlow<Boolean> get() = session.resultsPending

    /** §16.11, §16.15 entrega encerrada (todos receberam ou 5 min): a tela pode fechar a mesa. */
    val deliveryEnded: StateFlow<Boolean> get() = session.deliveryEnded

    init {
        // Uma só fonte das regras da mesa: as que os convidados receberam (§16.2) são as que o anfitrião aplica.
        session.rules.let {
            require(it.mode == config.mode && it.rules == config.rules && it.targetScore == config.targetScore &&
                it.difficulty == config.difficulty.id && it.stake == config.stake) { "Regras da mesa diferentes das do jogo" }
        }
        session.game = this
    }

    /**
     * Roda o jogo até o fim (§13) ou até o anfitrião passar o cargo ([handOver]). O anfitrião provisório (§16.10) só
     * começa depois de confirmado; se outro anfitrião prevalecer ([superseded]), pára sem pagar nada.
     */
    suspend fun run() = coroutineScope {
        if (!session.awaitCommitted()) return@coroutineScope
        scope = this
        runJob = coroutineContext[Job]
        val stepDown = launch { session.superseded.first { it }; runJob?.cancel() }
        val outbox = Channel<Pair<Long, Payload>>(Channel.UNLIMITED)
        val outboxSeat = Channel<Triple<Long, Seat, Payload>>(Channel.UNLIMITED)
        val relay = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.takeWhile { event ->
                val seq = stateSeq.get()
                if (event is GameEvent.RoundScored) {
                    // §16.11 pacote de fim de partida, por assento (a mesa final traz a mão de quem recebe).
                    remote.forEach { seat ->
                        roundOver(seat, event)?.let {
                            lastRoundOver[seat.index] = it
                            outboxSeat.trySend(Triple(seq, seat, it))
                        }
                    }
                } else {
                    toPayload(event)?.let { outbox.trySend(seq to it) }
                }
                event !is GameEvent.GameFinished
            }.collect()
        }
        val drain = launch { for ((seq, payload) in outbox) { awaitConfirmed(seq); session.broadcast(payload) } }
        val drainSeat = launch { for ((seq, seat, payload) in outboxSeat) { awaitConfirmed(seq); session.sendTo(seat, payload) } }
        // §16.10 os sucessores têm um estado desde o início (antes da 1ª jogada da partida não há ponto de salvamento).
        if (latestState.value == null) latestState.value = successorState(controller.saveSnapshot())
        val successorJob = launch { successorRelay() }
        remote.forEach { startViewJob(it) }
        evaluatePause()
        controller.run()
        // Fim do jogo: encerra as vistas, envia a final de cada convidado e entrega os eventos que faltam.
        viewJobs.values.forEach { it.cancel() }
        remote.forEach { seat -> sendView(seat, controller.stateOf(seat).value) }
        relay.join()
        outbox.close()
        outboxSeat.close()
        drain.join()
        drainSeat.join()
        successorJob.cancel()
        stepDown.cancel()
        session.finish(settle())
    }

    private fun startViewJob(seat: Seat) {
        val scope = scope ?: return
        viewJobs[seat.index]?.cancel()
        viewJobs[seat.index] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.stateOf(seat).collect {
                evaluatePause()
                awaitConfirmed(stateSeq.get())
                sendView(seat, it)
            }
        }
    }

    private suspend fun sendView(seat: Seat, snapshot: GameSnapshot) {
        val history = snapshot.history.takeIf { it != sentHistory[seat.index] }
        sentHistory[seat.index] = snapshot.history
        val view = snapshot.toSeatView(history)
        val wire = if (view.requestId == 0L) view else view.copy(requestId = view.requestId + requestOffset)
        if (wire.requestId > maxWireRequest) maxWireRequest = wire.requestId
        session.sendView(seat, wire)
    }

    /**
     * §16.13 fim do jogo: o que cada humano recebe vai a cada um (só o dele). Devolve, por jogador, o pacote para quem
     * está fora e volta depois do fim (§16.11): o fim da última partida, a vista final, o fim do jogo e o prêmio.
     */
    private suspend fun settle(): Map<Int, List<Payload>> {
        val state = controller.state.value
        val winner = state.winner ?: return emptyMap()
        val winners = config.mode.seats.map { config.mode.sideOf(it) == winner }
        val result = _ledger.value.payouts(winners)
        _payouts.value = result
        val finals = mutableMapOf<Int, List<Payload>>()
        session.roster.value.filter { it.id != session.hostId }.forEach { p ->
            val payout = Payout(config.stake, result[p.id] ?: 0)
            session.sendToPlayer(p.id, payout)
            val seat = p.seat ?: return@forEach
            val view = controller.stateOf(seat).value.toSeatView(controller.stateOf(seat).value.history)
            finals[p.id] = listOfNotNull(
                lastRoundOver[seat.index]?.takeIf { it.gameWinner != null },
                com.gtranca.net.ViewUpdate(view),
                GameFinished(winner, state.resigned),
                payout,
            )
        }
        return finals
    }

    // ---- §16.10 sucessão -------------------------------------------------------------------------------------------

    /** Chamado pelo controlador sob a trava a cada ponto de salvamento: só guarda a foto (nada vai para o disco). */
    private fun onSave(snapshot: SaveSnapshot) {
        latestState.value = successorState(snapshot)
    }

    private fun successorState(snapshot: SaveSnapshot): SuccessorState = withMeta(
        SuccessorState(
            stateSeq = stateSeq.incrementAndGet(),
            match = snapshot.match,
            events = snapshot.events,
            botRandomCalls = snapshot.botRandomCalls,
            members = session.membersForSuccessor(),
            nextPlayerId = session.nextPlayerId(),
        ),
    )

    /** A sala atual no estado do sucessor (§16.10): tokens, apostas, personagens, vagas abertas e a escolha da pausa. */
    private fun withMeta(state: SuccessorState): SuccessorState {
        val decision = _decision.value
        return state.copy(
            members = session.membersForSuccessor(),
            ledger = _ledger.value,
            nextPlayerId = session.nextPlayerId(),
            robotPersonas = _robotPersonas.value,
            requestBase = maxWireRequest,
            epoch = session.epoch,
            openSeats = session.openSeats.value.sortedBy { it.index },
            decisionSeat = decision?.seat,
            decisionChoice = decision?.choice,
        )
    }

    /** Envia o estado mais recente ao 1º e ao 2º sucessores (e a quem acaba de virar sucessor). */
    private suspend fun successorRelay(): Unit = coroutineScope {
        val sent = ConcurrentHashMap<Int, Long>()
        suspend fun push() {
            // §16.4 na passagem do cargo o estado completo vai só ao escolhido (os sucessores o descartam).
            if (handingOver) return
            val state = latestState.value ?: return
            val targets = session.successors.value
            _noSuccessor.value = targets.isEmpty()
            targets.forEach { id ->
                if ((sent[id] ?: -1) < state.stateSeq) {
                    sent[id] = state.stateSeq
                    session.sendSuccessorState(id, state.copy(members = session.membersForSuccessor()))
                }
            }
            sent.keys.retainAll(targets.toSet())
        }
        launch { session.successors.collect { push() } }
        // §16.10 a sala muda sem jogada (entrada, vaga aberta, escolha na pausa, apostas): o estado vai de novo.
        launch {
            kotlinx.coroutines.flow.combine(session.roster, session.openSeats, _decision, _ledger, _robotPersonas) { _, _, _, _, _ -> Unit }
                .collect { latestState.update { st -> st?.let { withMeta(it.copy(stateSeq = stateSeq.incrementAndGet())) } } }
        }
        latestState.collect { push() }
    }

    /**
     * §16.10 só segue (transmite aos demais) depois que o 1º sucessor confirmou o estado [seq]; sem confirmação no tempo
     * limite, ele é marcado indisponível e o 2º passa a 1º. Sem sucessor, segue (com o aviso [noSuccessor]).
     */
    private suspend fun awaitConfirmed(seq: Long) {
        while (true) {
            if (handingOver) return
            val first = session.successors.value.firstOrNull() ?: return
            if (latestState.value == null) return
            val ok = withTimeoutOrNull(successorAckTimeoutMillis) { acks.first { (it[first] ?: 0) >= seq } } != null
            if (ok) return
            if (session.successors.value.firstOrNull() == first) session.markUnavailable(first)
        }
    }

    override fun onSuccessorAck(playerId: Int, stateSeq: Long) {
        acks.update { if ((it[playerId] ?: 0) >= stateSeq) it else it + (playerId to stateSeq) }
        // §16.10 quem foi marcado indisponível volta a contar só ao confirmar o estado mais recente (uma confirmação
        // atrasada de um estado antigo não o promove de novo, para não custar o tempo limite a cada jogada).
        val latest = latestState.value?.stateSeq ?: return
        if (stateSeq >= latest && playerId !in session.successors.value) scope?.launch { session.markAvailable(playerId) }
    }

    /**
     * §16.9, §16.10 o anfitrião passa o cargo a [newHostId] (um humano conectado; padrão: o 1º sucessor) e deixa a mesa;
     * com [resign] o assento dele fica "VAGO" e ele perde a aposta (desistência de um jogador comum). No 1×1 a desistência
     * é a de §13.1 (o outro vence) e não há passagem. Devolve o novo anfitrião, ou `null` se não há humano: então, com
     * [resign], o jogo termina.
     */
    suspend fun handOver(newHostId: Int? = null, resign: Boolean = true): Int? {
        if (resign && config.mode.seatCount == 2) {
            if (controller.resign(hostSeat)) _ledger.update { it.forfeit(hostSeat.index) }
            return null
        }
        val candidates = session.roster.value.filter { it.id != session.hostId && it.connected && !it.resigned }
        // §16.10 o escolhido, depois os sucessores e a ordem de entrada.
        val ordered = (
            listOfNotNull(candidates.firstOrNull { it.id == newHostId }) +
                session.successors.value.mapNotNull { id -> candidates.firstOrNull { it.id == id } } +
                candidates.sortedBy { it.joinOrder }
            ).distinctBy { it.id }
        if (ordered.isEmpty()) return noHumanForHost(resign)
        handingOver = true
        try {
            if (resign) {
                _ledger.update { it.forfeit(hostSeat.index) }
                session.markHostResigned()
            }
            for (target in ordered) {
                // §16.4 o estado completo vai só ao escolhido; nenhum outro convidado o recebe. §16.10 o anfitrião só sai
                // depois da confirmação dele; sem ela no tempo limite, o próximo disponível.
                val state = handOverState()
                session.sendHandOverState(target.id, state)
                val acked = withTimeoutOrNull(successorAckTimeoutMillis) { acks.first { (it[target.id] ?: 0) >= state.stateSeq } } != null
                if (!acked) {
                    session.dropHandOverState(target.id)
                    continue
                }
                // O jogo pára aqui; se andou enquanto esperava (robôs), o escolhido recebe o estado final.
                runJob?.cancel()
                val final = handOverState()
                if (final.match != state.match || final.events != state.events) session.sendHandOverState(target.id, final)
                session.handOver(target.id)
                return target.id
            }
        } finally {
            handingOver = false
        }
        return noHumanForHost(resign)
    }

    /** Estado do jogo agora, com o anfitrião fora (para quem assume na passagem do cargo). */
    private fun handOverState(): SuccessorState = successorState(controller.saveSnapshot()).let { state ->
        state.copy(
            members = state.members.map {
                if (it.info.id == session.hostId) it.copy(info = it.info.copy(connected = false, isHost = false)) else it
            },
        )
    }

    /**
     * §16.9 sem humano conectado que receba o cargo: com [resign] é a desistência do lado do anfitrião (§13.1): o jogo
     * termina, o outro lado vence e a aposta dele fica perdida; quem estava fora no lado vencedor recebe ao voltar (§16.11).
     */
    private fun noHumanForHost(resign: Boolean): Int? {
        if (resign) {
            _ledger.update { it.forfeit(hostSeat.index) }
            controller.resign(hostSeat)
        }
        return null
    }

    // ---- §16.8, §16.9 pausa e decisão do anfitrião --------------------------------------------------------------

    /** O assento da vez é de um humano remoto que não está (caiu, desistiu): pausa e pede a decisão; senão, segue. */
    private suspend fun evaluatePause() {
        pauseLock.withLock {
            val waiting = remote.firstOrNull { controller.stateOf(it).value.humanRequestId > 0 }
            val player = waiting?.let { session.playerAt(it) }
            val absent = waiting != null && (player == null || !player.connected || player.resigned)
            val current = _decision.value
            if (absent) {
                if (current?.seat != waiting) {
                    val reason = if (player == null || player.resigned) PauseReason.VACANT else PauseReason.DISCONNECTED
                    // §16.10 a escolha já feita pelo anfitrião anterior nesta mesma pausa continua valendo.
                    val choice = restoredDecision?.takeIf { it.first == waiting }?.second
                    restoredDecision = null
                    _decision.value = HostDecision(waiting!!, player?.id, reason, choice)
                    // §16.7 pedidos de entrada para o assento da vez são aceitos enquanto o anfitrião decide.
                    session.setAwaitingSeat(waiting)
                    session.broadcast(Paused(waiting, reason, choice))
                }
            } else if (current != null) {
                _decision.value = null
                session.setAwaitingSeat(null)
                session.closeSeat(current.seat)
                session.broadcast(Resumed)
            }
        }
    }

    /** §16.8 opção 1: aguardar o jogador reingressar (todos são avisados). */
    suspend fun waitForPlayer() = choose(NoticeKind.WAITING)

    /** §16.8 opção 3: abrir o assento da vez para outro convidado ("aguardando recrutamento"). */
    suspend fun openSeatForRecruit() {
        val decision = _decision.value ?: return
        session.openSeat(decision.seat)
        choose(NoticeKind.SEAT_OPENED)
    }

    private suspend fun choose(kind: NoticeKind) {
        val decision = pauseLock.withLock {
            val d = _decision.value ?: return
            d.copy(choice = kind).also { _decision.value = it }
        }
        session.broadcast(TableNotice(kind, decision.seat, decision.playerId))
        session.broadcast(Paused(decision.seat, decision.reason, kind))
    }

    /**
     * §16.8 opção 2 (e §16.9 no assento "VAGO"): um robô assume o [seat] (padrão: o da decisão pendente) na hora, com o
     * primeiro personagem não usado. Quem estava perde a aposta (retida no assento). Todos são avisados; o jogo segue.
     */
    suspend fun replaceWithRobot(seat: Seat? = _decision.value?.seat): Boolean {
        val target = seat ?: return false
        if (target !in remote) return false
        val random = CountingRandom(botSeed(gameSeed, target.index))
        if (!controller.replaceWithBot(target, botFactory(target, random), random)) return false
        remote -= target
        viewJobs.remove(target.index)?.cancel()
        _ledger.update { it.forfeit(target.index) }
        // §16.8 sempre um personagem que não está na mesa.
        _robotPersonas.update { used -> personaPool.firstOrNull { it !in used.values }?.let { used + (target.index to it) } ?: used }
        session.seatToRobot(target, _robotPersonas.value[target.index])
        evaluatePause()
        return true
    }

    /**
     * §16.7 libera um assento de **robô** para alguém entrar (com aprovação); o robô joga até lá. Na mesa reduzida
     * (anfitrião × robô, §16.3) quem entra faz um 1×1 entre humanos.
     */
    suspend fun openRobotSeat(seat: Seat) {
        if (seat in remote || seat == hostSeat) return
        session.openSeat(seat)
        session.broadcast(TableNotice(NoticeKind.SEAT_OPENED, seat))
    }

    /**
     * §16.7 aprova a entrada [requestId]: o novo jogador ocupa o assento aberto e já entra jogando. No lugar de quem saiu
     * herda a aposta retida (sem débito); no lugar de um robô, debita a aposta ([StakeDebit] vai só a ele).
     */
    suspend fun approveJoin(requestId: Int): Boolean {
        val request = session.joinRequests.value.firstOrNull { it.requestId == requestId } ?: return false
        val seat = request.seat
        // §16.7 pedido feito durante a decisão da pausa: aprovar = abrir a vaga e aprovar.
        if (seat !in session.openSeats.value) {
            if (_decision.value?.seat != seat) return false
            openSeatForRecruit()
        }
        var wasRobot = false
        // O assento só muda de jogador se a sessão aprovar (sob a trava dela): sem corrida com a saída do candidato nem
        // com a volta do dono (§16.5).
        val info = session.approveJoin(requestId) attach@{ s ->
            wasRobot = s !in remote
            if (wasRobot) {
                if (!controller.replacePlayer(s, HumanPlayer())) return@attach false
                remote += s
            }
            true
        } ?: return false
        if (wasRobot) startViewJob(seat)
        var debit = 0
        // §16.8 opção 3: quem caiu e foi substituído por outro convidado perde a aposta, que o novo herda.
        _ledger.update { ledger -> ledger.forfeit(seat.index).occupy(seat.index, info.id).also { debit = it.second }.first }
        if (wasRobot) _robotPersonas.update { it - seat.index }
        session.sendTo(seat, StakeDebit(debit))
        evaluatePause()
        return true
    }

    // ---- HostGamePort ---------------------------------------------------------------------------------------------

    /**
     * §16.9 desistência do convidado do [seat], a qualquer momento: perde a aposta (retida no assento). Na mesa de 4 o
     * assento fica "VAGO" e o jogo segue até a vez dele (então o anfitrião decide); no 1×1 vale §13.1 (o outro vence).
     */
    override fun resign(seat: Seat): Boolean {
        if (seat !in remote) return false
        if (config.mode.seatCount == 2 && !controller.resign(seat)) return false
        _ledger.update { it.forfeit(seat.index) }
        return true
    }

    override fun onPresenceChanged() {
        scope?.launch { evaluatePause() }
    }

    /**
     * §16.5, §16.7 quem (re)entra recebe o fim da última partida se ainda não o tinha recebido (§16.11), a vista completa
     * (com o histórico) e, se o jogo está pausado, o porquê.
     */
    override fun onSeatAttached(seat: Seat, roundOverSeen: Int) {
        val scope = scope ?: return
        scope.launch {
            // §16.9 quem desistiu e voltou pelo token recupera a aposta.
            session.playerAt(seat)?.let { p -> _ledger.update { it.restore(seat.index, p.id) } }
            lastRoundOver[seat.index]?.takeIf { it.roundNumber > roundOverSeen }?.let { session.sendTo(seat, it) }
            sentHistory.remove(seat.index)
            if (seat in remote) sendView(seat, controller.stateOf(seat).value)
            _decision.value?.takeIf { it.seat != seat }?.let { session.sendTo(seat, Paused(it.seat, it.reason, it.choice)) }
            evaluatePause()
        }
    }

    override fun propose(seat: Seat, requestId: Long, action: Action): ActionRejected? {
        val open = controller.stateOf(seat).value.humanRequestId
        if (open == 0L) return ActionRejected(requestId, RejectReason.NOT_YOUR_TURN)
        if (requestId <= (answered[seat.index] ?: 0L)) return ActionRejected(requestId, RejectReason.STALE_REQUEST)
        if (requestId != open + requestOffset) return ActionRejected(requestId, RejectReason.STALE_REQUEST)
        controller.explain(seat, action, open)?.let {
            return ActionRejected(requestId, RejectReason.ILLEGAL, (it as? Enum<*>)?.name)
        }
        if (!controller.submit(seat, action, open)) return ActionRejected(requestId, RejectReason.ILLEGAL)
        answered[seat.index] = requestId
        return null
    }

    /** Para testes e a interface: id do humano no [seat] e os sucessores atuais (§16.10). */
    internal fun sessionPlayerAt(seat: Seat): Int? = session.playerAt(seat)?.id

    internal fun sessionSuccessors(): List<Int> = session.successors.value

    /** §16.7 pedidos de entrada esperando a aprovação deste anfitrião. */
    fun sessionJoinRequests(): StateFlow<List<com.gtranca.net.JoinRequest>> = session.joinRequests

    /** §16.11 o anfitrião terminou as telas de resultado: distribui a próxima partida (sem esperar ninguém). */
    fun continueRound(): Boolean = controller.continueToNextRound()

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

    private fun toPayload(event: GameEvent): Payload? = when (event) {
        is GameEvent.RoundStarted -> RoundStarted(event.roundNumber)
        is GameEvent.ActionApplied -> Played(event.roundNumber, event.event, event.redThrees)
        is GameEvent.RoundScored -> null // pacote por assento, ver [roundOver]
        is GameEvent.GameFinished -> GameFinished(event.winner, event.resigned)
        is GameEvent.SeatReplaced -> null // aviso enviado pela sessão (TableNotice)
    }

    companion object {
        /** §16.10 tempo limite da confirmação do 1º sucessor. */
        const val SUCCESSOR_ACK_TIMEOUT_MILLIS: Long = 3_000

        /**
         * §16.10 o sucessor assume: cria a sessão de anfitrião no mesmo transporte (o convidado já soltou com
         * [com.gtranca.net.GuestSession.release]) e o anfitrião a partir do [state] recebido. Os demais reingressam pelo token.
         */
        suspend fun takeOver(
            transport: com.gtranca.net.LocalTransport,
            tableId: String,
            rules: com.gtranca.net.TableRules,
            myId: Int,
            state: SuccessorState,
            scope: CoroutineScope,
            botFactory: ((Seat, Random) -> BotPlayer)? = null,
            computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
            /** §16.10 [com.gtranca.net.GuestSession.takeOverRank]: 0 = passagem do cargo; 1 ou 2 = provisório pela queda. */
            provisionalRank: Int = 0,
        ): MultiplayerHost {
            val me = state.members.first { it.info.id == myId }
            val session = HostSession(
                transport, tableId, rules, me.info.name,
                resumed = com.gtranca.net.ResumedTable(
                    myId, state.members, state.nextPlayerId,
                    epoch = state.epoch + 1,
                    provisionalRank = provisionalRank,
                    previousHostId = state.members.firstOrNull { it.info.isHost }?.info?.id,
                    openSeats = state.openSeats.toSet(),
                ),
            )
            session.open(scope)
            val config = GameConfig(rules.mode, Difficulty.fromId(rules.difficulty), rules.targetScore, rules.rules, rules.stake)
            val remote = state.members.mapNotNull { it.info.seat }.toSet() - me.info.seat!!
            val host = MultiplayerHost(
                session, config, state.match.seed, remote,
                botFactory = botFactory ?: { _, random -> createBot(config.difficulty, random) },
                computeDispatcher = computeDispatcher,
                restored = state,
            )
            // O aviso de novo anfitrião vai a cada um ao reingressar (HostSession).
            return host
        }
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
