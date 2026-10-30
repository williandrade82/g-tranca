package com.gtranca.online.client

import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.RejectReason
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.Replaced
import com.gtranca.online.contracts.Resign
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

private val FAST = ReconnectPolicy(maxAttempts = 3, initialDelayMs = 10, maxDelayMs = 20)

class MatchSessionTest {

    /** Coleta os eventos da sessão num canal, para os testes consumirem um a um com prazo. */
    private class Collector(scope: CoroutineScope, session: MatchSession) {
        val channel = Channel<MatchEvent>(Channel.UNLIMITED)
        val job = scope.launch {
            session.events().collect { channel.send(it) }
            channel.close()
        }

        suspend fun next(): MatchEvent = withTimeout(10_000) { channel.receive() }
        suspend fun message(): ServerMessage = (next() as MatchEvent.Message).message
    }

    // §18.8
    @Test
    fun `o handshake leva a versao do protocolo e o token nos cabecalhos e nunca na URL`() {
        val server = FakeMatchServer()
        server.scripts = listOf({
            with(server) {
                send(Fixtures.welcome())
                send(Fixtures.update(1))
            }
            delay(300)
        })
        server.run { client ->
            val c = Collector(this, client.matchSession("tok-secreto", "abc234", FAST))
            c.message().shouldBeInstanceOf<Welcome>()
            c.message().shouldBeInstanceOf<SeatUpdate>()
            c.job.cancel()
            server.handshakeVersions.first() shouldBe "2"
            server.handshakeAuth.first() shouldBe "Bearer tok-secreto"
            server.handshakeUris.first() shouldBe "/ws/match/ABC234"
        }
    }

    // §18.8
    @Test
    fun `versao recusada (426) termina sem reconectar com Atualize o aplicativo`() {
        val server = FakeMatchServer()
        server.refuse = 426 to ApiError(ErrorCode.VERSAO_INCOMPATIVEL, "Atualize o aplicativo")
        server.run { client ->
            val events = client.matchSession("t", "ABC234", FAST).events().toList()
            events.size shouldBe 1
            val failed = events.single().shouldBeInstanceOf<MatchEvent.Failed>()
            failed.error.kind shouldBe OnlineErrorKind.UPDATE_APP
            failed.error.retryable shouldBe false
            server.connections.get() shouldBe 0
        }
    }

    // §18.8
    @Test
    fun `servidor em manutencao (503) tenta de novo e depois falha como MAINTENANCE`() {
        val server = FakeMatchServer()
        server.refuse = 503 to ApiError(ErrorCode.VERSAO_INCOMPATIVEL, "Servidor em manutenção")
        server.run { client ->
            val policy = ReconnectPolicy(maxAttempts = 1, initialDelayMs = 5, maxDelayMs = 5)
            val events = client.matchSession("t", "ABC234", policy).events().toList()
            events.size shouldBe 2
            events[0].shouldBeInstanceOf<MatchEvent.Reconnecting>()
            (events[1] as MatchEvent.Failed).error.kind shouldBe OnlineErrorKind.MAINTENANCE
        }
    }

    // §18.1
    @Test
    fun `token invalido (401) falha como UNAUTHENTICATED sem reconectar`() {
        val server = FakeMatchServer()
        server.refuse = 401 to ApiError(ErrorCode.NAO_AUTENTICADO, "Entre novamente.")
        server.run { client ->
            val events = client.matchSession("t", "ABC234", FAST).events().toList()
            (events.single() as MatchEvent.Failed).error.kind shouldBe OnlineErrorKind.UNAUTHENTICATED
        }
    }

    // §18.4
    @Test
    fun `mesa inexistente (404) e recusa definitiva com o codigo`() {
        val server = FakeMatchServer()
        server.refuse = 404 to ApiError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada. Confira o id.")
        server.run { client ->
            val error = (client.matchSession("t", "ABC234", FAST).events().toList().single() as MatchEvent.Failed).error
            error.kind shouldBe OnlineErrorKind.API
            error.code shouldBe ErrorCode.MESA_INEXISTENTE
        }
    }

    // §18.4
    @Test
    fun `queda inesperada reconecta com o mesmo token e continua o seq depois do lastSeq do Welcome`() {
        val server = FakeMatchServer()
        server.scripts = listOf(
            {
                with(server) {
                    send(Fixtures.welcome(lastSeq = 0))
                    send(Fixtures.update(1))
                }
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "queda"))
            },
            {
                with(server) {
                    send(Fixtures.welcome(lastSeq = 5))
                    send(Fixtures.update(7))
                    collectClientMessages()
                }
            },
        )
        server.run { client ->
            val session = client.matchSession("mesmo-token", "ABC234", FAST)
            val c = Collector(this, session)
            c.message().shouldBeInstanceOf<Welcome>()
            c.message().shouldBeInstanceOf<SeatUpdate>()
            c.next().shouldBeInstanceOf<MatchEvent.Reconnecting>()
            (c.message() as Welcome).lastSeq shouldBe 5
            c.message().shouldBeInstanceOf<SeatUpdate>()
            session.play(Action.Discard(Card.parse("AS"))) shouldBe true
            withTimeout(10_000) { while (server.received.isEmpty()) delay(10) }
            val play = server.received.single() as PlayAction
            play.seq shouldBe 6
            play.stateVersion shouldBe 7
            server.handshakeAuth.toList() shouldContainExactly listOf("Bearer mesmo-token", "Bearer mesmo-token")
            c.job.cancel()
        }
    }

    // §18.4
    @Test
    fun `mensagens finais do servidor encerram o fluxo sem reconectar`() {
        val finals: List<ServerMessage> = listOf(
            Replaced("outra conexão"),
            YouResigned("você desistiu"),
            TableClosed("mesa encerrada"),
            GameError(ErrorCode.MENSAGEM_INVALIDA, "inválida"),
        )
        for (final in finals) {
            val server = FakeMatchServer()
            server.scripts = listOf({
                with(server) {
                    send(Fixtures.welcome())
                    send(final)
                }
                delay(2_000)
            })
            server.run { client ->
                val events = client.matchSession("t", "ABC234", FAST).events().toList()
                events.size shouldBe 2
                (events.last() as MatchEvent.Message).message shouldBe final
                server.connections.get() shouldBe 1
            }
        }
    }

    // §18.3
    @Test
    fun `jogada e desistencia saem numeradas com a versao da ultima vista`() {
        val server = FakeMatchServer()
        server.scripts = listOf({
            with(server) {
                send(Fixtures.welcome())
                send(Fixtures.update(3))
                collectClientMessages()
            }
        })
        server.run { client ->
            val session = client.matchSession("t", "ABC234", FAST)
            val c = Collector(this, session)
            c.message()
            c.message()
            session.play(Action.Discard(Card.parse("AS"))) shouldBe true
            session.resign() shouldBe true
            withTimeout(10_000) { while (server.received.size < 2) delay(10) }
            (server.received[0] as PlayAction).seq shouldBe 1
            (server.received[0] as PlayAction).stateVersion shouldBe 3
            (server.received[1] as Resign).seq shouldBe 2
            c.job.cancel()
        }
    }

    // §18.3
    @Test
    fun `mensagem Rejected e entregue como evento`() {
        val server = FakeMatchServer()
        server.scripts = listOf({
            with(server) {
                send(Fixtures.welcome())
                send(Rejected(1, RejectReason.ILLEGAL, "ilegal", 1))
            }
            delay(300)
        })
        server.run { client ->
            val c = Collector(this, client.matchSession("t", "ABC234", FAST))
            c.message()
            (c.message() as Rejected).reason shouldBe RejectReason.ILLEGAL
            c.job.cancel()
        }
    }

    @Test
    fun `sem conexao a jogada nao e enviada`() {
        val server = FakeMatchServer()
        server.run { client ->
            client.matchSession("t", "ABC234", FAST).play(Action.Discard(Card.parse("AS"))) shouldBe false
        }
    }

    @Test
    fun `sem tentativas restantes o fluxo falha`() {
        val server = FakeMatchServer()
        server.scripts = listOf({ close(CloseReason(CloseReason.Codes.GOING_AWAY, "queda")) })
        server.run { client ->
            val policy = ReconnectPolicy(maxAttempts = 2, initialDelayMs = 5, maxDelayMs = 5)
            val events = client.matchSession("t", "ABC234", policy).events().toList()
            events.last().shouldBeInstanceOf<MatchEvent.Failed>()
            events.count { it is MatchEvent.Reconnecting } shouldBe 2
            server.connections.get() shouldBe 3
        }
    }
}
