package com.gtranca.online.probe

import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.Resign
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.YouResigned
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cenarios da etapa 1.3c (relogio de jogada, desistencia no 1x1, mesa encerrada). Os jogadores aqui ficam QUIETOS (so
 * escutam): o que se prova e o comportamento do servidor, nao a habilidade de jogo.
 */
class Scenarios(private val server: String, private val http: HttpClient, private val scope: CoroutineScope) {
    private class Watcher(val name: String, val messages: CopyOnWriteArrayList<ServerMessage>, val job: Job) {
        @Volatile
        var closeInfo: String? = null
    }

    private suspend fun lobby(method: HttpMethod, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val r = http.request("http://$server/api/lobby/$path") {
            this.method = method
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return r.status.value to r.bodyAsText()
    }

    private suspend fun guest(name: String): GuestIdentity = OnlineJson.decodeFromString(
        GuestIdentity.serializer(),
        lobby(HttpMethod.Post, "guests", null, OnlineJson.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest(name))).second,
    )

    private suspend fun tableState(id: String, token: String): String =
        OnlineJson.decodeFromString(TableView.serializer(), lobby(HttpMethod.Get, "tables/$id", token).second).state.name

    private suspend fun newTable(host: GuestIdentity, rules: TableRules): TableView {
        val (code, text) = lobby(HttpMethod.Post, "tables", host.token, OnlineJson.encodeToString(CreateTableRequest.serializer(), CreateTableRequest(rules)))
        check(code == 201) { "criar mesa -> $code $text" }
        return OnlineJson.decodeFromString(TableView.serializer(), text)
    }

    private fun watch(name: String, tableId: String, token: String, onFrame: (ServerMessage) -> Unit = {}): Watcher {
        val messages = CopyOnWriteArrayList<ServerMessage>()
        lateinit var w: Watcher
        val job = scope.launch {
            http.webSocket("ws://$server/ws/match/$tableId", request = {
                header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
                header(HttpHeaders.Authorization, "Bearer $token")
            }) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val m = OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())
                    messages += m
                    when (m) {
                        is SeatsChanged -> println("[$name] mesa: assento ${m.seat} ${m.change}${m.reason?.let { " ($it)" } ?: ""}; tipos=${m.seats.map { it.kind.name + if (it.resigned) "*" else "" }}")
                        is YouResigned -> println("[$name] YouResigned: ${m.message}")
                        is TableClosed -> println("[$name] TableClosed: ${m.message}")
                        is SeatUpdate -> m.view.let { v ->
                            if (v.turn != null || v.stage.name == "GAME_OVER") {
                                println("[$name] vista v${v.version}: vez do assento ${v.currentSeat.index}, turn=${v.turn}, stage=${v.stage}, winner=${v.winner?.index}, endReason=${v.endReason}")
                            }
                        }
                        else -> Unit
                    }
                    onFrame(m)
                }
                closeInfo(w, closeReason.await()?.let { "${it.code} ${it.message}" })
            }
        }
        w = Watcher(name, messages, job)
        return w
    }

    private fun closeInfo(w: Watcher, text: String?) {
        w.closeInfo = text
        println("[${w.name}] conexao fechada: ${text ?: "sem motivo"}")
    }

    private suspend fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(200) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            println("!! TEMPO ESGOTADO esperando: $what")
        }
    }

    /** Mesa de duplas com tempo de 1 minuto e dois humanos quietos: a vez do humano esgota e um robo assume. */
    suspend fun tempo() {
        val ana = guest("Ana")
        val beto = guest("Beto")
        val t = newTable(ana, TableRules(TableMode.DUPLAS, "{}", targetScore = 3000, tempoDeJogada = TempoDeJogada.UM_MINUTO, robotLevel = BotLevel.FACIL))
        lobby(HttpMethod.Post, "tables/${t.id}/join", beto.token, "{}")
        lobby(HttpMethod.Post, "tables/${t.id}/seats/2/robot", ana.token)
        lobby(HttpMethod.Post, "tables/${t.id}/seats/3/robot", ana.token)
        println("mesa ${t.id} (duplas, 1 MIN) iniciada: ${lobby(HttpMethod.Post, "tables/${t.id}/start", ana.token).first}")
        val a = watch("Ana", t.id, ana.token)
        val b = watch("Beto", t.id, beto.token)
        println("... Ana e Beto quietos; esperando o relogio de 60 s da vez de um deles (queda de conexao nao o para)")
        waitFor(150_000, "um abandono por tempo") { (a.messages + b.messages).any { it is SeatsChanged && it.change.name == "RESIGNED" } }
        delay(3_000)
        println("lobby: estado da mesa depois do abandono = ${tableState(t.id, ana.token)} (o jogo segue com robo no lugar)")
        println("anfitriao encerra a mesa para limpar:")
        println("DELETE -> ${lobby(HttpMethod.Delete, "tables/${t.id}", ana.token).first}")
        delay(2_000)
        println("lobby: estado final = ${tableState(t.id, ana.token)}")
        a.job.cancel(); b.job.cancel()
    }

    /** 1x1 entre dois humanos: Beto desiste e Ana vence na hora; o lobby marca ENCERRADA. */
    suspend fun desistir() {
        val ana = guest("Ana")
        val beto = guest("Beto")
        val t = newTable(ana, TableRules(TableMode.INDIVIDUAL, "{}", targetScore = 3000))
        lobby(HttpMethod.Post, "tables/${t.id}/join", beto.token, "{}")
        println("mesa ${t.id} (1x1 entre humanos) iniciada: ${lobby(HttpMethod.Post, "tables/${t.id}/start", ana.token).first}; estado=${tableState(t.id, ana.token)}")
        val a = watch("Ana", t.id, ana.token)
        var betoSession: Job? = null
        val b = watch("Beto", t.id, beto.token) { m ->
            // Assim que o Beto recebe a 1a vista, desiste.
            if (m is SeatUpdate && betoSession == null) betoSession = scope.launch { }
        }
        waitFor(10_000, "os dois conectados") { a.messages.isNotEmpty() && b.messages.isNotEmpty() }
        sendResign(t.id, beto.token)
        waitFor(10_000, "fim do jogo por abandono") { a.messages.any { it is SeatUpdate && it.view.stage.name == "GAME_OVER" } }
        delay(2_000)
        println("lobby: estado da mesa = ${tableState(t.id, ana.token)}")
        a.job.cancel(); b.job.cancel()
    }

    /** Abre uma conexao curta so para enviar a desistencia do jogador. */
    private suspend fun sendResign(tableId: String, token: String) {
        http.webSocket("ws://$server/ws/match/$tableId", request = {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer $token")
        }) {
            // A nova conexao substitui a do observador (4001); a primeira mensagem e o Welcome com o ultimo seq.
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val m = OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())
                if (m is com.gtranca.online.contracts.Welcome) {
                    send(Frame.Text(OnlineJson.encodeToString(ClientMessage.serializer(), Resign(m.lastSeq + 1))))
                    println("[Beto] enviou Resign")
                }
                if (m is YouResigned) println("[Beto] YouResigned: ${m.message}")
            }
        }
    }

    /** O anfitriao encerra a mesa em jogo: os dois clientes recebem TableClosed, o lobby marca ENCERRADA. */
    suspend fun encerrar() {
        val ana = guest("Ana")
        val beto = guest("Beto")
        val t = newTable(ana, TableRules(TableMode.DUPLAS, "{}", targetScore = 3000, robotLevel = BotLevel.FACIL))
        lobby(HttpMethod.Post, "tables/${t.id}/join", beto.token, "{}")
        lobby(HttpMethod.Post, "tables/${t.id}/seats/2/robot", ana.token)
        lobby(HttpMethod.Post, "tables/${t.id}/seats/3/robot", ana.token)
        println("mesa ${t.id} iniciada: ${lobby(HttpMethod.Post, "tables/${t.id}/start", ana.token).first}; estado=${tableState(t.id, ana.token)}")
        val a = watch("Ana", t.id, ana.token)
        val b = watch("Beto", t.id, beto.token)
        waitFor(10_000, "os dois conectados") { a.messages.isNotEmpty() && b.messages.isNotEmpty() }
        println("Beto (nao anfitriao) tenta encerrar: ${lobby(HttpMethod.Delete, "tables/${t.id}", beto.token).first}")
        val r = lobby(HttpMethod.Delete, "tables/${t.id}", ana.token)
        println("Ana (anfitria) encerra: ${r.first}")
        waitFor(10_000, "TableClosed nos dois") { a.messages.any { it is TableClosed } && b.messages.any { it is TableClosed } }
        waitFor(5_000, "conexoes fechadas") { a.closeInfo != null && b.closeInfo != null }
        println("lobby: estado da mesa = ${tableState(t.id, ana.token)}")
        val again = http.get("http://$server/ws/match/${t.id}") {
            header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
            header(HttpHeaders.Authorization, "Bearer ${ana.token}")
        }
        println("reconectar depois de encerrada -> ${again.status.value} ${again.bodyAsText()}")
        a.job.cancel(); b.job.cancel()
    }
}
