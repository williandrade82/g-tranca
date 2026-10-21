package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** O jogo do anfitrião visto pela sessão: valida e aplica as jogadas dos convidados (§16.1). Não bloqueia. */
interface HostGamePort {
    /** Jogada do convidado no [seat] para o pedido [requestId]; `null` se aceita, ou a recusa (sem alterar o jogo). */
    fun propose(seat: Seat, requestId: Long, action: Action): ActionRejected?

    /**
     * §16.9 o convidado do [seat] desistiu (a qualquer momento depois do início, na vez de qualquer um). Devolve se a
     * desistência foi aplicada.
     */
    fun resign(seat: Seat): Boolean
}

/**
 * Sessão do **anfitrião** (§16.1, §16.2): publica a mesa, recebe os convidados (versão, nome, token, assento), mantém e
 * redistribui a sala e, no jogo, encaminha as jogadas ao [HostGamePort] e envia a cada convidado **só** o que é dele
 * ([sendView]) ou público ([broadcast]). Identidade é o token, nunca a ponta do transporte; o token não vai na sala.
 * Nunca registra cartas nem tokens.
 *
 * O anfitrião ocupa sempre a posição 1 (assento 0, §16.2). Reconexão, queda e sucessão ficam para a etapa 4.
 */
class HostSession(
    private val transport: LocalTransport,
    val tableId: String,
    val rules: TableRules,
    hostName: String,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val newToken: () -> String = ::randomToken,
) {
    private class Guest(val endpoint: EndpointId, val link: SequencedLink = SequencedLink()) {
        var player: PlayerInfo? = null
        var token: String? = null
    }

    private val mutex = Mutex()
    private val guests = mutableMapOf<EndpointId, Guest>()
    private var nextId = 1
    private var started = false

    @Volatile
    var game: HostGamePort? = null

    private val _roster = MutableStateFlow(listOf(PlayerInfo(id = 0, name = hostName, joinOrder = 0, seat = HOST_SEAT, isHost = true, confirmed = true)))

    private val _droppedAtStart = MutableStateFlow<List<PlayerInfo>>(emptyList())

    /**
     * §16.2 aviso ao anfitrião: quem ainda não tinha confirmado a posição quando o jogo começou e foi desconectado (o
     * assento ficou com robô). Fica disponível para a interface mostrar depois.
     */
    val droppedAtStart: StateFlow<List<PlayerInfo>> = _droppedAtStart.asStateFlow()

    /** Sala atual (id, nome, ordem de entrada e assento de cada um; sem tokens). */
    val roster: StateFlow<List<PlayerInfo>> = _roster.asStateFlow()

    /** Publica a mesa e passa a atender os convidados em [scope]. */
    suspend fun open(scope: CoroutineScope) {
        scope.launch { transport.events.collect { handle(it) } }
        transport.advertise(TableAdvert(tableId, rules.tableName, _roster.value.first().name))
    }

    /** Assentos de convidados humanos (por assento, o id público). */
    fun guestSeats(): Map<Seat, Int> = _roster.value.filter { !it.isHost && it.seat != null }.associate { it.seat!! to it.id }

    /** §16.2 o anfitrião inicia: a sala é fechada e todos são avisados; assentos vagos ficam com robôs. */
    suspend fun startGame(): Map<Seat, Int> {
        mutex.withLock {
            check(!started) { "O jogo já começou" }
            started = true
            // §16.2 quem não confirmou a posição é desconectado, e o anfitrião é avisado.
            val unconfirmed = guests.values.filter { it.player?.confirmed == false }.toList()
            _droppedAtStart.value = unconfirmed.mapNotNull { it.player }
            unconfirmed.forEach { removeLocked(it) }
            transport.stopAdvertising()
            broadcastLocked(GameStarted(_roster.value))
        }
        return guestSeats()
    }

    /** Envia a vista do [seat] **só** ao convidado sentado nele (§16.4). */
    suspend fun sendView(seat: Seat, view: SeatView) {
        require(view.seat == seat) { "Vista de outro assento" }
        mutex.withLock {
            val guest = guests.values.firstOrNull { it.player?.seat == seat } ?: return
            sendLocked(guest, ViewUpdate(view))
        }
    }

    /** Envia [payload] **só** ao convidado sentado no [seat] (ex.: o pacote de fim de partida, §16.11). */
    suspend fun sendTo(seat: Seat, payload: Payload) {
        mutex.withLock {
            val guest = guests.values.firstOrNull { it.player?.seat == seat } ?: return
            sendLocked(guest, payload)
        }
    }

    /** Envia uma mensagem pública a todos os convidados da sala. */
    suspend fun broadcast(payload: Payload) {
        mutex.withLock { broadcastLocked(payload) }
    }

    private suspend fun handle(event: TransportEvent) {
        mutex.withLock {
            when (event) {
                is TransportEvent.Connected -> guests[event.endpoint] = Guest(event.endpoint)
                is TransportEvent.Disconnected -> guests[event.endpoint]?.let { removeLocked(it, notify = false) }
                is TransportEvent.Received -> receiveLocked(event)
            }
        }
    }

    private suspend fun receiveLocked(event: TransportEvent.Received) {
        val guest = guests[event.endpoint] ?: return
        when (val decoded = codec.decode(event.bytes)) {
            is Decoded.VersionMismatch -> refuseLocked(guest, VERSION_MISMATCH_MESSAGE)
            Decoded.Malformed -> Unit
            is Decoded.Ok -> guest.link.accept(decoded.envelope).forEach { processLocked(guest, it.p) }
        }
    }

    private suspend fun processLocked(guest: Guest, payload: Payload) {
        val player = guest.player
        if (player == null) {
            if (payload is Hello) welcomeLocked(guest, payload) else refuseLocked(guest, "Entre na mesa antes de jogar.")
            return
        }
        when (payload) {
            // §16.2 só para um assento vago (nunca troca com outro jogador); o próprio assento é aceito sem mudança.
            is ChooseSeat -> if (!started) {
                if (payload.seat == player.seat || isFreeLocked(payload.seat)) {
                    updatePlayerLocked(guest, player.copy(seat = payload.seat))
                } else {
                    broadcastLocked(RosterUpdate(_roster.value))
                }
            }
            ConfirmSeat -> if (!started && player.seat != null) updatePlayerLocked(guest, player.copy(confirmed = true))
            is Rename -> updatePlayerLocked(guest, player.copy(name = nameOrDefault(payload.name, player.id)))
            Leave -> if (!started) removeLocked(guest)
            // §16.9 aceita a qualquer momento depois do início: o robô assume, todos são avisados e quem desistiu sai.
            Resign -> {
                val seat = player.seat
                if (started && seat != null && game?.resign(seat) == true) {
                    removeLocked(guest)
                    // No 1×1 entre dois humanos não entra robô: o fim de jogo (§13.1) vai a todos pelo jogo.
                    if (rules.mode.seatCount > 2) broadcastLocked(SeatTakenByRobot(seat))
                }
            }
            is ProposeAction -> {
                val seat = player.seat
                val port = game
                val rejection = if (!started || seat == null || port == null) {
                    ActionRejected(payload.requestId, RejectReason.NOT_PLAYING)
                } else {
                    port.propose(seat, payload.requestId, payload.action)
                }
                if (rejection != null) sendLocked(guest, rejection)
            }

            else -> Unit
        }
    }

    private suspend fun welcomeLocked(guest: Guest, hello: Hello) {
        if (started) return refuseLocked(guest, "O jogo desta mesa já começou.")
        val seats = rules.mode.seatCount
        if (_roster.value.size >= seats) return refuseLocked(guest, "Mesa cheia.")
        val id = nextId++
        // §16.2 ocupa automaticamente o primeiro assento livre.
        val seat = (0 until seats).map(::Seat).first(::isFreeLocked)
        val info = PlayerInfo(id = id, name = nameOrDefault(hello.name, id), joinOrder = id, seat = seat)
        val token = newToken()
        guest.player = info
        guest.token = token
        _roster.value = _roster.value + info
        sendLocked(guest, Welcome(token, id, rules, _roster.value))
        broadcastLocked(RosterUpdate(_roster.value), except = guest)
    }

    private fun isFreeLocked(seat: Seat): Boolean =
        seat.index in 0 until rules.mode.seatCount && _roster.value.none { it.seat == seat }

    private suspend fun updatePlayerLocked(guest: Guest, info: PlayerInfo) {
        guest.player = info
        _roster.value = _roster.value.map { if (it.id == info.id) info else it }
        broadcastLocked(RosterUpdate(_roster.value))
    }

    private suspend fun removeLocked(guest: Guest, notify: Boolean = true) {
        guests.remove(guest.endpoint)
        guest.player?.let { info -> _roster.value = _roster.value.filter { it.id != info.id } }
        if (notify) transport.disconnect(guest.endpoint)
        if (guest.player != null) broadcastLocked(RosterUpdate(_roster.value))
    }

    private suspend fun refuseLocked(guest: Guest, message: String) {
        sendLocked(guest, Rejected(message))
        guests.remove(guest.endpoint)
        transport.disconnect(guest.endpoint)
    }

    private suspend fun broadcastLocked(payload: Payload, except: Guest? = null) {
        guests.values.filter { it.player != null && it !== except }.toList().forEach { sendLocked(it, payload) }
    }

    private suspend fun sendLocked(guest: Guest, payload: Payload) {
        transport.send(guest.endpoint, codec.encode(guest.link.nextSeq(), payload))
    }

    /** §16.2 nome em branco vira "Jogador N" (N = id do jogador na mesa). */
    private fun nameOrDefault(name: String, id: Int): String = name.trim().ifBlank { "Jogador $id" }

    companion object {
        /** §16.2 o anfitrião ocupa sempre a posição 1. */
        val HOST_SEAT = Seat(0)
    }
}

private val secureRandom = SecureRandom()

/** Token aleatório de 128 bits (§16.5); nunca exibido nem registrado. */
fun randomToken(): String = ByteArray(16).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }
