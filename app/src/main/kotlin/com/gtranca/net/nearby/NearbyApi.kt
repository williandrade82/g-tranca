package com.gtranca.net.nearby

/**
 * O que o [NearbyTransport] usa do Nearby Connections, só com tipos de Kotlin (sem Android nem Play Services), para a
 * lógica ser testada na JVM com um falso. A implementação real é [GmsNearbyApi]. Funções `suspend` lançam exceção na falha.
 * Os retornos de chamada podem vir de qualquer thread.
 */
interface NearbyApi {
    suspend fun startAdvertising(endpointInfo: ByteArray, callback: ConnectionCallback)
    fun stopAdvertising()
    suspend fun startDiscovery(callback: DiscoveryCallback)
    fun stopDiscovery()
    suspend fun requestConnection(localInfo: ByteArray, endpointId: String, callback: ConnectionCallback)
    suspend fun acceptConnection(endpointId: String, onBytes: (endpointId: String, bytes: ByteArray) -> Unit)
    suspend fun sendBytes(endpointId: String, bytes: ByteArray)

    /** Desconexão local: o Nearby **não** avisa o próprio lado (só o outro recebe `onDisconnected`). */
    fun disconnect(endpointId: String)

    /** Tamanho máximo de um payload BYTES. */
    val maxBytes: Int
}

/** O endpoint pedido já não existe no Nearby (8011): o anúncio antigo morreu quando o aparelho reanunciou. */
class EndpointUnknownException(message: String) : Exception(message)

interface ConnectionCallback {
    fun onInitiated(endpointId: String)
    fun onResult(endpointId: String, success: Boolean)
    fun onDisconnected(endpointId: String)
}

interface DiscoveryCallback {
    fun onFound(endpointId: String, endpointInfo: ByteArray)
    fun onLost(endpointId: String)
}
