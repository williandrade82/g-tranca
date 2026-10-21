package com.gtranca.net.nearby

import com.gtranca.net.EndpointId
import com.gtranca.net.LocalTransport
import com.gtranca.net.TableAdvert
import com.gtranca.net.TransportEvent
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

    /** Mesas achadas, por endpoint do Nearby (a mesma mesa pode reaparecer com outro endpoint depois de reanunciar). */
    private val found = MutableStateFlow<Map<String, TableAdvert>>(emptyMap())
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
            val advert = AdvertCodec.decode(endpointInfo) ?: return
            found.update { it + (endpointId to advert) }
        }

        override fun onLost(endpointId: String) = found.update { it - endpointId }
    }

    private fun dropped(endpointId: String) {
        val was = synchronized(lock) { connected.remove(endpointId) }
        if (was) inbox.trySend(TransportEvent.Disconnected(EndpointId(endpointId)))
    }

    // ---- anúncio ----------------------------------------------------------------------------------------------------

    override suspend fun advertise(advert: TableAdvert) {
        val bytes = AdvertCodec.encode(advert)
        control.withLock {
            val current = advertised
            if (current != null && current.contentEquals(bytes)) return
            if (current != null) api.stopAdvertising()
            advertised = null
            api.startAdvertising(bytes, connectionCallback)
            advertised = bytes
        }
    }

    override suspend fun stopAdvertising() {
        control.withLock {
            if (advertised == null) return
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

    /** Liga a busca se preciso; devolve quando ela foi ligada (para o aquecimento). */
    private suspend fun acquireDiscovery(): Long = control.withLock {
        subscribers++
        lingerJob?.cancel()
        lingerJob = null
        if (!discovering) {
            found.value = emptyMap()
            api.startDiscovery(discoveryCallback)
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
