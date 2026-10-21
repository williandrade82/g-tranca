package com.gtranca.net.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [NearbyApi] real: Nearby Connections do Google Play Services, estratégia `P2P_STAR` (1 anfitrião + até 3 convidados). */
class GmsNearbyApi(context: Context) : NearbyApi {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    override val maxBytes: Int = ConnectionsClient.MAX_BYTES_DATA_SIZE

    private fun lifecycle(callback: ConnectionCallback) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) = callback.onInitiated(endpointId)
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) =
            callback.onResult(endpointId, resolution.status.isSuccess)
        override fun onDisconnected(endpointId: String) = callback.onDisconnected(endpointId)
    }

    override suspend fun startAdvertising(endpointInfo: ByteArray, callback: ConnectionCallback) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(endpointInfo, SERVICE_ID, lifecycle(callback), options).await()
    }

    override fun stopAdvertising() = client.stopAdvertising()

    override suspend fun startDiscovery(callback: DiscoveryCallback) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (info.serviceId == SERVICE_ID) callback.onFound(endpointId, info.endpointInfo)
                }
                override fun onEndpointLost(endpointId: String) = callback.onLost(endpointId)
            },
            options,
        ).await()
    }

    override fun stopDiscovery() = client.stopDiscovery()

    override suspend fun requestConnection(localInfo: ByteArray, endpointId: String, callback: ConnectionCallback) {
        client.requestConnection(localInfo, endpointId, lifecycle(callback)).await()
    }

    override suspend fun acceptConnection(endpointId: String, onBytes: (String, ByteArray) -> Unit) {
        client.acceptConnection(
            endpointId,
            object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { onBytes(endpointId, it) }
                }
                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
            },
        ).await()
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).await()
    }

    override fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    companion object {
        /** Serviço do Nearby: só aparelhos com este app se veem. */
        const val SERVICE_ID: String = "br.com.funnyandplay.trancabot.mesa"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
