package com.gtranca.net.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
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
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR)
            .setConnectionType(ConnectionType.NON_DISRUPTIVE).setDisruptiveUpgrade(false).setLowPower(LOW_POWER).build()
        try {
            client.startAdvertising(endpointInfo, SERVICE_ID, lifecycle(callback), options).await()
        } catch (e: com.google.android.gms.common.api.ApiException) {
            // 8001: um anúncio anterior (mesa anterior ou estado perdido) ainda está ativo no serviço: para e anuncia de novo.
            if (e.statusCode != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) throw e
            client.stopAdvertising()
            client.startAdvertising(endpointInfo, SERVICE_ID, lifecycle(callback), options).await()
        }
    }

    override fun stopAdvertising() = client.stopAdvertising()

    override suspend fun startDiscovery(callback: DiscoveryCallback) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).setLowPower(LOW_POWER).build()
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
        val options = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.NON_DISRUPTIVE).setDisruptiveUpgrade(false).setLowPower(LOW_POWER).build()
        client.requestConnection(localInfo, endpointId, lifecycle(callback), options).await()
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

        /**
         * §16.15 sem troca de banda que atrapalhe: as mensagens são pequenas (fotos até 20 KB), então a conexão não precisa
         * subir para Wi-Fi Direct/hotspot; a troca de meio deixava o Nearby ocupado por dezenas de segundos e travava a
         * entrada na mesa. `NON_DISRUPTIVE` + `disruptiveUpgrade = false` valem nos dois lados. `lowPower` (só BLE) fica
         * desligado: os anúncios têm até 100 bytes e o BLE de baixa potência pode não os levar inteiros.
         */
        const val LOW_POWER: Boolean = false
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
