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
) : LocalTransport {

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
            if (endpointInfo.firstOrNull()?.toInt() == AdvertCodec.PRESENCE_FORMAT) {
                val presence = AdvertCodec.decodePresence(endpointInfo) ?: return
                found.update { it - endpointId }
                foundPresence.update { it + (endpointId to presence) }
                return
            }
            val advert = AdvertCodec.decode(endpointInfo) ?: return
            foundPresence.update { it - endpointId }
            found.update { it + (endpointId to advert) }
        }

        override fun onLost(endpointId: String) {
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

    /** §16.2 para a presença (a mesa, se for ela no ar, continua). */
    override suspend fun stopPresence() = stopKind(presence = true)

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
        try {
            emitAll(foundPresence.map { it.values.toList() }.distinctUntilChanged())
        } finally {
            releaseDiscovery()
        }
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

    override suspend fun connect(advert: TableAdvert): EndpointId {
        val endpointId = found.value.entries.lastOrNull { it.value.tableId == advert.tableId && it.value.hostId == advert.hostId }?.key
            ?: error("Mesa não encontrada")
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

        /** Nome local na conexão: neutro (nada privado; a identidade vem no `Hello` do protocolo). */
        private val LOCAL_INFO = byteArrayOf(1)
    }
}
