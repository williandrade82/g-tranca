package com.gtranca.net.nearby

/**
 * Nearby falso para a JVM: imita o que importa do Nearby Connections (anúncio com `endpointInfo`, busca com
 * achado/perdido, pedido de conexão aceito pelos dois lados, bytes, e a desconexão que só avisa o **outro** lado).
 * Tudo síncrono.
 */
class FakeNearbyNetwork {
    private var counter = 0
    val nodes = mutableMapOf<String, FakeNearbyApi>()

    /** Como o Nearby real: parar de anunciar não avisa `onLost` a quem procura (ou avisa muito depois). */
    var silentStop = false

    fun api(): FakeNearbyApi = FakeNearbyApi(this, "n${++counter}").also { nodes[it.id] = it }

    internal fun advertisers() = nodes.values.filter { it.advertInfo != null }

    internal fun announce(from: FakeNearbyApi) {
        val info = from.advertInfo ?: return
        nodes.values.filter { it !== from }.forEach { it.discovery?.onFound(from.id, info) }
    }

    internal fun withdraw(from: FakeNearbyApi) {
        nodes.values.filter { it !== from }.forEach { it.discovery?.onLost(from.id) }
    }

    /** Aparelho some (desligado/fora do alcance): os pares recebem `onDisconnected` e a busca, `onLost`. */
    fun vanish(id: String) {
        val node = nodes.getValue(id)
        node.advertInfo = null
        withdraw(node)
        node.links.keys.toList().forEach { peer -> nodes[peer]?.remoteDisconnected(id); node.links.remove(peer) }
    }
}

class FakeNearbyApi(private val network: FakeNearbyNetwork, val id: String) : NearbyApi {
    var advertInfo: ByteArray? = null
    private var advertCallback: ConnectionCallback? = null
    var discovery: DiscoveryCallback? = null
    var advertiseStarts = 0
    var advertiseStops = 0
    var discoveryStarts = 0
    var discoveryStops = 0
    val sent = mutableListOf<ByteArray>()

    internal class Link(val callback: ConnectionCallback, var onBytes: ((String, ByteArray) -> Unit)? = null, var connected: Boolean = false)

    internal val links = mutableMapOf<String, Link>()

    override val maxBytes: Int = 1047552

    override suspend fun startAdvertising(endpointInfo: ByteArray, callback: ConnectionCallback) {
        check(advertInfo == null) { "STATUS_ALREADY_ADVERTISING" }
        advertiseStarts++
        advertInfo = endpointInfo
        advertCallback = callback
        network.announce(this)
    }

    override fun stopAdvertising() {
        if (advertInfo == null) return
        advertiseStops++
        advertInfo = null
        if (!network.silentStop) network.withdraw(this)
    }

    override suspend fun startDiscovery(callback: DiscoveryCallback) {
        check(discovery == null) { "STATUS_ALREADY_DISCOVERING" }
        discoveryStarts++
        discovery = callback
        network.advertisers().filter { it !== this }.forEach { callback.onFound(it.id, it.advertInfo!!) }
    }

    override fun stopDiscovery() {
        if (discovery != null) discoveryStops++
        discovery = null
    }

    override suspend fun requestConnection(localInfo: ByteArray, endpointId: String, callback: ConnectionCallback) {
        val target = network.nodes[endpointId] ?: error("STATUS_ENDPOINT_UNKNOWN")
        val targetCallback = target.advertCallback ?: error("STATUS_ENDPOINT_UNKNOWN")
        links[endpointId] = Link(callback)
        target.links[id] = Link(targetCallback)
        targetCallback.onInitiated(id)
        callback.onInitiated(endpointId)
    }

    override suspend fun acceptConnection(endpointId: String, onBytes: (String, ByteArray) -> Unit) {
        val link = links[endpointId] ?: error("STATUS_ENDPOINT_UNKNOWN")
        link.onBytes = onBytes
        val other = network.nodes.getValue(endpointId).links[id] ?: return
        if (other.onBytes != null && !link.connected) {
            link.connected = true
            other.connected = true
            link.callback.onResult(endpointId, true)
            other.callback.onResult(id, true)
        }
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray) {
        if (links[endpointId]?.connected != true) error("STATUS_ENDPOINT_UNKNOWN")
        check(bytes.size <= maxBytes)
        sent += bytes
        network.nodes.getValue(endpointId).links[id]?.onBytes?.invoke(id, bytes)
    }

    override fun disconnect(endpointId: String) {
        if (links.remove(endpointId) == null) return
        network.nodes[endpointId]?.remoteDisconnected(id)
    }

    internal fun remoteDisconnected(peer: String) {
        links.remove(peer)?.callback?.onDisconnected(peer)
    }
}
