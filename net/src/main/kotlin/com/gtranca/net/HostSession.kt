package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.model.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
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

    /**
     * §16.5, §16.7 um jogador (re)entrou no [seat] com o jogo em andamento: precisa da vista completa e, se ainda não o
     * recebeu (último recebido: [roundOverSeen]), do pacote de fim da última partida (§16.11).
     */
    fun onSeatAttached(seat: Seat, roundOverSeen: Int = Int.MAX_VALUE) {}

    /** §16.10 o sucessor [playerId] confirmou o estado [stateSeq]. */
    fun onSuccessorAck(playerId: Int, stateSeq: Long) {}
}

/** §16.7 pedido de entrada no meio do jogo, à espera da aprovação do anfitrião. */
data class JoinRequest(val requestId: Int, val name: String, val seat: Seat)

/** §14.4 outro participante escolhido na montagem da mesa, além do anfitrião (que é sempre o primeiro). */
sealed interface Participant {
    /** §16.2 jogador próximo convidado (presença achada na busca). */
    data class Nearby(val presence: PresenceAdvert) : Participant

    /** §14.5 personagem escolhido. */
    data class Robot(val persona: String) : Participant
}

/**
 * §14.4, §16.2 mesa montada pelo anfitrião: os outros participantes ([others], na ordem escolhida), os personagens
 * possíveis ([personaPool], para sortear os que sobram) e a semente da mesa (só no anfitrião; nunca trafega).
 */
data class LobbySetup(val others: List<Participant>, val personaPool: List<String>, val seed: Long)

/** Para um sucessor que assume (§16.10): a sala que ele herda. */
data class ResumedTable(
    val hostId: Int,
    val members: List<MemberRecord>,
    val nextPlayerId: Int,
    /** §16.10 geração deste anfitrião (a do anterior + 1). */
    val epoch: Int = 1,
    /** §16.10 assumiu por queda sem confirmação ainda (posição de sucessor, 1 ou 2); 0 = confirmado (passagem do cargo). */
    val provisionalRank: Int = 0,
    /** Anfitrião anterior (não confirma o provisório: ele é quem pode ter só piscado). */
    val previousHostId: Int? = null,
    /** §16.7 assentos que o anterior tinha aberto a outro convidado. */
    val openSeats: Set<Seat> = emptySet(),
)

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
    rules: TableRules,
    hostName: String,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val newToken: () -> String = ::randomToken,
    resumed: ResumedTable? = null,
    /** §16.2 foto do perfil do anfitrião (Base64), repassada a quem entrar; `null` = sem foto. */
    hostPhoto: String? = null,
    /** §16.10, §16.15 provisório sem outro humano que possa confirmar: confirma sozinho depois deste tempo. */
    private val provisionalSoloMillis: Long = PROVISIONAL_SOLO_MILLIS,
    /** §16.10, §16.15 provisório com outros humanos e sem reingresso: oferece "Assumir mesmo assim" depois deste tempo. */
    private val provisionalForceMillis: Long = PROVISIONAL_FORCE_MILLIS,
    /** §16.11, §16.15 depois do fim do jogo, a mesa fecha quando todos receberam ou depois deste tempo. */
    private val finishedOpenMillis: Long = FINISHED_OPEN_MILLIS,
    /**
     * §14.4, §16.2 mesa montada (toda mesa nova): convite aos jogadores próximos e escolha de posições com rotação e
     * confirmação; o jogo começa sozinho 2 s depois que todos confirmam. `null` = mesa antiga (assento vago, anfitrião na
     * posição 1 e início manual por [startGame]; a fase 2 remove).
     */
    lobby: LobbySetup? = null,
) {
    private class Member(val token: String, var info: PlayerInfo) {
        var link: ReliableLink? = null

        /** §16.2 chave de presença do convidado enquanto o convite não foi respondido. */
        var inviteKey: String? = null
    }

    /** Uma conexão ainda sem jogador (antes do [Hello]) ou à espera de aprovação (§16.7). */
    private class Pending(val link: ReliableLink) {
        var request: JoinRequest? = null

        /** §16.2 foto do pedido de entrada no meio (vai à mesa se for aprovado). */
        var photo: String? = null

        /** §16.11 conexão só para entregar o resultado depois do fim: nada mais é processado nela. */
        var resultsOnly = false
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
    private var watcher: Job? = null
    private var closed = false
    private var successorIds: List<Int> = emptyList()

    /** §16.10 geração deste anfitrião (0 = o original). */
    val epoch: Int = resumed?.epoch ?: 0

    private val previousHostId: Int? = resumed?.previousHostId
    private var provisionalRank: Int = resumed?.provisionalRank ?: 0

    private val _committed = MutableStateFlow(provisionalRank == 0)

    /** §16.10 confirmado como anfitrião (o provisório só retoma o jogo depois disto). */
    val committed: StateFlow<Boolean> = _committed.asStateFlow()

    private val _superseded = MutableStateFlow(false)

    /**
     * §16.10 outro anfitrião da mesma mesa prevalece (o provisório achou o original, ou o original achou um confirmado):
     * esta sessão fechou e o aparelho volta como convidado com [hostToken].
     */
    val superseded: StateFlow<Boolean> = _superseded.asStateFlow()

    private val _canForceCommit = MutableStateFlow(false)

    /**
     * §16.10, §16.15 provisório com outros humanos que ninguém confirmou no tempo: a tela mostra "Assumir mesmo assim"
     * ([forceCommit]). Volta a `false` ao ser confirmado ou ao fechar. (Provisório, revisar com produto.)
     */
    val canForceCommit: StateFlow<Boolean> = _canForceCommit.asStateFlow()

    private val _resultsPending = MutableStateFlow(false)

    /** §16.11 depois do fim do jogo, ainda há humano fora que não recebeu o resultado e o prêmio. */
    val resultsPending: StateFlow<Boolean> = _resultsPending.asStateFlow()

    private val _deliveryEnded = MutableStateFlow(false)

    /**
     * §16.11, §16.15 depois do fim do jogo, a entrega do resultado acabou (todos receberam ou passaram 5 min): a mesa não
     * é mais anunciada e quem voltar é recusado. Os conectados seguem ligados; o anfitrião pode fechar a sessão.
     */
    val deliveryEnded: StateFlow<Boolean> = _deliveryEnded.asStateFlow()

    /** §16.8 assento da pausa (decisão do anfitrião pendente): aceita pedido de entrada mesmo sem vaga aberta (§16.7). */
    private var awaitingSeat: Seat? = null

    /** §16.11 o que falta entregar a quem estava fora no fim do jogo (por id): fim de jogo e prêmio. */
    private val finals = mutableMapOf<Int, List<Payload>>()

    /** §16.10 quem ainda não soube que este é o novo anfitrião (avisado ao reingressar). */
    private val hostNoticePending = mutableSetOf<Int>()

    @Volatile
    var game: HostGamePort? = null

    /** Regras da mesa (§16.2): não mudam depois da montagem (§16.3). */
    val rules: TableRules = rules

    /** §16.2 sala da escolha de posições (só na mesa montada, antes do início). */
    private var seatingRoom: SeatingRoom? = null

    private val _seating = MutableStateFlow<SeatingState?>(null)

    /** §16.2 escolha de posições (ocupantes, confirmações, trava); `null` na mesa antiga. */
    val seating: StateFlow<SeatingState?> = _seating.asStateFlow()

    private val _gameStart = MutableStateFlow<Map<Seat, Int>?>(null)

    /**
     * §16.2 o jogo começou sozinho (2 s depois de todos confirmarem): os assentos dos convidados humanos (como o retorno
     * de [startGame]). Os personagens dos robôs estão em [robots].
     */
    val gameStart: StateFlow<Map<Seat, Int>?> = _gameStart.asStateFlow()

    private val _lobbyNotices = MutableSharedFlow<TableNotice>(extraBufferCapacity = 64)

    /** §16.2 avisos ao anfitrião na escolha de posições ("<nome> saiu; <robô> assume o lugar", com o motivo). */
    val lobbyNotices: SharedFlow<TableNotice> = _lobbyNotices.asSharedFlow()

    /**
     * §16.2, §16.8 personagem de cada assento de robô (índice do assento → id), a única fonte: vai a todos no
     * [GameStarted] e a quem entra com o jogo em andamento ([Welcome]). Definido no início ([startGame]) e mantido pelo
     * jogo do anfitrião (robô substituto, entrada no meio).
     */
    @Volatile
    var robots: Map<Int, String> = emptyMap()

    /** Id público do anfitrião atual (dono da mesa, §16.10). */
    val hostId: Int

    /** §16.5 token do anfitrião: se ele cair e voltar, reingressa como jogador comum. Só neste aparelho. */
    val hostToken: String

    private val _roster: MutableStateFlow<List<PlayerInfo>>

    private fun snapshotLocked() {
        memberSnapshot = members.values.map { MemberRecord(it.info, it.token) }
        nextIdSnapshot = nextId
    }

    init {
        if (resumed == null && lobby != null) {
            hostId = 0
            hostToken = newToken()
            var id = 0
            val occupants = mutableListOf<SeatOccupant>(SeatOccupant.Human(0))
            val invitees = mutableListOf<Member>()
            lobby.others.forEach { p ->
                when (p) {
                    is Participant.Robot -> occupants += SeatOccupant.Robot(p.persona)
                    is Participant.Nearby -> {
                        id++
                        occupants += SeatOccupant.Human(id)
                        val name = p.presence.name.trim().ifBlank { "Jogador $id" }
                        invitees += Member(newToken(), PlayerInfo(id = id, name = name, joinOrder = id, connected = false, invited = true))
                            .also { it.inviteKey = p.presence.key }
                    }
                }
            }
            seatingRoom = SeatingRoom.create(rules.mode, occupants, lobby.personaPool, lobby.seed)
            members[0] = Member(hostToken, PlayerInfo(id = 0, name = hostName, joinOrder = 0, isHost = true))
            invitees.forEach { members[it.info.id] = it }
            nextId = id + 1
            syncSeatsLocked()
        } else if (resumed == null) {
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
        snapshotLocked()
    }

    private val _photos = MutableStateFlow<Map<Int, String>>(
        hostPhoto?.takeIf { it.length <= MAX_PHOTO_BASE64 }?.let { mapOf(hostId to it) } ?: emptyMap(),
    )

    /** §16.2 fotos dos jogadores da mesa (id → Base64), inclusive a do anfitrião; só em memória. */
    val photos: StateFlow<Map<Int, String>> = _photos.asStateFlow()

    /**
     * §16.2 guarda a foto do [member] (se veio no [Hello]) e a repassa aos outros, e manda a ele as fotos dos demais: uma
     * vez por entrada (inclusive reingresso e entrada no meio), nunca a cada vista.
     */
    private suspend fun sharePhotosLocked(member: Member, link: ReliableLink, photo: String?) {
        val id = member.info.id
        if (photo != null && photo.length <= MAX_PHOTO_BASE64 && _photos.value[id] != photo) {
            _photos.value = _photos.value + (id to photo)
            broadcastLocked(PlayerPhoto(id, photo), except = member)
        }
        _photos.value.filterKeys { it != id && members.containsKey(it) }.forEach { (other, data) -> link.sendLocked(PlayerPhoto(other, data)) }
    }

    /** Assento do anfitrião. */
    val hostSeat: Seat get() = members[hostId]?.info?.seat ?: HOST_SEAT

    private val _droppedAtStart = MutableStateFlow<List<PlayerInfo>>(emptyList())

    /** §16.2 aviso ao anfitrião: quem não tinha confirmado a posição no início e foi desconectado. */
    val droppedAtStart: StateFlow<List<PlayerInfo>> = _droppedAtStart.asStateFlow()

    /** Sala atual (id, nome, ordem de entrada, assento e presença de cada um; sem tokens). */
    val roster: StateFlow<List<PlayerInfo>> = _roster.asStateFlow()

    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())

    /** §16.7 pedidos de entrada no meio do jogo esperando o anfitrião ([approveJoin]/[refuseJoin]). */
    val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    private val _openSeats = MutableStateFlow(resumed?.openSeats ?: emptySet())

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
        // §16.10 outro anfitrião da mesma mesa que prevaleça (só com o jogo em andamento).
        watcher = scope.launch {
            transport.discover().collect { list ->
                val mine = mutex.withLock { if (started && !closed) advertLocked() else null } ?: return@collect
                if (list.any { it.tableId == tableId && it.hostId != hostId && it.outranks(mine) }) supersede()
            }
        }
        mutex.withLock {
            if (started) refreshSuccessorsLocked()
            advertiseLocked(force = true)
        }
        // §16.2, §16.15 o prazo de 10 s para o convidado se conectar conta da publicação do anúncio com as chaves; sem
        // conexão, ele não está mais ao alcance e um robô assume.
        members.values.filter { it.inviteKey != null }.forEach { invitee ->
            scope.launch {
                delay(INVITE_TIMEOUT_MILLIS)
                mutex.withLock { if (invitee.inviteKey != null && invitee.link == null) refuseInviteeLocked(invitee, RefusalReason.OUT_OF_RANGE) }
            }
        }
        // §16.10 provisório sem outro humano que possa confirmar: confirma sozinho depois do tempo de §16.15.
        if (provisionalRank > 0 && members.values.none { it.info.id != hostId && it.info.id != previousHostId && !it.info.resigned }) {
            scope.launch {
                delay(provisionalSoloMillis)
                mutex.withLock { commitLocked() }
            }
        } else if (provisionalRank > 0) {
            // §16.10 com outros humanos e ninguém reingressa: depois do tempo de §16.15, oferece "Assumir mesmo assim".
            scope.launch {
                delay(provisionalForceMillis)
                mutex.withLock { if (provisionalRank > 0 && !closed) _canForceCommit.value = true }
            }
        }
    }

    /**
     * §16.10 "Assumir mesmo assim": o provisório passa a confirmado e retoma o jogo. Só vale depois de [canForceCommit];
     * devolve se confirmou. (Provisório, revisar com produto.)
     */
    suspend fun forceCommit(): Boolean = mutex.withLock {
        if (!_canForceCommit.value || provisionalRank == 0 || closed) return@withLock false
        commitLocked()
        true
    }

    /** §16.10 o provisório passa a anfitrião confirmado (retoma o jogo e anuncia sem a marca de provisório). */
    private suspend fun commitLocked() {
        if (provisionalRank == 0 || closed) return
        provisionalRank = 0
        _canForceCommit.value = false
        _committed.value = true
        advertiseLocked()
    }

    /** §16.10 outro anfitrião prevalece: fecha esta sessão (os convidados dela reconectam no outro). */
    private suspend fun supersede() {
        if (_superseded.value) return
        _superseded.value = true
        close()
    }

    /** §16.10 espera a confirmação (devolve `true`) ou que outro anfitrião prevaleça (`false`). */
    suspend fun awaitCommitted(): Boolean {
        combine(_committed, _superseded) { c, s -> c || s }.first { it }
        return !_superseded.value
    }

    /** Assentos de convidados humanos (por assento, o id público), inclusive os desconectados e "VAGO". */
    fun guestSeats(): Map<Seat, Int> = _roster.value.filter { it.id != hostId && it.seat != null }.associate { it.seat!! to it.id }

    /** §16.2 o anfitrião inicia: quem não confirmou é desconectado; assentos vagos ficam com robôs. */
    suspend fun startGame(): Map<Seat, Int> {
        mutex.withLock {
            check(!started) { "O jogo já começou" }
            check(seatingRoom == null) { "Na mesa montada o jogo começa sozinho quando todos confirmam (§16.2)" }
            started = true
            val unconfirmed = members.values.filter { !it.info.confirmed }.toList()
            _droppedAtStart.value = unconfirmed.map { it.info }
            unconfirmed.forEach { removeMemberLocked(it, disconnect = true) }
            pending.values.toList().forEach { closePendingLocked(it) }
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = false)
            advertiseLocked()
            if (robots.isEmpty()) {
                // §16.2 os assentos sem humano recebem os robôs escolhidos pelo anfitrião, em ordem de assento.
                val humans = _roster.value.mapNotNull { it.seat }.toSet()
                robots = rules.mode.seats.filter { it !in humans }.zip(rules.robots).associate { (seat, code) -> seat.index to code }
            }
            broadcastLocked(GameStarted(_roster.value, robots))
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

    /** §16.7, §16.8 assento da pausa (ou `null`): pedidos de entrada para ele são aceitos e aparecem junto das três ações. */
    suspend fun setAwaitingSeat(seat: Seat?) {
        mutex.withLock {
            val before = awaitingSeat
            awaitingSeat = seat
            if (before != null && before != seat && before !in _openSeats.value) refuseRequestsLocked(before)
        }
    }

    private suspend fun refuseRequestsLocked(seat: Seat) {
        pending.values.filter { it.request?.seat == seat }.toList().forEach { refuseLocked(it, SEAT_TAKEN_MESSAGE) }
    }

    /** Fecha o [seat] à entrada (ex.: o anfitrião pôs um robô); os pedidos para ele são recusados. */
    suspend fun closeSeat(seat: Seat) {
        mutex.withLock { closeSeatLocked(seat) }
    }

    private suspend fun closeSeatLocked(seat: Seat) {
        // §16.5 eventos simultâneos: os pedidos restantes para o assento são recusados.
        refuseRequestsLocked(seat)
        if (seat !in _openSeats.value) return
        _openSeats.value = _openSeats.value - seat
        advertiseLocked()
    }

    /**
     * §16.7 aprova o pedido [requestId]: o novo jogador ocupa **exatamente** o assento aberto, recebe um token e entra
     * jogando. Quem ocupava o assento (caiu ou desistiu) perde o direito de reingressar. Devolve o novo jogador.
     *
     * [attach] roda sob a trava, depois de conferido o pedido e antes de qualquer mudança: é onde o jogo troca o jogador do
     * assento; se devolver `false`, nada muda (a aprovação é atômica).
     */
    suspend fun approveJoin(requestId: Int, attach: (Seat) -> Boolean = { true }): PlayerInfo? {
        if (mutex.withLock { seatingRoom != null && !started }) return approveLobbyJoin(requestId)
        val info = mutex.withLock {
            val entry = pending.entries.firstOrNull { it.value.request?.requestId == requestId } ?: return null
            val request = entry.value.request!!
            if (request.seat !in _openSeats.value) return null
            if (!attach(request.seat)) return null
            pending.remove(entry.key)
            memberAtLocked(request.seat)?.let { removeMemberLocked(it, disconnect = true) }
            val id = nextId++
            val info = PlayerInfo(id = id, name = nameOrDefault(request.name, id), joinOrder = id, seat = request.seat, confirmed = true)
            val member = Member(newToken(), info).also { it.link = entry.value.link }
            members[id] = member
            _openSeats.value = _openSeats.value - request.seat
            _joinRequests.value = _joinRequests.value.filter { it.requestId != requestId }
            refuseRequestsLocked(request.seat)
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = false)
            member.link!!.sendLocked(Welcome(member.token, id, rules, _roster.value, started = true, robots = robots))
            sharePhotosLocked(member, member.link!!, entry.value.photo)
            broadcastLocked(RosterUpdate(_roster.value), except = member)
            broadcastLocked(TableNotice(NoticeKind.JOINED, request.seat, id))
            advertiseLocked()
            info
        }
        game?.onSeatAttached(info.seat!!, Int.MAX_VALUE)
        game?.onPresenceChanged()
        return info
    }

    /** §16.2, §16.7 recusa o pedido [requestId] (o candidato vê o motivo: o anfitrião recusou). */
    suspend fun refuseJoin(requestId: Int) {
        mutex.withLock {
            pending.values.firstOrNull { it.request?.requestId == requestId }
                ?.let { refuseLocked(it, refusalMessage(RefusalReason.HOST_REFUSED), RefusalReason.HOST_REFUSED) }
        }
    }

    /**
     * §16.2 pedido de entrada aceito na escolha de posições: o novo jogador entra no lugar do **primeiro robô** na ordem
     * dos assentos e passa a escolher a posição. Sem robô (ou já travado): recusa com "mesa cheia".
     */
    private suspend fun approveLobbyJoin(requestId: Int): PlayerInfo? = mutex.withLock {
        val entry = pending.entries.firstOrNull { it.value.request?.requestId == requestId } ?: return@withLock null
        val room = seatingRoom ?: return@withLock null
        val id = nextId
        val next = room.admit(id)
        if (next == null) {
            refuseLocked(entry.value, refusalMessage(RefusalReason.TABLE_FULL), RefusalReason.TABLE_FULL)
            return@withLock null
        }
        nextId++
        pending.remove(entry.key)
        _joinRequests.value = _joinRequests.value.filter { it.requestId != requestId }
        val member = Member(newToken(), PlayerInfo(id = id, name = nameOrDefault(entry.value.request!!.name, id), joinOrder = id))
            .also { it.link = entry.value.link }
        members[id] = member
        seatingRoom = next
        syncSeatsLocked()
        publishRosterLocked(broadcast = false)
        member.link!!.sendLocked(Welcome(member.token, id, rules, _roster.value, seating = next.state))
        sharePhotosLocked(member, member.link!!, entry.value.photo)
        broadcastLocked(RosterUpdate(_roster.value), except = member)
        broadcastLocked(SeatingUpdate(next.state), except = member)
        broadcastLocked(TableNotice(NoticeKind.JOINED, member.info.seat, id))
        if (!next.state.listed) refuseAllRequestsLocked()
        advertiseLocked()
        member.info
    }

    /** §16.2 o anfitrião rotaciona a própria posição; devolve se trocou. */
    suspend fun rotate(direction: RotateDirection): Boolean = mutex.withLock { rotateLocked(hostId, direction) }

    /** §16.2 o anfitrião confirma a própria posição; devolve se confirmou. */
    suspend fun confirmPosition(): Boolean = mutex.withLock { confirmLocked(hostId) }

    /** §16.2 "Sair" do anfitrião na escolha de posições: desfaz a mesa e avisa os convidados. */
    suspend fun disband() {
        mutex.withLock { if (!started) broadcastLocked(TableClosed(CloseReason.HOST_LEFT)) }
        close()
    }

    /**
     * §16.7 o anfitrião desliga o Wi-Fi do jogo no meio de um jogo com humanos: avisa os convidados (que não tentam voltar
     * nem assumir, §16.10), descarta o estado dos sucessores e encerra a sessão. Devolve, por assento, o id de cada humano
     * remoto (conectado ou não) que agora deve ser **substituído por um robô** pelo jogo do anfitrião, com as regras de
     * aposta da opção 2 de §16.8 (aplicadas fora do `:net`). Mesa só com robôs: vazio.
     */
    suspend fun goOffline(): Map<Seat, Int> {
        val (remote, links) = mutex.withLock {
            val seats = members.values.filter { it.info.id != hostId && it.info.seat != null }.associate { it.info.seat!! to it.info.id }
            successorIds.forEach { members[it]?.link?.sendLocked(SuccessorDrop) }
            broadcastLocked(TableClosed(CloseReason.HOST_OFFLINE))
            seats to members.values.mapNotNull { it.link }
        }
        // §16.10 o aviso precisa chegar antes de a mesa sumir (senão o sucessor assumiria): espera a confirmação dentro do
        // orçamento de reenvio de §16.15 (1 s, até 5 vezes).
        var waited = 0L
        while (waited < ReliableLink.RESEND_AFTER_MILLIS * (ReliableLink.MAX_RESENDS + 1)) {
            if (mutex.withLock { links.all { it.allConfirmed } }) break
            delay(OFFLINE_POLL_MILLIS)
            waited += OFFLINE_POLL_MILLIS
        }
        close()
        return remote
    }

    private suspend fun applySeatingLocked(next: SeatingRoom) {
        val wasLocked = seatingRoom?.state?.locked == true
        seatingRoom = next
        syncSeatsLocked()
        publishRosterLocked(broadcast = true)
        broadcastLocked(SeatingUpdate(next.state))
        // Só na transição: uma troca depois de travar (abandono nos 2 s) não reinicia a contagem nem o início.
        if (next.state.locked && !wasLocked) lockLocked() else advertiseLocked()
    }

    private suspend fun rotateLocked(playerId: Int, direction: RotateDirection): Boolean {
        if (started || closed || members[playerId]?.inviteKey != null) return false
        val next = seatingRoom?.rotate(playerId, direction) ?: return false
        applySeatingLocked(next)
        return true
    }

    private suspend fun confirmLocked(playerId: Int): Boolean {
        if (started || closed || members[playerId]?.inviteKey != null) return false
        val next = seatingRoom?.confirm(playerId) ?: return false
        applySeatingLocked(next)
        return true
    }

    /** §16.2 o jogador [member] sai da escolha de posições: um robô sorteado assume, e todos veem a troca e o motivo. */
    private suspend fun replaceInLobbyLocked(member: Member, refusal: RefusalReason?) {
        val room = seatingRoom ?: return
        val seat = member.info.seat
        val next = room.replaceWithRobot(member.info.id) ?: return
        member.inviteKey = null
        removeMemberLocked(member, disconnect = true)
        val persona = seat?.let { (next.state.occupants[it.index] as? SeatOccupant.Robot)?.persona }
        val notice = TableNotice(NoticeKind.REPLACED_BY_ROBOT, seat, member.info.id, persona, refusal)
        broadcastLocked(notice)
        _lobbyNotices.tryEmit(notice)
        applySeatingLocked(next)
    }

    /** §16.2 o convidado recusou ou não estava ao alcance: o anfitrião informa o motivo a ele e um robô assume. */
    private suspend fun refuseInviteeLocked(member: Member, reason: RefusalReason) {
        member.link?.sendLocked(Rejected(refusalMessage(reason), reason))
        replaceInLobbyLocked(member, reason)
    }

    /** §16.2 todos confirmaram: posições travadas, a mesa sai da lista e o jogo começa 2 s depois. */
    private suspend fun lockLocked() {
        refuseAllRequestsLocked()
        broadcastLocked(SeatsLocked(_seating.value!!))
        advertiseLocked()
        scope.launch {
            delay(START_DELAY_MILLIS)
            val seats = mutex.withLock {
                if (started || closed) return@launch
                started = true
                robots = seatingRoom!!.state.robots
                seatingRoom = null
                refreshSuccessorsLocked()
                publishRosterLocked(broadcast = false)
                advertiseLocked()
                broadcastLocked(GameStarted(_roster.value, robots))
                guestSeats()
            }
            _gameStart.value = seats
        }
    }

    private suspend fun refuseAllRequestsLocked() {
        pending.values.filter { it.request != null }.toList()
            .forEach { refuseLocked(it, refusalMessage(RefusalReason.TABLE_FULL), RefusalReason.TABLE_FULL) }
    }

    /** §16.2 assento e confirmação de cada humano conforme a escolha de posições. */
    private fun syncSeatsLocked() {
        val state = seatingRoom?.state ?: return
        _seating.value = state
        members.values.forEach { m ->
            val seat = state.seatOf(m.info.id)
            m.info = m.info.copy(seat = seat, confirmed = seat != null && state.isConfirmed(seat), invited = m.inviteKey != null)
        }
    }

    /** §16.2 o convidado se conectou com a chave de presença: recebe o convite (anfitrião, participantes, regras). */
    private suspend fun inviteHelloLocked(p: Pending, hello: Hello, key: String) {
        val member = members.values.firstOrNull { it.inviteKey == key && it.link == null }
            ?: return refuseLocked(p, refusalMessage(RefusalReason.OUT_OF_RANGE), RefusalReason.OUT_OF_RANGE)
        pending.remove(p.link.endpoint)
        member.link = p.link
        updateLocked(member) { it.copy(connected = true) }
        publishRosterLocked(broadcast = true)
        p.link.sendLocked(Invite(hostId, member.info.id, rules, _roster.value, _seating.value!!))
        sharePhotosLocked(member, p.link, hello.photo)
        advertiseLocked()
        // §16.2, §16.15 sem resposta, o convite é aceito sozinho (o convidado aceita no prazo; aqui, a folga da rede).
        scope.launch {
            delay(INVITE_TIMEOUT_MILLIS + INVITE_REPLY_GRACE_MILLIS)
            mutex.withLock { if (member.inviteKey != null && member.link != null && members.containsKey(member.info.id)) acceptInviteLocked(member) }
        }
    }

    private suspend fun acceptInviteLocked(member: Member) {
        if (member.inviteKey == null) return
        member.inviteKey = null
        syncSeatsLocked()
        publishRosterLocked(broadcast = false)
        member.link?.sendLocked(Welcome(member.token, member.info.id, null, _roster.value, seating = _seating.value))
        broadcastLocked(RosterUpdate(_roster.value), except = member)
        advertiseLocked()
    }

    /** §16.2 pedido de entrada na escolha de posições (sem convite): fica à espera do anfitrião. */
    private suspend fun lobbyRequestLocked(p: Pending, hello: Hello) {
        val state = _seating.value!!
        val seat = state.occupants.indexOfFirst { it is SeatOccupant.Robot }.takeIf { it >= 0 && !state.locked }
            ?: return refuseLocked(p, refusalMessage(RefusalReason.TABLE_FULL), RefusalReason.TABLE_FULL)
        val request = JoinRequest(nextRequest++, hello.name.trim(), Seat(seat))
        p.request = request
        p.photo = hello.photo
        _joinRequests.value = _joinRequests.value + request
        p.link.sendLocked(JoinWaiting)
    }

    /**
     * §16.8 opção 2: um robô assumiu o [seat]. O humano que estava nele sai da sala (o token deixa de valer) e todos são
     * avisados. Devolve o id de quem saiu.
     */
    suspend fun seatToRobot(seat: Seat, persona: String? = null): Int? = mutex.withLock {
        val member = memberAtLocked(seat)?.takeIf { it.info.id != hostId } ?: return@withLock null
        closeSeatLocked(seat)
        removeMemberLocked(member, disconnect = true)
        broadcastLocked(TableNotice(NoticeKind.REPLACED_BY_ROBOT, seat, member.info.id, persona))
        member.info.id
    }

    /** §16.2 o anfitrião muda o próprio nome (em branco: "Jogador 1"); todos veem e o anúncio da mesa acompanha. */
    suspend fun renameHost(name: String) {
        mutex.withLock {
            if (closed) return
            updateLocked(members.getValue(hostId)) { it.copy(name = name.trim().ifBlank { "Jogador ${hostId + 1}" }) }
            publishRosterLocked(broadcast = true)
            advertiseLocked()
        }
    }

    /** §16.9 o anfitrião marca a própria desistência (o assento dele fica "VAGO" depois que ele passar o cargo). */
    suspend fun markHostResigned() {
        mutex.withLock {
            updateLocked(members.getValue(hostId)) { it.copy(resigned = true) }
            publishRosterLocked(broadcast = true)
        }
    }

    /** §16.10 o escolhido na passagem do cargo não confirmou: descarta o estado (se não for sucessor). */
    suspend fun dropHandOverState(playerId: Int) {
        mutex.withLock { if (playerId !in successorIds) members[playerId]?.link?.sendLocked(SuccessorDrop) }
    }

    /** §16.10 quem foi marcado indisponível confirmou o estado mais recente: volta a contar como sucessor. */
    suspend fun markAvailable(playerId: Int) {
        mutex.withLock {
            if (!unavailable.remove(playerId)) return
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = true)
        }
    }

    /** §16.10 o sucessor [playerId] não confirmou a tempo: deixa de ser sucessor (o próximo sobe). */
    suspend fun markUnavailable(playerId: Int) {
        mutex.withLock {
            unavailable += playerId
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = true)
        }
    }

    /**
     * Fim do jogo: nada mais se recruta. [results] é, por jogador, o fim do jogo e o prêmio dele (§16.11): quem está fora
     * agora recebe ao voltar pelo token, e a mesa fica anunciada (fora da lista) até todos receberem.
     */
    suspend fun finish(results: Map<Int, List<Payload>> = emptyMap()) {
        mutex.withLock {
            finished = true
            _openSeats.value = emptySet()
            pending.values.toList().forEach { closePendingLocked(it) }
            // Guardado para todos (quem cai logo depois do fim também recebe); quem está conectado já recebeu agora.
            results.forEach { (id, payloads) -> if (members[id]?.info?.resigned == false) finals[id] = payloads }
            advertiseLocked()
            if (finishers.isEmpty() && !closed) {
                // §16.11, §16.15 a entrega fica aberta até todos receberem (com folga para quem cair logo depois do
                // fim) ou até o tempo acabar. Ao encerrar, só deixa de anunciar e recusa reingressos: quem está
                // conectado não é desligado (segue nas suas telas de resultado).
                finishers += scope.launch { delay(finishedOpenMillis); endDelivery() }
                finishers += scope.launch {
                    while (true) {
                        _resultsPending.first { !it }
                        delay(FINISHED_GRACE_MILLIS)
                        if (!_resultsPending.value) break
                    }
                    endDelivery()
                }
            }
        }
    }

    private val finishers = mutableListOf<Job>()

    /** §16.11 fim da entrega do resultado: a mesa deixa de ser anunciada e quem voltar depois é recusado. */
    private suspend fun endDelivery() {
        mutex.withLock {
            if (_deliveryEnded.value) return
            _deliveryEnded.value = true
            finals.clear()
            advertiseLocked()
        }
        finishers.forEach { it.cancel() }
    }

    /**
     * §16.10 o anfitrião deixa a mesa (passou o cargo ou desistiu): avisa a todos quem é o novo anfitrião, deixa de anunciar,
     * fecha as conexões e solta o transporte (o aparelho pode voltar como convidado, com [hostToken]).
     */
    suspend fun handOver(newHostId: Int) {
        mutex.withLock {
            // §16.10 os demais sucessores descartam o estado completo.
            successorIds.filter { it != newHostId }.forEach { members[it]?.link?.sendLocked(SuccessorDrop) }
            members[newHostId]?.link?.sendLocked(BecomeHost)
            broadcastLocked(TableNotice(NoticeKind.HOST_CHANGED, members[newHostId]?.info?.seat, newHostId))
        }
        close()
    }

    /** Encerra a sessão: para de anunciar, desconecta todos e deixa de ouvir o transporte. */
    suspend fun close() {
        mutex.withLock {
            finished = true
            closed = true
            finals.clear()
            _canForceCommit.value = false
            _resultsPending.value = false
            if (advertising) transport.stopAdvertising()
            advertising = false
            members.values.forEach { m -> m.link?.let { it.close(); transport.disconnect(it.endpoint) }; m.link = null }
            pending.values.forEach { it.link.close(); transport.disconnect(it.link.endpoint) }
            pending.clear()
        }
        watcher?.cancel()
        collector?.cancel()
        finishers.forEach { it.cancel() }
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
        if (!started && seatingRoom != null) {
            // §16.2 quem não está mais ao alcance antes do início vira robô.
            replaceInLobbyLocked(member, RefusalReason.OUT_OF_RANGE)
            return false
        }
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
                link.sendLocked(Rejected(versionMismatchMessage(codec.version, decoded.remoteVersion ?: 0)))
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
        if (p.resultsOnly) return
        if (payload !is Hello) return refuseLocked(p, "Entre na mesa antes de jogar.")
        if (p.request != null) return
        val token = payload.token
        if (token != null) return rejoinLocked(p, payload, token)
        if (!started && seatingRoom != null) {
            val key = payload.inviteKey
            return if (key != null) inviteHelloLocked(p, payload, key) else lobbyRequestLocked(p, payload)
        }
        if (!started) return welcomeLocked(p, payload)
        // §16.7 durante o jogo só se entra no assento que o anfitrião abriu (o pedido diz qual, se houver mais de um) ou,
        // enquanto ele decide a pausa, no assento da vez (aprovar = abrir a vaga e aprovar).
        val open = if (finished) emptySet() else _openSeats.value + listOfNotNull(awaitingSeat)
        val seat = payload.seat?.takeIf { it in open } ?: open.singleOrNull()
            ?: return refuseLocked(
                p,
                when {
                    finished -> "O jogo desta mesa terminou."
                    open.isEmpty() -> "O jogo desta mesa já começou."
                    else -> "Escolha um dos assentos abertos pelo anfitrião."
                },
            )
        val request = JoinRequest(nextRequest++, payload.name.trim(), seat)
        p.request = request
        p.photo = payload.photo
        _joinRequests.value = _joinRequests.value + request
        p.link.sendLocked(JoinWaiting)
    }

    /** §16.5 reingresso pelo token, no mesmo assento, enquanto não foi substituído nem ocupado. */
    private suspend fun rejoinLocked(p: Pending, hello: Hello, token: String) {
        val member = members.values.firstOrNull { it.token == token && it.info.id != hostId }
        if (member == null || (hello.seat != null && hello.seat != member.info.seat)) {
            return refuseLocked(p, "Não foi possível voltar à mesa: o lugar já foi ocupado.")
        }
        if (finished) return resultsOnlyLocked(p, member)
        pending.remove(p.link.endpoint)
        // §16.5 sessão duplicada: a conexão nova assume; a antiga recebe só o aviso de encerramento (para não tentar voltar
        // nem assumir como sucessor) e é fechada.
        member.link?.let { old ->
            old.sendLocked(Rejected(REPLACED_MESSAGE))
            old.close()
            transport.disconnect(old.endpoint)
        }
        member.link = p.link
        // §16.9 quem desistiu e volta (o anfitrião aguardou) joga de novo no assento e recupera a aposta (o jogo
        // restaura o razão em onSeatAttached).
        updateLocked(member) { it.copy(connected = true, resigned = false) }
        // §16.10 volta a contar como sucessor; §16.5 o dono voltou: a vaga fecha e os pedidos para ela são recusados.
        unavailable -= member.info.id
        member.info.seat?.let { closeSeatLocked(it) }
        // §16.10 outro jogador reingressou neste anfitrião pelo token: ele também perdeu o anterior (confirma o provisório).
        commitLocked()
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = false)
        p.link.sendLocked(Welcome(member.token, member.info.id, rules, _roster.value, started = started, robots = if (started) robots else emptyMap()))
        sharePhotosLocked(member, p.link, hello.photo)
        broadcastLocked(RosterUpdate(_roster.value), except = member)
        if (hostNoticePending.remove(member.info.id)) {
            p.link.sendLocked(TableNotice(NoticeKind.HOST_CHANGED, hostSeat, hostId))
        }
        if (started) {
            broadcastLocked(TableNotice(NoticeKind.RECONNECTED, member.info.seat, member.info.id), except = member)
            advertiseLocked()
            // A vista completa e a retomada saem do jogo, fora da trava.
            scope.launch {
                member.info.seat?.let { game?.onSeatAttached(it, hello.roundOverSeen) }
                game?.onPresenceChanged()
            }
        }
    }

    /** §16.11 voltou depois do fim: só recebe o fim do jogo e o prêmio (sem jogar). */
    private suspend fun resultsOnlyLocked(p: Pending, member: Member) {
        val results = finals.remove(member.info.id)
            ?: return refuseLocked(p, "O jogo desta mesa terminou.")
        p.resultsOnly = true
        p.link.sendLocked(Welcome(member.token, member.info.id, rules, _roster.value, started = true, robots = robots))
        results.forEach { p.link.sendLocked(it) }
        // A conexão fica (as mensagens acima precisam ser confirmadas) até o convidado ou o anfitrião fecharem.
        advertiseLocked()
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
            ConfirmSeat -> if (seatingRoom != null) {
                confirmLocked(player.id)
            } else if (!started && player.seat != null) {
                updateLocked(member) { it.copy(confirmed = true) }
                publishRosterLocked(broadcast = true)
            }
            is RotateSeat -> rotateLocked(player.id, payload.direction)
            // §14.6, §16.2 quem entrou pelo pedido e não tem saldo sai com o motivo; um robô assume.
            is InviteReply -> if (member.inviteKey == null && !started && seatingRoom != null && payload.reason == RefusalReason.INSUFFICIENT_BALANCE) {
                refuseInviteeLocked(member, RefusalReason.INSUFFICIENT_BALANCE)
            } else if (member.inviteKey != null && !started) {
                if (payload.accepted) {
                    acceptInviteLocked(member)
                } else {
                    val reason = payload.reason?.takeIf { it == RefusalReason.INSUFFICIENT_BALANCE } ?: RefusalReason.INVITE_REFUSED
                    refuseInviteeLocked(member, reason)
                }
            }
            is Rename -> {
                updateLocked(member) { it.copy(name = nameOrDefault(payload.name, player.id)) }
                publishRosterLocked(broadcast = true)
            }
            // §16.9 sair durante o jogo vale como desistência.
            Leave -> when {
                // §16.2 abandonar a escolha de posições: um robô sorteado assume.
                !started && seatingRoom != null -> replaceInLobbyLocked(member, null)
                !started -> removeMemberLocked(member, disconnect = true)
                else -> resignLocked(member)
            }
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
        sharePhotosLocked(member, p.link, hello.photo)
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
        snapshotLocked()
        _roster.value = rosterLocked()
        if (broadcast) broadcastLocked(RosterUpdate(_roster.value))
    }

    /**
     * §16.10 o 1º e o 2º sucessores: convidados humanos conectados, que não desistiram e não foram marcados indisponíveis,
     * na ordem de entrada. Quem deixa de ser sucessor recebe [SuccessorDrop] (descarta o estado completo).
     */
    private suspend fun refreshSuccessorsLocked() {
        val old = successorIds
        val next = if (!started || finished || closed) emptyList() else members.values
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
        _photos.value = _photos.value - member.info.id
        member.link?.let { link ->
            link.close()
            if (disconnect) transport.disconnect(link.endpoint)
        }
        member.link = null
        refreshSuccessorsLocked()
        publishRosterLocked(broadcast = true)
        if (started) advertiseLocked()
    }

    private suspend fun refuseLocked(p: Pending, message: String, reason: RefusalReason? = null) {
        p.link.sendLocked(Rejected(message, reason))
        closePendingLocked(p)
    }

    private suspend fun closePendingLocked(p: Pending) {
        pending.remove(p.link.endpoint)
        p.request?.let { r -> _joinRequests.value = _joinRequests.value.filter { it.requestId != r.requestId } }
        // A recusa sai antes da desconexão (o transporte entrega em ordem).
        p.link.close()
        transport.disconnect(p.link.endpoint)
    }

    private fun advertLocked(): TableAdvert {
        val open = !finished && _openSeats.value.isNotEmpty()
        val lobby = _seating.value?.takeIf { !started && seatingRoom != null }
        return TableAdvert(
            tableId, rules.tableName, members.getValue(hostId).info.name,
            inProgress = started && open,
            listed = if (lobby != null) lobby.listed else !started || open,
            hostId = hostId, epoch = epoch, provisionalRank = provisionalRank,
            // §16.2 convidados ainda não conectados acham aqui a própria chave.
            invited = members.values.filter { it.inviteKey != null && it.link == null }.mapNotNull { it.inviteKey },
        )
    }

    /**
     * Anúncio da mesa (§16.7): durante todo o jogo, para o reingresso pelo token; na lista ([TableAdvert.listed]) só antes
     * do início ou com vaga aberta ("jogo em andamento"). Depois do fim, só enquanto alguém que estava fora ainda não
     * recebeu o resultado (§16.11).
     */
    private suspend fun advertiseLocked(force: Boolean = false) {
        val outside = finished && !closed && finals.keys.any { members[it]?.link == null }
        _resultsPending.value = outside
        val want = !closed && (!finished || outside)
        if (want) {
            if (!advertising || force || started || seatingRoom != null) transport.advertise(advertLocked())
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
    fun membersForSuccessor(): List<MemberRecord> = memberSnapshot

    /** Próximo id público (o sucessor continua a numeração). */
    fun nextPlayerId(): Int = nextIdSnapshot

    /** Foto dos humanos e tokens, refeita sob a trava a cada mudança da sala (lida sem trava pelo jogo). */
    @Volatile
    private var memberSnapshot: List<MemberRecord> = emptyList()

    @Volatile
    private var nextIdSnapshot: Int = 0

    /**
     * §16.4, §16.10 envia o estado completo ao jogador [playerId] **só se ele ainda é sucessor** (conferido sob a trava:
     * quem acabou de ser rebaixado e recebeu [SuccessorDrop] nunca recebe outro estado). Devolve se enviou.
     */
    suspend fun sendSuccessorState(playerId: Int, state: SuccessorState): Boolean = mutex.withLock {
        if (playerId !in successorIds) return@withLock false
        val link = members[playerId]?.link ?: return@withLock false
        link.sendLocked(state)
        true
    }

    /**
     * §16.10 passagem do cargo: o estado completo vai **só** ao escolhido [playerId] (mesmo que não seja sucessor), e a
     * nenhum outro convidado.
     */
    suspend fun sendHandOverState(playerId: Int, state: SuccessorState) {
        mutex.withLock { members[playerId]?.takeIf { it.info.id != hostId }?.link?.sendLocked(state) }
    }

    /** §16.2 nome em branco vira "Jogador N" (N = id do jogador na mesa). */
    private fun nameOrDefault(name: String, id: Int): String = name.trim().ifBlank { "Jogador $id" }

    companion object {
        /** Mesa antiga: o anfitrião ocupa a posição 1 (na mesa montada ele escolhe, §16.2). */
        val HOST_SEAT = Seat(0)

        /** Folga da rede depois do prazo do convite antes de o anfitrião dar o convite como aceito (§16.2, sem resposta). */
        const val INVITE_REPLY_GRACE_MILLIS: Long = 2_000

        /** Intervalo da verificação da entrega do aviso de Wi-Fi desligado (técnico, não é regra). */
        const val OFFLINE_POLL_MILLIS: Long = 100

        /**
         * §16.7 "Wi-Fi ligado no meio de um jogo só com robôs": o jogador vira anfitrião da mesa (o jogo não muda) e pode
         * liberar assentos de robô para entrada ([openSeat]). A mesa fica fora da lista até abrir um assento.
         */
        fun forRobotGame(
            transport: LocalTransport,
            tableId: String,
            rules: TableRules,
            hostName: String,
            hostSeat: Seat,
            robots: Map<Int, String>,
            codec: ProtocolCodec = ProtocolCodec(),
            newToken: () -> String = ::randomToken,
            hostPhoto: String? = null,
        ): HostSession {
            val host = PlayerInfo(id = 0, name = hostName, joinOrder = 0, seat = hostSeat, isHost = true, confirmed = true)
            return HostSession(
                transport, tableId, rules, hostName, codec, newToken,
                resumed = ResumedTable(hostId = 0, members = listOf(MemberRecord(host, newToken())), nextPlayerId = 1, epoch = 0),
                hostPhoto = hostPhoto,
            ).also { it.robots = robots }
        }

        /** §16.15 provisório sem outro humano que possa confirmar. */
        const val PROVISIONAL_SOLO_MILLIS: Long = 15_000

        /** §16.15 provisório com outros humanos: "Assumir mesmo assim" (provisório, revisar com produto). */
        const val PROVISIONAL_FORCE_MILLIS: Long = 30_000

        /** §16.11, §16.15 mesa aberta depois do fim do jogo para quem estava fora (provisório, revisar com produto). */
        const val FINISHED_OPEN_MILLIS: Long = 5 * 60_000

        /** Folga antes de encerrar a entrega: quem cair logo depois do fim ainda volta (reenvio de §16.15: 1 s, até 5 vezes). */
        const val FINISHED_GRACE_MILLIS: Long = 5_000

        /** §16.5 eventos simultâneos: o assento já tem dono. */
        const val SEAT_TAKEN_MESSAGE: String = "A vaga foi preenchida."
    }
}

private val secureRandom = SecureRandom()

/** Token aleatório de 128 bits (§16.5); nunca exibido nem registrado. */
fun randomToken(): String = ByteArray(16).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }
