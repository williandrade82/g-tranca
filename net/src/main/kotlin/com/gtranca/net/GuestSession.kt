package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Situação do convidado na mesa. */
sealed interface GuestStatus {
    data object Connecting : GuestStatus

    /** Na sala, antes do início (§16.2). */
    data object Lobby : GuestStatus

    /** §16.7 pediu para entrar no meio do jogo; aguardando a aprovação do anfitrião. */
    data object WaitingApproval : GuestStatus

    /** Jogo em andamento. */
    data object Playing : GuestStatus

    /** §16.5 a conexão caiu com o jogo em andamento: tentando reingressar pelo token. */
    data object Reconnecting : GuestStatus

    /** §16.10 este jogador assume como anfitrião (a interface cria o anfitrião a partir de [GuestSession.takeOver]). */
    data object TakingOver : GuestStatus

    /** Entrada recusada ou mesa de outra versão; [message] em pt-BR para a tela. */
    data class Refused(val message: String) : GuestStatus

    /** §16.9 o jogador desistiu: o jogo acabou para ele (derrota por desistência, §13.1). */
    data object Resigned : GuestStatus

    /** Conexão encerrada. */
    data object Closed : GuestStatus
}

/**
 * Sessão do **convidado** (§16.1): entra na mesa, escolhe o assento e, no jogo, só **propõe** jogadas e recebe a vista
 * do seu assento e as jogadas públicas. Nunca valida regra. Se a conexão cai com o jogo em andamento, reingressa sozinho
 * pelo token (§16.5). Se for sucessor (§16.10), guarda **só em memória** o estado completo e o descarta ao deixar de ser
 * sucessor ou no fim do jogo. Nunca registra cartas nem tokens.
 */
class GuestSession(
    private val transport: LocalTransport,
    val name: String,
    private val codec: ProtocolCodec = ProtocolCodec(),
    /** §16.10 quanto o 1º sucessor espera a mesa reaparecer antes de assumir (o anfitrião pode ter só piscado). */
    private val successorGraceMillis: Long = SUCCESSOR_GRACE_MILLIS,
    /** Quanto os demais esperam a mesa (do anfitrião ou do sucessor) reaparecer. */
    private val reconnectTimeoutMillis: Long = RECONNECT_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private lateinit var scope: CoroutineScope
    private var link: ReliableLink? = null
    private var collector: Job? = null
    private var tableId: String = ""

    private val _status = MutableStateFlow<GuestStatus>(GuestStatus.Connecting)
    val status: StateFlow<GuestStatus> = _status.asStateFlow()

    private val _rules = MutableStateFlow<TableRules?>(null)

    /** Regras da mesa, recebidas uma vez ao entrar (§16.2). */
    val rules: StateFlow<TableRules?> = _rules.asStateFlow()

    private val _roster = MutableStateFlow<List<PlayerInfo>>(emptyList())

    /** Sala: nomes, assentos, presença ("VAGO" = [PlayerInfo.resigned]), anfitrião e sucessores. */
    val roster: StateFlow<List<PlayerInfo>> = _roster.asStateFlow()

    /** Id público deste jogador na mesa (0 enquanto não entrou). */
    @Volatile
    var playerId: Int = 0
        private set

    /** §16.5 token deste jogador (só em memória; a gravação da sessão é da interface). */
    @Volatile
    var token: String? = null
        private set

    /** Assento deste jogador. */
    val seat: Seat? get() = _roster.value.firstOrNull { it.id == playerId }?.seat

    private val _view = MutableStateFlow<SeatView?>(null)

    /** Vista do assento deste jogador, com o histórico sempre preenchido (§16.4). */
    val view: StateFlow<SeatView?> = _view.asStateFlow()

    private val _game = MutableSharedFlow<Payload>(extraBufferCapacity = Int.MAX_VALUE)

    /** Mensagens públicas do jogo ([RoundStarted], [Played], [GameFinished]) em ordem (§16.6). */
    val gameEvents: SharedFlow<Payload> = _game.asSharedFlow()

    private val _notices = MutableSharedFlow<TableNotice>(extraBufferCapacity = Int.MAX_VALUE)

    /** Avisos da mesa (§16.8: queda, volta, desistência, robô, vaga aberta, entrada, novo anfitrião). */
    val notices: SharedFlow<TableNotice> = _notices.asSharedFlow()

    private val _paused = MutableStateFlow<Paused?>(null)

    /** §16.8 o jogo está pausado (e por quê); `null` = seguindo. */
    val paused: StateFlow<Paused?> = _paused.asStateFlow()

    private val _payout = MutableStateFlow<Payout?>(null)

    /** §16.13 resultado em moedas deste jogador, ao fim do jogo. */
    val payout: StateFlow<Payout?> = _payout.asStateFlow()

    private val _stakeDebit = MutableStateFlow<Int?>(null)

    /** §16.7 entrou no meio do jogo: quanto debitar da carteira deste celular (0 = herdou a aposta retida). */
    val stakeDebit: StateFlow<Int?> = _stakeDebit.asStateFlow()

    private val _rejections = MutableSharedFlow<ActionRejected>(extraBufferCapacity = Int.MAX_VALUE)
    val rejections: SharedFlow<ActionRejected> = _rejections.asSharedFlow()

    /** §16.10 estado completo (só sucessores, só em memória). Nunca exibir nem registrar. */
    @Volatile
    private var successorState: SuccessorState? = null

    /** Este aparelho guarda o estado completo agora (é sucessor). */
    val holdsSuccessorState: Boolean get() = successorState != null

    private val _takeOver = MutableStateFlow<SuccessorState?>(null)

    /**
     * §16.10 o anfitrião caiu ou passou o cargo e **este** jogador assume: o estado para criar o anfitrião novo (a
     * interface chama [release] e abre a mesa no mesmo transporte). Só em memória.
     */
    val takeOver: StateFlow<SuccessorState?> = _takeOver.asStateFlow()

    /** Conecta-se à mesa [tableId] e pede para entrar (§16.2), ou reingressa com [token] (§16.5, "Continuar"). */
    suspend fun join(scope: CoroutineScope, tableId: String, token: String? = null, seat: Seat? = null) {
        this.scope = scope
        this.tableId = tableId
        this.token = token
        collector = scope.launch { transport.events.collect { handle(it) } }
        connectAndHello(seat)
    }

    private suspend fun connectAndHello(seat: Seat?) {
        val endpoint = transport.connect(tableId)
        mutex.withLock {
            link = ReliableLink(endpoint, transport, codec, scope, mutex)
            link!!.sendLocked(Hello(name, token, seat))
        }
    }

    private val _roundOver = MutableStateFlow<RoundOver?>(null)

    /** §16.11 pacote de fim da partida que o jogador ainda está vendo; [advance] libera a seguinte. */
    val roundOver: StateFlow<RoundOver?> = _roundOver.asStateFlow()

    /** Partida que o jogador está vendo; o que chega de uma partida posterior espera o [advance]. */
    private var shownRound = 1

    /** Depois de um reingresso a partida mostrada vem da primeira mensagem do jogo. */
    private var resync = false

    /** §16.10 o anfitrião anunciou o novo anfitrião (passagem do cargo). */
    @Volatile
    private var handedTo: Int? = null

    private val held = ArrayDeque<Payload>()

    /** Troca para um assento vago (§16.2), mesmo depois de confirmar; só na sala de espera. */
    suspend fun chooseSeat(seat: Seat) = send(ChooseSeat(seat))

    /** §16.2 confirma a posição ("Aguardando início do jogo"). */
    suspend fun confirmSeat() = send(ConfirmSeat)

    /** §16.9 desiste (a qualquer momento): o jogo acaba para este jogador. */
    suspend fun resign() {
        mutex.withLock {
            if (_status.value == GuestStatus.Playing || _status.value == GuestStatus.Reconnecting) _status.value = GuestStatus.Resigned
            successorState = null
            link?.sendLocked(Resign)
        }
    }

    /** §16.11 o jogador terminou as telas de resultado da partida. Local: nada é enviado. */
    suspend fun advance() {
        mutex.withLock {
            val over = _roundOver.value ?: return
            _roundOver.value = null
            if (over.gameWinner != null) return
            shownRound = over.roundNumber + 1
            val pending = held.toList()
            held.clear()
            pending.forEach { process(it) }
        }
    }

    suspend fun rename(newName: String) = send(Rename(newName))

    suspend fun leave() = send(Leave)

    /** Propõe [action] para o pedido em aberto da vista atual (ou para [requestId], se informado). */
    suspend fun propose(action: Action, requestId: Long = _view.value?.requestId ?: 0) = send(ProposeAction(requestId, action))

    /** Solta o transporte (para o mesmo aparelho virar anfitrião, §16.10, ou sair). */
    suspend fun release() {
        mutex.withLock {
            link?.close()
            link = null
        }
        collector?.cancel()
    }

    private suspend fun send(payload: Payload) {
        mutex.withLock { link?.sendLocked(payload) }
    }

    private suspend fun handle(event: TransportEvent) {
        mutex.withLock {
            val current = link
            when (event) {
                is TransportEvent.Connected -> Unit
                is TransportEvent.Disconnected -> if (event.endpoint == current?.endpoint) lostLocked()
                is TransportEvent.Received -> if (event.endpoint == current?.endpoint) receiveLocked(current, event.bytes)
            }
        }
    }

    /** A conexão caiu: no jogo, tenta reingressar (§16.5) ou, se for o 1º sucessor e a mesa sumiu, assume (§16.10). */
    private fun lostLocked() {
        link?.close()
        link = null
        when (_status.value) {
            GuestStatus.Playing, GuestStatus.Reconnecting -> {
                _status.value = GuestStatus.Reconnecting
                scope.launch { reconnect() }
            }
            is GuestStatus.Refused, GuestStatus.Resigned, GuestStatus.TakingOver -> Unit
            else -> _status.value = GuestStatus.Closed
        }
    }

    private suspend fun reconnect() {
        // §16.10 se o cargo foi passado a outro, quem assume é ele: aqui só se reingressa.
        val first = handedTo == null && _roster.value.firstOrNull { it.id == playerId }?.successorRank == 1 && successorState != null
        var found = false
        withTimeoutOrNull(if (first) successorGraceMillis else reconnectTimeoutMillis) {
            while (!found) {
                transport.discover().first { list -> list.any { it.tableId == tableId } }
                found = runCatching { connectAndHello(seat) }.isSuccess
                if (!found) delay(RETRY_MILLIS)
            }
        }
        if (found) return
        mutex.withLock {
            if (_status.value != GuestStatus.Reconnecting) return
            val state = successorState
            if (first && state != null) {
                _status.value = GuestStatus.TakingOver
                _takeOver.value = state
            } else {
                _status.value = GuestStatus.Closed
                successorState = null
            }
        }
    }

    private suspend fun receiveLocked(current: ReliableLink, bytes: ByteArray) {
        when (val decoded = codec.decode(bytes)) {
            is Decoded.VersionMismatch -> {
                _status.value = GuestStatus.Refused(VERSION_MISMATCH_MESSAGE)
                current.close()
                link = null
                transport.disconnect(current.endpoint)
            }
            Decoded.Malformed -> Unit
            is Decoded.Ok -> current.receiveLocked(decoded.envelope).forEach { payload ->
                when (payload) {
                    is SuccessorState -> {
                        successorState = payload
                        current.controlLocked(SuccessorAck(payload.stateSeq))
                    }
                    SuccessorDrop -> successorState = null
                    BecomeHost -> successorState?.let {
                        _status.value = GuestStatus.TakingOver
                        _takeOver.value = it
                    }
                    else -> process(payload)
                }
            }
        }
    }

    /** Partida a que a mensagem do jogo se refere, se for de uma partida específica. */
    private fun roundOf(payload: Payload): Int? = when (payload) {
        is ViewUpdate -> payload.view.roundNumber
        is RoundStarted -> payload.roundNumber
        is Played -> payload.roundNumber
        is RoundOver -> payload.roundNumber
        else -> null
    }

    private fun process(payload: Payload) {
        if (_status.value == GuestStatus.Resigned || _status.value == GuestStatus.TakingOver) return
        val round = roundOf(payload)
        if (resync && round != null) {
            shownRound = round
            resync = false
        }
        // §16.11 o jogador ainda está nas telas de resultado: o que é da partida seguinte espera o advance().
        if (round != null && round > shownRound) {
            held.addLast(payload)
            return
        }
        when (payload) {
            is Welcome -> {
                token = payload.token
                playerId = payload.you
                _rules.value = payload.rules
                _roster.value = payload.roster
                if (payload.started) {
                    // §16.5, §16.7 reingresso ou entrada no meio: a vista completa vem em seguida.
                    resync = true
                    held.clear()
                    _roundOver.value = null
                    _paused.value = null
                    _status.value = GuestStatus.Playing
                } else {
                    _status.value = GuestStatus.Lobby
                }
            }
            JoinWaiting -> _status.value = GuestStatus.WaitingApproval
            is Rejected -> _status.value = GuestStatus.Refused(payload.message)
            is RosterUpdate -> _roster.value = payload.roster
            is GameStarted -> {
                _roster.value = payload.roster
                _status.value = GuestStatus.Playing
            }
            is ViewUpdate -> {
                if (_roundOver.value?.roundNumber == payload.view.roundNumber) return
                val previous = _view.value
                val view = payload.view
                _view.value = if (view.history == null) view.copy(history = previous?.history ?: emptyList()) else view
            }
            is ActionRejected -> _rejections.tryEmit(payload)
            is RoundOver -> {
                _roundOver.value = payload
                val previous = _view.value
                _view.value = payload.view.let { v -> if (v.history == null) v.copy(history = previous?.history ?: emptyList()) else v }
            }
            is Paused -> _paused.value = payload
            Resumed -> _paused.value = null
            is TableNotice -> {
                if (payload.kind == NoticeKind.HOST_CHANGED) handedTo = payload.playerId
                _notices.tryEmit(payload)
            }
            is Payout -> _payout.value = payload
            is StakeDebit -> _stakeDebit.value = payload.amount
            is GameFinished -> {
                successorState = null
                _paused.value = null
                _game.tryEmit(payload)
            }
            is RoundStarted, is Played -> _game.tryEmit(payload)
            else -> Unit
        }
    }

    companion object {
        const val SUCCESSOR_GRACE_MILLIS: Long = 5_000
        const val RECONNECT_TIMEOUT_MILLIS: Long = 60_000
        private const val RETRY_MILLIS: Long = 500
    }
}
