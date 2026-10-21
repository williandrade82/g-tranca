package com.gtranca.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gtranca.net.EndpointId
import com.gtranca.net.TableAdvert
import com.gtranca.net.TransportEvent
import com.gtranca.net.nearby.MultiplayerAccess
import com.gtranca.net.nearby.MultiplayerAccessGate
import com.gtranca.net.nearby.NearbyTransport
import com.gtranca.net.nearby.newTableId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Laboratório do Nearby (só no build debug; sem telas do jogo, etapa 5): testa o transporte cru em celulares reais.
 * Abrir com `adb shell am start -n br.com.funnyandplay.trancabot/com.gtranca.debug.NearbyLabActivity`.
 * Não mostra nem envia nada do jogo: só bytes de teste.
 */
class NearbyLabActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob())
    private var transport: NearbyTransport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var ready by remember { mutableStateOf(false) }
                    if (!ready) {
                        MultiplayerAccessGate(onReady = { ready = true }, onCancel = { finish() })
                    } else {
                        val t = remember { MultiplayerAccess.transport(this, scope).also { transport = it } }
                        Lab(t)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        val t = transport
        scope.launch { t?.close() }.invokeOnCompletion { scope.coroutineContext[Job]?.cancel() }
        super.onDestroy()
    }
}

private const val PING: Byte = 1
private const val PONG: Byte = 2
private const val BULK: Byte = 3

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Lab(t: NearbyTransport) {
    val ui = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val peers = remember { mutableStateListOf<EndpointId>() }
    var tables by remember { mutableStateOf<List<TableAdvert>>(emptyList()) }
    var advert by remember { mutableStateOf<TableAdvert?>(null) }
    var discovering by remember { mutableStateOf<Job?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    fun add(line: String) { log.add(0, "${fmt.format(Date())}  $line"); if (log.size > 200) log.removeAt(log.lastIndex) }

    LaunchedEffect(t) {
        t.events.collect { e ->
            when (e) {
                is TransportEvent.Connected -> { peers += e.endpoint; add("conectado: ${e.endpoint.value}") }
                is TransportEvent.Disconnected -> { peers -= e.endpoint; add("desconectado: ${e.endpoint.value}") }
                is TransportEvent.Received -> when (e.bytes.firstOrNull()) {
                    PING -> t.send(e.endpoint, e.bytes.copyOf().also { it[0] = PONG })
                    PONG -> add("pong de ${e.endpoint.value}: ${System.currentTimeMillis() - ByteBuffer.wrap(e.bytes, 1, 8).long} ms")
                    BULK -> add("recebidos ${e.bytes.size} bytes de ${e.endpoint.value}")
                    else -> add("bytes desconhecidos")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Laboratório Nearby (Android ${Build.VERSION.SDK_INT}, ${Build.MODEL})", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val a = advert?.let { it.copy(epoch = it.epoch + 1, listed = !it.listed) }
                    ?: TableAdvert(newTableId(), "Mesa de teste", Build.MODEL.take(20), hostId = 0)
                advert = a
                ui.launch { runCatching { t.advertise(a) }.onSuccess { add("anunciando ${a.tableId} epoch=${a.epoch} listed=${a.listed}") }.onFailure { add("falha ao anunciar: ${it.message}") } }
            }) { Text(if (advert == null) "Anunciar" else "Mudar anúncio") }
            Button(onClick = { ui.launch { t.stopAdvertising(); advert = null; add("anúncio parado") } }) { Text("Parar anúncio") }
            Button(onClick = {
                discovering?.let { it.cancel(); discovering = null; add("busca parada (desliga em 10 s)"); return@Button }
                add("procurando...")
                discovering = ui.launch {
                    t.discover().collect { list ->
                        val before = tables.map { it.tableId to it.hostId }.toSet()
                        val after = list.map { it.tableId to it.hostId }.toSet()
                        (after - before).forEach { add("mesa achada: ${it.first}") }
                        (before - after).forEach { add("mesa sumiu: ${it.first}") }
                        tables = list
                    }
                }
            }) { Text(if (discovering == null) "Procurar" else "Parar busca") }
            Button(onClick = {
                peers.forEach { p -> ui.launch { t.send(p, ByteBuffer.allocate(9).put(PING).putLong(System.currentTimeMillis()).array()) } }
            }) { Text("Ping") }
            Button(onClick = {
                peers.forEach { p -> ui.launch { t.send(p, ByteArray(100_000).also { it[0] = BULK }); add("enviados 100000 bytes") } }
            }) { Text("Enviar 100 KB") }
            Button(onClick = { peers.toList().forEach { p -> ui.launch { t.disconnect(p) } } }) { Text("Desconectar") }
        }
        tables.forEach { table ->
            Button(onClick = {
                ui.launch {
                    add("conectando a ${table.tableId}...")
                    runCatching { t.connect(table) }.onFailure { add("falha: ${it.message}") }
                }
            }) { Text("Conectar: ${table.tableName} / ${table.hostName} (epoch ${table.epoch}, ${if (table.listed) "na lista" else "fora da lista"})") }
        }
        Text("Pares: ${peers.joinToString { it.value }}")
        LazyColumn(Modifier.fillMaxSize()) { items(log) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
