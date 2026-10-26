package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Situação do convidado na mesa. */
sealed interface GuestStatus {
    data object Connecting : GuestStatus

    /** §16.2 recrutado já na mesa do jogo, esperando o início ("Prepare-se!"). */
    data object Lobby : GuestStatus

    /** §16.7 pediu para entrar no meio do jogo; aguardando a aprovação do anfitrião. */
    data object WaitingApproval : GuestStatus

    /** Jogo em andamento. */
    data object Playing : GuestStatus

    /** §16.5 a conexão caiu com o jogo em andamento: tentando reingressar pelo token. */
    data object Reconnecting : GuestStatus

    /** §16.10 este jogador assume como anfitrião (a interface cria o anfitrião a partir de [GuestSession.takeOver]). */
    data object TakingOver : GuestStatus

    /** Entrada recusada ou mesa de outra versão; [message] em pt-BR para a tela e, no convite/pedido, o [reason] (§16.2). */
    data class Refused(val message: String, val reason: RefusalReason? = null) : GuestStatus

    /** O anfitrião encerrou a mesa ([reason]): desfez antes do início ou desligou o Wi-Fi do jogo (§16.2, §16.7). */
    data class TableEnded(val reason: CloseReason) : GuestStatus

    /** §16.9 o jogador desistiu: o jogo acabou para ele (derrota por desistência, §13.1). */
    data object Resigned : GuestStatus

    /** Conexão encerrada. */
    data object Closed : GuestStatus

    /** §16.2 não conseguiu entrar na mesa; [kind] diz por quê (cada causa tem o seu texto na tela). */
    data class Failed(val kind: JoinFailureKind) : GuestStatus
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
    /**
     * §16.10, §16.15 quanto tempo **sem a mesa na busca** o 1º sucessor espera antes de assumir (o 2º espera o dobro):
     * perder só a própria conexão não basta.
     */
    private val successorGraceMillis: Long = SUCCESSOR_GRACE_MILLIS,
    /** §16.5, §16.15 mesa ausente da busca por este tempo seguido = encerrada. */
    private val absentLimitMillis: Long = ABSENT_LIMIT_MILLIS,
    /** §16.2 foto do perfil deste jogador (Base64, JPEG pequeno), enviada no [Hello]; `null` = sem foto. */
    private val photo: String? = null,
    /** §16.2 quanto o recrutado procura a mesa (busca e conexão repetidas) antes de desistir. */
    private val joinWaitMillis: Long = JOIN_WAIT_MILLIS,
    /** §16.2 quanto o recrutado espera a resposta do anfitrião depois de conectar. */
    private val replyWaitMillis: Long = REPLY_WAIT_MILLIS,
    /** Diagnóstico (só no build debug): passos da entrada, sem nomes, cartas nem tokens. */
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private lateinit var scope: CoroutineScope
    private var link: ReliableLink? = null
    private var collector: Job? = null
    private var tableId: String = ""

    /** Assento pedido ao entrar no meio do jogo (§16.7): o pedido é refeito com ele se o anfitrião cair. */
    private var requestedSeat: Seat? = null

    /** §16.5 última partida cujo pacote de fim (§16.11) foi recebido (vai no reingresso). */
    @Volatile
    private var roundOverSeen = 0

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

    private val _viewUpdates = MutableSharedFlow<SeatView>(replay = 1, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * §16.6 cada vista recebida, em ordem e **sem juntar** (o [view] guarda só a última): uma por jogada do anfitrião, para
     * a tela animar uma jogada por vez. A última fica para quem começar a ouvir depois.
     */
    val viewUpdates: SharedFlow<SeatView> = _viewUpdates.asSharedFlow()

    private val _photos = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** §16.2 fotos dos outros jogadores (id → Base64), recebidas uma vez cada; só em memória. */
    val photos: StateFlow<Map<Int, String>> = _photos.asStateFlow()

    private val meter = LinkQualityMeter()
    private val _linkQuality = MutableStateFlow(LinkQuality.GOOD)

    /** §16.15 qualidade da conexão com o anfitrião, de 1 (ruim) a 3 (boa); 1 enquanto reconecta. */
    val linkQuality: StateFlow<Int> = _linkQuality.asStateFlow()

    private var pingJob: Job? = null

    /**
     * §16.15 depois de receber uma mensagem do jogo: se o convidado não enviar nada em 5 s, manda uma mensagem curta de
     * teste ([Ping]) para medir a conexão. Só se arma com o jogo trazendo mensagens (nada de temporizador parado).
     */
    private fun armPingLocked() {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            delay(LinkQuality.PING_IDLE_MILLIS)
            mutex.withLock {
                pingJob = null
                val current = link ?: return@withLock
                if (_status.value == GuestStatus.Playing && current.now() - current.lastSentAt >= LinkQuality.PING_IDLE_MILLIS) {
                    current.sendLocked(Ping)
                }
            }
        }
    }

    private fun refreshQualityLocked() {
        _linkQuality.value = if (_status.value == GuestStatus.Reconnecting) LinkQuality.POOR else meter.level()
    }

    private val _robots = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** §16.2, §16.8 personagem de cada assento de robô (índice → id), como o anfitrião escolheu; vazio antes do início. */
    val robots: StateFlow<Map<Int, String>> = _robots.asStateFlow()

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

    /**
     * §16.10 como este jogador assume: 0 = confirmado (o anfitrião passou o cargo); 1 ou 2 = provisório, pela queda do
     * anfitrião, na posição de sucessor (ver `HostSession`: só retoma o jogo quando confirmado).
     */
    @Volatile
    var takeOverRank: Int = 0
        private set

    /**
     * Conecta-se à mesa [tableId] e pede para entrar (§16.2), ou reingressa com [token] (§16.5, "Continuar"), mesmo que
     * a mesa não esteja na lista (ela fica anunciada o jogo todo, §16.7). Falha se a mesa não está na busca.
     */
    suspend fun join(scope: CoroutineScope, tableId: String, token: String? = null, seat: Seat? = null, inviteKey: String? = null) {
        this.scope = scope
        this.tableId = tableId
        this.token = token
        this.requestedSeat = seat
        this.inviteKey = inviteKey
        collector = scope.launch { transport.events.collect { handle(it) } }
        locateAndConnect(seat)
    }

    /**
     * §16.2, §16.15 acha a mesa e conecta, **repetindo** até o prazo ([joinWaitMillis] do recrutado; [FIND_TABLE_MILLIS] nos
     * outros): a busca fica "viva" ([LocalTransport.holdSearch]) para ver o anúncio novo, e a conexão que falha é refeita
     * com espera crescente (1 s, 2 s, 3 s... até 5 s). A falha diz a causa ([JoinFailure]): a mesa nunca apareceu
     * ([JoinFailureKind.NOT_FOUND]) ou apareceu e a conexão não deu certo ([JoinFailureKind.CONNECT_FAILED]).
     */
    private suspend fun locateAndConnect(seat: Seat?) {
        val window = if (inviteKey != null) joinWaitMillis else FIND_TABLE_MILLIS
        var seenTable = false
        var attempt = 0
        var lastError: Throwable? = null
        transport.holdSearch(true)
        try {
            var joined = false
            withTimeoutOrNull(window) {
                var backoff = 1_000L
                while (!joined) {
                    attempt++
                    log("join attempt $attempt")
                    val advert = transport.discover().mapNotNull { it.preferred(tableId) }.first()
                    seenTable = true
                    try {
                        connectAndHello(advert, seat)
                        joined = true
                        log("join ok attempt=$attempt")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        log("connect failed ${e.message?.take(40)}")
                        delay(backoff)
                        backoff = minOf(backoff + 1_000, 5_000)
                    }
                }
            }
            if (!joined) {
                val kind = if (seenTable) JoinFailureKind.CONNECT_FAILED else JoinFailureKind.NOT_FOUND
                log("join failed $kind")
                throw JoinFailure(kind, if (seenTable) "Não foi possível conectar à mesa" else "Mesa não encontrada", lastError)
            }
        } finally {
            transport.holdSearch(false)
        }
        // §16.2 recrutado: o anfitrião tem de responder (Welcome ou recusa); sem resposta a entrada falha por TIMEOUT.
        if (inviteKey != null) {
            replyWatch = scope.launch {
                delay(replyWaitMillis)
                mutex.withLock {
                    if (_status.value == GuestStatus.Connecting) {
                        log("join failed ${JoinFailureKind.TIMEOUT}")
                        link?.close()
                        link = null
                        _status.value = GuestStatus.Failed(JoinFailureKind.TIMEOUT)
                    }
                }
            }
        }
    }

    private var replyWatch: Job? = null

    /** §16.2 chave de presença com que este aparelho foi recrutado (vai no [Hello]). */
    private var inviteKey: String? = null

    /**
     * A mesa [tableId] na busca (o anúncio preferido, §16.10), esperando até [FIND_TABLE_MILLIS]: no rádio real a busca
     * acha as mesas aos poucos, e a primeira lista pode trazer outra mesa e não esta.
     */
    private suspend fun findTable(): TableAdvert? =
        withTimeoutOrNull(FIND_TABLE_MILLIS) { transport.discover().mapNotNull { it.preferred(tableId) }.first() }

    private suspend fun connectAndHello(advert: TableAdvert, seat: Seat?) {
        val endpoint = transport.connect(advert)
        mutex.withLock {
            link = ReliableLink(endpoint, transport, codec, scope, mutex, meter = meter)
            link!!.sendLocked(Hello(name, token, seat, roundOverSeen, photo, inviteKey.takeIf { token == null }))
        }
    }

    /**
     * §16.9 "Voltar à mesa": quem desistiu reingressa pelo token enquanto o assento não foi substituído nem ocupado
     * (recupera a aposta). Devolve se a mesa foi achada; a resposta do anfitrião chega em [status].
     */
    suspend fun returnToTable(): Boolean {
        mutex.withLock {
            if (_status.value != GuestStatus.Resigned || token == null) return false
            _status.value = GuestStatus.Reconnecting
        }
        val advert = findTable()
        if (advert != null && runCatching { connectAndHello(advert, seat) }.isSuccess) return true
        mutex.withLock { if (_status.value == GuestStatus.Reconnecting) _status.value = GuestStatus.Resigned }
        return false
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

    /** Sai da sala; durante o jogo vale como desistência (§16.9). */
    suspend fun leave() {
        mutex.withLock {
            successorState = null
            if (_status.value == GuestStatus.Playing || _status.value == GuestStatus.Reconnecting) _status.value = GuestStatus.Resigned
            link?.sendLocked(Leave)
        }
    }

    /** Propõe [action] para o pedido em aberto da vista atual (ou para [requestId], se informado). */
    suspend fun propose(action: Action, requestId: Long = _view.value?.requestId ?: 0) = send(ProposeAction(requestId, action))

    /** Solta o transporte (para o mesmo aparelho virar anfitrião, §16.10, ou sair). */
    suspend fun release() {
        mutex.withLock {
            // §16.4 quem assumiu já passou o estado ao anfitrião novo; aqui ele não fica mais guardado.
            successorState = null
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

    /**
     * A conexão caiu: no jogo (ou à espera da aprovação, §16.7), tenta voltar (§16.5); se for sucessor e a mesa sumiu
     * da busca, assume (§16.10).
     */
    private fun lostLocked() {
        link?.close()
        link = null
        // §16.11 com o resultado e o prêmio já recebidos, o jogo acabou para este aparelho: não há o que reingressar.
        if (_payout.value != null) {
            if (_status.value == GuestStatus.Playing || _status.value == GuestStatus.Reconnecting) _status.value = GuestStatus.Closed
            successorState = null
            return
        }
        when (_status.value) {
            GuestStatus.Playing, GuestStatus.Reconnecting -> {
                _status.value = GuestStatus.Reconnecting
                scope.launch { reconnect() }
            }
            GuestStatus.WaitingApproval -> scope.launch { reconnect() }
            is GuestStatus.Refused, is GuestStatus.TableEnded, GuestStatus.Resigned, GuestStatus.TakingOver -> Unit
            else -> _status.value = GuestStatus.Closed
        }
    }

    private fun reconnecting(): Boolean =
        link == null && (_status.value == GuestStatus.Reconnecting || _status.value == GuestStatus.WaitingApproval)

    /**
     * §16.5 tenta voltar **sem limite enquanto a mesa existir** (quem decide o assento é o anfitrião, §16.8): intervalos de
     * 1 s dobrando até 15 s e, depois, a cada 15 s (poupa bateria). Mesa ausente da busca por 60 s seguidos =
     * encerrada. §16.10 o sucessor só assume com evidência de queda: a mesa sumiu da busca por 3 s (1º) ou 6 s (2º); se o
     * cargo acabou de ser passado a outro (que ainda não assumiu), só reingressa. Quem esperava aprovação refaz o pedido (sem token) no anfitrião que achar.
     */
    private suspend fun reconnect() {
        // §16.10 se o cargo foi passado a outro (aviso de novo anfitrião que a sala ainda não mostra), quem assume é ele.
        val handed = handedTo
        val handedPending = handed != null && handed != _roster.value.firstOrNull { it.isHost }?.id
        val rank = if (!handedPending && successorState != null && _status.value == GuestStatus.Reconnecting) {
            _roster.value.firstOrNull { it.id == playerId }?.successorRank ?: 0
        } else {
            0
        }
        var backoff = RETRY_MIN_MILLIS
        while (true) {
            if (!reconnecting()) return
            val window = if (rank > 0) rank * successorGraceMillis else absentLimitMillis
            val advert = withTimeoutOrNull(window) { transport.discover().mapNotNull { it.preferred(tableId) }.first() }
            if (advert == null) {
                mutex.withLock {
                    if (!reconnecting()) return
                    val state = successorState
                    if (rank > 0 && state != null) {
                        takeOverRank = rank
                        _status.value = GuestStatus.TakingOver
                        _takeOver.value = state
                    } else {
                        _status.value = GuestStatus.Closed
                        successorState = null
                    }
                }
                return
            }
            if (runCatching { connectAndHello(advert, if (_status.value == GuestStatus.WaitingApproval) requestedSeat else seat) }.isSuccess) return
            delay(backoff)
            backoff = minOf(backoff * 2, RETRY_MAX_MILLIS)
        }
    }

    private suspend fun receiveLocked(current: ReliableLink, bytes: ByteArray) {
        when (val decoded = codec.decode(bytes)) {
            is Decoded.VersionMismatch -> {
                _status.value = GuestStatus.Refused(versionMismatchMessage(decoded.remoteVersion, codec.version))
                current.close()
                link = null
                transport.disconnect(current.endpoint)
            }
            Decoded.Malformed -> Unit
            is Decoded.Ok -> current.receiveLocked(decoded.envelope).also { payloads ->
                refreshQualityLocked()
                if (payloads.any { it !is AckOnly }) armPingLocked()
            }.forEach { payload ->
                when (payload) {
                    is SuccessorState -> {
                        successorState = payload
                        current.controlLocked(SuccessorAck(payload.stateSeq))
                    }
                    SuccessorDrop -> successorState = null
                    BecomeHost -> successorState?.let {
                        takeOverRank = 0
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
                if (payload.started && payload.robots.isNotEmpty()) _robots.value = payload.robots
                if (payload.started) {
                    // §16.5, §16.7 reingresso ou entrada no meio: a vista completa vem em seguida (e antes dela o fim de
                    // partida que faltou). Quem está nas telas de resultado continua nelas: o que é da partida seguinte
                    // volta a ficar guardado até o advance().
                    resync = _roundOver.value == null
                    held.clear()
                    _paused.value = null
                    _status.value = GuestStatus.Playing
                } else {
                    _status.value = GuestStatus.Lobby
                    // §16.2, §16.15 Pronto: o recrutado confirma que está na mesa.
                    if (inviteKey != null) {
                        log("ready sent")
                        scope.launch { mutex.withLock { link?.sendLocked(Ready) } }
                    }
                }
            }
            JoinWaiting -> _status.value = GuestStatus.WaitingApproval
            is Rejected -> {
                // §16.5 recusado ou substituído por outra conexão (sessão duplicada): não volta nem assume, e o estado
                // completo (se era sucessor) é descartado.
                _status.value = GuestStatus.Refused(payload.message, payload.reason)
                successorState = null
            }
            is TableClosed -> {
                // §16.2, §16.7 o anfitrião encerrou: nada a reingressar nem a assumir (§16.10).
                successorState = null
                _status.value = GuestStatus.TableEnded(payload.reason)
            }
            is RosterUpdate -> _roster.value = payload.roster
            is GameStarted -> {
                _roster.value = payload.roster
                if (payload.robots.isNotEmpty()) _robots.value = payload.robots
                _status.value = GuestStatus.Playing
            }
            is ViewUpdate -> {
                if (_roundOver.value?.roundNumber == payload.view.roundNumber) return
                val previous = _view.value
                val view = payload.view
                _view.value = if (view.history == null) view.copy(history = previous?.history ?: emptyList()) else view
                _viewUpdates.tryEmit(_view.value!!)
            }
            is ActionRejected -> _rejections.tryEmit(payload)
            is RoundOver -> {
                if (payload.roundNumber > roundOverSeen) roundOverSeen = payload.roundNumber
                _roundOver.value = payload
                val previous = _view.value
                _view.value = payload.view.let { v -> if (v.history == null) v.copy(history = previous?.history ?: emptyList()) else v }
                _viewUpdates.tryEmit(_view.value!!)
            }
            is Paused -> _paused.value = payload
            Resumed -> _paused.value = null
            is TableNotice -> {
                if (payload.kind == NoticeKind.HOST_CHANGED) {
                    handedTo = payload.playerId
                    // §16.10 quem não é o novo anfitrião descarta o estado completo (o novo envia o dele aos sucessores).
                    if (payload.playerId != playerId) successorState = null
                }
                _notices.tryEmit(payload)
            }
            is Payout -> _payout.value = payload
            is PlayerPhoto -> if (payload.photo.length <= MAX_PHOTO_BASE64) _photos.value = _photos.value + (payload.playerId to payload.photo)
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
        /** §16.15 mesa sumida da busca para o 1º sucessor assumir (o 2º: o dobro). */
        const val SUCCESSOR_GRACE_MILLIS: Long = 3_000

        /** §16.15 mesa ausente da busca até ser dada como encerrada. */
        const val ABSENT_LIMIT_MILLIS: Long = 60_000

        /** §16.15 intervalos da reconexão: 1 s dobrando até 15 s; depois, a cada 15 s. */
        const val RETRY_MIN_MILLIS: Long = 1_000
        const val RETRY_MAX_MILLIS: Long = 15_000

        /** Espera técnica pela mesa na busca ao entrar ou voltar (não é regra de §16.15: só o tempo do rádio achar). */
        const val FIND_TABLE_MILLIS: Long = 10_000

        /** §16.2, §16.15 quanto o recrutado procura a mesa do jogo depois do início. */
        const val JOIN_WAIT_MILLIS: Long = 30_000

        /** §16.2, §16.15 quanto o recrutado espera a resposta do anfitrião depois de conectar. */
        const val REPLY_WAIT_MILLIS: Long = 20_000
    }
}
