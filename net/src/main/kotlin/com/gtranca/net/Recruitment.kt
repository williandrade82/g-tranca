package com.gtranca.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** §16.2 aviso ao recrutador: o chamado recusou, saiu da mesa ou sumiu ([reason] `null` = "Sair da mesa"). */
data class RecruitNotice(val key: String, val name: String, val refused: Boolean, val reason: RefusalReason?)

/** Jogador chamado (`accepted = false`) ou recrutado deste recrutador, na ordem dos lugares. */
data class Recruit(val key: String, val name: String, val accepted: Boolean)

/** Chamados e recrutados do layout, por lugar. */
fun TableLayout.recruits(): List<Recruit> = slots.mapNotNull {
    when (it) {
        is Slot.Called -> Recruit(it.key, it.name, accepted = false)
        is Slot.Recruited -> Recruit(it.key, it.name, accepted = true)
        else -> null
    }
}

/** §16.2 situação deste aparelho como chamado/recrutado por outro recrutador. */
sealed interface RecruitStatus {
    /** Livre: pode ser chamado. */
    data object Free : RecruitStatus

    /**
     * Convite imediato recebido do recrutador [hostKey] (sem prazo): Aceitar ou Recusar. [table] traz aposta, tipo, regras e
     * lugares; [you] é o lugar do chamado (a posição vem de `table.layout.relationOf(you)`). Atualizado a cada [RecruitSync].
     */
    data class Invited(
        val hostKey: String,
        val hostName: String,
        val table: RecruitTable,
        val you: Int,
        val version: Long = 0,
        /**
         * §16.15 limite para responder, no relógio local ([Recruitment.nowMillis]) de quem recebeu o convite: o aparelho desenha
         * a contagem com [remainingMillis]. Sem resposta até ele, o convite vale como recusa.
         */
        val deadlineMillis: Long = 0,
    ) : RecruitStatus {
        /** Quanto falta para responder agora ([now] = [Recruitment.nowMillis]). */
        fun remainingMillis(now: Long): Long = (deadlineMillis - now).coerceAtLeast(0)
    }

    /** Aceitou: vê a mesa do recrutador ([table], com os lugares) ao vivo e só para leitura. */
    data class Joined(val hostKey: String, val hostName: String, val table: RecruitTable, val you: Int, val version: Long = 0) : RecruitStatus
}

/** §16.2 acontecimentos do lado do chamado/recrutado (avisos e o início). */
sealed interface RecruitEvent {
    /** "<recrutador> tirou você da mesa". */
    data class Removed(val hostName: String) : RecruitEvent

    /** O convite foi recusado sozinho (§14.6, §16.2): saldo menor que a aposta. A tela avisa "Saldo insuficiente para esta mesa". */
    data class Refused(val hostName: String, val reason: RefusalReason) : RecruitEvent

    /** "Mesa encerrada" (o recrutador voltou, saiu, desligou o Wi-Fi do jogo ou a conexão não voltou a tempo). */
    data class Closed(val hostName: String) : RecruitEvent

    /** O recrutador tocou em Começar Partida: mostrar "Prepare-se!" por no mínimo [prepareMillis] e entrar na mesa [tableId]. */
    data class Start(val tableId: String, val inviteKey: String, val prepareMillis: Long = PREPARE_MILLIS) : RecruitEvent
}

/**
 * §16.2 recrutamento (chamar jogadores próximos para os lugares da mesa). Máquina de estados e regras de sincronização:
 * `docs/recrutamento.md`.
 *
 * - **Estado na presença:** [setFree] (tela *Iniciar novo jogo*, pode ser chamado) e [openTable] (tela *Seleção de jogadores*,
 *   recrutando). Quem está livre abre a conexão de **espera** com um recrutador; recrutadores não se conectam entre si.
 * - **Recrutador:** [call], [placeRobot], [removeSeat], [move], [fillWithRobots] mexem em [layout]; o convite sai pela
 *   conexão na hora; o estado completo e numerado ([RecruitSync]) vai a chamados e recrutados 0,3 s depois da última
 *   mudança; [start] devolve o [TablePlan] para a mesa do jogo.
 * - **Chamado/recrutado:** [status], [events], [accept], [refuse], [leave].
 * - Enquanto [run] roda, é o único leitor dos eventos do transporte (as fotos vão a [photos]).
 */
class Recruitment(
    private val transport: LocalTransport,
    private val scope: CoroutineScope,
    val myKey: String,
    myName: String,
    private val photos: PresencePhotos? = null,
    /** §14.6 o saldo cobre a aposta? (pode esperar a leitura da carteira). */
    private val canAfford: suspend (stake: Int) -> Boolean = { true },
    private val codec: ProtocolCodec = ProtocolCodec(),
    /** Diagnóstico (só no build debug): eventos e versões, sem nomes, fotos nem bytes. */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var myName: String = myName

    /** Todas as conexões de recrutamento (deste aparelho como recrutador ou como chamado). */
    private val links = ConcurrentHashMap<EndpointId, ReliableLink>()
    private val peerKey = mutableMapOf<EndpointId, String>()
    private val photoSent = mutableSetOf<EndpointId>()

    private fun k(key: String) = key.take(2)

    /** Relógio monotônico local (ms) em que os prazos ([RecruitStatus.Invited.deadlineMillis]) são medidos. */
    fun nowMillis(): Long = clock()

    private val lastHeard = mutableMapOf<EndpointId, Long>()
    private var heartbeatJob: Job? = null
    private var inviteTimer: Job? = null

    /** §16.15 liga a mensagem de teste das conexões abertas (só roda enquanto há conexão). */
    private fun ensureHeartbeatLocked() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MILLIS)
                if (mutex.withLock { heartbeatTickLocked() }) break
            }
        }
    }

    /** Devolve se acabou (sem conexões). Conexão muda por mais de [DEAD_AFTER_MILLIS] é dada como caída. */
    private suspend fun heartbeatTickLocked(): Boolean {
        if (links.isEmpty()) {
            heartbeatJob = null
            return true
        }
        val now = clock()
        links.entries.toList().forEach { (ep, link) ->
            if (now - (lastHeard[ep] ?: now) > DEAD_AFTER_MILLIS) {
                log("heartbeat dead ${k(peerKey[ep] ?: "??")} silent=${now - (lastHeard[ep] ?: now)} ms")
                val key = peerKey[ep]
                links.remove(ep)
                lastHeard.remove(ep)
                link.close()
                key?.let { transport.forgetPresence(it) }
                lostLocked(ep, link)
                scope.launch { runCatching { transport.disconnect(ep) } }
            } else {
                link.controlLocked(RecruitPing)
            }
        }
        return false
    }

    // ---- presença -----------------------------------------------------------------------------------------------
    private var freeFlag = false
    private var presences: List<PresenceAdvert> = emptyList()

    private val _recruiters = MutableStateFlow<List<PresenceAdvert>>(emptyList())

    /** §16.2 outros jogadores **recrutando** (para a lista da tela *Iniciar novo jogo*; só informativa). */
    val recruiters: StateFlow<List<PresenceAdvert>> = _recruiters.asStateFlow()

    private val _free = MutableStateFlow<List<PresenceAdvert>>(emptyList())

    /**
     * §16.2 jogadores próximos **livres** (para a *Seleção de jogadores*); `recruitedBy` diferente de nulo e de [myKey] =
     * "Recrutado" por outro (não selecionável).
     */
    val free: StateFlow<List<PresenceAdvert>> = _free.asStateFlow()

    // ---- recrutador ---------------------------------------------------------------------------------------------
    private class Remote(val key: String, var name: String, val calledAt: Long) {
        var link: ReliableLink? = null
        var absentJob: Job? = null
        var graceJob: Job? = null
    }

    private val remotes = linkedMapOf<String, Remote>()
    private var rules: TableRules? = null
    private var hostDisplayName: String = ""
    private var layoutState: TableLayout? = null
    private var version = 0L
    private var debounceJob: Job? = null

    private val _layout = MutableStateFlow<TableLayout?>(null)

    /** §16.2 os lugares da mesa (`null` = a *Seleção de jogadores* não está aberta). */
    val layout: StateFlow<TableLayout?> = _layout.asStateFlow()

    private val _notices = MutableSharedFlow<RecruitNotice>(extraBufferCapacity = 64)

    /** §16.2 recusas, saídas e quedas dos chamados/recrutados (com o motivo). */
    val notices: SharedFlow<RecruitNotice> = _notices.asSharedFlow()

    // ---- chamado/recrutado --------------------------------------------------------------------------------------
    private val _status = MutableStateFlow<RecruitStatus>(RecruitStatus.Free)

    /** §16.2 situação deste aparelho como chamado/recrutado. */
    val status: StateFlow<RecruitStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RecruitEvent>(extraBufferCapacity = 64)

    /** §16.2 avisos ao chamado/recrutado e o início. */
    val events: SharedFlow<RecruitEvent> = _events.asSharedFlow()

    /** Conexão que este aparelho abriu a um recrutador (espera, convite ou reingresso). */
    private var outLink: ReliableLink? = null

    /** Conexão que trouxe o convite: vale para aceite, lugares, saída e início. */
    private var hostLink: ReliableLink? = null
    private var hostKey: String? = null

    @Volatile
    private var closed = false

    /** Recrutado sem conexão e recusado no reingresso: o início se perdeu; espera a mesa aparecer. */
    private var awaitingStart = false
    private var appliedVersion = 0L
    private var contacting: Job? = null
    private var rejoinJob: Job? = null

    private fun recruiting() = layoutState != null
    private fun isFree() = freeFlag && !recruiting()

    /** O endpoint é uma conexão de recrutamento (a troca de fotos não deve tocá-lo). */
    fun ownsEndpoint(endpoint: EndpointId): Boolean = links.containsKey(endpoint)

    /** Anuncia a presença e atende o recrutamento até ser cancelado. */
    suspend fun run() = coroutineScope {
        mutex.withLock { advertiseLocked() }
        launch { transport.discoverPresence().collect { list -> mutex.withLock { presencesLocked(list) } } }
        launch { transport.discover().collect { list -> mutex.withLock { tablesLocked(list) } } }
        transport.events.collect { handle(it) }
    }

    private fun transition(to: RecruitStatus) {
        val from = _status.value
        if (from::class == to::class && from !is RecruitStatus.Invited && from !is RecruitStatus.Joined) return
        _status.value = to
        if (to == RecruitStatus.Free) awaitingStart = false
        if (from::class != to::class) log("rec state ${from::class.simpleName}->${to::class.simpleName}")
        if (to !is RecruitStatus.Invited) {
            inviteTimer?.cancel()
            inviteTimer = null
        }
    }

    private fun pinned(): Set<String> = remotes.keys + listOfNotNull(hostKey)

    private suspend fun advertiseLocked() {
        transport.setActiveMode(!closed && (freeFlag || recruiting()))
        val recruitedBy = if (_status.value == RecruitStatus.Free) null else hostKey
        val inviting = remotes.values.filter { it.link == null }.map { it.key }.take(AdvertCodecLimits.MAX_INVITED)
        runCatching {
            transport.pinPresence(pinned())
            transport.advertisePresence(
                PresenceAdvert(myKey, myName, recruitedBy = recruitedBy, inviting = inviting, hosting = recruiting(), free = isFree()),
            )
        }
    }

    /** §16.2, §16.15 "atualizar": rajada de busca de 5 s a cada 1 s (os toques repetidos durante a rajada são ignorados). */
    fun refreshNow() {
        log("rec refreshNow")
        transport.burst()
    }

    /** O nome do perfil mudou: o anúncio acompanha (o estado do recrutamento não muda). */
    suspend fun rename(name: String) {
        mutex.withLock {
            if (myName == name) return
            myName = name
            advertiseLocked()
        }
    }

    /** §16.2 a tela *Iniciar novo jogo* está aberta (`true`) ou fechou: só quem está livre pode ser chamado. */
    suspend fun setFree(on: Boolean) {
        mutex.withLock {
            if (freeFlag == on) return
            freeFlag = on
            log("rec free=$on")
            advertiseLocked()
            evaluateContactLocked()
        }
    }

    // ---- API do recrutador --------------------------------------------------------------------------------------

    /**
     * §16.2 abre a *Seleção de jogadores*: os lugares começam VAGO ([TableLayout.initial]) e a presença passa a dizer
     * "recrutando". [rules] traz modo, aposta, dificuldade, regras, alvo e o título da regra.
     */
    suspend fun openTable(rules: TableRules, hostName: String) {
        mutex.withLock {
            if (_status.value != RecruitStatus.Free) return
            this.rules = rules
            hostDisplayName = hostName
            version = 0
            layoutState = TableLayout.initial(rules.mode)
            _layout.value = layoutState
            log("rec table open mode=${rules.mode}")
            outLink?.let { dropLinkLocked(it) }
            advertiseLocked()
        }
    }

    /** A mesa como o jogador [key] a vê: só a própria chave de presença nos lugares (§16.4). */
    private fun tableFor(key: String): RecruitTable? = layoutState?.let { RecruitTable(hostDisplayName, rules!!, it.redactedFor(key)) }

    private suspend fun changedLocked(immediate: Boolean = false) {
        val l = layoutState ?: return
        _layout.value = l
        version++
        advertiseLocked()
        if (immediate) {
            debounceJob?.cancel()
            syncAllLocked()
        } else {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(SYNC_DEBOUNCE_MILLIS)
                mutex.withLock { syncAllLocked() }
            }
        }
    }

    private suspend fun syncAllLocked() {
        remotes.values.forEach { r ->
            val link = r.link ?: return@forEach
            val table = tableFor(r.key) ?: return@forEach
            val seat = layoutState?.seatOf(r.key) ?: return@forEach
            link.sendLocked(RecruitSync(version, table, seat))
            log("rec seats v=$version send to=${k(r.key)}")
        }
    }

    /**
     * §16.2 chama o jogador [presence] para o lugar [seat] (VAGO): o convite chega na hora, pela conexão de espera se ela
     * existe (senão pela reserva do anúncio). `false` se o lugar não está VAGO, o jogador já está em outro lugar ou foi
     * recrutado por outro recrutador.
     */
    suspend fun call(seat: Int, presence: PresenceAdvert): Boolean = mutex.withLock {
        val l = layoutState ?: return@withLock false
        if (presence.key == myKey || remotes.size >= 3) return@withLock false
        if (presence.recruitedBy != null && presence.recruitedBy != myKey) return@withLock false
        val next = l.call(seat, presence.key, presence.name) ?: return@withLock false
        layoutState = next
        val r = Remote(presence.key, presence.name, clock())
        remotes[presence.key] = r
        // §16.2, §16.15 o chamado tem 25 s (+ folga): sem resposta, o lugar volta a VAGO e o jogador pode ser chamado de novo.
        scope.launch {
            delay(INVITE_TIMEOUT_MILLIS + INVITE_HOST_GRACE_MILLIS)
            mutex.withLock {
                if (remotes[presence.key] === r && layoutState?.slots?.any { it is Slot.Called && it.key == presence.key } == true) {
                    r.link?.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
                    vacateLocked(presence.key, RecruitNotice(presence.key, r.name, refused = true, reason = RefusalReason.NO_RESPONSE), "no response")
                }
            }
        }
        val link = links.entries.firstOrNull { peerKey[it.key] == presence.key }?.value
        if (link != null) {
            r.link = link
            version++
            _layout.value = next
            link.sendLocked(RecruitInvite(version, tableFor(presence.key)!!, seat))
            log("rec call ${k(presence.key)} invite sent via link in ${clock() - r.calledAt} ms")
            debounceJob?.cancel()
            syncOthersLocked(except = presence.key)
            advertiseLocked()
        } else {
            log("rec call ${k(presence.key)} no link: advert fallback")
            changedLocked(immediate = true)
        }
        true
    }

    private suspend fun syncOthersLocked(except: String) {
        remotes.values.filter { it.key != except }.forEach { r ->
            val table = tableFor(r.key) ?: return@forEach
            val seat = layoutState?.seatOf(r.key) ?: return@forEach
            r.link?.sendLocked(RecruitSync(version, table, seat))
        }
    }

    /** §14.5, §16.2 põe o robô [persona] no lugar [seat] (VAGO). */
    suspend fun placeRobot(seat: Int, persona: String): Boolean = mutate { it.placeRobot(seat, persona) }

    /** §16.2 "Completar com robôs": sorteia (semente do recrutador) robôs ainda não usados nos lugares VAGO. */
    suspend fun fillWithRobots(pool: List<String>, seed: Long) {
        mutate { it.fillWithRobots(pool, Random(seed)) }
    }

    /**
     * §16.2 remove quem está no lugar [seat] (nunca o do recrutador): robô volta à lista; Chamado tem o convite cancelado
     * (sem aviso); Recrutado recebe "tirou você da mesa".
     */
    suspend fun removeSeat(seat: Int): Boolean = mutex.withLock {
        val l = layoutState ?: return@withLock false
        val key = l.slots.getOrNull(seat)?.remoteKey()
        val next = l.remove(seat) ?: return@withLock false
        layoutState = next
        key?.let { endRemoteLocked(it, if (l.slots[seat] is Slot.Recruited) RecruitEndReason.REMOVED else RecruitEndReason.CANCELLED) }
        log("rec remove seat=$seat")
        changedLocked(immediate = true)
        true
    }

    /** §16.2 só o recrutador move gente: [from] -> [to] (ocupado troca, VAGO move; o lugar do recrutador não se move). */
    suspend fun move(from: Int, to: Int): Boolean = mutate(immediate = false) { it.move(from, to) }

    private suspend fun mutate(immediate: Boolean = false, change: (TableLayout) -> TableLayout?): Boolean = mutex.withLock {
        val l = layoutState ?: return@withLock false
        val next = change(l) ?: return@withLock false
        if (next == l) return@withLock false
        layoutState = next
        changedLocked(immediate)
        true
    }

    private suspend fun endRemoteLocked(key: String, reason: RecruitEndReason) {
        val r = remotes.remove(key) ?: return
        r.absentJob?.cancel()
        r.graceJob?.cancel()
        r.link?.sendLocked(RecruitEnd(reason))
    }

    /**
     * §16.2 Começar Partida: exige [TableLayout.canStart]. Os recrutados recebem [RecruitStart] (mostrar "Prepare-se!" por
     * no mínimo [PREPARE_MILLIS] e entrar na mesa [tableId] com a própria chave); a presença volta a "livre". Devolve o
     * plano de lugares para a mesa do jogo, ou `null` se ainda não dá.
     */
    suspend fun start(tableId: String, personaPool: List<String>, seed: Long): TablePlan? {
        val (plan, all) = mutex.withLock {
            val l = layoutState ?: return null
            val r = rules ?: return null
            if (!l.canStart) return null
            debounceJob?.cancel()
            remotes.values.forEach { it.link?.sendLocked(RecruitStart(tableId, PREPARE_MILLIS)) }
            log("rec start prepare=$PREPARE_MILLIS remotes=${remotes.size}")
            remotes.values.forEach { it.absentJob?.cancel(); it.graceJob?.cancel() }
            val plan = TablePlan(l, personaPool, seed)
            remotes.clear()
            layoutState = null
            _layout.value = null
            advertiseLocked()
            plan to links.values.toList()
        }
        closeAfterConfirm(all, START_CONFIRM_MILLIS)
        return plan
    }

    /** §16.2 o recrutador voltou à tela 1, saiu ou desligou o Wi-Fi: chamados têm o convite cancelado; recrutados, "Mesa encerrada". */
    suspend fun closeHosting() {
        val all = mutex.withLock {
            if (layoutState == null && remotes.isEmpty() && links.isEmpty()) return
            debounceJob?.cancel()
            remotes.values.forEach { r ->
                val recruited = layoutState?.slots?.any { it is Slot.Recruited && it.key == r.key } == true
                r.link?.sendLocked(RecruitEnd(if (recruited) RecruitEndReason.CLOSED else RecruitEndReason.CANCELLED))
                r.absentJob?.cancel()
                r.graceJob?.cancel()
            }
            remotes.clear()
            layoutState = null
            _layout.value = null
            log("rec table closed")
            advertiseLocked()
            links.values.filter { it !== hostLink }
        }
        closeAfterConfirm(all, CLOSE_CONFIRM_MILLIS)
    }

    /** Larga tudo (Wi-Fi do jogo desligado ou o aparelho entrou numa mesa/jogo): encerra o recrutamento nos dois papéis. */
    suspend fun shutdown() {
        mutex.withLock {
            closed = true
            // §16.2 aviso explícito de saída: quem recebe me tira das listas na hora (as confirmações esperam até 0,5 s).
            links.values.forEach { it.sendLocked(RecruitBye(myKey)) }
            advertiseLocked()
        }
        closeHosting()
        val rest = mutex.withLock {
            contacting?.cancel()
            rejoinJob?.cancel()
            val l = hostLink
            if (l != null && _status.value is RecruitStatus.Joined) l.sendLocked(RecruitLeave(null))
            val all = links.values.toList()
            hostLink = null
            outLink = null
            hostKey = null
            _status.value = RecruitStatus.Free
            all
        }
        if (rest.isNotEmpty()) closeAfterConfirm(rest, CLOSE_CONFIRM_MILLIS)
    }

    // ---- API do chamado/recrutado -------------------------------------------------------------------------------

    /** §16.2 aceita o convite: passa a ver a mesa do recrutador. Devolve se havia convite e conexão. */
    suspend fun accept(): Boolean = mutex.withLock {
        val invited = _status.value as? RecruitStatus.Invited ?: return@withLock false
        val link = hostLink ?: return@withLock false
        link.sendLocked(RecruitReply(myKey, true))
        log("rec accept sent")
        transition(RecruitStatus.Joined(invited.hostKey, invited.hostName, invited.table, invited.you, invited.version))
        advertiseLocked()
        true
    }

    /** §16.2 recusa o convite (volta a ficar disponível para todos). */
    suspend fun refuse() {
        mutex.withLock { refuseLocked(RefusalReason.INVITE_REFUSED) }
    }

    private suspend fun refuseLocked(reason: RefusalReason) {
        val invited = _status.value as? RecruitStatus.Invited ?: return
        if (reason != RefusalReason.INVITE_REFUSED) _events.tryEmit(RecruitEvent.Refused(invited.hostName, reason))
        hostLink?.sendLocked(RecruitReply(myKey, false, reason))
        transition(RecruitStatus.Free)
        advertiseLocked()
    }

    /** §16.2 "Sair da mesa": o lugar volta a VAGO e este aparelho fica livre. */
    suspend fun leave() {
        mutex.withLock {
            if (_status.value !is RecruitStatus.Joined) return
            hostLink?.sendLocked(RecruitLeave(null))
            transition(RecruitStatus.Free)
            advertiseLocked()
        }
    }

    // ---- descoberta ---------------------------------------------------------------------------------------------

    /** Devo me ligar a [host]? Só quem está livre, e a um recrutador (ou a quem me lista em `inviting`). */
    private fun wants(host: PresenceAdvert): Boolean = !closed && !host.leaving && host.key != myKey && !recruiting() &&
        (myKey in host.inviting || (host.hosting && freeFlag))

    private suspend fun presencesLocked(list: List<PresenceAdvert>) {
        presences = list.associateBy { it.key }.values.toList()
        _recruiters.value = presences.filter { it.hosting && !it.leaving && it.key != myKey }
        _free.value = presences.filter { it.free && !it.hosting && !it.leaving && it.key != myKey }
        val keys = presences.map { it.key }.toSet()
        // Recrutador: chamado sem conexão que sumiu da busca por 10 s volta a VAGO ("fora do alcance").
        remotes.values.forEach { r ->
            val called = layoutState?.slots?.any { it is Slot.Called && it.key == r.key } == true
            if (!called || r.key in keys || r.link != null) {
                r.absentJob?.cancel()
                r.absentJob = null
            } else if (r.absentJob == null) {
                r.absentJob = scope.launch {
                    delay(PENDING_ABSENT_MILLIS)
                    mutex.withLock {
                        if (remotes[r.key] === r && r.link == null && presences.none { it.key == r.key }) {
                            vacateLocked(r.key, RecruitNotice(r.key, r.name, refused = false, reason = RefusalReason.OUT_OF_RANGE), "out of range")
                        }
                    }
                }
            }
        }
        evaluateContactLocked()
    }

    /** O jogador [key] deixa o lugar (recusa, saída, queda): o lugar volta a VAGO e o recrutador é avisado. */
    private suspend fun vacateLocked(key: String, notice: RecruitNotice?, why: String) {
        val l = layoutState ?: return
        val next = l.vacate(key) ?: return
        val r = remotes.remove(key)
        r?.absentJob?.cancel()
        r?.graceJob?.cancel()
        layoutState = next
        log("rec vacate ${k(key)}: $why")
        notice?.let { _notices.tryEmit(it) }
        changedLocked(immediate = true)
    }

    /** Chamado/recrutado: conecta ao recrutador; larga a espera de quem parou de recrutar. */
    private suspend fun evaluateContactLocked() {
        if (closed || recruiting()) return
        if (!freeFlag && _status.value == RecruitStatus.Free) {
            outLink?.let { dropLinkLocked(it) }
            return
        }
        val status = _status.value
        val out = outLink
        if (out != null) {
            val peer = peerKey[out.endpoint]
            val host = presences.firstOrNull { it.key == peer }
            if (status == RecruitStatus.Free && out !== hostLink && host != null && !wants(host)) {
                log("rec standby dropped: host not recruiting")
                dropLinkLocked(out)
            } else if (status == RecruitStatus.Free && out !== hostLink) {
                val inviter = presences.firstOrNull { myKey in it.inviting && it.key != peer && !it.leaving }
                if (inviter != null) {
                    log("rec standby switch to ${k(inviter.key)}")
                    dropLinkLocked(out)
                }
            }
            if (outLink != null) return
        }
        if (contacting != null) return
        val rejoin = status is RecruitStatus.Joined
        if (rejoin && awaitingStart) return
        val target = if (rejoin) {
            if (hostLink != null) return
            hostKey?.let { id -> presences.firstOrNull { it.key == id } }
        } else {
            if (hostLink != null) return
            presences.firstOrNull { myKey in it.inviting && !it.leaving } ?: presences.filter { wants(it) }.minByOrNull { it.key }
        } ?: return
        startContactLocked(target.key, rejoin)
    }

    private fun startContactLocked(host: String, rejoin: Boolean) {
        contacting = scope.launch {
            var attempt = 0
            while (true) {
                val presence = mutex.withLock {
                    presences.firstOrNull { it.key == host }
                        ?.takeIf { !closed && outLink == null && !recruiting() && (rejoin || wants(it)) }
                }
                if (presence == null) break
                val endpoint = runCatching { transport.connectPresence(presence) }.getOrElse { log("rec connect failed ${it.message?.take(40)}"); null }
                if (endpoint != null) {
                    mutex.withLock {
                        val link = links[endpoint] ?: ReliableLink(endpoint, transport, codec, scope, mutex).also { links[endpoint] = it }
                        lastHeard[endpoint] = clock()
                        ensureHeartbeatLocked()
                        outLink = link
                        peerKey[endpoint] = host
                        if (rejoin) {
                            hostLink = link
                            hostKey = host
                            rejoinJob?.cancel()
                        }
                        link.sendLocked(RecruitHello(myKey, myName, rejoin))
                        sendPhotoOnceLocked(link)
                        log("rec connected to ${k(host)} rejoin=$rejoin")
                    }
                    break
                }
                delay(CONTACT_BACKOFF_MILLIS[minOf(attempt++, CONTACT_BACKOFF_MILLIS.lastIndex)])
            }
            mutex.withLock { contacting = null }
        }
    }

    private suspend fun dropLinkLocked(l: ReliableLink) {
        if (outLink === l) outLink = null
        if (hostLink === l) {
            hostLink = null
            if (_status.value == RecruitStatus.Free) hostKey = null
        }
        links.remove(l.endpoint)
        peerKey.remove(l.endpoint)
        photoSent -= l.endpoint
        l.close()
        scope.launch { runCatching { transport.disconnect(l.endpoint) } }
    }

    /** O início se perdeu: a mesa do recrutador apareceu com a minha chave em `invited`. */
    private suspend fun tablesLocked(list: List<TableAdvert>) {
        if (_status.value !is RecruitStatus.Joined) return
        val table = list.firstOrNull { myKey in it.invited } ?: return
        log("rec start via table advert")
        startedLocked(table.tableId, PREPARE_MILLIS)
    }

    private suspend fun startedLocked(tableId: String, prepareMillis: Long) {
        rejoinJob?.cancel()
        transition(RecruitStatus.Free)
        _events.tryEmit(RecruitEvent.Start(tableId, myKey, prepareMillis))
        hostLink?.let { dropLinkLocked(it) }
    }

    // ---- eventos ------------------------------------------------------------------------------------------------

    private suspend fun handle(event: TransportEvent) {
        val forPhotos = mutex.withLock {
            when (event) {
                is TransportEvent.Connected -> false
                is TransportEvent.Disconnected -> {
                    val link = links.remove(event.endpoint)
                    if (link != null) {
                        link.close()
                        lostLocked(event.endpoint, link)
                        false
                    } else {
                        true
                    }
                }
                is TransportEvent.Received -> receiveLocked(event)
            }
        }
        if (forPhotos) photos?.handle(event)
    }

    /** Devolve se o evento é da troca de fotos. */
    private suspend fun receiveLocked(event: TransportEvent.Received): Boolean {
        val envelope = (codec.decode(event.bytes) as? Decoded.Ok)?.envelope ?: return false
        var link = links[event.endpoint]
        if (link == null) {
            if (envelope.p is PresencePhoto) return true
            if (envelope.p !is RecruitHello) return false
            link = ReliableLink(event.endpoint, transport, codec, scope, mutex).also { links[event.endpoint] = it }
            ensureHeartbeatLocked()
        }
        lastHeard[event.endpoint] = clock()
        link.receiveLocked(envelope).forEach { processLocked(link, it) }
        return false
    }

    private suspend fun sendPhotoOnceLocked(link: ReliableLink) {
        if (photoSent.add(link.endpoint)) link.sendLocked(photos?.mine() ?: PresencePhoto(myKey))
    }

    private suspend fun processLocked(link: ReliableLink, payload: Payload) {
        when (payload) {
            // ---- recrutador ----
            is RecruitHello -> helloLocked(link, payload)
            is RecruitReply -> {
                val l = layoutState ?: return
                val r = remotes[payload.key]
                if (r == null || l.slots.none { it is Slot.Called && it.key == payload.key }) {
                    if (payload.accepted) link.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
                    return
                }
                r.link = link
                if (payload.accepted) {
                    layoutState = l.accept(payload.key)
                    log("rec accepted ${k(payload.key)}")
                    changedLocked(immediate = true)
                } else {
                    log("rec refused ${k(payload.key)}")
                    vacateLocked(payload.key, RecruitNotice(payload.key, r.name, refused = true, reason = payload.reason ?: RefusalReason.INVITE_REFUSED), "refused")
                }
            }
            is RecruitLeave -> {
                val r = remotes.values.firstOrNull { it.link === link } ?: return
                vacateLocked(r.key, RecruitNotice(r.key, r.name, refused = false, reason = payload.reason), "left")
            }
            // ---- chamado/recrutado ----
            is RecruitInvite -> {
                if (_status.value != RecruitStatus.Free || recruiting()) return
                val host = peerKey[link.endpoint] ?: return
                hostLink = link
                hostKey = host
                if (!canAffordNow(payload.table.rules.stake)) {
                    log("rec invite auto-refused: balance")
                    transition(RecruitStatus.Invited(host, payload.table.hostName, payload.table, payload.you, payload.version))
                    refuseLocked(RefusalReason.INSUFFICIENT_BALANCE)
                    return
                }
                appliedVersion = payload.version
                val deadline = clock() + payload.timeoutMillis
                transition(RecruitStatus.Invited(host, payload.table.hostName, payload.table, payload.you, payload.version, deadline))
                // §16.15 sem resposta no prazo vale como recusa (nunca aceita sozinho).
                inviteTimer?.cancel()
                inviteTimer = scope.launch {
                    delay(payload.timeoutMillis)
                    mutex.withLock {
                        val s = _status.value
                        if (s is RecruitStatus.Invited && s.deadlineMillis == deadline) {
                            log("rec invite expired: no response")
                            refuseLocked(RefusalReason.NO_RESPONSE)
                        }
                    }
                }
                log("rec invite shown v=${payload.version} timeout=${payload.timeoutMillis}")
                advertiseLocked()
            }
            is RecruitSync -> {
                if (link !== hostLink) return
                if (payload.version <= appliedVersion) {
                    log("rec seats stale v=${payload.version} applied=$appliedVersion")
                    return
                }
                appliedVersion = payload.version
                when (val s = _status.value) {
                    is RecruitStatus.Invited -> _status.value = s.copy(table = payload.table, you = payload.you, version = payload.version)
                    is RecruitStatus.Joined -> _status.value = s.copy(table = payload.table, you = payload.you, version = payload.version)
                    RecruitStatus.Free -> return
                }
                log("rec seats v=${payload.version} apply")
            }
            is RecruitEnd -> {
                if (link !== hostLink) return
                val s = _status.value
                val name = (s as? RecruitStatus.Joined)?.hostName ?: (s as? RecruitStatus.Invited)?.hostName ?: ""
                if (s == RecruitStatus.Free) return
                if (s is RecruitStatus.Joined && payload.reason == RecruitEndReason.CANCELLED) {
                    // O recrutador já não tem este aceite (ex.: começou a partida e o início se perdeu): espera a mesa aparecer.
                    log("rec cancelled while joined: awaiting table")
                    awaitingStart = true
                    dropLinkLocked(link)
                    rejoinJob?.cancel()
                    rejoinJob = scope.launch {
                        delay(REJOIN_MILLIS)
                        mutex.withLock {
                            if (hostLink == null && _status.value is RecruitStatus.Joined) {
                                transition(RecruitStatus.Free)
                                _events.tryEmit(RecruitEvent.Closed(s.hostName))
                                advertiseLocked()
                            }
                        }
                    }
                    return
                }
                transition(RecruitStatus.Free)
                advertiseLocked()
                when (payload.reason) {
                    RecruitEndReason.REMOVED -> _events.tryEmit(RecruitEvent.Removed(name))
                    RecruitEndReason.CLOSED -> _events.tryEmit(RecruitEvent.Closed(name))
                    RecruitEndReason.CANCELLED -> Unit
                }
            }
            is RecruitStart -> {
                if (_status.value !is RecruitStatus.Joined || link !== hostLink) return
                startedLocked(payload.tableId, payload.prepareMillis)
            }
            // ---- os dois ----
            RecruitPing -> link.controlLocked(RecruitPong)
            RecruitPong -> Unit
            is RecruitBye -> {
                log("rec bye ${k(payload.key)}")
                transport.forgetPresence(payload.key)
                // Quem estava na minha mesa e saiu de propósito: o lugar volta a VAGO na hora.
                if (layoutState?.seatOf(payload.key) != null) {
                    val r = remotes[payload.key]
                    vacateLocked(payload.key, RecruitNotice(payload.key, r?.name.orEmpty(), refused = false, reason = null), "bye")
                }
            }
            is PresencePhoto -> {
                photos?.store(payload)
                sendPhotoOnceLocked(link)
            }
            else -> Unit
        }
    }

    /** §14.6 o saldo cobre a aposta? (a leitura da carteira é rápida; sem ela, o convite é aceito para a tela decidir). */
    private suspend fun canAffordNow(stake: Int): Boolean = runCatching { canAfford(stake) }.getOrDefault(true)

    /** Recrutador: um jogador próximo conectou (espera, convite pendente pela reserva ou reingresso). */
    private suspend fun helloLocked(link: ReliableLink, hello: RecruitHello) {
        val l = layoutState
        val inLayout = l?.seatOf(hello.key)
        if (inLayout == null && (l == null || peerKey.size >= MAX_STANDBY)) {
            link.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
            log("rec hello ${k(hello.key)} rejected recruiting=${l != null}")
            return
        }
        links.entries.filter { peerKey[it.key] == hello.key && it.value !== link }.forEach { (ep, old) ->
            old.close()
            links.remove(ep)
            peerKey.remove(ep)
            scope.launch { runCatching { transport.disconnect(ep) } }
        }
        peerKey[link.endpoint] = hello.key
        sendPhotoOnceLocked(link)
        if (inLayout == null) {
            log("rec standby ${k(hello.key)}")
            return
        }
        val r = remotes[hello.key] ?: return
        r.link = link
        r.graceJob?.cancel()
        r.graceJob = null
        r.name = hello.name.ifBlank { r.name }
        r.absentJob?.cancel()
        r.absentJob = null
        val table = tableFor(hello.key)!!
        if (l.slots[inLayout] is Slot.Called) {
            val remaining = (INVITE_TIMEOUT_MILLIS - (clock() - r.calledAt)).coerceAtLeast(0)
            link.sendLocked(RecruitInvite(version, table, inLayout, remaining))
            log("rec invite sent after hello in ${clock() - r.calledAt} ms")
        } else {
            version++
            link.sendLocked(RecruitSync(version, table, inLayout))
            log("rec rejoin ${k(hello.key)}")
        }
        advertiseLocked()
    }

    /** A conexão caiu: o recrutador dá o prazo de reingresso a quem aceitou; o chamado/recrutado tenta voltar. */
    private suspend fun lostLocked(endpoint: EndpointId, link: ReliableLink) {
        peerKey.remove(endpoint)
        photoSent -= endpoint
        remotes.values.firstOrNull { it.link === link }?.let { r ->
            r.link = null
            val recruited = layoutState?.slots?.any { it is Slot.Recruited && it.key == r.key } == true
            if (recruited) {
                log("rec recruited ${k(r.key)} link lost: grace")
                r.graceJob?.cancel()
                r.graceJob = scope.launch {
                    delay(REJOIN_MILLIS)
                    mutex.withLock {
                        if (remotes[r.key] === r && r.link == null) {
                            vacateLocked(r.key, RecruitNotice(r.key, r.name, refused = false, reason = RefusalReason.OUT_OF_RANGE), "link lost")
                        }
                    }
                }
            } else {
                log("rec called ${k(r.key)} link lost: advert fallback")
                advertiseLocked()
            }
        }
        if (link === outLink) outLink = null
        if (link === hostLink) {
            hostLink = null
            when (val s = _status.value) {
                is RecruitStatus.Joined -> {
                    log("rec host link lost: rejoin for $REJOIN_MILLIS ms")
                    rejoinJob?.cancel()
                    rejoinJob = scope.launch {
                        delay(REJOIN_MILLIS)
                        mutex.withLock {
                            if (hostLink == null && _status.value is RecruitStatus.Joined) {
                                transition(RecruitStatus.Free)
                                _events.tryEmit(RecruitEvent.Closed(s.hostName))
                                advertiseLocked()
                            }
                        }
                    }
                }
                is RecruitStatus.Invited -> {
                    transition(RecruitStatus.Free)
                    advertiseLocked()
                }
                RecruitStatus.Free -> hostKey = null
            }
            evaluateContactLocked()
        }
    }

    /** Espera a confirmação (até [maxMillis]) e desconecta. */
    private suspend fun closeAfterConfirm(sent: List<ReliableLink>, maxMillis: Long) {
        var waited = 0L
        while (waited < maxMillis && !mutex.withLock { sent.all { it.allConfirmed } }) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        mutex.withLock {
            sent.forEach { link ->
                link.close()
                links.remove(link.endpoint)
                peerKey.remove(link.endpoint)
                if (link === hostLink) hostLink = null
                if (link === outLink) outLink = null
            }
        }
        sent.forEach { runCatching { transport.disconnect(it.endpoint) } }
    }

    private object AdvertCodecLimits {
        const val MAX_INVITED: Int = 3
    }

    companion object {
        /** Conexões de espera aceitas além dos chamados (limite de rádio). */
        const val MAX_STANDBY: Int = 6

        /** §16.15 os lugares só vão aos chamados/recrutados 0,3 s depois da última mudança (convite e resposta não esperam). */
        const val SYNC_DEBOUNCE_MILLIS: Long = 300

        /** §16.15 reingresso de quem aceitou e perdeu a conexão. */
        const val REJOIN_MILLIS: Long = 8_000

        /** §16.15 chamado sem conexão que sumiu da busca. */
        const val PENDING_ABSENT_MILLIS: Long = 10_000

        /** §16.15 tentativas de conexão ao recrutador: 0,3 s, 1 s, 2 s, 4 s e, daí em diante, 8 s. */
        val CONTACT_BACKOFF_MILLIS: List<Long> = listOf(300, 1_000, 2_000, 4_000, 8_000)

        /** §16.15 confirmações antes de desconectar: no Começar Partida e ao fechar / desligar o Wi-Fi. */
        const val START_CONFIRM_MILLIS: Long = 2_000
        const val CLOSE_CONFIRM_MILLIS: Long = 500
        const val POLL_MILLIS: Long = 50

        /** §16.15 mensagem de teste das conexões abertas a cada 400 ms; sem resposta por 1 s a conexão é dada como caída. */
        const val HEARTBEAT_MILLIS: Long = 400
        const val DEAD_AFTER_MILLIS: Long = 1_000
    }
}
