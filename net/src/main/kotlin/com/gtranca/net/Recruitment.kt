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

/** §16.2 um jogador próximo marcado por este anfitrião: convite pendente ou aceito. */
data class Recruit(val key: String, val name: String, val accepted: Boolean)

/** §16.2 aviso ao anfitrião: o marcado recusou o convite ou saiu da mesa ([reason] `null` = "Sair da mesa"). */
data class RecruitNotice(val key: String, val name: String, val refused: Boolean, val reason: RefusalReason?)

/** §16.2 situação deste aparelho como recrutado. */
sealed interface RecruitStatus {
    /** Livre: pode ser marcado por qualquer anfitrião. */
    data object Free : RecruitStatus

    /** Convite imediato recebido do anfitrião [hostKey] (sem prazo): Aceitar ou Recusar. */
    data class Invited(val hostKey: String, val hostName: String) : RecruitStatus

    /** Aceitou: vê a montagem do anfitrião ([config], `null` até a primeira sincronização) só para leitura. */
    data class Joined(val hostKey: String, val hostName: String, val config: RecruitConfig? = null, val version: Long = 0) : RecruitStatus
}

/** §16.2 acontecimentos do lado do recrutado (avisos e o início da escolha de posições). */
sealed interface RecruitEvent {
    /** "<anfitrião> tirou você da mesa". */
    data class Removed(val hostName: String) : RecruitEvent

    /** "Mesa encerrada" (o anfitrião saiu, desligou o Wi-Fi do jogo ou a conexão não voltou a tempo). */
    data class Closed(val hostName: String) : RecruitEvent

    /** "Saldo insuficiente": a aposta passou do saldo; saiu sozinho. */
    data class InsufficientBalance(val hostName: String) : RecruitEvent

    /** O anfitrião tocou em Escolher posições: entrar na mesa [tableId] com a própria chave ([GuestSession.join]). */
    data class Start(val tableId: String, val inviteKey: String) : RecruitEvent
}

/**
 * §16.2 recrutamento imediato, fora de mesa. Máquina de estados e regras de sincronização: `docs/recrutamento.md`.
 *
 * - **Espera ("standby"):** quem ainda não está em mesa e vê um anfitrião com a montagem aberta ([PresenceAdvert.hosting])
 *   ou que o convidou ([PresenceAdvert.inviting]) conecta a ele (spoke de um hub: o anfitrião já é o centro da presença) e
 *   envia [RecruitHello]. A conexão fica aberta: **o convite chega como mensagem** ([RecruitInvite]) no instante do
 *   `mark()`, sem reanunciar nada; aceite, recusa, [RecruitSync], remoção, saída e início passam por ela. O anúncio
 *   (`inviting`) é só a reserva para quem ainda não conectou, e `recruitedBy` serve ao selo "Recrutado" dos terceiros.
 * - **Montagem:** sempre completa e numerada ([RecruitSync.version]); o anfitrião espera [CONFIG_DEBOUNCE_MILLIS] sem novas
 *   mudanças; o recrutado só aplica versão maior que a aplicada.
 * - **Queda:** quem já aceitou fica na mesa por [REJOIN_MILLIS] tentando voltar (a montagem é reenviada inteira).
 * - Enquanto [run] roda, é o único leitor dos eventos do transporte (as fotos vão a [photos]); o estado vive no objeto, não
 *   em [run], então sobrevive a reinícios dele.
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

    /** Todas as conexões de recrutamento (deste aparelho como anfitrião ou como recrutado). */
    private val links = ConcurrentHashMap<EndpointId, ReliableLink>()
    private val peerKey = mutableMapOf<EndpointId, String>()
    private val photoSent = mutableSetOf<EndpointId>()

    private fun k(key: String) = key.take(2)

    // ---- anfitrião ----------------------------------------------------------------------------------------------
    private class Marked(val key: String, var name: String, val markedAt: Long) {
        var accepted = false
        var link: ReliableLink? = null
        var absentJob: Job? = null
        var graceJob: Job? = null
    }

    private val marked = linkedMapOf<String, Marked>()
    private var hosting = false
    private var config: RecruitConfig? = null
    private var pendingConfig: RecruitConfig? = null
    private var version = 0L
    private var debounceJob: Job? = null

    private val _recruits = MutableStateFlow<List<Recruit>>(emptyList())

    /** §16.2 jogadores marcados por este anfitrião (pendentes e aceitos), na ordem em que foram marcados. */
    val recruits: StateFlow<List<Recruit>> = _recruits.asStateFlow()

    private val _notices = MutableSharedFlow<RecruitNotice>(extraBufferCapacity = 64)

    /** §16.2 recusas e saídas dos marcados (com o motivo). */
    val notices: SharedFlow<RecruitNotice> = _notices.asSharedFlow()

    // ---- recrutado ----------------------------------------------------------------------------------------------
    private val _status = MutableStateFlow<RecruitStatus>(RecruitStatus.Free)

    /** §16.2 situação deste aparelho como recrutado. */
    val status: StateFlow<RecruitStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RecruitEvent>(extraBufferCapacity = 64)

    /** §16.2 avisos ao recrutado e o início da escolha de posições. */
    val events: SharedFlow<RecruitEvent> = _events.asSharedFlow()

    /** Conexão que este aparelho abriu a um anfitrião (espera, convite ou reingresso). */
    private var outLink: ReliableLink? = null

    /** Conexão que trouxe o convite (a que abri ou a que o outro abriu): vale para aceite, montagem, saída e início. */
    private var hostLink: ReliableLink? = null
    private var hostKey: String? = null

    @Volatile
    private var closed = false

    /** Aceito, sem conexão e recusado no reingresso: o início se perdeu; espera a mesa aparecer (até o prazo de reingresso). */
    private var awaitingStart = false

    /** Só anuncia "hosting" (e aceita esperas) quem está com a montagem aberta e livre. */
    private fun effectiveHosting() = hosting && _status.value == RecruitStatus.Free
    private var hostName: String = ""
    private var appliedVersion = 0L
    private var contacting: Job? = null
    private var rejoinJob: Job? = null
    private var presences: List<PresenceAdvert> = emptyList()

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
        if (from == to) return
        _status.value = to
        if (to == RecruitStatus.Free) awaitingStart = false
        log("rec state ${from::class.simpleName}->${to::class.simpleName}")
    }

    private fun pinned(): Set<String> = marked.keys + listOfNotNull(hostKey)

    private suspend fun advertiseLocked() {
        val recruitedBy = if (_status.value == RecruitStatus.Free) null else hostKey
        val inviting = marked.values.filter { !it.accepted && it.link == null }.map { it.key }
        runCatching {
            transport.pinPresence(pinned())
            transport.advertisePresence(PresenceAdvert(myKey, myName, recruitedBy = recruitedBy, inviting = inviting, hosting = effectiveHosting()))
        }
    }

    private fun publishLocked() {
        _recruits.value = marked.values.map { Recruit(it.key, it.name, it.accepted) }
    }

    /** O nome do perfil mudou: o anúncio acompanha (o estado do recrutamento não muda). */
    suspend fun rename(name: String) {
        mutex.withLock {
            if (myName == name) return
            myName = name
            advertiseLocked()
        }
    }

    // ---- API do anfitrião ---------------------------------------------------------------------------------------

    /**
     * §16.2 a montagem da mesa está aberta (ou fechou): enquanto aberta, a presença leva a marca "hosting" e os jogadores
     * próximos mantêm uma conexão de espera, para o convite chegar na hora. Fechar encerra o recrutamento ([closeHosting]).
     */
    suspend fun setHosting(on: Boolean) {
        if (!on) return closeHosting()
        mutex.withLock {
            if (hosting) return
            hosting = true
            log("rec hosting on")
            advertiseLocked()
        }
    }

    /**
     * §16.2 marca [presence]: o convite chega na hora (pela conexão de espera, se já existe; senão pela reserva do
     * anúncio). `false` se ele já está recrutado por outro anfitrião, já há 3 marcados ou este aparelho está recrutado.
     */
    suspend fun mark(presence: PresenceAdvert): Boolean = mutex.withLock {
        if (presence.key == myKey || presence.key in marked || marked.size >= MAX_RECRUITS) return@withLock false
        if (presence.recruitedBy != null && presence.recruitedBy != myKey) return@withLock false
        if (_status.value != RecruitStatus.Free) return@withLock false
        val m = Marked(presence.key, presence.name, clock())
        marked[presence.key] = m
        publishLocked()
        val link = links.entries.firstOrNull { peerKey[it.key] == presence.key }?.value
        if (link != null) {
            m.link = link
            link.sendLocked(RecruitInvite(myName))
            log("rec mark ${k(presence.key)} invite sent via link in ${clock() - m.markedAt} ms")
        } else {
            log("rec mark ${k(presence.key)} no link: advert fallback")
        }
        advertiseLocked()
        true
    }

    /** §16.2 desmarca: cancela o convite pendente ou tira da mesa quem já aceitou ("<anfitrião> tirou você da mesa"). */
    suspend fun unmark(key: String) {
        mutex.withLock {
            val m = marked.remove(key) ?: return
            m.absentJob?.cancel()
            m.graceJob?.cancel()
            m.link?.sendLocked(RecruitEnd(if (m.accepted) RecruitEndReason.REMOVED else RecruitEndReason.CANCELLED))
            log("rec unmark ${k(key)} accepted=${m.accepted}")
            publishLocked()
            advertiseLocked()
        }
    }

    /** §16.2 a montagem mudou: vai (completa e numerada) a quem aceitou, 0,3 s depois da última mudança. */
    suspend fun updateConfig(newConfig: RecruitConfig) {
        mutex.withLock {
            if (newConfig == (pendingConfig ?: config)) return
            pendingConfig = newConfig
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(CONFIG_DEBOUNCE_MILLIS)
                mutex.withLock { flushConfigLocked() }
            }
        }
    }

    private suspend fun flushConfigLocked() {
        val c = pendingConfig ?: return
        pendingConfig = null
        if (c == config) return
        config = c
        version++
        marked.values.filter { it.accepted }.forEach { sendSyncLocked(it) }
    }

    /** A montagem com o "aceitou" de cada recrutado conferido aqui (o anfitrião só sabe quem aceitou de fato). */
    private fun effectiveConfig(): RecruitConfig? = config?.let { c ->
        c.copy(participants = c.participants.map { p -> p.key?.let { key -> p.copy(accepted = marked[key]?.accepted == true) } ?: p })
    }

    private suspend fun sendSyncLocked(m: Marked) {
        val link = m.link ?: return
        val c = effectiveConfig() ?: return
        link.sendLocked(RecruitSync(version, c))
        log("rec cfg send v=$version to=${k(m.key)}")
    }

    /**
     * §16.2 Escolher posições: quem aceitou recebe [RecruitStart] (entra com a própria chave, sem novo convite); os
     * convites pendentes são cancelados. Devolve as presenças aceitas, na ordem de marcação, para o [LobbySetup].
     */
    suspend fun start(tableId: String): List<PresenceAdvert> {
        val (accepted, all) = mutex.withLock {
            debounceJob?.cancel()
            val acc = marked.values.filter { it.accepted }
            acc.forEach { it.link?.sendLocked(RecruitStart(tableId)) }
            marked.values.filter { !it.accepted }.forEach { it.link?.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED)) }
            log("rec start accepted=${acc.size}")
            marked.values.forEach { it.absentJob?.cancel(); it.graceJob?.cancel() }
            marked.clear()
            hosting = false
            publishLocked()
            advertiseLocked()
            acc.map { PresenceAdvert(it.key, it.name) } to links.values.toList()
        }
        closeAfterConfirm(all, START_CONFIRM_MILLIS)
        return accepted
    }

    /** §16.2 o anfitrião saiu da montagem ou desligou o Wi-Fi do jogo: "Mesa encerrada" a quem aceitou. */
    suspend fun closeHosting() {
        val all = mutex.withLock {
            if (!hosting && marked.isEmpty() && links.isEmpty()) return
            debounceJob?.cancel()
            marked.values.filter { it.accepted }.forEach { it.link?.sendLocked(RecruitEnd(RecruitEndReason.CLOSED)) }
            marked.values.forEach { it.absentJob?.cancel(); it.graceJob?.cancel() }
            marked.clear()
            hosting = false
            log("rec hosting off")
            publishLocked()
            advertiseLocked()
            links.values.filter { it !== hostLink }
        }
        closeAfterConfirm(all, CLOSE_CONFIRM_MILLIS)
    }

    /** Larga tudo (Wi-Fi do jogo desligado ou o aparelho entrou numa mesa/jogo): encerra o recrutamento nos dois papéis. */
    suspend fun shutdown() {
        mutex.withLock { closed = true }
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

    // ---- API do recrutado ---------------------------------------------------------------------------------------

    /** §16.2 aceita o convite: passa a ver a montagem do anfitrião. Devolve se havia convite e conexão. */
    suspend fun accept(): Boolean = mutex.withLock {
        val invited = _status.value as? RecruitStatus.Invited ?: return@withLock false
        val link = hostLink ?: return@withLock false
        link.sendLocked(RecruitReply(myKey, true))
        appliedVersion = 0
        log("rec accept sent")
        transition(RecruitStatus.Joined(invited.hostKey, invited.hostName))
        advertiseLocked()
        true
    }

    /** §16.2 recusa o convite (volta a ficar disponível para todos). */
    suspend fun refuse() {
        mutex.withLock {
            if (_status.value !is RecruitStatus.Invited) return
            hostLink?.sendLocked(RecruitReply(myKey, false, RefusalReason.INVITE_REFUSED))
            transition(RecruitStatus.Free)
            advertiseLocked()
        }
    }

    /** §16.2 "Sair da mesa": volta à própria configuração e fica disponível. */
    suspend fun leave() = leaveWith(null)

    private suspend fun leaveWith(reason: RefusalReason?) {
        mutex.withLock {
            if (_status.value !is RecruitStatus.Joined) return
            hostLink?.sendLocked(RecruitLeave(reason))
            transition(RecruitStatus.Free)
            advertiseLocked()
        }
    }

    // ---- descoberta ---------------------------------------------------------------------------------------------

    /**
     * Devo abrir uma conexão a [host]? Sim se ele me lista em `inviting` (reserva do anúncio) ou se está com a montagem
     * aberta e eu não sou também anfitrião (ou, entre dois anfitriões, se a minha chave é a menor: um só abre).
     */
    private fun wants(host: PresenceAdvert): Boolean = !closed && !host.leaving && host.key != myKey &&
        (myKey in host.inviting || (host.hosting && (!effectiveHosting() || myKey < host.key)))

    /** Presenças vistas agora: convite pela reserva do anúncio, conexão de espera e marcados que sumiram da busca. */
    private suspend fun presencesLocked(list: List<PresenceAdvert>) {
        presences = list.associateBy { it.key }.values.toList()
        val keys = presences.map { it.key }.toSet()
        // Anfitrião: pendente sem conexão que sumiu da busca por 10 s deixa de estar marcado.
        marked.values.filter { !it.accepted }.forEach { m ->
            if (m.key in keys || m.link != null) {
                m.absentJob?.cancel()
                m.absentJob = null
            } else if (m.absentJob == null) {
                m.absentJob = scope.launch {
                    delay(PENDING_ABSENT_MILLIS)
                    mutex.withLock {
                        if (marked[m.key] === m && m.link == null && !m.accepted && m.key !in presences.map { it.key }) {
                            marked.remove(m.key)
                            log("rec removed ${k(m.key)}: out of range")
                            _notices.tryEmit(RecruitNotice(m.key, m.name, refused = false, reason = RefusalReason.OUT_OF_RANGE))
                            publishLocked()
                            advertiseLocked()
                        }
                    }
                }
            }
        }
        evaluateContactLocked()
    }

    /** Recrutado: conecta ao anfitrião que o convidou ou que está montando a mesa; larga a espera de quem parou. */
    private suspend fun evaluateContactLocked() {
        if (closed || marked.isNotEmpty()) return
        val status = _status.value
        val out = outLink
        if (out != null) {
            // Espera sem convite: o anfitrião parou de montar a mesa (e não me convida) -> solta a conexão.
            val peer = peerKey[out.endpoint]
            val host = presences.firstOrNull { it.key == peer }
            if (status == RecruitStatus.Free && out !== hostLink && host != null && !wants(host)) {
                log("rec standby dropped: host not hosting")
                dropLinkLocked(out)
            } else if (status == RecruitStatus.Free && out !== hostLink) {
                // Outro anfitrião me convidou pela reserva do anúncio: troca de espera.
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
                        ?.takeIf { !closed && outLink == null && marked.isEmpty() && (rejoin || wants(it)) }
                }
                if (presence == null) break
                val endpoint = runCatching { transport.connectPresence(presence) }.getOrElse { log("rec connect failed ${it.message?.take(40)}"); null }
                if (endpoint != null) {
                    mutex.withLock {
                        val link = links[endpoint] ?: ReliableLink(endpoint, transport, codec, scope, mutex).also { links[endpoint] = it }
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

    /** O início se perdeu: a mesa do anfitrião apareceu com a minha chave em `invited`. */
    private suspend fun tablesLocked(list: List<TableAdvert>) {
        if (_status.value !is RecruitStatus.Joined) return
        val table = list.firstOrNull { myKey in it.invited } ?: return
        log("rec start via table advert")
        startedLocked(table.tableId)
    }

    private suspend fun startedLocked(tableId: String) {
        rejoinJob?.cancel()
        transition(RecruitStatus.Free)
        _events.tryEmit(RecruitEvent.Start(tableId, myKey))
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
        }
        link.receiveLocked(envelope).forEach { processLocked(link, it) }
        return false
    }

    private suspend fun sendPhotoOnceLocked(link: ReliableLink) {
        if (photoSent.add(link.endpoint)) link.sendLocked(photos?.mine() ?: PresencePhoto(myKey))
    }

    private suspend fun processLocked(link: ReliableLink, payload: Payload) {
        when (payload) {
            // ---- anfitrião ----
            is RecruitHello -> helloLocked(link, payload)
            is RecruitReply -> {
                val m = marked[payload.key]
                if (m == null || m.accepted) {
                    if (payload.accepted && m == null) link.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
                    return
                }
                m.link = link
                if (payload.accepted) {
                    flushConfigLocked() // a montagem pendente vai só aos que já estavam; este recebe uma versão nova abaixo
                    m.accepted = true
                    log("rec host marked accepted ${k(m.key)}")
                    // Todos os aceitos recebem a montagem inteira de novo, já com este jogador como "aceitou".
                    version++
                    marked.values.filter { it.accepted }.forEach { sendSyncLocked(it) }
                } else {
                    marked.remove(m.key)
                    log("rec refused ${k(m.key)}")
                    _notices.tryEmit(RecruitNotice(m.key, m.name, refused = true, reason = payload.reason ?: RefusalReason.INVITE_REFUSED))
                }
                publishLocked()
                advertiseLocked()
            }
            is RecruitLeave -> {
                val m = marked.values.firstOrNull { it.link === link } ?: return
                marked.remove(m.key)
                m.graceJob?.cancel()
                log("rec left ${k(m.key)} reason=${payload.reason}")
                _notices.tryEmit(RecruitNotice(m.key, m.name, refused = false, reason = payload.reason))
                publishLocked()
                advertiseLocked()
            }
            // ---- recrutado ----
            is RecruitInvite -> {
                if (_status.value != RecruitStatus.Free || marked.isNotEmpty()) return
                val host = peerKey[link.endpoint] ?: return
                hostLink = link
                hostKey = host
                hostName = payload.hostName
                transition(RecruitStatus.Invited(host, payload.hostName))
                log("rec invite shown")
                advertiseLocked()
            }
            is RecruitSync -> {
                val s = _status.value as? RecruitStatus.Joined ?: return
                if (link !== hostLink) return
                if (payload.version <= appliedVersion) {
                    log("rec cfg stale v=${payload.version} applied=$appliedVersion")
                    return
                }
                appliedVersion = payload.version
                log("rec cfg apply v=${payload.version}")
                _status.value = s.copy(config = payload.config, version = payload.version)
                val stake = payload.config.stake
                scope.launch {
                    // §14.6, §16.2 a aposta passou do saldo: sai sozinho com o motivo (o anfitrião é avisado).
                    if (!canAfford(stake) && (_status.value as? RecruitStatus.Joined)?.config?.stake == stake) {
                        _events.tryEmit(RecruitEvent.InsufficientBalance(s.hostName))
                        leaveWith(RefusalReason.INSUFFICIENT_BALANCE)
                    }
                }
            }
            is RecruitEnd -> {
                if (link !== hostLink) return
                val s = _status.value
                val name = (s as? RecruitStatus.Joined)?.hostName ?: (s as? RecruitStatus.Invited)?.hostName ?: hostName
                if (s == RecruitStatus.Free) return
                if (s is RecruitStatus.Joined && payload.reason == RecruitEndReason.CANCELLED) {
                    // O anfitrião já não tem este aceite (ex.: iniciou a mesa e o início se perdeu): espera a mesa aparecer.
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
                startedLocked(payload.tableId)
            }
            // ---- os dois ----
            is PresencePhoto -> {
                photos?.store(payload)
                sendPhotoOnceLocked(link)
            }
            else -> Unit
        }
    }

    /** Anfitrião: um jogador próximo conectou (espera, convite pendente pela reserva ou reingresso). */
    private suspend fun helloLocked(link: ReliableLink, hello: RecruitHello) {
        val m = marked[hello.key]
        if (m == null && (!effectiveHosting() || peerKey.size >= MAX_STANDBY)) {
            link.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
            log("rec hello ${k(hello.key)} rejected hosting=${effectiveHosting()}")
            return
        }
        // Mesma chave em outra conexão: a nova assume e a antiga fecha.
        links.entries.filter { peerKey[it.key] == hello.key && it.value !== link }.forEach { (ep, old) ->
            old.close()
            links.remove(ep)
            peerKey.remove(ep)
            scope.launch { runCatching { transport.disconnect(ep) } }
        }
        peerKey[link.endpoint] = hello.key
        sendPhotoOnceLocked(link)
        if (m == null) {
            log("rec standby ${k(hello.key)}")
            return
        }
        m.link = link
        m.graceJob?.cancel()
        m.graceJob = null
        m.name = hello.name.ifBlank { m.name }
        if (m.accepted) {
            version++
            sendSyncLocked(m)
            log("rec rejoin ${k(hello.key)}")
        } else {
            link.sendLocked(RecruitInvite(myName))
            log("rec invite sent after hello in ${clock() - m.markedAt} ms")
        }
        publishLocked()
        advertiseLocked()
    }

    /** A conexão caiu: o anfitrião dá o prazo de reingresso a quem aceitou; o recrutado tenta voltar. */
    private suspend fun lostLocked(endpoint: EndpointId, link: ReliableLink) {
        peerKey.remove(endpoint)
        photoSent -= endpoint
        marked.values.firstOrNull { it.link === link }?.let { m ->
            m.link = null
            if (m.accepted) {
                log("rec accepted ${k(m.key)} link lost: grace")
                m.graceJob?.cancel()
                m.graceJob = scope.launch {
                    delay(REJOIN_MILLIS)
                    mutex.withLock {
                        if (marked[m.key] === m && m.link == null) {
                            marked.remove(m.key)
                            log("rec removed ${k(m.key)}: out of range")
                            _notices.tryEmit(RecruitNotice(m.key, m.name, refused = false, reason = RefusalReason.OUT_OF_RANGE))
                            publishLocked()
                            advertiseLocked()
                        }
                    }
                }
            } else {
                log("rec pending ${k(m.key)} link lost: advert fallback")
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

    companion object {
        /** §16.2 até 3 jogadores próximos marcados (4 participantes com o anfitrião). */
        const val MAX_RECRUITS: Int = 3

        /** Conexões de espera aceitas além dos marcados (limite de rádio). */
        const val MAX_STANDBY: Int = 6

        /** §16.15 a montagem só vai ao recrutado 0,3 s depois da última mudança. */
        const val CONFIG_DEBOUNCE_MILLIS: Long = 300

        /** §16.15 reingresso de quem aceitou e perdeu a conexão. */
        const val REJOIN_MILLIS: Long = 8_000

        /** §16.15 marcado pendente, sem conexão, que sumiu da busca. */
        const val PENDING_ABSENT_MILLIS: Long = 10_000

        /** §16.15 tentativas de conexão ao anfitrião: 0,3 s, 1 s, 2 s, 4 s e, daí em diante, 8 s. */
        val CONTACT_BACKOFF_MILLIS: List<Long> = listOf(300, 1_000, 2_000, 4_000, 8_000)

        /** §16.15 confirmações antes de desconectar: no Escolher posições e ao fechar / desligar o Wi-Fi. */
        const val START_CONFIRM_MILLIS: Long = 2_000
        const val CLOSE_CONFIRM_MILLIS: Long = 500
        const val POLL_MILLIS: Long = 50
    }
}
