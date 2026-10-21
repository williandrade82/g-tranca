package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** O jogo do anfitrião visto pela sessão (§16.1). Chamado com a trava da sessão: não bloqueia nem suspende. */
interface HostGamePort {
    /** Jogada do convidado no [seat] para o pedido [requestId]; `null` se aceita, ou a recusa (sem alterar o jogo). */
    fun propose(seat: Seat, requestId: Long, action: Action): ActionRejected?

    /** §16.9 o convidado do [seat] desistiu (a qualquer momento depois do início). Devolve se foi aceita. */
    fun resign(seat: Seat): Boolean

    /** §16.5, §16.8 a presença de algum jogador mudou (caiu, voltou, desistiu, entrou). */
    fun onPresenceChanged() {}

    /** §16.5, §16.7 um jogador (re)entrou no [seat] com o jogo em andamento: precisa da vista completa. */
    fun onSeatAttached(seat: Seat) {}

    /** §16.10 o sucessor [playerId] confirmou o estado [stateSeq]. */
    fun onSuccessorAck(playerId: Int, stateSeq: Long) {}
}

/** §16.7 pedido de entrada no meio do jogo, à espera da aprovação do anfitrião. */
data class JoinRequest(val requestId: Int, val name: String, val seat: Seat)

/** Para um sucessor que assume (§16.10): a sala que ele herda. */
data class ResumedTable(val hostId: Int, val members: List<MemberRecord>, val nextPlayerId: Int)

/**
 * Sessão do **anfitrião** (§16.1, §16.2, §16.5, §16.7, §16.8): publica a mesa, recebe os convidados (versão, nome, token,
 * assento), mantém e redistribui a sala e, no jogo, encaminha as jogadas ao [HostGamePort] e envia a cada convidado
 * **só** o que é dele ([sendView]) ou público ([broadcast]). A identidade é o **token**, nunca a ponta do transporte: com o
 * jogo em andamento, quem cai continua na sala (desconectado) e reingressa com o token; o token não vai na sala. Nunca
 * registra cartas nem tokens.
 *
 * Mesa nova: o anfitrião ocupa a posição 1 (assento 0, §16.2). Mesa herdada por um sucessor ([resumed]): o anfitrião é
 * quem assumiu, no assento dele, e o jogo já está em andamento.
 */
class HostSession(
    private val transport: LocalTransport,
    val tableId: String,
    val rules: TableRules,
    hostName: String,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val newToken: () -> String = ::randomToken,
    resumed: ResumedTable? = null,
) {
    private class Member(val token: String, var info: PlayerInfo) {
        var link: ReliableLink? = null
    }

    /** Uma conexão ainda sem jogador (antes do [Hello]) ou à espera de aprovação (§16.7). */
    private class Pending(val link: ReliableLink) {
        var request: JoinRequest? = null
    }

    private val mutex = Mutex()
    private lateinit var scope: CoroutineScope
    private val members = linkedMapOf<Int, Member>()
    private val pending = mutableMapOf<EndpointId, Pending>()
    private var nextId: Int
    private var nextRequest = 1
    private var started = resumed != null
    private var finished = false
    private var advertising = false
    private var collector: Job? = null
    private var successorIds: List<Int> = emptyList()

    /** §16.10 quem ainda não soube que este é o novo anfitrião (avisado ao reingressar). */
    private val hostNoticePending = mutableSetOf<Int>()

    @Volatile
    var game: HostGamePort? = null

    /** Id público do anfitrião atual (dono da mesa, §16.10). */
    val hostId: Int

    /** §16.5 token do anfitrião: se ele cair e voltar, reingressa como jogador comum. Só neste aparelho. */
    val hostToken: String

    private val _roster: MutableStateFlow<List<PlayerInfo>>

    init {
        if (resumed == null) {
            hostId = 0
            hostToken = newToken()
            members[0] = Member(hostToken, PlayerInfo(id = 0, name = hostName, joinOrder = 0, seat = HOST_SEAT, isHost = true, confirmed = true))
            nextId = 1
        } else {
            hostId = resumed.hostId
            resumed.members.forEach { record ->
                val me = record.info.id == hostId
                members[record.info.id] = Member(
                    record.token,
                    record.info.copy(isHost = me, connected = me, successorRank = 0, resigned = record.info.resigned && !me),
                )
            }
            hostToken = members.getValue(hostId).token
            nextId = resumed.nextPlayerId
            hostNoticePending += members.keys - hostId
        }
        _roster = MutableStateFlow(rosterLocked())
    }

    /** Assento do anfitrião. */
    val hostSeat: Seat get() = members.getValue(hostId).info.seat ?: HOST_SEAT

    private val _droppedAtStart = MutableStateFlow<List<PlayerInfo>>(emptyList())

    /** §16.2 aviso ao anfitrião: quem não tinha confirmado a posição no início e foi desconectado. */
    val droppedAtStart: StateFlow<List<PlayerInfo>> = _droppedAtStart.asStateFlow()

    /** Sala atual (id, nome, ordem de entrada, assento e presença de cada um; sem tokens). */
    val roster: StateFlow<List<PlayerInfo>> = _roster.asStateFlow()

    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())

    /** §16.7 pedidos de entrada no meio do jogo esperando o anfitrião ([approveJoin]/[refuseJoin]). */
    val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    private val _openSeats = MutableStateFlow<Set<Seat>>(emptySet())

    /** §16.7, §16.8 assentos abertos a outro convidado (mesa na lista com "jogo em andamento"). */
    val openSeats: StateFlow<Set<Seat>> = _openSeats.asStateFlow()

    private val _successors = MutableStateFlow<List<Int>>(emptyList())

    /** §16.10 ids do 1º e do 2º sucessores (ordem de entrada e disponibilidade); vazio = sem sucessor. */
    val successors: StateFlow<List<Int>> = _successors.asStateFlow()

    /** Sucessores marcados indisponíveis (não confirmaram a tempo, §16.10). */
    private val unavailable = mutableSetOf<Int>()

    /** Publica a mesa e passa a atender os convidados em [scope]. */
    suspend fun open(scope: CoroutineScope) {
        this.scope = scope
        collector = scope.launch { transport.events.collect { handle(it) } }
        mutex.withLock {
            if (started) refreshSuccessorsLocked()
            advertiseLocked(force = true)
        }
    }

    /** Assentos de convidados humanos (por assento, o id público), inclusive os desconectados e "VAGO". */
    fun guestSeats(): Map<Seat, Int> = _roster.value.filter { it.id != hostId && it.seat != null }.associate { it.seat!! to it.id }

    /** §16.2 o anfitrião inicia: quem não confirmou é desconectado; assentos vagos ficam com robôs. */
    suspend fun startGame(): Map<Seat, Int> {
        mutex.withLock {
            check(!started) { "O jogo já começou" }
            started = true
            val unconfirmed = members.values.filter { !it.info.confirmed }.toList()
            _droppedAtStart.value = unconfirmed.map { it.info }
            unconfirmed.forEach { removeMemberLocked(it, disconnect = true) }
            pending.values.toList().forEach { closePendingLocked(it) }
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = false)
            advertiseLocked()
            broadcastLocked(GameStarted(_roster.value))
        }
        return guestSeats()
    }

    /** Envia a vista do [seat] **só** ao convidado conectado nele (§16.4). */
    suspend fun sendView(seat: Seat, view: SeatView) {
        require(view.seat == seat) { "Vista de outro assento" }
        sendTo(seat, ViewUpdate(view))
    }

    /** Envia [payload] **só** ao convidado conectado no [seat]. */
    suspend fun sendTo(seat: Seat, payload: Payload) {
        mutex.withLock { memberAtLocked(seat)?.takeIf { it.info.id != hostId }?.link?.sendLocked(payload) }
    }

    /** Envia [payload] **só** ao jogador [playerId], se conectado (§16.10 estado do sucessor). */
    suspend fun sendToPlayer(playerId: Int, payload: Payload) {
        mutex.withLock { members[playerId]?.takeIf { it.info.id != hostId }?.link?.sendLocked(payload) }
    }

    /** Envia uma mensagem pública a todos os convidados conectados. */
    suspend fun broadcast(payload: Payload) {
        mutex.withLock { broadcastLocked(payload) }
    }

    /** Humano do [seat] agora (id, presença), ou `null` se o assento é de robô. */
    fun playerAt(seat: Seat): PlayerInfo? = _roster.value.firstOrNull { it.seat == seat }

    /**
     * §16.7, §16.8 opção 3: abre o [seat] a outro convidado. A mesa volta à lista com "jogo em andamento"; quem pedir para
     * entrar fica em [joinRequests] até o anfitrião aprovar.
     */
    suspend fun openSeat(seat: Seat) {
        mutex.withLock {
            if (!started || finished || seat == hostSeat) return
            _openSeats.value = _openSeats.value + seat
            advertiseLocked()
        }
    }

    /** Fecha o [seat] à entrada (ex.: o anfitrião pôs um robô); os pedidos para ele são recusados. */
    suspend fun closeSeat(seat: Seat) {
        mutex.withLock { closeSeatLocked(seat) }
    }

    private suspend fun closeSeatLocked(seat: Seat) {
        if (seat !in _openSeats.value) return
        _openSeats.value = _openSeats.value - seat
        pending.values.filter { it.request?.seat == seat }.forEach { refuseLocked(it, "A vaga foi preenchida.") }
        advertiseLocked()
    }

    /**
     * §16.7 aprova o pedido [requestId]: o novo jogador ocupa **exatamente** o assento aberto, recebe um token e entra
     * jogando. Quem ocupava o assento (caiu ou desistiu) perde o direito de reingressar. Devolve o novo jogador.
     */
    suspend fun approveJoin(requestId: Int): PlayerInfo? {
        val info = mutex.withLock {
            val entry = pending.entries.firstOrNull { it.value.request?.requestId == requestId } ?: return null
            val request = entry.value.request!!
            if (request.seat !in _openSeats.value) return null
            pending.remove(entry.key)
            memberAtLocked(request.seat)?.let { removeMemberLocked(it, disconnect = true) }
            val id = nextId++
            val info = PlayerInfo(id = id, name = nameOrDefault(request.name, id), joinOrder = id, seat = request.seat, confirmed = true)
            val member = Member(newToken(), info).also { it.link = entry.value.link }
            members[id] = member
            _openSeats.value = _openSeats.value - request.seat
            _joinRequests.value = _joinRequests.value.filter { it.requestId != requestId }
            pending.values.filter { it.request?.seat == request.seat }.forEach { refuseLocked(it, "A vaga foi preenchida.") }
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = false)
            member.link!!.sendLocked(Welcome(member.token, id, rules, _roster.value, started = true))
            broadcastLocked(RosterUpdate(_roster.value), except = member)
            broadcastLocked(TableNotice(NoticeKind.JOINED, request.seat, id))
            advertiseLocked()
            info
        }
        game?.onSeatAttached(info.seat!!)
        game?.onPresenceChanged()
        return info
    }

    /** §16.7 recusa o pedido [requestId]. */
    suspend fun refuseJoin(requestId: Int) {
        mutex.withLock {
            pending.values.firstOrNull { it.request?.requestId == requestId }?.let { refuseLocked(it, "O anfitrião não aprovou a entrada.") }
        }
    }

    /**
     * §16.8 opção 2: um robô assumiu o [seat]. O humano que estava nele sai da sala (o token deixa de valer) e todos são
     * avisados. Devolve o id de quem saiu.
     */
    suspend fun seatToRobot(seat: Seat): Int? = mutex.withLock {
        val member = memberAtLocked(seat)?.takeIf { it.info.id != hostId } ?: return@withLock null
        closeSeatLocked(seat)
        removeMemberLocked(member, disconnect = true)
        broadcastLocked(TableNotice(NoticeKind.REPLACED_BY_ROBOT, seat, member.info.id))
        member.info.id
    }

    /** §16.9 o anfitrião marca a própria desistência (o assento dele fica "VAGO" depois que ele passar o cargo). */
    suspend fun markHostResigned() {
        mutex.withLock { updateLocked(members.getValue(hostId)) { it.copy(resigned = true) } }
    }

    /** §16.10 o sucessor [playerId] não confirmou a tempo: deixa de ser sucessor (o próximo sobe). */
    suspend fun markUnavailable(playerId: Int) {
        mutex.withLock {
            unavailable += playerId
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = true)
        }
    }

    /** Fim do jogo: nada mais se recruta; a mesa deixa de ser anunciada. */
    suspend fun finish() {
        mutex.withLock {
            finished = true
            _openSeats.value = emptySet()
            pending.values.toList().forEach { closePendingLocked(it) }
            advertiseLocked()
        }
    }

    /**
     * §16.10 o anfitrião deixa a mesa (passou o cargo ou desistiu): avisa a todos quem é o novo anfitrião, deixa de anunciar,
     * fecha as conexões e solta o transporte (o aparelho pode voltar como convidado, com [hostToken]).
     */
    suspend fun handOver(newHostId: Int) {
        mutex.withLock {
            members[newHostId]?.link?.sendLocked(BecomeHost)
            broadcastLocked(TableNotice(NoticeKind.HOST_CHANGED, members[newHostId]?.info?.seat, newHostId))
        }
        close()
    }

    /** Encerra a sessão: para de anunciar, desconecta todos e deixa de ouvir o transporte. */
    suspend fun close() {
        mutex.withLock {
            finished = true
            if (advertising) transport.stopAdvertising()
            advertising = false
            members.values.forEach { m -> m.link?.let { it.close(); transport.disconnect(it.endpoint) }; m.link = null }
            pending.values.forEach { it.link.close(); transport.disconnect(it.link.endpoint) }
            pending.clear()
        }
        collector?.cancel()
    }

    private suspend fun handle(event: TransportEvent) {
        var presence = false
        mutex.withLock {
            when (event) {
                is TransportEvent.Connected -> pending[event.endpoint] = Pending(ReliableLink(event.endpoint, transport, codec, scope, mutex))
                is TransportEvent.Disconnected -> presence = disconnectedLocked(event.endpoint)
                is TransportEvent.Received -> receiveLocked(event)
            }
        }
        if (presence) game?.onPresenceChanged()
    }

    private suspend fun disconnectedLocked(endpoint: EndpointId): Boolean {
        pending.remove(endpoint)?.let { p ->
            p.link.close()
            if (p.request != null) _joinRequests.value = _joinRequests.value.filter { it.requestId != p.request?.requestId }
            return false
        }
        val member = memberByEndpoint(endpoint) ?: return false
        member.link?.close()
        member.link = null
        if (!started) {
            removeMemberLocked(member, disconnect = false)
            return false
        }
        // §16.8 com o jogo em andamento, quem cai continua na sala (reservado a ele) e pode reingressar pelo token.
        updateLocked(member) { it.copy(connected = false) }
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = true)
        if (!member.info.resigned) broadcastLocked(TableNotice(NoticeKind.DISCONNECTED, member.info.seat, member.info.id))
        advertiseLocked()
        return true
    }

    private fun memberByEndpoint(endpoint: EndpointId): Member? = members.values.firstOrNull { it.link?.endpoint == endpoint }

    private fun memberAtLocked(seat: Seat): Member? = members.values.firstOrNull { it.info.seat == seat }

    private suspend fun receiveLocked(event: TransportEvent.Received) {
        val member = memberByEndpoint(event.endpoint)
        val waiting = if (member == null) pending[event.endpoint] ?: return else null
        val link = member?.link ?: waiting!!.link
        when (val decoded = codec.decode(event.bytes)) {
            is Decoded.VersionMismatch -> {
                link.sendLocked(Rejected(VERSION_MISMATCH_MESSAGE))
                if (waiting != null) closePendingLocked(waiting)
            }
            Decoded.Malformed -> Unit
            is Decoded.Ok -> link.receiveLocked(decoded.envelope).forEach { payload ->
                val now = memberByEndpoint(event.endpoint)
                if (now != null) processLocked(now, payload) else pending[event.endpoint]?.let { helloLocked(it, payload) }
            }
        }
    }

    /** Uma conexão sem jogador: só [Hello] (entrada, reingresso ou pedido de entrada no meio). */
    private suspend fun helloLocked(p: Pending, payload: Payload) {
        if (payload !is Hello) return refuseLocked(p, "Entre na mesa antes de jogar.")
        if (p.request != null) return
        val token = payload.token
        if (token != null) return rejoinLocked(p, payload, token)
        if (!started) return welcomeLocked(p, payload)
        // §16.7 jogo em andamento: só entra num assento aberto, com a aprovação do anfitrião.
        val seat = _openSeats.value.minByOrNull { it.index }
            ?: return refuseLocked(p, if (finished) "O jogo desta mesa terminou." else "O jogo desta mesa já começou.")
        val request = JoinRequest(nextRequest++, payload.name.trim(), seat)
        p.request = request
        _joinRequests.value = _joinRequests.value + request
        p.link.sendLocked(JoinWaiting)
    }

    /** §16.5 reingresso pelo token, no mesmo assento, enquanto não foi substituído nem ocupado. */
    private suspend fun rejoinLocked(p: Pending, hello: Hello, token: String) {
        val member = members.values.firstOrNull { it.token == token && it.info.id != hostId }
        if (member == null || (hello.seat != null && hello.seat != member.info.seat) || finished) {
            return refuseLocked(p, "Não foi possível voltar à mesa: o lugar já foi ocupado.")
        }
        pending.remove(p.link.endpoint)
        member.link?.let { old -> old.close(); transport.disconnect(old.endpoint) }
        member.link = p.link
        updateLocked(member) { it.copy(connected = true) }
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = false)
        p.link.sendLocked(Welcome(member.token, member.info.id, rules, _roster.value, started = started))
        broadcastLocked(RosterUpdate(_roster.value), except = member)
        if (hostNoticePending.remove(member.info.id)) {
            p.link.sendLocked(TableNotice(NoticeKind.HOST_CHANGED, hostSeat, hostId))
        }
        if (started) {
            broadcastLocked(TableNotice(NoticeKind.RECONNECTED, member.info.seat, member.info.id), except = member)
            advertiseLocked()
            // A vista completa e a retomada saem do jogo, fora da trava.
            scope.launch {
                member.info.seat?.let { game?.onSeatAttached(it) }
                game?.onPresenceChanged()
            }
        }
    }

    private suspend fun processLocked(member: Member, payload: Payload) {
        val player = member.info
        when (payload) {
            // §16.2 só para um assento vago (nunca troca com outro jogador), mesmo depois de confirmar, e só na sala de
            // espera: depois do início ninguém troca de assento.
            is ChooseSeat -> if (!started) {
                if (payload.seat == player.seat || isFreeLocked(payload.seat)) {
                    updateLocked(member) { it.copy(seat = payload.seat) }
                }
                publishRosterLocked(broadcast = true)
            }
            ConfirmSeat -> if (!started && player.seat != null) {
                updateLocked(member) { it.copy(confirmed = true) }
                publishRosterLocked(broadcast = true)
            }
            is Rename -> {
                updateLocked(member) { it.copy(name = nameOrDefault(payload.name, player.id)) }
                publishRosterLocked(broadcast = true)
            }
            Leave -> if (!started) removeMemberLocked(member, disconnect = true)
            Resign -> resignLocked(member)
            is ProposeAction -> {
                val seat = player.seat
                val port = game
                val rejection = if (!started || seat == null || port == null || player.resigned) {
                    ActionRejected(payload.requestId, RejectReason.NOT_PLAYING)
                } else {
                    port.propose(seat, payload.requestId, payload.action)
                }
                if (rejection != null) member.link?.sendLocked(rejection)
            }
            is SuccessorAck -> game?.onSuccessorAck(player.id, payload.stateSeq)
            else -> Unit
        }
    }

    /**
     * §16.9 desistência do convidado (a qualquer momento): na mesa de 4 o assento fica "VAGO" (o jogador sai, mas o
     * assento continua dele até o anfitrião decidir na vez do assento); no 1×1 o outro vence (§13.1).
     */
    private suspend fun resignLocked(member: Member) {
        val seat = member.info.seat ?: return
        if (!started || member.info.resigned || game?.resign(seat) != true) return
        updateLocked(member) { it.copy(resigned = true, connected = false) }
        member.link?.let { it.close(); transport.disconnect(it.endpoint) }
        member.link = null
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = true)
        broadcastLocked(TableNotice(NoticeKind.RESIGNED, seat, member.info.id))
        advertiseLocked()
        scope.launch { game?.onPresenceChanged() }
    }

    private suspend fun welcomeLocked(p: Pending, hello: Hello) {
        val seats = rules.mode.seatCount
        if (members.size >= seats) return refuseLocked(p, "Mesa cheia.")
        val id = nextId++
        // §16.2 ocupa automaticamente o primeiro assento livre.
        val seat = (0 until seats).map(::Seat).first(::isFreeLocked)
        val info = PlayerInfo(id = id, name = nameOrDefault(hello.name, id), joinOrder = id, seat = seat)
        val member = Member(newToken(), info).also { it.link = p.link }
        pending.remove(p.link.endpoint)
        members[id] = member
        publishRosterLocked(broadcast = false)
        p.link.sendLocked(Welcome(member.token, id, rules, _roster.value))
        broadcastLocked(RosterUpdate(_roster.value), except = member)
    }

    private fun isFreeLocked(seat: Seat): Boolean =
        seat.index in 0 until rules.mode.seatCount && members.values.none { it.info.seat == seat }

    private fun updateLocked(member: Member, change: (PlayerInfo) -> PlayerInfo) {
        member.info = change(member.info)
    }

    private fun rosterLocked(): List<PlayerInfo> = members.values.map { m ->
        val rank = successorIds.indexOf(m.info.id) + 1
        m.info.copy(successorRank = rank)
    }

    private suspend fun publishRosterLocked(broadcast: Boolean) {
        _roster.value = rosterLocked()
        if (broadcast) broadcastLocked(RosterUpdate(_roster.value))
    }

    /**
     * §16.10 o 1º e o 2º sucessores: convidados humanos conectados, que não desistiram e não foram marcados indisponíveis,
     * na ordem de entrada. Quem deixa de ser sucessor recebe [SuccessorDrop] (descarta o estado completo).
     */
    private suspend fun refreshSuccessorsLocked() {
        val old = successorIds
        val next = if (!started || finished) emptyList() else members.values
            .filter { it.info.id != hostId && it.info.connected && !it.info.resigned && it.info.id !in unavailable && it.link != null }
            .sortedBy { it.info.joinOrder }
            .take(2)
            .map { it.info.id }
        successorIds = next
        _successors.value = next
        (old - next.toSet()).forEach { id -> members[id]?.link?.sendLocked(SuccessorDrop) }
    }

    private suspend fun removeMemberLocked(member: Member, disconnect: Boolean) {
        members.remove(member.info.id)
        member.link?.let { link ->
            link.close()
            if (disconnect) transport.disconnect(link.endpoint)
        }
        member.link = null
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = true)
        if (started) advertiseLocked()
    }

    private suspend fun refuseLocked(p: Pending, message: String) {
        p.link.sendLocked(Rejected(message))
        closePendingLocked(p)
    }

    private suspend fun closePendingLocked(p: Pending) {
        pending.remove(p.link.endpoint)
        p.request?.let { r -> _joinRequests.value = _joinRequests.value.filter { it.requestId != r.requestId } }
        // A recusa sai antes da desconexão (o transporte entrega em ordem).
        p.link.close()
        transport.disconnect(p.link.endpoint)
    }

    /**
     * Anúncio da mesa: antes do início, sempre; com o jogo em andamento, enquanto alguém pode voltar (desconectado, §16.5)
     * ou há assento aberto (§16.7, marca "jogo em andamento"); nunca depois do fim.
     */
    private suspend fun advertiseLocked(force: Boolean = false) {
        val open = _openSeats.value.isNotEmpty()
        val want = !finished && (!started || open || members.values.any { it.info.id != hostId && !it.info.connected })
        if (want) {
            if (!advertising || force || started) {
                transport.advertise(TableAdvert(tableId, rules.tableName, members.getValue(hostId).info.name, inProgress = started && open))
            }
            advertising = true
        } else if (advertising) {
            transport.stopAdvertising()
            advertising = false
        }
    }

    private suspend fun broadcastLocked(payload: Payload, except: Member? = null) {
        members.values.filter { it !== except && it.info.id != hostId }.mapNotNull { it.link }.toList().forEach { it.sendLocked(payload) }
    }

    /** Todos os humanos com os tokens, para o estado do sucessor (§16.10). Só o anfitrião chama. */
    fun membersForSuccessor(): List<MemberRecord> = members.values.map { MemberRecord(it.info, it.token) }

    /** Próximo id público (o sucessor continua a numeração). */
    fun nextPlayerId(): Int = nextId

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
