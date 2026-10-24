package com.gtranca.net.nearby

import com.gtranca.net.EndpointId
import com.gtranca.net.LocalTransport
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.TableAdvert
import com.gtranca.net.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocalTransport] sobre o Nearby Connections (`P2P_STAR`, §16): o anfitrião anuncia a mesa, os convidados procuram e
 * conectam, e os bytes do protocolo vão como payload BYTES. Identidade é o token do protocolo, nunca o endpoint (§16.5):
 * toda conexão pedida é aceita aqui e a sessão recusa quem não deve entrar.
 *
 * Decisões (etapa 5):
 * - **Anúncio:** o Nearby não atualiza o `endpointInfo`; trocar os dados exige parar e anunciar de novo. Parar de
 *   anunciar **não** derruba as conexões existentes. Como a sessão chama [advertise] a cada mudança da sala, aqui só se
 *   reanuncia se os **bytes** mudaram (senão a busca dos outros veria a mesa sumir e voltar, e o sucessor poderia
 *   assumir sem queda, §16.10).
 * - **Busca única e compartilhada:** o Nearby permite uma busca por cliente; vários coletores (lista de mesas, vigia do
 *   anfitrião, reconexão) dividem a mesma, que pára [lingerMillis] depois do último sair. Busca recém-ligada não emite a
 *   lista vazia: espera até achar algo ou [warmUpMillis] (o `discover().first()` da sessão não pode ver "nenhuma mesa"
 *   só porque o rádio acabou de ligar).
 * - **Tamanho:** payload BYTES tem teto de [NearbyApi.maxBytes] (1 MB no AAR 19.5.1); as mensagens do protocolo ficam
 *   muito abaixo (ver o teste de tamanho), então não há fragmentação; acima do teto, o envio é recusado.
 * - Envio a uma ponta que caiu é descartado em silêncio (como no transporte em memória): o reenvio é do protocolo.
 * - **Presença sempre atualizada (§16.2, §16.15):** o Nearby avisa `onLost` com atraso grande (ou nunca) quando o
 *   anunciante só para. Enquanto alguém observa [discoverPresence], a busca recomeça a cada [refreshMillis] (o Nearby
 *   reporta de novo quem continua no ar) e a presença não revista em [sweepGraceMillis] sai da lista. As listas não são
 *   apagadas no recomeço (quem continua não pisca), as **mesas** não são varridas (a "mesa sumida" do sucessor, §16.10,
 *   continua dependendo só do `onLost`) e as conexões não são afetadas.
 * - Nada é logado (nem bytes, nem nomes).
 */
class NearbyTransport(
    private val api: NearbyApi,
    private val scope: CoroutineScope,
    private val warmUpMillis: Long = WARM_UP_MILLIS,
    private val lingerMillis: Long = LINGER_MILLIS,
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    /** Relógio do aquecimento da busca (o virtual nos testes). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val refreshMillis: Long = PRESENCE_REFRESH_MILLIS,
    private val sweepGraceMillis: Long = PRESENCE_SWEEP_GRACE_MILLIS,
    private val leavingMillis: Long = PRESENCE_LEAVING_MILLIS,
    /** Diagnóstico (só no build debug): eventos sem nomes, fotos nem bytes. */
    private val log: (String) -> Unit = {},
) : LocalTransport {

    /** §16.2 quando cada presença foi vista (achada) pela última vez, por endpoint. */
    private val presenceSeen = mutableMapOf<String, Long>()

    /** §16.2 varreduras seguidas em que a presença não foi revista (sai da lista na [SWEEPS_TO_REMOVE]ª). */
    private val presenceMisses = mutableMapOf<String, Int>()
    private var presenceSubscribers = 0
    private var refresher: Job? = null

    private val inbox = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = inbox.receiveAsFlow()

    private val lock = Any()
    private val connected = mutableSetOf<String>()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val control = Mutex()
    private var advertised: ByteArray? = null

    /** O que está anunciado agora é a presença (§16.2), não uma mesa: um aparelho anuncia uma coisa por vez. */
    private var advertisingPresence = false

    /** Mesas achadas, por endpoint do Nearby (a mesma mesa pode reaparecer com outro endpoint depois de reanunciar). */
    private val found = MutableStateFlow<Map<String, TableAdvert>>(emptyMap())

    /** §16.2 jogadores próximos achados (presença), por endpoint do Nearby. */
    private val foundPresence = MutableStateFlow<Map<String, PresenceAdvert>>(emptyMap())
    private var subscribers = 0
    private var discovering = false
    private var discoveryStartedAt = 0L
    private var lingerJob: Job? = null

    private val connectionCallback = object : ConnectionCallback {
        override fun onInitiated(endpointId: String) {
            scope.launch {
                runCatching { api.acceptConnection(endpointId) { id, bytes -> inbox.trySend(TransportEvent.Received(EndpointId(id), bytes)) } }
                    .onFailure { synchronized(lock) { pending.remove(endpointId) }?.complete(false) }
            }
        }

        override fun onResult(endpointId: String, success: Boolean) {
            val waiting = synchronized(lock) {
                if (success) connected += endpointId
                pending.remove(endpointId)
            }
            if (success) inbox.trySend(TransportEvent.Connected(EndpointId(endpointId)))
            waiting?.complete(success)
        }

        override fun onDisconnected(endpointId: String) = dropped(endpointId)
    }

    private val discoveryCallback = object : DiscoveryCallback {
        override fun onFound(endpointId: String, endpointInfo: ByteArray) {
            // O primeiro byte distingue a presença (§16.2) do anúncio de mesa.
            if (AdvertCodec.isPresence(endpointInfo)) {
                val presence = AdvertCodec.decodePresence(endpointInfo) ?: return
                found.update { it - endpointId }
                if (presence.leaving) {
                    // §16.2 "saindo": todas as entradas com a chave somem na hora.
                    log("found leaving ${presence.key.take(2)}")
                    val gone = foundPresence.value.filterValues { it.key == presence.key }.keys + endpointId
                    synchronized(presenceSeen) { gone.forEach { presenceSeen.remove(it); presenceMisses.remove(it) } }
                    foundPresence.update { it - gone }
                    return
                }
                log("found presence ${presence.key.take(2)}")
                synchronized(presenceSeen) { presenceSeen[endpointId] = now(); presenceMisses.remove(endpointId) }
                foundPresence.update { it + (endpointId to presence) }
                return
            }
            val advert = AdvertCodec.decode(endpointInfo) ?: return
            foundPresence.update { it - endpointId }
            found.update { it + (endpointId to advert) }
        }

        override fun onLost(endpointId: String) {
            log("lost ${endpointId.take(2)}")
            found.update { it - endpointId }
            foundPresence.update { it - endpointId }
        }
    }

    private fun dropped(endpointId: String) {
        val was = synchronized(lock) { connected.remove(endpointId) }
        if (was) inbox.trySend(TransportEvent.Disconnected(EndpointId(endpointId)))
    }

    // ---- anúncio ----------------------------------------------------------------------------------------------------

    override suspend fun advertise(advert: TableAdvert) = advertiseBytes(AdvertCodec.encode(advert), presence = false)

    /** §16.2 anuncia a presença deste jogador (fora de um jogo); toma o lugar de qualquer anúncio anterior. */
    override suspend fun advertisePresence(presence: PresenceAdvert) =
        advertiseBytes(AdvertCodec.encodePresence(presence), presence = true)

    private suspend fun advertiseBytes(bytes: ByteArray, presence: Boolean) {
        control.withLock {
            val current = advertised
            if (current != null && current.contentEquals(bytes)) return
            if (current != null) api.stopAdvertising()
            advertised = null
            try {
                api.startAdvertising(bytes, connectionCallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Falha do rádio não fecha o app nem derruba a mesa: as conexões seguem e o próximo anúncio tenta de novo.
                return
            }
            advertised = bytes
            advertisingPresence = presence
        }
    }

    /** Para o anúncio da mesa (a presença, se for ela no ar, continua). */
    override suspend fun stopAdvertising() = stopKind(presence = false)

    /**
     * §16.2, §16.15 para a presença (a mesa, se for ela no ar, continua): antes, anuncia "saindo" com a mesma chave por
     * [leavingMillis], para os outros tirarem o jogador da lista na hora.
     */
    override suspend fun stopPresence() {
        val leaving = control.withLock {
            val current = advertised
            if (current == null || !advertisingPresence) return
            val presence = AdvertCodec.decodePresence(current) ?: return@withLock null
            if (presence.leaving) return@withLock null
            val bytes = AdvertCodec.encodePresenceLeaving(presence.key)
            api.stopAdvertising()
            advertised = null
            runCatching { api.startAdvertising(bytes, connectionCallback) }.onSuccess { advertised = bytes; log("advertise leaving") }
            bytes.takeIf { advertised === it }
        }
        if (leaving != null) delay(leavingMillis)
        control.withLock {
            // Outra coisa (a mesa, ou a presença de novo) pode ter tomado o lugar durante o aviso.
            if (leaving != null && advertised !== leaving) return
            if (advertised == null || !advertisingPresence) return
            api.stopAdvertising()
            advertised = null
        }
    }

    private suspend fun stopKind(presence: Boolean) {
        control.withLock {
            if (advertised == null || advertisingPresence != presence) return
            api.stopAdvertising()
            advertised = null
        }
    }

    // ---- busca -----------------------------------------------------------------------------------------------------

    override fun discover(): Flow<List<TableAdvert>> = flow {
        val startedAt = acquireDiscovery()
        try {
            val waited = now() - startedAt
            if (waited < warmUpMillis) withTimeoutOrNull(warmUpMillis - waited) { found.first { it.isNotEmpty() } }
            emitAll(found.map { it.values.toList() }.distinctUntilChanged())
        } finally {
            releaseDiscovery()
        }
    }

    /** §16.2 jogadores próximos achados (a mesma busca das mesas, sem o aquecimento: a lista vazia vale como "Recrutando"). */
    override fun discoverPresence(): Flow<List<PresenceAdvert>> = flow {
        acquireDiscovery()
        control.withLock {
            if (presenceSubscribers++ == 0) refresher = scope.launch { refreshPresence() }
        }
        try {
            emitAll(foundPresence.map { it.values.toList() }.distinctUntilChanged())
        } finally {
            scope.launch { control.withLock { if (--presenceSubscribers == 0) { refresher?.cancel(); refresher = null } } }
            releaseDiscovery()
        }
    }

    /**
     * §16.2, §16.15 recomeça a busca periodicamente e tira da lista as presenças que não reapareceram em
     * [SWEEPS_TO_REMOVE] recomeços seguidos. Falha ao religar não abandona: tenta de novo depois de [RESTART_RETRY_MILLIS].
     * Com conexão de presença pendente ou aberta (troca de fotos), o recomeço é adiado (no Nearby ele pode abortá-la).
     */
    private suspend fun refreshPresence() {
        delay(refreshMillis)
        while (true) {
            if (presenceConnectionBusy()) {
                delay(RESTART_RETRY_MILLIS)
                continue
            }
            val restartedAt = control.withLock {
                if (subscribers == 0) return@withLock null
                val at = now()
                if (discovering) runCatching { api.stopDiscovery() }
                try {
                    api.startDiscovery(discoveryCallback)
                    discovering = true
                    log("restart ok")
                    at
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    discovering = false
                    log("restart failed ${e.message?.take(40)}")
                    null
                }
            }
            if (restartedAt == null) {
                delay(RESTART_RETRY_MILLIS)
                continue
            }
            delay(sweepGraceMillis)
            sweep(restartedAt)
            delay((refreshMillis - sweepGraceMillis).coerceAtLeast(0))
        }
    }

    private fun sweep(restartedAt: Long) {
        val live = synchronized(lock) { connected.toSet() }
        val remove = synchronized(presenceSeen) {
            foundPresence.value.keys.filter { endpoint ->
                val seen = presenceSeen[endpoint] ?: Long.MIN_VALUE
                if (seen >= restartedAt || endpoint in live) {
                    presenceMisses.remove(endpoint)
                    false
                } else {
                    val misses = (presenceMisses[endpoint] ?: 0) + 1
                    presenceMisses[endpoint] = misses
                    misses >= SWEEPS_TO_REMOVE
                }
            }.toSet().also { gone -> gone.forEach { presenceSeen.remove(it); presenceMisses.remove(it) } }
        }
        if (remove.isNotEmpty()) {
            log("sweep removed ${remove.size}")
            foundPresence.update { it - remove }
        }
    }

    /** Há conexão pendente ou aberta com um jogador próximo (troca de fotos, §16.2). */
    private fun presenceConnectionBusy(): Boolean {
        val presenceEndpoints = foundPresence.value.keys
        return synchronized(lock) { pending.keys.any { it in presenceEndpoints } || connected.any { it in presenceEndpoints } }
    }

    /** Liga a busca se preciso; devolve quando ela foi ligada (para o aquecimento). */
    private suspend fun acquireDiscovery(): Long = control.withLock {
        subscribers++
        lingerJob?.cancel()
        lingerJob = null
        if (!discovering) {
            found.value = emptyMap()
            foundPresence.value = emptyMap()
            try {
                api.startDiscovery(discoveryCallback)
            } catch (e: Exception) {
                subscribers-- // a busca não começou: quem pediu recebe o erro e não fica contado
                throw e
            }
            discovering = true
            discoveryStartedAt = now()
        }
        discoveryStartedAt
    }

    private fun releaseDiscovery() {
        scope.launch {
            control.withLock {
                subscribers--
                if (subscribers > 0 || !discovering) return@withLock
                lingerJob = scope.launch {
                    delay(lingerMillis)
                    control.withLock {
                        if (subscribers == 0 && discovering) {
                            api.stopDiscovery()
                            discovering = false
                            found.value = emptyMap()
                            foundPresence.value = emptyMap()
                        }
                    }
                }
            }
        }
    }

    // ---- conexão e bytes -------------------------------------------------------------------------------------------

    override suspend fun connect(advert: TableAdvert): EndpointId = connectTo(
        found.value.entries.lastOrNull { it.value.tableId == advert.tableId && it.value.hostId == advert.hostId }?.key
            ?: error("Mesa não encontrada"),
    )

    /** §16.2 conexão curta com um jogador próximo (pedido da foto). */
    override suspend fun connectPresence(presence: PresenceAdvert): EndpointId = connectTo(
        foundPresence.value.entries.lastOrNull { it.value.key == presence.key }?.key ?: error("Presença não encontrada"),
    )

    private suspend fun connectTo(endpointId: String): EndpointId = try {
        connectEndpoint(endpointId).also { log("connect ok") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("connect failed ${(e.cause?.message ?: e.message)?.take(40)}")
        throw e
    }

    private suspend fun connectEndpoint(endpointId: String): EndpointId {
        val waiting = synchronized(lock) {
            if (endpointId in connected) return EndpointId(endpointId)
            pending.getOrPut(endpointId) { CompletableDeferred() }
        }
        try {
            api.requestConnection(LOCAL_INFO, endpointId, connectionCallback)
        } catch (e: Exception) {
            synchronized(lock) { pending.remove(endpointId) }
            throw IllegalStateException("Falha ao conectar", e)
        }
        val ok = withTimeoutOrNull(connectTimeoutMillis) { waiting.await() } ?: false
        if (!ok) {
            synchronized(lock) { pending.remove(endpointId) }
            api.disconnect(endpointId)
            error("Falha ao conectar")
        }
        return EndpointId(endpointId)
    }

    override suspend fun send(endpoint: EndpointId, bytes: ByteArray) {
        require(bytes.size <= api.maxBytes) { "Mensagem acima do limite do Nearby" }
        if (synchronized(lock) { endpoint.value !in connected }) return
        runCatching { api.sendBytes(endpoint.value, bytes) }
    }

    override suspend fun disconnect(endpoint: EndpointId) {
        api.disconnect(endpoint.value)
        // O Nearby não avisa o lado que desconecta; o transporte em memória avisa os dois, e a sessão conta com isso.
        dropped(endpoint.value)
    }

    /** Larga tudo (sair do modo multijogador). */
    suspend fun close() {
        stopAdvertising()
        stopPresence()
        control.withLock {
            lingerJob?.cancel()
            if (discovering) api.stopDiscovery()
            discovering = false
        }
        synchronized(lock) { connected.toList() }.forEach { disconnect(EndpointId(it)) }
    }

    private fun now(): Long = clock()

    companion object {
        const val WARM_UP_MILLIS: Long = 3_000
        const val LINGER_MILLIS: Long = 10_000
        const val CONNECT_TIMEOUT_MILLIS: Long = 20_000

        /** §16.15 a busca recomeça a cada 8 s enquanto alguém observa os jogadores próximos. */
        const val PRESENCE_REFRESH_MILLIS: Long = 8_000

        /** §16.15 folga depois de cada recomeço para o Nearby reportar de novo quem continua no ar. */
        const val PRESENCE_SWEEP_GRACE_MILLIS: Long = 3_000

        /** §16.15 sai da lista quem não foi revisto em dois recomeços seguidos (cerca de 20 s). */
        const val SWEEPS_TO_REMOVE: Int = 2

        /** §16.15 aviso de "saindo" no ar antes de parar a presença. */
        const val PRESENCE_LEAVING_MILLIS: Long = 2_000

        /** Espera técnica antes de tentar religar a busca (falhou ou havia conexão de presença em andamento). */
        const val RESTART_RETRY_MILLIS: Long = 1_000

        /** Nome local na conexão: neutro (nada privado; a identidade vem no `Hello` do protocolo). */
        private val LOCAL_INFO = byteArrayOf(1)
    }
}
