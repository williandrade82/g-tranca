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
data class TableAdvert(
    val tableId: String,
    val tableName: String,
    val hostName: String,
    /** §16.7 "jogo em andamento" com assento ocupável (aprovação do anfitrião); fora disso, só reingresso pelo token. */
    val inProgress: Boolean = false,
)

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

    /** Pares conectados agora: entregas só entre eles (uma queda corta o par). */
    private val links = mutableSetOf<Pair<EndpointId, EndpointId>>()

    /** Pontas que não conseguem (re)conectar agora (fora do alcance). */
    private val blocked = mutableSetOf<EndpointId>()

    /** Pontas "mortas" (aparelho desligado): não anunciam, não enviam e não conectam mais. */
    private val dead = mutableSetOf<EndpointId>()

    /** A ponta [id] fica fora do alcance (as tentativas de conexão falham) até [unblock]. */
    @Synchronized
    fun block(id: EndpointId) { blocked += id }

    @Synchronized
    fun unblock(id: EndpointId) { blocked -= id }

    /** O aparelho [id] some de vez (queda abrupta, §16.10): como [drop], e nunca mais anuncia nem envia. */
    fun kill(id: EndpointId) {
        synchronized(this) { dead += id }
        drop(id)
    }

    private fun key(a: EndpointId, b: EndpointId) = if (a.value < b.value) a to b else b to a

    @Volatile
    var tap: ((Delivery) -> List<Delivery>)? = null

    @Synchronized
    fun transport(): MemoryTransport {
        val id = EndpointId("e${++counter}")
        return MemoryTransport(this, id).also { nodes[id] = it }
    }

    internal fun advertised(): StateFlow<Map<String, Pair<TableAdvert, EndpointId>>> = tables.asStateFlow()

    internal fun advertise(advert: TableAdvert, host: EndpointId) = if (synchronized(this) { host in dead }) Unit else tables.update { it + (advert.tableId to (advert to host)) }

    internal fun stopAdvertising(host: EndpointId) = tables.update { map -> map.filterValues { it.second != host } }

    @Synchronized
    internal fun connect(guest: EndpointId, tableId: String): EndpointId {
        val host = tables.value[tableId]?.second ?: error("Mesa não encontrada")
        check(guest !in blocked && guest !in dead && host !in dead) { "Fora do alcance" }
        links += key(guest, host)
        node(host).push(TransportEvent.Connected(guest))
        node(guest).push(TransportEvent.Connected(host))
        return host
    }

    @Synchronized
    internal fun disconnect(a: EndpointId, b: EndpointId) {
        if (!links.remove(key(a, b))) return
        nodes[a]?.push(TransportEvent.Disconnected(b))
        nodes[b]?.push(TransportEvent.Disconnected(a))
    }

    /**
     * Queda abrupta da ponta [id] (sem aviso do jogo): todos os pares dela recebem a desconexão e, se era anfitrião, a
     * mesa deixa de ser anunciada (§16.8, §16.10).
     */
    fun drop(id: EndpointId) {
        val peers = synchronized(this) {
            val mine = links.filter { it.first == id || it.second == id }
            links.removeAll(mine.toSet())
            mine.map { if (it.first == id) it.second else it.first }
        }
        stopAdvertising(id)
        peers.forEach { peer ->
            nodes[peer]?.push(TransportEvent.Disconnected(id))
            nodes[id]?.push(TransportEvent.Disconnected(peer))
        }
    }

    internal fun send(from: EndpointId, to: EndpointId, bytes: ByteArray) {
        if (synchronized(this) { key(from, to) !in links }) return
        val delivery = Delivery(from, to, bytes)
        (tap?.invoke(delivery) ?: listOf(delivery)).forEach(::deliver)
    }

    /** Entrega direta (sem passar pelo [tap]): para reordenar ou reinjetar uma mensagem nos testes. */
    fun deliver(delivery: Delivery) {
        if (synchronized(this) { key(delivery.from, delivery.to) !in links }) return
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
