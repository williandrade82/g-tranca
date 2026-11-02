package com.gtranca.online.probe

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.online.contracts.AppSignature
import com.gtranca.online.contracts.BoosterConfirmFailed
import com.gtranca.online.contracts.BoosterPrompt
import com.gtranca.online.contracts.BoostersConfirmed
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.CreateGuestRequest
import com.gtranca.online.contracts.CreateTableRequest
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.PROTOCOL_HEADER
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.SelectBoosters
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.Welcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * Prova da etapa 1.3b no cluster: 2 convidados, mesa dupla (2 robos), jogo por WebSocket ate o fim, com uma queda e
 * reconexao do Beto. Uso: `probe <servidor> [pontuacao-alvo] [queda-apos-N-acoes] [jogo|tempo|desistir|encerrar]` (ex.: `probe 192.168.1.5 200 8`).
 */
fun main(args: Array<String>) = runBlocking {
    val server = args.getOrNull(0) ?: error("uso: probe <servidor> [pontuacao-alvo] [queda-apos-N-acoes]")
    val target = args.getOrNull(1)?.toInt() ?: 200
    val dropAfter = args.getOrNull(2)?.toInt() ?: 8
    val http = HttpClient(CIO) { install(WebSockets) }
    // §18.2: a chave do aplicativo vem do ambiente (a mesma do Secret do cluster); sem ela os pedidos vao sem assinatura.
    val appKey = System.getenv("APP_SIGNING_KEY")?.takeIf { it.isNotBlank() }
    fun io.ktor.client.request.HttpRequestBuilder.app(method: String, path: String, token: String?, version: String = ONLINE_PROTOCOL_VERSION.toString()) {
        header(PROTOCOL_HEADER, version)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (appKey != null) {
            val now = System.currentTimeMillis()
            header(AppSignature.TIME_HEADER, now.toString())
            header(AppSignature.SIGNATURE_HEADER, AppSignature.sign(appKey, method, path, now, token))
        }
    }

    // Etapa 1.3c: cenarios de relogio, desistencia no 1x1 e mesa encerrada (4o argumento).
    // Os cenarios de relogio (tempo|desistir|encerrar, Scenarios.kt) criavam mesas proprias: nao rodam com as mesas do
    // servidor (§18.2). Ficam no codigo para a futura mesa criada pelo jogador.
    args.getOrNull(3)?.let { require(it == "jogo") { "nesta fase so o cenario 'jogo' roda (mesas do servidor, §18.2)" } }

    suspend fun lobby(path: String, token: String?, body: String): String {
        val r = http.post("http://$server/api/lobby/$path") {
            app("POST", "/api/lobby/$path", token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(r.status.value in 200..299) { "lobby $path -> ${r.status.value} ${r.bodyAsText()}" }
        return r.bodyAsText()
    }

    val ana = OnlineJson.decodeFromString(
        GuestIdentity.serializer(),
        lobby("guests", null, OnlineJson.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest("Ana"))),
    )
    val beto = OnlineJson.decodeFromString(
        GuestIdentity.serializer(),
        lobby("guests", null, OnlineJson.encodeToString(CreateGuestRequest.serializer(), CreateGuestRequest("Beto"))),
    )
    println("convidados: Ana=${ana.id.take(8)} Beto=${beto.id.take(8)}")

    // §18.2: as mesas sao do servidor; Ana entra na primeira aberta com 2 lugares livres e vira anfitria.
    val unsigned = http.get("http://$server/api/lobby/tables") {
        header(PROTOCOL_HEADER, ONLINE_PROTOCOL_VERSION.toString())
        header(HttpHeaders.Authorization, "Bearer ${ana.token}")
    }
    println("lista sem assinatura do app -> ${unsigned.status.value}")
    val list = OnlineJson.decodeFromString(
        TableListView.serializer(),
        http.get("http://$server/api/lobby/tables") { app("GET", "/api/lobby/tables", ana.token) }.bodyAsText(),
    )
    println("lista: ${list.tables.size} mesa(s), ${list.onlinePlayers} jogador(es) online")
    val open = list.tables.first { it.state == TableState.ABERTA && it.seats.count { s -> s.kind == SeatKind.VAGO } >= 2 }
    val table = OnlineJson.decodeFromString(TableView.serializer(), lobby("tables/${open.id}/join", ana.token, "{}"))
    println("mesa ${table.id}: anfitriao=${table.hostName} (alvo ${table.rules.targetScore}, ${table.rules.tempoDeJogada})")
    lobby("tables/${table.id}/join", beto.token, "{}")
    val started = OnlineJson.decodeFromString(TableView.serializer(), lobby("tables/${table.id}/start", ana.token, ""))
    println("mesa iniciada: estado=${started.state}")

    // Recusas do handshake (antes do upgrade) e a rota interna fechada no ingress.
    val bad = http.get("http://$server/ws/match/${table.id}") { app("GET", "/ws/match/${table.id}", ana.token, version = "0") }
    println("handshake com versao 0 -> ${bad.status.value} ${bad.bodyAsText()}")
    val noAuth = http.get("http://$server/ws/match/${table.id}") { app("GET", "/ws/match/${table.id}", null) }
    println("handshake sem token -> ${noAuth.status.value} ${noAuth.bodyAsText()}")
    println("GET /internal/sim pelo ingress -> ${http.get("http://$server/internal/sim").status.value}")

    class Brain(val name: String, val token: String) {
        var seat = -1
        var seq = 0L
        var view: OnlineSeatView? = null
        var welcome: Welcome? = null
        var actions = 0
        var rejections = 0
        var rounds = 0
        val bot = createBot(Difficulty.FACIL, Random(name.hashCode().toLong()))
        var lastRound = 0
    }

    suspend fun DefaultClientWebSocketSession.sendMsg(m: ClientMessage) =
        send(Frame.Text(OnlineJson.encodeToString(ClientMessage.serializer(), m)))

    /** Joga a vez se houver jogadas legais na vista atual. Devolve true se derrubou a conexao (queda simulada). */
    suspend fun DefaultClientWebSocketSession.maybeAct(b: Brain, dropAt: Int, injectIllegal: Boolean): Boolean {
        val v = b.view ?: return false
        if (v.stage != TableStage.PLAYING || v.legal.isEmpty()) return false
        if (injectIllegal && b.actions == 3) {
            // Jogada ilegal de proposito: descartar uma carta que nao esta na mao.
            val foreign = Card.parse(if (Card.parse("AS") in v.hand) "AH" else "AS")
            sendMsg(PlayAction(++b.seq, v.version, Action.Discard(foreign)))
            println("[${b.name}] enviou jogada ilegal de proposito (seq=${b.seq})")
            b.actions++
            return false
        }
        if (dropAt > 0 && b.actions >= dropAt) {
            println("[${b.name}] DERRUBANDO a conexao (acoes=${b.actions}, ultimo seq=${b.seq}, versao=${v.version})")
            close(CloseReason(CloseReason.Codes.GOING_AWAY, "queda simulada"))
            return true
        }
        val act = b.bot.chooseAction(v.toPlayerView(b.welcome!!.ruleSet), v.legal)
        b.actions++
        sendMsg(PlayAction(++b.seq, v.version, act))
        return false
    }

    /** Joga ate o fim do jogo; se [dropAt] > 0, derruba a conexao depois dessa quantidade de acoes e devolve false. */
    suspend fun session(b: Brain, dropAt: Int, injectIllegal: Boolean): Boolean {
        var done = false
        http.webSocket("ws://$server/ws/match/${table.id}", request = { app("GET", "/ws/match/${table.id}", b.token) }) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val m = OnlineJson.decodeFromString(ServerMessage.serializer(), frame.readText())) {
                    is Welcome -> {
                        b.welcome = m
                        b.seat = m.you.index
                        b.seq = maxOf(b.seq, m.lastSeq)
                        println("[${b.name}] Welcome: assento ${m.you.index}, lastSeq=${m.lastSeq}, regras=${m.ruleSet.name}, robos=${m.seats.filter { it.profile != null }.map { it.name }}")
                    }
                    is SeatUpdate -> {
                        val v = m.view
                        b.view = v
                        if (v.stage == TableStage.PLAYING && v.roundNumber != b.lastRound) {
                            b.bot.onNewRound()
                            b.lastRound = v.roundNumber
                        }
                        m.event?.let(b.bot::observe)
                        if (v.stage == TableStage.ROUND_OVER) {
                            b.rounds++
                            println("[${b.name}] fim da partida ${v.roundNumber}: placar ${v.totals}")
                        }
                        if (v.stage == TableStage.GAME_OVER) {
                            println("[${b.name}] FIM DO JOGO: vencedor lado ${v.winner?.index}, placar ${v.totals}, partidas=${v.history?.size}")
                            done = true
                            close()
                            return@webSocket
                        }
                        if (maybeAct(b, dropAt, injectIllegal)) return@webSocket
                    }
                    is Rejected -> {
                        b.rejections++
                        println("[${b.name}] RECUSADA seq=${m.seq}: ${m.reason} (${m.message}) recusas=${m.refusals}")
                        if (maybeAct(b, dropAt, injectIllegal)) return@webSocket
                    }
                    is SeatsChanged -> println("[${b.name}] mesa: assento ${m.seat} ${m.change}")
                    is GameError -> println("[${b.name}] ERRO ${m.code}: ${m.message}")
                    // §17.3/§18.3: o probe nao testa a escolha em si, so confirma sem boosters para a partida seguir.
                    is BoosterPrompt -> { println("[${b.name}] seletor de boosters: confirmando sem boosters"); sendMsg(SelectBoosters(emptyList())) }
                    is BoostersConfirmed -> println("[${b.name}] boosters confirmados: ${m.boosters}")
                    is BoosterConfirmFailed -> println("[${b.name}] falha ao confirmar boosters: ${m.message}")
                    else -> println("[${b.name}] ${m::class.simpleName}")
                }
            }
        }
        return done
    }

    val a = Brain("Ana", ana.token)
    val b = Brain("Beto", beto.token)
    val jobs = listOf(
        async { session(a, 0, injectIllegal = true) },
        async {
            if (!session(b, dropAfter, injectIllegal = false)) {
                println("[Beto] ... conexao caiu; esperando 3 s e voltando pelo mesmo assento com o mesmo token")
                delay(3_000)
                session(b, 0, injectIllegal = false)
            } else {
                true
            }
        },
    )
    val results = jobs.awaitAll()
    println("RESULTADO: terminou=$results acoesAna=${a.actions} acoesBeto=${b.actions} recusas=${a.rejections}/${b.rejections} partidas=${a.rounds}")
    delay(2_000)
    val after = http.get("http://$server/api/lobby/tables/${table.id}") { app("GET", "/api/lobby/tables/${table.id}", ana.token) }
    println("lobby depois do fim do jogo: estado=${OnlineJson.decodeFromString(TableView.serializer(), after.bodyAsText()).state}")
    http.close()
}
