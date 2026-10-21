package com.gtranca.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Ponta de uma conexão no transporte. Não identifica o jogador (quem identifica é o token, §16.5). */
@JvmInline
value class EndpointId(val value: String)

/** Mesa anunciada (o necessário para a lista de mesas; as regras vão no [Welcome]). */
data class TableAdvert(val tableId: String, val tableName: String, val hostName: String)

/** Eventos do transporte: só conexões e bytes. */
sealed interface TransportEvent {
    data class Connected(val endpoint: EndpointId) : TransportEvent
    data class Disconnected(val endpoint: EndpointId) : TransportEvent
    class Received(val endpoint: EndpointId, val bytes: ByteArray) : TransportEvent
}

/**
 * Transporte entre aparelhos (§16): só entrega bytes e eventos de conexão. Implementações: [MemoryTransport] (testes
 * JVM) e, na etapa 5, a do Android (Nearby Connections `P2P_STAR`). Nada fora da implementação depende da tecnologia.
 */
interface LocalTransport {
    /** Eventos desta ponta, em ordem; um único coletor. */
    val events: Flow<TransportEvent>

    /** Anfitrião: publica a mesa para os aparelhos próximos. */
    suspend fun advertise(advert: TableAdvert)

    /** Anfitrião: deixa de publicar. */
    suspend fun stopAdvertising()

    /** Convidado: mesas publicadas por perto (o estado atual da busca). */
    fun discover(): Flow<List<TableAdvert>>

    /** Convidado: conecta-se à mesa; devolve a ponta do anfitrião. */
    suspend fun connect(tableId: String): EndpointId

    suspend fun send(endpoint: EndpointId, bytes: ByteArray)

    suspend fun disconnect(endpoint: EndpointId)
}

/** Uma entrega na [MemoryNetwork] (de [from] para [to]). */
class Delivery(val from: EndpointId, val to: EndpointId, val bytes: ByteArray)

/**
 * Rede em memória: anfitrião e convidados no mesmo processo (testes JVM, sem Android e sem relógio real). Entrega
 * na hora, em ordem; [tap] vê (e pode alterar) cada entrega: devolver vazio perde a mensagem, duas cópias a duplicam, e
 * guardar para entregar depois com [deliver] a reordena.
 */
class MemoryNetwork {
    private val nodes = mutableMapOf<EndpointId, MemoryTransport>()
    private val tables = MutableStateFlow<Map<String, Pair<TableAdvert, EndpointId>>>(emptyMap())
    private var counter = 0

    @Volatile
    var tap: ((Delivery) -> List<Delivery>)? = null

    @Synchronized
    fun transport(): MemoryTransport {
        val id = EndpointId("e${++counter}")
        return MemoryTransport(this, id).also { nodes[id] = it }
    }

    internal fun advertised(): StateFlow<Map<String, Pair<TableAdvert, EndpointId>>> = tables.asStateFlow()

    internal fun advertise(advert: TableAdvert, host: EndpointId) = tables.update { it + (advert.tableId to (advert to host)) }

    internal fun stopAdvertising(host: EndpointId) = tables.update { map -> map.filterValues { it.second != host } }

    internal fun connect(guest: EndpointId, tableId: String): EndpointId {
        val host = tables.value[tableId]?.second ?: error("Mesa não encontrada")
        node(host).push(TransportEvent.Connected(guest))
        node(guest).push(TransportEvent.Connected(host))
        return host
    }

    internal fun disconnect(a: EndpointId, b: EndpointId) {
        nodes[a]?.push(TransportEvent.Disconnected(b))
        nodes[b]?.push(TransportEvent.Disconnected(a))
    }

    internal fun send(from: EndpointId, to: EndpointId, bytes: ByteArray) {
        val delivery = Delivery(from, to, bytes)
        (tap?.invoke(delivery) ?: listOf(delivery)).forEach(::deliver)
    }

    /** Entrega direta (sem passar pelo [tap]): para reordenar ou reinjetar uma mensagem nos testes. */
    fun deliver(delivery: Delivery) {
        nodes[delivery.to]?.push(TransportEvent.Received(delivery.from, delivery.bytes))
    }

    private fun node(id: EndpointId): MemoryTransport = nodes[id] ?: error("Ponta desconhecida")
}

/** Ponta da [MemoryNetwork]. */
class MemoryTransport internal constructor(private val network: MemoryNetwork, val id: EndpointId) : LocalTransport {
    private val inbox = Channel<TransportEvent>(Channel.UNLIMITED)

    override val events: Flow<TransportEvent> = inbox.receiveAsFlow()

    internal fun push(event: TransportEvent) {
        inbox.trySend(event)
    }

    override suspend fun advertise(advert: TableAdvert) = network.advertise(advert, id)

    override suspend fun stopAdvertising() = network.stopAdvertising(id)

    override fun discover(): Flow<List<TableAdvert>> = network.advertised().map { map -> map.values.map { it.first } }

    override suspend fun connect(tableId: String): EndpointId = network.connect(id, tableId)

    override suspend fun send(endpoint: EndpointId, bytes: ByteArray) = network.send(id, endpoint, bytes)

    override suspend fun disconnect(endpoint: EndpointId) = network.disconnect(id, endpoint)

}
