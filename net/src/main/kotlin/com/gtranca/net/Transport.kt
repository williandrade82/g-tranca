package com.gtranca.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Ponta de uma conexão no transporte. Não identifica o jogador (quem identifica é o token, §16.5). */
@JvmInline
value class EndpointId(val value: String)

/**
 * Mesa anunciada (o necessário para a lista de mesas; as regras vão no [Welcome]). A mesa fica anunciada o jogo todo para
 * o reingresso pelo token (§16.5, §16.7); [listed] diz se ela aparece na **lista** para quem não está nela.
 */
data class TableAdvert(
    val tableId: String,
    val tableName: String,
    val hostName: String,
    /** §16.7 "jogo em andamento" com assento ocupável (aprovação do anfitrião). */
    val inProgress: Boolean = false,
    /** §16.7 aparece na lista de mesas (antes do início, ou com vaga aberta); fora disso, só para reingresso. */
    val listed: Boolean = true,
    /** Id público do anfitrião que anuncia (§16.10: distingue o original de quem assumiu). */
    val hostId: Int = 0,
    /** §16.10 geração do anfitrião: 0 = o original; cada sucessão soma 1. */
    val epoch: Int = 0,
    /** §16.10 posição do sucessor que assumiu por queda e ainda não foi confirmado (1 ou 2); 0 = confirmado. */
    val provisionalRank: Int = 0,
    /**
     * §16.2 chaves de presença ([PresenceAdvert.key]) dos jogadores próximos convidados e ainda sem resposta: quem acha a
     * própria chave aqui conecta-se ao anfitrião e recebe o [Invite] (no `P2P_STAR` quem anuncia é o centro, então o
     * convite não pode ir ao aparelho sem que ele se conecte).
     */
    val invited: List<String> = emptyList(),
) {
    /**
     * §16.10 este anúncio prevalece sobre [other] (da mesma mesa): o anfitrião confirmado mais recente, depois o original,
     * depois o provisório (o 1º antes do 2º); empate (raro, ex.: dois confirmados da mesma geração) vai ao menor id, para a
     * ordem ser total e só um deixar o cargo.
     */
    fun outranks(other: TableAdvert): Boolean =
        compareValuesBy(this, other, { if (it.provisionalRank == 0) 1 else 0 }, { it.epoch }, { -it.provisionalRank }, { -it.hostId }) > 0
}

/**
 * §16.2 presença de um **jogador próximo** (Wi-Fi do jogo ligado, fora de um jogo): só a [key] (curta, aleatória, gerada
 * por [newPresenceKey] a cada vez que o Wi-Fi do jogo liga) e o [name]. Distinto do anúncio de mesa ([TableAdvert]). A foto
 * não vai aqui: vai pela conexão, depois do convite.
 */
data class PresenceAdvert(
    val key: String,
    val name: String,
    /** §16.2 aviso de "saindo" (Wi-Fi do jogo desligando): quem vê tira a [key] da lista na hora. */
    val leaving: Boolean = false,
)

/** §16.2 chave de presença nova (6 caracteres, cabe no anúncio). Não identifica o jogador fora desta sessão de busca. */
fun newPresenceKey(random: java.util.Random = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return String(CharArray(6) { alphabet[random.nextInt(alphabet.length)] })
}

/** §16.2 a mesa que convidou a presença [key] (para o convidado conectar e receber o [Invite]), ou `null`. */
fun List<TableAdvert>.invitationFor(key: String): TableAdvert? = firstOrNull { key in it.invited }

/** §16.10 o anúncio preferido da mesa [tableId] entre os encontrados (ou `null` se ela não está na busca). */
fun List<TableAdvert>.preferred(tableId: String): TableAdvert? =
    filter { it.tableId == tableId }.reduceOrNull { best, a -> if (a.outranks(best)) a else best }

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

    /** Convidado: conecta-se à mesa anunciada por [advert] (aquele anfitrião); devolve a ponta dele. */
    suspend fun connect(advert: TableAdvert): EndpointId

    suspend fun send(endpoint: EndpointId, bytes: ByteArray)

    suspend fun disconnect(endpoint: EndpointId)

    /**
     * §16.2 anuncia a presença deste jogador (substitui o anúncio de presença anterior). A presença e a mesa
     * ([advertise]) **se excluem**: quem abre uma mesa deixa de anunciar presença (no Nearby há um anúncio por vez).
     */
    suspend fun advertisePresence(presence: PresenceAdvert) {}

    /** §16.2 deixa de anunciar a presença (entrou num jogo ou desligou o Wi-Fi do jogo). */
    suspend fun stopPresence() {}

    /** §16.2 conecta-se ao jogador próximo que anuncia [presence] (só para pedir a foto, [PresencePhotos]). */
    suspend fun connectPresence(presence: PresenceAdvert): EndpointId = error("Presença não encontrada")

    /** §16.2 jogadores próximos que anunciam presença agora (sem este aparelho). */
    fun discoverPresence(): Flow<List<PresenceAdvert>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

/** Uma entrega na [MemoryNetwork] (de [from] para [to]). */
class Delivery(val from: EndpointId, val to: EndpointId, val bytes: ByteArray)

/**
 * Rede em memória: anfitrião e convidados no mesmo processo (testes JVM, sem Android e sem relógio real). Entrega
 * na hora, em ordem; [tap] vê (e pode alterar) cada entrega: devolver vazio perde a mensagem, duas cópias a duplicam, e
 * guardar para entregar depois com [deliver] a reordena. Cada anfitrião tem o seu anúncio (dois podem anunciar a mesma
 * mesa, §16.10). Uma ponta fora do alcance ([block]) não vê nem é vista na busca e não conecta.
 */
class MemoryNetwork {
    private val nodes = mutableMapOf<EndpointId, MemoryTransport>()
    private val tables = MutableStateFlow<Map<EndpointId, TableAdvert>>(emptyMap())
    private var counter = 0

    /** Pares conectados agora: entregas só entre eles (uma queda corta o par). */
    private val links = mutableSetOf<Pair<EndpointId, EndpointId>>()

    /** Pontas fora do alcance agora: não veem nem são vistas na busca, e as conexões falham. */
    private val blocked = MutableStateFlow<Set<EndpointId>>(emptySet())

    /** Pontas "mortas" (aparelho desligado): não anunciam, não veem, não enviam e não conectam mais. */
    private val dead = mutableSetOf<EndpointId>()

    /** Pontas que veem a busca mas cujas conexões falham (rádio instável). */
    private val jammed = mutableSetOf<EndpointId>()

    /** As conexões da ponta [id] falham (ela continua vendo as mesas) até [unjam]. */
    @Synchronized
    fun jam(id: EndpointId) { jammed += id }

    @Synchronized
    fun unjam(id: EndpointId) { jammed -= id }

    /** A ponta [id] fica fora do alcance até [unblock]. */
    fun block(id: EndpointId) = blocked.update { it + id }

    fun unblock(id: EndpointId) = blocked.update { it - id }

    /** O aparelho [id] some de vez (queda abrupta, §16.10): como [drop], e nunca mais anuncia nem envia. */
    fun kill(id: EndpointId) {
        synchronized(this) { dead += id }
        blocked.update { it + id }
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

    /** Anúncios que a ponta [viewer] vê agora. */
    internal fun visibleTo(viewer: EndpointId): Flow<List<TableAdvert>> = combine(tables, blocked) { map, out ->
        if (viewer in out) emptyList() else map.filterKeys { it !in out }.values.toList()
    }.distinctUntilChanged()

    /** Todos os anúncios (visão de fora da rede, para os testes). */
    fun adverts(): List<TableAdvert> = tables.value.values.toList()

    internal fun advertise(advert: TableAdvert, host: EndpointId) {
        if (synchronized(this) { host in dead }) return
        tables.update { it + (host to advert) }
    }

    internal fun stopAdvertising(host: EndpointId) = tables.update { it - host }

    private val presences = MutableStateFlow<Map<EndpointId, PresenceAdvert>>(emptyMap())

    internal fun advertisePresence(presence: PresenceAdvert, from: EndpointId) {
        if (synchronized(this) { from in dead }) return
        presences.update { it + (from to presence) }
    }

    internal fun stopPresence(from: EndpointId) = presences.update { it - from }

    @Synchronized
    internal fun connectPresence(from: EndpointId, presence: PresenceAdvert): EndpointId {
        val target = presences.value.entries.firstOrNull { it.value.key == presence.key }?.key ?: error("Presença não encontrada")
        val out = blocked.value
        check(from !in out && target !in out && from !in dead && target !in dead && from !in jammed) { "Fora do alcance" }
        links += key(from, target)
        node(target).push(TransportEvent.Connected(from))
        node(from).push(TransportEvent.Connected(target))
        return target
    }

    internal fun presencesVisibleTo(viewer: EndpointId): Flow<List<PresenceAdvert>> = combine(presences, blocked) { map, out ->
        if (viewer in out) emptyList() else map.filterKeys { it !in out && it != viewer }.values.toList()
    }.distinctUntilChanged()

    @Synchronized
    internal fun connect(guest: EndpointId, advert: TableAdvert): EndpointId {
        val host = tables.value.entries.firstOrNull { it.value.tableId == advert.tableId && it.value.hostId == advert.hostId }?.key
            ?: error("Mesa não encontrada")
        val out = blocked.value
        check(guest !in out && host !in out && guest !in dead && host !in dead && guest !in jammed) { "Fora do alcance" }
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
     * Queda abrupta da ponta [id] (sem aviso do jogo): todos os pares dela recebem a desconexão. O anúncio fica (o
     * aparelho segue vivo, só fora do alcance) a menos que ele tenha morrido ([kill]).
     */
    fun drop(id: EndpointId) {
        val (peers, gone) = synchronized(this) {
            val mine = links.filter { it.first == id || it.second == id }
            links.removeAll(mine.toSet())
            mine.map { if (it.first == id) it.second else it.first } to (id in dead)
        }
        if (gone) stopAdvertising(id)
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

    override fun discover(): Flow<List<TableAdvert>> = network.visibleTo(id)

    override suspend fun connect(advert: TableAdvert): EndpointId = network.connect(id, advert)

    override suspend fun send(endpoint: EndpointId, bytes: ByteArray) = network.send(id, endpoint, bytes)

    override suspend fun disconnect(endpoint: EndpointId) = network.disconnect(id, endpoint)

    override suspend fun advertisePresence(presence: PresenceAdvert) = network.advertisePresence(presence, id)

    override suspend fun stopPresence() = network.stopPresence(id)

    override fun discoverPresence(): Flow<List<PresenceAdvert>> = network.presencesVisibleTo(id)

    override suspend fun connectPresence(presence: PresenceAdvert): EndpointId = network.connectPresence(id, presence)
}
