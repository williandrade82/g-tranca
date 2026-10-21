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

    /** §16.9 o jogador desistiu: o jogo acabou para ele (derrota por desistência, §13.1); um robô assume o assento. */
    data object Resigned : GuestStatus

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

    /** Mensagens públicas do jogo ([RoundStarted], [Played], [GameFinished], [SeatTakenByRobot]) em ordem (§16.6). */
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

    private val _roundOver = MutableStateFlow<RoundOver?>(null)

    /**
     * §16.11 pacote de fim da partida que o jogador ainda está vendo (telas de resultado), ou `null`. Enquanto existir, o
     * que chega da partida seguinte fica guardado; [advance] libera.
     */
    val roundOver: StateFlow<RoundOver?> = _roundOver.asStateFlow()

    /** Partida que o jogador está vendo; o que chega de uma partida posterior espera o [advance]. */
    private var shownRound = 1

    /** Mensagens da partida seguinte guardadas até o jogador avançar as telas de resultado (§16.11). */
    private val held = ArrayDeque<Payload>()

    /** Troca para um assento vago (§16.2). */
    suspend fun chooseSeat(seat: Seat) = send(ChooseSeat(seat))

    /** §16.2 confirma a posição ("Aguardando início do jogo"). */
    suspend fun confirmSeat() = send(ConfirmSeat)

    /** §16.9 desiste (a qualquer momento): o jogo acaba para este jogador e um robô assume o assento. */
    suspend fun resign() {
        mutex.withLock { if (_status.value == GuestStatus.Playing) _status.value = GuestStatus.Resigned }
        send(Resign)
    }

    /**
     * §16.11 o jogador terminou as telas de resultado da partida: passa a mostrar a seguinte, já recebida (ou a que vier).
     * Local: nada é enviado ao anfitrião, que nunca espera por isso. Ignorado sem pacote de fim de partida.
     */
    suspend fun advance() {
        mutex.withLock {
            val over = _roundOver.value ?: return
            _roundOver.value = null
            if (over.gameWinner != null) return
            shownRound = over.roundNumber + 1
            val pending = held.toList()
            held.clear()
            pending.forEach(::process)
        }
    }

    suspend fun rename(newName: String) = send(Rename(newName))

    suspend fun leave() = send(Leave)

    /** Propõe [action] para o pedido em aberto da vista atual (ou para [requestId], se informado). */
    suspend fun propose(action: Action, requestId: Long = _view.value?.requestId ?: 0) = send(ProposeAction(requestId, action))


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
                    if (_status.value !is GuestStatus.Refused && _status.value != GuestStatus.Resigned) _status.value = GuestStatus.Closed
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

    /** Partida a que a mensagem do jogo se refere, se for de uma partida específica. */
    private fun roundOf(payload: Payload): Int? = when (payload) {
        is ViewUpdate -> payload.view.roundNumber
        is RoundStarted -> payload.roundNumber
        is Played -> payload.roundNumber
        is RoundOver -> payload.roundNumber
        else -> null
    }

    private fun process(payload: Payload) {
        if (_status.value == GuestStatus.Resigned) return
        // §16.11 o jogador ainda está nas telas de resultado: o que é da partida seguinte espera o advance().
        val round = roundOf(payload)
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
                _status.value = GuestStatus.Lobby
            }
            is Rejected -> _status.value = GuestStatus.Refused(payload.message)
            is RosterUpdate -> _roster.value = payload.roster
            is GameStarted -> {
                _roster.value = payload.roster
                _status.value = GuestStatus.Playing
            }
            is ViewUpdate -> {
                // Com o pacote de fim de partida em mãos, a mesa final dele vale (uma vista atrasada não a sobrescreve).
                if (_roundOver.value?.roundNumber == payload.view.roundNumber) return
                val previous = _view.value
                val view = payload.view
                _view.value = if (view.history == null) view.copy(history = previous?.history ?: emptyList()) else view
            }
            is ActionRejected -> _rejections.tryEmit(payload)
            is RoundOver -> {
                _roundOver.value = payload
                val previous = _view.value
                // A mesa final do assento (com o histórico completo) passa a ser a vista atual.
                _view.value = payload.view.let { v -> if (v.history == null) v.copy(history = previous?.history ?: emptyList()) else v }
            }
            is RoundStarted, is Played, is GameFinished, is SeatTakenByRobot -> _game.tryEmit(payload)
            else -> Unit
        }
    }
}
