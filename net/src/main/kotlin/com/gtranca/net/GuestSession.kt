package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Situação do convidado na mesa. */
sealed interface GuestStatus {
    data object Connecting : GuestStatus

    /** Na sala, antes do início (§16.2). */
    data object Lobby : GuestStatus

    /** Jogo em andamento. */
    data object Playing : GuestStatus

    /** Entrada recusada ou mesa de outra versão; [message] em pt-BR para a tela. */
    data class Refused(val message: String) : GuestStatus

    /** Conexão encerrada. */
    data object Closed : GuestStatus
}

/**
 * Sessão do **convidado** (§16.1): entra na mesa, escolhe o assento e, no jogo, só **propõe** jogadas e recebe a vista
 * do seu assento e as jogadas públicas. Nunca valida regra nem conhece o estado oculto. Guarda o token só em memória
 * (a gravação da sessão é da etapa 4/6) e nunca o registra.
 */
class GuestSession(
    private val transport: LocalTransport,
    val name: String,
    private val codec: ProtocolCodec = ProtocolCodec(),
) {
    private val mutex = Mutex()
    private val link = SequencedLink()
    private var host: EndpointId? = null

    private val _status = MutableStateFlow<GuestStatus>(GuestStatus.Connecting)
    val status: StateFlow<GuestStatus> = _status.asStateFlow()

    private val _rules = MutableStateFlow<TableRules?>(null)

    /** Regras da mesa, recebidas uma vez ao entrar (§16.2). */
    val rules: StateFlow<TableRules?> = _rules.asStateFlow()

    private val _roster = MutableStateFlow<List<PlayerInfo>>(emptyList())
    val roster: StateFlow<List<PlayerInfo>> = _roster.asStateFlow()

    /** Id público deste jogador na mesa (0 enquanto não entrou). */
    @Volatile
    var playerId: Int = 0
        private set

    /** §16.5 token deste jogador (só em memória). */
    @Volatile
    var token: String? = null
        private set

    private val _view = MutableStateFlow<SeatView?>(null)

    /** Vista do assento deste jogador, com o histórico sempre preenchido (§16.4). */
    val view: StateFlow<SeatView?> = _view.asStateFlow()

    private val _game = MutableSharedFlow<Payload>(extraBufferCapacity = Int.MAX_VALUE)

    /** Mensagens públicas do jogo ([RoundStarted], [Played], [RoundScored], [GameFinished]) em ordem (§16.6). */
    val gameEvents: SharedFlow<Payload> = _game.asSharedFlow()

    private val _rejections = MutableSharedFlow<ActionRejected>(extraBufferCapacity = Int.MAX_VALUE)
    val rejections: SharedFlow<ActionRejected> = _rejections.asSharedFlow()

    /** Conecta-se à mesa [tableId] e pede para entrar (§16.2). */
    suspend fun join(scope: CoroutineScope, tableId: String) {
        scope.launch { transport.events.collect { handle(it) } }
        val endpoint = transport.connect(tableId)
        mutex.withLock { host = endpoint }
        send(Hello(name))
    }

    suspend fun chooseSeat(seat: Seat) = send(ChooseSeat(seat))

    suspend fun rename(newName: String) = send(Rename(newName))

    suspend fun leave() = send(Leave)

    /** Propõe [action] para o pedido em aberto da vista atual (ou para [requestId], se informado). */
    suspend fun propose(action: Action, requestId: Long = _view.value?.requestId ?: 0) = send(ProposeAction(requestId, action))

    /** §16.11 "Continuar" no fim da partida. */
    suspend fun continueRound() {
        val view = _view.value ?: return
        send(ContinueRound(view.roundNumber))
    }

    private suspend fun send(payload: Payload) {
        mutex.withLock {
            val endpoint = host ?: return
            transport.send(endpoint, codec.encode(link.nextSeq(), payload))
        }
    }

    private suspend fun handle(event: TransportEvent) {
        mutex.withLock {
            when (event) {
                is TransportEvent.Connected -> Unit
                is TransportEvent.Disconnected -> if (event.endpoint == host) {
                    host = null
                    if (_status.value !is GuestStatus.Refused) _status.value = GuestStatus.Closed
                }
                is TransportEvent.Received -> if (event.endpoint == host) receiveLocked(event.bytes)
            }
        }
    }

    private suspend fun receiveLocked(bytes: ByteArray) {
        when (val decoded = codec.decode(bytes)) {
            is Decoded.VersionMismatch -> {
                _status.value = GuestStatus.Refused(VERSION_MISMATCH_MESSAGE)
                host?.let { transport.disconnect(it) }
                host = null
            }
            Decoded.Malformed -> Unit
            is Decoded.Ok -> link.accept(decoded.envelope).forEach { process(it.p) }
        }
    }

    private fun process(payload: Payload) {
        when (payload) {
            is Welcome -> {
                token = payload.token
                playerId = payload.you
                _rules.value = payload.rules
                _roster.value = payload.roster
                _status.value = GuestStatus.Lobby
            }
            is Rejected -> _status.value = GuestStatus.Refused(payload.message)
            is RosterUpdate -> _roster.value = payload.roster
            is GameStarted -> {
                _roster.value = payload.roster
                _status.value = GuestStatus.Playing
            }
            is ViewUpdate -> {
                val previous = _view.value
                val view = payload.view
                _view.value = if (view.history == null) view.copy(history = previous?.history ?: emptyList()) else view
            }
            is ActionRejected -> _rejections.tryEmit(payload)
            is RoundStarted, is Played, is RoundScored, is GameFinished -> _game.tryEmit(payload)
            else -> Unit
        }
    }
}
