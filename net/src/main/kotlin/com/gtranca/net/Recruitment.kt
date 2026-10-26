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

/** Â§16.2 aviso ao recrutador: o chamado recusou, saiu da mesa ou sumiu ([reason] `null` = "Sair da mesa"). */
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

/** Â§16.2 situaÃ§Ã£o deste aparelho como chamado/recrutado por outro recrutador. */
sealed interface RecruitStatus {
    /** Livre: pode ser chamado. */
    data object Free : RecruitStatus

    /**
     * Convite imediato recebido do recrutador [hostKey] (sem prazo): Aceitar ou Recusar. [table] traz aposta, tipo, regras e
     * lugares; [you] Ã© o lugar do chamado (a posiÃ§Ã£o vem de `table.layout.relationOf(you)`). Atualizado a cada [RecruitSync].
     */
    data class Invited(
        val hostKey: String,
        val hostName: String,
        val table: RecruitTable,
        val you: Int,
        val version: Long = 0,
        /**
         * Â§16.15 limite para responder, no relÃ³gio local ([Recruitment.nowMillis]) de quem recebeu o convite: o aparelho desenha
         * a contagem com [remainingMillis]. Sem resposta atÃ© ele, o convite vale como recusa.
         */
        val deadlineMillis: Long = 0,
    ) : RecruitStatus {
        /** Quanto falta para responder agora ([now] = [Recruitment.nowMillis]). */
        fun remainingMillis(now: Long): Long = (deadlineMillis - now).coerceAtLeast(0)
    }

    /** Aceitou: vÃª a mesa do recrutador ([table], com os lugares) ao vivo e sÃ³ para leitura. */
    data class Joined(val hostKey: String, val hostName: String, val table: RecruitTable, val you: Int, val version: Long = 0) : RecruitStatus
}

/** Â§16.2 acontecimentos do lado do chamado/recrutado (avisos e o inÃ­cio). */
sealed interface RecruitEvent {
    /** "<recrutador> tirou vocÃª da mesa". */
    data class Removed(val hostName: String) : RecruitEvent

    /** O convite foi recusado sozinho (Â§14.6, Â§16.2): saldo menor que a aposta. A tela avisa "Saldo insuficiente para esta mesa". */
    data class Refused(val hostName: String, val reason: RefusalReason) : RecruitEvent

    /** "Mesa encerrada" (o recrutador voltou, saiu, desligou o Wi-Fi do jogo ou a conexÃ£o nÃ£o voltou a tempo). */
    data class Closed(val hostName: String) : RecruitEvent

    /** O recrutador tocou em ComeÃ§ar Partida: mostrar "Prepare-se!" por no mÃ­nimo [prepareMillis] e entrar na mesa [tableId]. */
    data class Start(val tableId: String, val inviteKey: String, val prepareMillis: Long = PREPARE_MILLIS) : RecruitEvent
}

/**
 * Â§16.2 recrutamento (chamar jogadores prÃ³ximos para os lugares da mesa). MÃ¡quina de estados e regras de sincronizaÃ§Ã£o:
 * `docs/recrutamento.md`.
 *
 * - **Estado na presenÃ§a:** [setFree] (tela *Iniciar novo jogo*, pode ser chamado) e [openTable] (tela *SeleÃ§Ã£o de jogadores*,
 *   recrutando). Quem estÃ¡ livre abre a conexÃ£o de **espera** com um recrutador; recrutadores nÃ£o se conectam entre si.
 * - **Recrutador:** [call], [placeRobot], [removeSeat], [move], [fillWithRobots] mexem em [layout]; o convite sai pela
 *   conexÃ£o na hora; o estado completo e numerado ([RecruitSync]) vai a chamados e recrutados 0,3 s depois da Ãºltima
 *   mudanÃ§a; [start] devolve o [TablePlan] para a mesa do jogo.
 * - **Chamado/recrutado:** [status], [events], [accept], [refuse], [leave].
 * - Enquanto [run] roda, Ã© o Ãºnico leitor dos eventos do transporte (as fotos vÃ£o a [photos]).
 */
class Recruitment(
    private val transport: LocalTransport,
    private val scope: CoroutineScope,
    val myKey: String,
    myName: String,
    private val photos: PresencePhotos? = null,
    /** Â§14.6 o saldo cobre a aposta? (pode esperar a leitura da carteira). */
    private val canAfford: suspend (stake: Int) -> Boolean = { true },
    private val codec: ProtocolCodec = ProtocolCodec(),
    /** DiagnÃ³stico (sÃ³ no build debug): eventos e versÃµes, sem nomes, fotos nem bytes. */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var myName: String = myName

    /** Todas as conexÃµes de recrutamento (deste aparelho como recrutador ou como chamado). */
    private val links = ConcurrentHashMap<EndpointId, ReliableLink>()
    private val peerKey = mutableMapOf<EndpointId, String>()
    private val photoSent = mutableSetOf<EndpointId>()

    private fun k(key: String) = key.take(2)

    /** RelÃ³gio monotÃ´nico local (ms) em que os prazos ([RecruitStatus.Invited.deadlineMillis]) sÃ£o medidos. */
    fun nowMillis(): Long = clock()

    private val lastHeard = mutableMapOf<EndpointId, Long>()
    private var heartbeatJob: Job? = null
    private var inviteTimer: Job? = null

    /** Â§16.15 liga a mensagem de teste das conexÃµes abertas (sÃ³ roda enquanto hÃ¡ conexÃ£o). */
    private fun ensureHeartbeatLocked() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MILLIS)
                if (mutex.withLock { heartbeatTickLocked() }) break
            }
        }
    }

    /** Devolve se acabou (sem conexÃµes). ConexÃ£o muda por mais de [DEAD_AFTER_MILLIS] Ã© dada como caÃ­da. */
    private suspend fun heartbeatTickLocked(): Boolean {
        if (links.isEmpty()) {
            heartbeatJob = null
            return true
        }
        val now = clock()
        links.entries.toList().forEach { (ep, link) ->
            if (!startPending && now - (lastHeard[ep] ?: now) > DEAD_AFTER_MILLIS) {
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

    // ---- presenÃ§a -----------------------------------------------------------------------------------------------
    private var freeFlag = false
    private var presences: List<PresenceAdvert> = emptyList()

    private val _recruiters = MutableStateFlow<List<PresenceAdvert>>(emptyList())

    /** Â§16.2 outros jogadores **recrutando** (para a lista da tela *Iniciar novo jogo*; sÃ³ informativa). */
    val recruiters: StateFlow<List<PresenceAdvert>> = _recruiters.asStateFlow()

    private val _free = MutableStateFlow<List<PresenceAdvert>>(emptyList())

    /**
     * Â§16.2 jogadores prÃ³ximos **livres** (para a *SeleÃ§Ã£o de jogadores*); `recruitedBy` diferente de nulo e de [myKey] =
     * "Recrutado" por outro (nÃ£o selecionÃ¡vel).
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

    /** Â§16.2 os lugares da mesa (`null` = a *SeleÃ§Ã£o de jogadores* nÃ£o estÃ¡ aberta). */
    val layout: StateFlow<TableLayout?> = _layout.asStateFlow()

    private val _notices = MutableSharedFlow<RecruitNotice>(extraBufferCapacity = 64)

    /** Â§16.2 recusas, saÃ­das e quedas dos chamados/recrutados (com o motivo). */
    val notices: SharedFlow<RecruitNotice> = _notices.asSharedFlow()

    // ---- chamado/recrutado --------------------------------------------------------------------------------------
    private val _status = MutableStateFlow<RecruitStatus>(RecruitStatus.Free)

    /** Â§16.2 situaÃ§Ã£o deste aparelho como chamado/recrutado. */
    val status: StateFlow<RecruitStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RecruitEvent>(extraBufferCapacity = 64)

    /** Â§16.2 avisos ao chamado/recrutado e o inÃ­cio. */
    val events: SharedFlow<RecruitEvent> = _events.asSharedFlow()

    /** ConexÃ£o que este aparelho abriu a um recrutador (espera, convite ou reingresso). */
    private var outLink: ReliableLink? = null

    /** ConexÃ£o que trouxe o convite: vale para aceite, lugares, saÃ­da e inÃ­cio. */
    private var hostLink: ReliableLink? = null
    private var hostKey: String? = null

    @Volatile
    private var closed = false

    /** §16.15 o recrutador montou o plano e ainda não enviou o início: nada pode sair dizendo "o recrutador saiu" (Bye, Leave, fechar conexões). */
    @Volatile
    private var startPending = false

    /** Últimos anúncios de mesa vistos: o recrutado em reingresso procura neles a sua chave de convite. */
    private var lastTables: List<TableAdvert> = emptyList()

    /** §16.15 o início foi enviado ou recebido: o anúncio é da mesa do jogo e a presença não o toca mais. */
    @Volatile
    private var yielded = false

    /** Recrutado sem conexÃ£o e recusado no reingresso: o inÃ­cio se perdeu; espera a mesa aparecer. */
    private var awaitingStart = false
    private var appliedVersion = 0L
    private var contacting: Job? = null
    private var rejoinJob: Job? = null

    private fun recruiting() = layoutState != null
    private fun isFree() = freeFlag && !recruiting()

    /** O endpoint Ã© uma conexÃ£o de recrutamento (a troca de fotos nÃ£o deve tocÃ¡-lo). */
    fun ownsEndpoint(endpoint: EndpointId): Boolean = links.containsKey(endpoint)

    /** Anuncia a presenÃ§a e atende o recrutamento atÃ© ser cancelado. */
    suspend fun run() = coroutineScope {
        mutex.withLock { advertiseLocked() }
        launch { transport.discoverPresence().collect { list -> mutex.withLock { presencesLocked(list) } } }
        launch { transport.discover().collect { list -> mutex.withLock { tablesLocked(list) } } }
        transport.events.collect { handle(it) }
    }

    /** VersÃ£o do Ãºltimo convite que o usuÃ¡rio jÃ¡ respondeu (por recrutador): o reenvio dele nÃ£o reabre o modal. */
    private val answeredVersion = mutableMapOf<String, Long>()
    private var inviteLossJob: Job? = null

    private fun transition(to: RecruitStatus, reason: String = "") {
        val from = _status.value
        if (from::class == to::class && from !is RecruitStatus.Invited && from !is RecruitStatus.Joined) return
        _status.value = to
        if (to == RecruitStatus.Free) awaitingStart = false
        if (from::class != to::class) log("rec state ${from::class.simpleName}->${to::class.simpleName}${if (reason.isEmpty()) "" else " reason=$reason"}")
        if (to !is RecruitStatus.Invited) {
            inviteTimer?.cancel()
            inviteTimer = null
        }
    }

    private fun pinned(): Set<String> = remotes.keys + listOfNotNull(hostKey)

    private suspend fun advertiseLocked() {
        transport.setActiveMode(!closed && (freeFlag || recruiting()))
        // §16.2, §16.15 a mesa do jogo já tem o anúncio (ou está para ter): a presença nunca anuncia por cima dela.
        if (yielded) {
            log("rec presence advert skipped (table has priority)")
            return
        }
        val recruitedBy = if (_status.value == RecruitStatus.Free) null else hostKey
        val inviting = remotes.values.filter { it.link == null }.map { it.key }.take(AdvertCodecLimits.MAX_INVITED)
        runCatching {
            transport.pinPresence(pinned())
            transport.advertisePresence(
                PresenceAdvert(myKey, myName, recruitedBy = recruitedBy, inviting = inviting, hosting = recruiting(), free = isFree()),
            )
        }
    }

    /** Â§16.2, Â§16.15 "atualizar": rajada de busca de 5 s a cada 1 s (os toques repetidos durante a rajada sÃ£o ignorados). */
    fun refreshNow() {
        log("rec refreshNow")
        transport.burst()
    }

    /** O nome do perfil mudou: o anÃºncio acompanha (o estado do recrutamento nÃ£o muda). */
    suspend fun rename(name: String) {
        mutex.withLock {
            if (myName == name) return
            myName = name
            advertiseLocked()
        }
    }

    /** Â§16.2 a tela *Iniciar novo jogo* estÃ¡ aberta (`true`) ou fechou: sÃ³ quem estÃ¡ livre pode ser chamado. */
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
     * Â§16.2 abre a *SeleÃ§Ã£o de jogadores*: os lugares comeÃ§am VAGO ([TableLayout.initial]) e a presenÃ§a passa a dizer
     * "recrutando". [rules] traz modo, aposta, dificuldade, regras, alvo e o tÃ­tulo da regra.
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

    /** A mesa como o jogador [key] a vÃª: sÃ³ a prÃ³pria chave de presenÃ§a nos lugares (Â§16.4). */
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
     * Â§16.2 chama o jogador [presence] para o lugar [seat] (VAGO): o convite chega na hora, pela conexÃ£o de espera se ela
     * existe (senÃ£o pela reserva do anÃºncio). `false` se o lugar nÃ£o estÃ¡ VAGO, o jogador jÃ¡ estÃ¡ em outro lugar ou foi
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
        // Â§16.2, Â§16.15 o chamado tem 25 s (+ folga): sem resposta, o lugar volta a VAGO e o jogador pode ser chamado de novo.
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

    /** Â§14.5, Â§16.2 pÃµe o robÃ´ [persona] no lugar [seat] (VAGO). */
    suspend fun placeRobot(seat: Int, persona: String): Boolean = mutate { it.placeRobot(seat, persona) }

    /** Â§16.2 "Completar com robÃ´s": sorteia (semente do recrutador) robÃ´s ainda nÃ£o usados nos lugares VAGO. */
    suspend fun fillWithRobots(pool: List<String>, seed: Long) {
        mutate { it.fillWithRobots(pool, Random(seed)) }
    }

    /**
     * Â§16.2 remove quem estÃ¡ no lugar [seat] (nunca o do recrutador): robÃ´ volta Ã  lista; Chamado tem o convite cancelado
     * (sem aviso); Recrutado recebe "tirou vocÃª da mesa".
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

    /** Â§16.2 sÃ³ o recrutador move gente: [from] -> [to] (ocupado troca, VAGO move; o lugar do recrutador nÃ£o se move). */
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
     * Â§16.2 ComeÃ§ar Partida: exige [TableLayout.canStart]. Os recrutados recebem [RecruitStart] (mostrar "Prepare-se!" por
     * no mÃ­nimo [PREPARE_MILLIS] e entrar na mesa [tableId] com a prÃ³pria chave); a presenÃ§a volta a "livre". Devolve o
     * plano de lugares para a mesa do jogo, ou `null` se ainda nÃ£o dÃ¡.
     */
    suspend fun start(tableId: String, personaPool: List<String>, seed: Long): TablePlan? {
        val plan = plan(personaPool, seed) ?: return null
        sendStart(tableId, plan)
        return plan
    }

    /** §16.2 o plano de lugares da mesa do jogo, ou `null` se ainda não dá para começar. Não envia nada. */
    suspend fun plan(personaPool: List<String>, seed: Long): TablePlan? = mutex.withLock {
        val l = layoutState ?: return@withLock null
        if (rules == null || !l.canStart) return@withLock null
        // Daqui em diante o único passo válido é [sendStart]: encerrar a mesa de lugares agora avisaria os recrutados de "Mesa encerrada".
        yielded = true
        startPending = true
        log("rec start planned")
        TablePlan(l, personaPool, seed)
    }

    /**
     * §16.2, §16.15 envia o início aos recrutados. Quem chama já deixou a mesa do jogo **no ar** (o anúncio da mesa vem
     * antes) e largou a leitura dos eventos. A partir daqui a presença não anuncia mais por cima da mesa
     * ([advertiseLocked]).
     */
    suspend fun sendStart(tableId: String, plan: TablePlan) {
        val all = mutex.withLock {
            yielded = true
            debounceJob?.cancel()
            remotes.values.forEach { it.link?.sendLocked(RecruitStart(tableId, PREPARE_MILLIS)) }
            // Quem estava só em espera (não é recrutado) fica sabendo que este recrutador saiu; os recrutados só recebem o início.
            val recruitedLinks = remotes.values.mapNotNull { it.link }.toSet()
            links.values.filter { it !in recruitedLinks }.forEach { it.sendLocked(RecruitBye(myKey)) }
            startPending = false
            log("rec start sent prepare=$PREPARE_MILLIS remotes=${remotes.size}")
            remotes.values.forEach { it.absentJob?.cancel(); it.graceJob?.cancel() }
            remotes.clear()
            layoutState = null
            _layout.value = null
            advertiseLocked()
            links.values.toList()
        }
        closeAfterConfirm(all, START_CONFIRM_MILLIS)
    }

    /** Â§16.2 o recrutador voltou Ã  tela 1, saiu ou desligou o Wi-Fi: chamados tÃªm o convite cancelado; recrutados, "Mesa encerrada". */
    suspend fun closeHosting() {
        val all = mutex.withLock {
            if (yielded) return
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

    /** Larga tudo (Wi-Fi do jogo desligado ou o aparelho entrou numa mesa/jogo): encerra o recrutamento nos dois papÃ©is. */
    suspend fun shutdown() {
        // §16.15 entre o plano e o início, largar as conexões diria aos recrutados que o recrutador saiu: só marca como fechado;
        // o início (sendStart) entrega a mensagem e fecha as conexões.
        if (mutex.withLock { closed = true; startPending }) {
            log("rec shutdown deferred: start pending")
            return
        }
        mutex.withLock {
            closed = true
            // Â§16.2 aviso explÃ­cito de saÃ­da: quem recebe me tira das listas na hora (as confirmaÃ§Ãµes esperam atÃ© 0,5 s).
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
        // §16.15 depois do início (enviado ou recebido) nada espera confirmação: a entrada na mesa não pode ficar atrás da desconexão.
        if (rest.isNotEmpty()) closeAfterConfirm(rest, if (yielded) 0 else CLOSE_CONFIRM_MILLIS)
    }

    // ---- API do chamado/recrutado -------------------------------------------------------------------------------

    /** Â§16.2 aceita o convite: passa a ver a mesa do recrutador. Devolve se havia convite e conexÃ£o. */
    suspend fun accept(): Boolean = mutex.withLock {
        val invited = _status.value as? RecruitStatus.Invited ?: return@withLock false
        val link = hostLink ?: return@withLock false
        link.sendLocked(RecruitReply(myKey, true))
        answeredVersion[invited.hostKey] = maxOf(answeredVersion[invited.hostKey] ?: 0, invited.version)
        inviteLossJob?.cancel()
        log("rec accept sent")
        transition(RecruitStatus.Joined(invited.hostKey, invited.hostName, invited.table, invited.you, invited.version))
        advertiseLocked()
        true
    }

    /** Â§16.2 recusa o convite (volta a ficar disponÃ­vel para todos). */
    suspend fun refuse() {
        mutex.withLock { refuseLocked(RefusalReason.INVITE_REFUSED) }
    }

    private suspend fun refuseLocked(reason: RefusalReason) {
        val invited = _status.value as? RecruitStatus.Invited ?: return
        if (reason != RefusalReason.INVITE_REFUSED) _events.tryEmit(RecruitEvent.Refused(invited.hostName, reason))
        hostLink?.sendLocked(RecruitReply(myKey, false, reason))
        answeredVersion[invited.hostKey] = maxOf(answeredVersion[invited.hostKey] ?: 0, invited.version)
        inviteLossJob?.cancel()
        transition(RecruitStatus.Free, "refused:$reason")
        advertiseLocked()
    }

    /** Â§16.2 "Sair da mesa": o lugar volta a VAGO e este aparelho fica livre. */
    suspend fun leave() {
        mutex.withLock {
            if (_status.value !is RecruitStatus.Joined) return
            hostLink?.sendLocked(RecruitLeave(null))
            transition(RecruitStatus.Free)
            advertiseLocked()
        }
    }

    // ---- descoberta ---------------------------------------------------------------------------------------------

    /** Devo me ligar a [host]? SÃ³ quem estÃ¡ livre, e a um recrutador (ou a quem me lista em `inviting`). */
    private fun wants(host: PresenceAdvert): Boolean = !closed && !host.leaving && host.key != myKey && !recruiting() &&
        (myKey in host.inviting || (host.hosting && freeFlag))

    private suspend fun presencesLocked(list: List<PresenceAdvert>) {
        presences = list.associateBy { it.key }.values.toList()
        _recruiters.value = presences.filter { it.hosting && !it.leaving && it.key != myKey }
        _free.value = presences.filter { it.free && !it.hosting && !it.leaving && it.key != myKey }
        val keys = presences.map { it.key }.toSet()
        // Recrutador: chamado sem conexÃ£o que sumiu da busca por 10 s volta a VAGO ("fora do alcance").
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

    /** O jogador [key] deixa o lugar (recusa, saÃ­da, queda): o lugar volta a VAGO e o recrutador Ã© avisado. */
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

    /** O inÃ­cio se perdeu: a mesa do recrutador apareceu com a minha chave em `invited`. */
    private suspend fun tablesLocked(list: List<TableAdvert>) {
        lastTables = list
        if (_status.value !is RecruitStatus.Joined) return
        val table = list.firstOrNull { myKey in it.invited } ?: return
        log("rec start via table advert")
        startedLocked(table.tableId, PREPARE_MILLIS)
    }

    private suspend fun startedLocked(tableId: String, prepareMillis: Long) {
        rejoinJob?.cancel()
        yielded = true
        log("rec start received")
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

    /** Devolve se o evento Ã© da troca de fotos. */
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
                val host = peerKey[link.endpoint] ?: return
                val current = _status.value
                if (current is RecruitStatus.Invited && current.hostKey == host) {
                    // Convite reenviado depois de uma queda: atualiza no lugar, sem reabrir o modal e SEM renovar o prazo.
                    inviteLossJob?.cancel()
                    hostLink = link
                    appliedVersion = maxOf(appliedVersion, payload.version)
                    _status.value = current.copy(table = payload.table, you = payload.you, version = maxOf(current.version, payload.version))
                    log("rec invite resent: kept deadline, remaining=${current.remainingMillis(clock())} ms")
                    return
                }
                if (current != RecruitStatus.Free || recruiting()) return
                if (payload.version <= (answeredVersion[host] ?: 0)) {
                    log("rec invite ignored: already answered v=${payload.version}")
                    return
                }
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
                // Â§16.15 sem resposta no prazo vale como recusa (nunca aceita sozinho).
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
                    // O recrutador jÃ¡ nÃ£o tem este aceite (ex.: comeÃ§ou a partida e o inÃ­cio se perdeu): espera a mesa aparecer.
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
                inviteLossJob?.cancel()
                transition(RecruitStatus.Free, "end:${payload.reason}")
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
                // §16.15 o recrutador que me recrutou não sai da mesa por aviso de saída: o fim vem como RecruitEnd; o início, como RecruitStart.
                if (_status.value is RecruitStatus.Joined && payload.key == hostKey) {
                    log("rec bye from host ignored while joined")
                    return
                }
                log("rec bye ${k(payload.key)}")
                transport.forgetPresence(payload.key)
                // Quem estava na minha mesa e saiu de propÃ³sito: o lugar volta a VAGO na hora.
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

    /** Â§14.6 o saldo cobre a aposta? (a leitura da carteira Ã© rÃ¡pida; sem ela, o convite Ã© aceito para a tela decidir). */
    private suspend fun canAffordNow(stake: Int): Boolean = runCatching { canAfford(stake) }.getOrDefault(true)

    /** Recrutador: um jogador prÃ³ximo conectou (espera, convite pendente pela reserva ou reingresso). */
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

    /** A conexÃ£o caiu: o recrutador dÃ¡ o prazo de reingresso a quem aceitou; o chamado/recrutado tenta voltar. */
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
                        // §16.15 o início pode ter saído com a conexão já caída: a busca fica viva para ver a mesa do recrutador.
                        transport.holdSearch(true)
                        try {
                            delay(REJOIN_MILLIS)
                        } finally {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { transport.holdSearch(false) }
                        }
                        mutex.withLock {
                            val table = lastTables.firstOrNull { myKey in it.invited }
                            if (table != null && _status.value is RecruitStatus.Joined) {
                                log("rec start via table advert (last look)")
                                startedLocked(table.tableId, PREPARE_MILLIS)
                            } else if (hostLink == null && _status.value is RecruitStatus.Joined) {
                                transition(RecruitStatus.Free)
                                _events.tryEmit(RecruitEvent.Closed(s.hostName))
                                advertiseLocked()
                            }
                        }
                    }
                }
                is RecruitStatus.Invited -> {
                    // Â§16.15 queda de conexÃ£o: o modal fica 1,5 s Ã  espera de a conexÃ£o voltar (o convite reenviado atualiza no lugar).
                    log("rec invite link lost: keeping modal ${INVITE_LOSS_GRACE_MILLIS} ms")
                    inviteLossJob?.cancel()
                    inviteLossJob = scope.launch {
                        delay(INVITE_LOSS_GRACE_MILLIS)
                        mutex.withLock {
                            val cur = _status.value
                            if (cur is RecruitStatus.Invited && cur.deadlineMillis == s.deadlineMillis && hostLink == null) {
                                transition(RecruitStatus.Free, "link lost")
                                advertiseLocked()
                            }
                        }
                    }
                }
                RecruitStatus.Free -> hostKey = null
            }
            evaluateContactLocked()
        }
    }

    /** Espera a confirmaÃ§Ã£o (atÃ© [maxMillis]) e desconecta. */
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
        /** ConexÃµes de espera aceitas alÃ©m dos chamados (limite de rÃ¡dio). */
        const val MAX_STANDBY: Int = 6

        /** Â§16.15 os lugares sÃ³ vÃ£o aos chamados/recrutados 0,3 s depois da Ãºltima mudanÃ§a (convite e resposta nÃ£o esperam). */
        const val SYNC_DEBOUNCE_MILLIS: Long = 300

        /** Â§16.15 reingresso de quem aceitou e perdeu a conexÃ£o. */
        const val REJOIN_MILLIS: Long = 8_000

        /** Â§16.15 chamado sem conexÃ£o que sumiu da busca. */
        const val PENDING_ABSENT_MILLIS: Long = 10_000

        /** Â§16.15 tentativas de conexÃ£o ao recrutador: 0,3 s, 1 s, 2 s, 4 s e, daÃ­ em diante, 8 s. */
        val CONTACT_BACKOFF_MILLIS: List<Long> = listOf(300, 1_000, 2_000, 4_000, 8_000)

        /** Â§16.15 confirmaÃ§Ãµes antes de desconectar: no ComeÃ§ar Partida e ao fechar / desligar o Wi-Fi. */
        const val START_CONFIRM_MILLIS: Long = 2_000
        const val CLOSE_CONFIRM_MILLIS: Long = 500
        const val POLL_MILLIS: Long = 50

        /** Â§16.15 mensagem de teste das conexÃµes abertas a cada 400 ms; sem resposta por 1 s a conexÃ£o Ã© dada como caÃ­da. */
        const val HEARTBEAT_MILLIS: Long = 400
        const val DEAD_AFTER_MILLIS: Long = 4_000

        /** Â§16.15 convite que perde a conexÃ£o continua na tela por este tempo Ã  espera de ela voltar. */
        const val INVITE_LOSS_GRACE_MILLIS: Long = 1_500
    }
}
