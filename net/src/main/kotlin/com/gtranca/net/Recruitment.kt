package com.gtranca.net

import kotlinx.coroutines.CoroutineScope
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
    data class Joined(val hostKey: String, val hostName: String, val config: RecruitConfig? = null) : RecruitStatus
}

/** §16.2 acontecimentos do lado do recrutado (avisos e o início da escolha de posições). */
sealed interface RecruitEvent {
    /** "<anfitrião> tirou você da mesa". */
    data class Removed(val hostName: String) : RecruitEvent

    /** "Mesa encerrada" (o anfitrião saiu, desligou o Wi-Fi do jogo ou a conexão caiu). */
    data class Closed(val hostName: String) : RecruitEvent

    /** "Saldo insuficiente": a aposta passou do saldo; saiu sozinho. */
    data class InsufficientBalance(val hostName: String) : RecruitEvent

    /** O anfitrião tocou em Escolher posições: entrar na mesa [tableId] com a própria chave ([GuestSession.join]). */
    data class Start(val tableId: String, val inviteKey: String) : RecruitEvent
}

/**
 * §16.2 recrutamento imediato, fora de mesa, sobre a presença: o mesmo aparelho pode recrutar (anfitrião montando a mesa)
 * e ser recrutado.
 *
 * - **Anunciar:** a presença leva [PresenceAdvert.inviting] (as chaves marcadas e ainda sem resposta) e
 *   [PresenceAdvert.recruitedBy] (o anfitrião que o convidou, pendente ou aceito: "Recrutado" para os outros).
 * - **Convite:** quem vê a própria chave em `inviting` de um anfitrião e está livre fica [RecruitStatus.Invited] (sem
 *   prazo). Aceitar ou recusar abre uma conexão ao anfitrião ([LocalTransport.connectPresence]; ele é o centro) com
 *   [RecruitReply]. Aceito, recebe [RecruitSync] a cada mudança da montagem; sai com [RecruitLeave]; o anfitrião tira com
 *   [RecruitEnd] e, em Escolher posições, manda [RecruitStart].
 * - Enquanto [run] roda, ele é o único leitor dos eventos do transporte (as fotos vão a [photos]): pare-o antes de abrir
 *   ou entrar numa mesa.
 */
class Recruitment(
    private val transport: LocalTransport,
    private val scope: CoroutineScope,
    val myKey: String,
    private val myName: String,
    private val photos: PresencePhotos? = null,
    /** §14.6 o saldo cobre a aposta? (pode esperar a leitura da carteira). */
    private val canAfford: suspend (stake: Int) -> Boolean = { true },
    private val codec: ProtocolCodec = ProtocolCodec(),
) {
    private val mutex = Mutex()
    private val links = mutableMapOf<EndpointId, ReliableLink>()

    // ---- anfitrião ----------------------------------------------------------------------------------------------
    private class Marked(val key: String, val name: String) {
        var accepted = false
        var endpoint: EndpointId? = null
    }

    private val marked = linkedMapOf<String, Marked>()
    private var config: RecruitConfig? = null

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

    private var hostLink: ReliableLink? = null
    private var presences: List<PresenceAdvert> = emptyList()

    /** Anuncia a presença e atende o recrutamento até ser cancelado. */
    suspend fun run() = coroutineScope {
        mutex.withLock { advertiseLocked() }
        launch { transport.discoverPresence().collect { list -> mutex.withLock { presencesLocked(list) } } }
        transport.events.collect { handle(it) }
    }

    private suspend fun advertiseLocked() {
        val recruitedBy = when (val s = _status.value) {
            is RecruitStatus.Invited -> s.hostKey
            is RecruitStatus.Joined -> s.hostKey
            RecruitStatus.Free -> null
        }
        val inviting = marked.values.filter { !it.accepted }.map { it.key }
        runCatching { transport.advertisePresence(PresenceAdvert(myKey, myName, recruitedBy = recruitedBy, inviting = inviting)) }
    }

    private fun publishLocked() {
        _recruits.value = marked.values.map { Recruit(it.key, it.name, it.accepted) }
    }

    // ---- API do anfitrião ---------------------------------------------------------------------------------------

    /**
     * §16.2 marca [presence]: o convite chega na hora. `false` se ele já está recrutado por outro anfitrião, já há 3
     * marcados ou este aparelho está recrutado.
     */
    suspend fun mark(presence: PresenceAdvert): Boolean = mutex.withLock {
        if (presence.key == myKey || presence.key in marked || marked.size >= MAX_RECRUITS) return@withLock false
        if (presence.recruitedBy != null && presence.recruitedBy != myKey) return@withLock false
        if (_status.value != RecruitStatus.Free) return@withLock false
        marked[presence.key] = Marked(presence.key, presence.name)
        publishLocked()
        advertiseLocked()
        true
    }

    /** §16.2 desmarca: cancela o convite pendente ou tira da mesa quem já aceitou ("<anfitrião> tirou você da mesa"). */
    suspend fun unmark(key: String) {
        val link = mutex.withLock {
            val m = marked.remove(key) ?: return
            publishLocked()
            advertiseLocked()
            m.endpoint?.let { links[it] }?.also { it.sendLocked(RecruitEnd(RecruitEndReason.REMOVED)) }
        }
        if (link != null) closeAfterConfirm(listOf(link))
    }

    /** §16.2 a montagem mudou: vai na hora a quem aceitou (só para leitura). */
    suspend fun updateConfig(newConfig: RecruitConfig) {
        mutex.withLock {
            if (config == newConfig) return
            config = newConfig
            acceptedLinksLocked().forEach { it.sendLocked(RecruitSync(newConfig)) }
        }
    }

    /**
     * §16.2 Escolher posições: quem aceitou recebe [RecruitStart] (entra com a própria chave, sem novo convite); os
     * convites pendentes são cancelados. Devolve as presenças aceitas, na ordem de marcação, para o [LobbySetup].
     */
    suspend fun start(tableId: String): List<PresenceAdvert> {
        val (accepted, sent) = mutex.withLock {
            val acc = marked.values.filter { it.accepted }
            val sent = acceptedLinksLocked().onEach { it.sendLocked(RecruitStart(tableId)) }
            marked.clear()
            publishLocked()
            advertiseLocked()
            acc.map { PresenceAdvert(it.key, it.name) } to sent
        }
        closeAfterConfirm(sent)
        return accepted
    }

    /** §16.2 o anfitrião saiu da montagem ou desligou o Wi-Fi do jogo: "Mesa encerrada" a quem aceitou. */
    suspend fun closeHosting() {
        val sent = mutex.withLock {
            val sent = acceptedLinksLocked().onEach { it.sendLocked(RecruitEnd(RecruitEndReason.CLOSED)) }
            marked.clear()
            publishLocked()
            advertiseLocked()
            sent
        }
        closeAfterConfirm(sent)
    }

    private fun acceptedLinksLocked(): List<ReliableLink> =
        marked.values.filter { it.accepted }.mapNotNull { m -> m.endpoint?.let { links[it] } }

    // ---- API do recrutado ---------------------------------------------------------------------------------------

    /** §16.2 aceita o convite: conecta ao anfitrião e passa a ver a montagem dele. Devolve se conectou. */
    suspend fun accept(): Boolean = reply(accepted = true)

    /** §16.2 recusa o convite (volta a ficar disponível para todos). */
    suspend fun refuse() {
        reply(accepted = false)
    }

    private suspend fun reply(accepted: Boolean): Boolean {
        val invited = _status.value as? RecruitStatus.Invited ?: return false
        val host = presences.firstOrNull { it.key == invited.hostKey }
        val endpoint = host?.let { runCatching { transport.connectPresence(it) }.getOrNull() }
        if (endpoint == null) {
            mutex.withLock { freeLocked() }
            return false
        }
        val link = mutex.withLock {
            val link = ReliableLink(endpoint, transport, codec, scope, mutex)
            links[endpoint] = link
            link.sendLocked(RecruitReply(myKey, accepted, if (accepted) null else RefusalReason.INVITE_REFUSED))
            if (accepted) {
                hostLink = link
                _status.value = RecruitStatus.Joined(invited.hostKey, invited.hostName)
            } else {
                freeLocked()
            }
            link
        }
        if (!accepted) closeAfterConfirm(listOf(link))
        return accepted
    }

    /** §16.2 "Sair da mesa": volta à própria configuração e fica disponível. */
    suspend fun leave() = leaveWith(null)

    private suspend fun leaveWith(reason: RefusalReason?) {
        val link = mutex.withLock {
            val link = hostLink ?: return
            link.sendLocked(RecruitLeave(reason))
            hostLink = null
            freeLocked()
            link
        }
        closeAfterConfirm(listOf(link))
    }

    private suspend fun freeLocked() {
        hostLink = null
        if (_status.value == RecruitStatus.Free) return
        _status.value = RecruitStatus.Free
        advertiseLocked()
        // Pode haver outro convite já visível.
        presencesLocked(presences)
    }

    // ---- eventos ------------------------------------------------------------------------------------------------

    /** Convite imediato de outro anfitrião visível (ou que sumiu). */
    private suspend fun presencesLocked(list: List<PresenceAdvert>) {
        presences = list
        when (val s = _status.value) {
            RecruitStatus.Free -> {
                val host = list.firstOrNull { !it.leaving && myKey in it.inviting && it.key !in marked } ?: return
                if (marked.isNotEmpty()) return // montando a própria mesa com convidados: não aceita ser recrutado
                _status.value = RecruitStatus.Invited(host.key, host.name)
                advertiseLocked()
            }
            is RecruitStatus.Invited -> {
                // Desmarcado antes da resposta (ou o anfitrião sumiu): o convite é cancelado.
                if (list.none { it.key == s.hostKey && !it.leaving && myKey in it.inviting }) freeLocked()
            }
            is RecruitStatus.Joined -> {
                if (list.any { it.key == s.hostKey && it.leaving }) {
                    hostLink?.let { it.close(); runCatching { transport.disconnect(it.endpoint) } }
                    links.values.remove(hostLink)
                    freeLocked()
                    _events.tryEmit(RecruitEvent.Closed(s.hostName))
                }
            }
        }
    }

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
            if (envelope.p !is RecruitReply) return false
            link = ReliableLink(event.endpoint, transport, codec, scope, mutex).also { links[event.endpoint] = it }
        }
        link.receiveLocked(envelope).forEach { processLocked(link, it) }
        return false
    }

    private suspend fun processLocked(link: ReliableLink, payload: Payload) {
        when (payload) {
            // Anfitrião.
            is RecruitReply -> {
                val m = marked[payload.key]
                if (m == null || m.accepted) {
                    // Convite já cancelado: avisa e encerra.
                    if (payload.accepted) link.sendLocked(RecruitEnd(RecruitEndReason.REMOVED))
                    scope.launch { closeAfterConfirm(listOf(link)) }
                    return
                }
                if (payload.accepted) {
                    m.accepted = true
                    m.endpoint = link.endpoint
                    config?.let { link.sendLocked(RecruitSync(it)) }
                } else {
                    marked.remove(m.key)
                    _notices.tryEmit(RecruitNotice(m.key, m.name, refused = true, reason = payload.reason ?: RefusalReason.INVITE_REFUSED))
                }
                publishLocked()
                advertiseLocked()
            }
            is RecruitLeave -> {
                val m = marked.values.firstOrNull { it.endpoint == link.endpoint } ?: return
                marked.remove(m.key)
                publishLocked()
                advertiseLocked()
                _notices.tryEmit(RecruitNotice(m.key, m.name, refused = false, reason = payload.reason))
                scope.launch { closeAfterConfirm(listOf(link)) }
            }
            // Recrutado.
            is RecruitSync -> {
                val s = _status.value as? RecruitStatus.Joined ?: return
                if (link !== hostLink) return
                _status.value = s.copy(config = payload.config)
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
                val s = _status.value as? RecruitStatus.Joined ?: return
                if (link !== hostLink) return
                freeLocked()
                _events.tryEmit(if (payload.reason == RecruitEndReason.REMOVED) RecruitEvent.Removed(s.hostName) else RecruitEvent.Closed(s.hostName))
            }
            is RecruitStart -> {
                if (_status.value !is RecruitStatus.Joined || link !== hostLink) return
                freeLocked()
                _events.tryEmit(RecruitEvent.Start(payload.tableId, myKey))
            }
            else -> Unit
        }
    }

    /** A conexão caiu: o anfitrião perde o recrutado; o recrutado fica livre ("Mesa encerrada"). */
    private suspend fun lostLocked(endpoint: EndpointId, link: ReliableLink) {
        marked.values.firstOrNull { it.endpoint == endpoint && it.accepted }?.let { m ->
            marked.remove(m.key)
            publishLocked()
            advertiseLocked()
            _notices.tryEmit(RecruitNotice(m.key, m.name, refused = false, reason = RefusalReason.OUT_OF_RANGE))
        }
        if (link === hostLink) {
            val s = _status.value as? RecruitStatus.Joined
            freeLocked()
            if (s != null) _events.tryEmit(RecruitEvent.Closed(s.hostName))
        }
    }

    /** Espera a confirmação (até [CONFIRM_MILLIS]) e desconecta. */
    private suspend fun closeAfterConfirm(sent: List<ReliableLink>) {
        var waited = 0L
        while (waited < CONFIRM_MILLIS && !mutex.withLock { sent.all { it.allConfirmed } }) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        mutex.withLock {
            sent.forEach { link ->
                link.close()
                links.remove(link.endpoint)
            }
        }
        sent.forEach { runCatching { transport.disconnect(it.endpoint) } }
    }

    companion object {
        /** §16.2 até 3 jogadores próximos marcados (4 participantes com o anfitrião). */
        const val MAX_RECRUITS: Int = 3

        /** Espera técnica pela confirmação antes de desconectar (o reenvio do protocolo cobre perdas). */
        const val CONFIRM_MILLIS: Long = 2_000
        const val POLL_MILLIS: Long = 50
    }
}
