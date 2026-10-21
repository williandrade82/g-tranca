package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Escopo das sessões nos testes: no relógio virtual, mas **não** em segundo plano (o `advanceUntilIdle` não roda o
 * `backgroundScope`). Os coletores só suspendem; não há trabalho pendurado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val TestScope.sessionScope: CoroutineScope
    get() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private val rules = TableRules("Mesa", GameMode.DUPLAS, RuleSet.DEFAULT, 1000, 0, "facil")

    @Test
    fun `§16_1 versao diferente e reconhecida antes de ler o conteudo`() {
        val bytes = ProtocolCodec(version = 2).encode(1, Hello("Ana"))
        ProtocolCodec().decode(bytes) shouldBe Decoded.VersionMismatch(2)
        // Mesmo com um tipo de mensagem que esta versão não conhece.
        ProtocolCodec().decode("""{"v":9,"seq":1,"p":{"t":"novidade"}}""".encodeToByteArray()) shouldBe Decoded.VersionMismatch(9)
        ProtocolCodec().decode("lixo".encodeToByteArray()) shouldBe Decoded.Malformed
        val ok = ProtocolCodec().decode(ProtocolCodec().encode(3, ProposeAction(7, Action.DrawFromStock)))
        (ok as Decoded.Ok).envelope shouldBe Envelope(PROTOCOL_VERSION, 3, ProposeAction(7, Action.DrawFromStock))
    }

    @Test
    fun `§16_5 duplicada e atrasada sao descartadas e a adiantada espera as anteriores`() {
        val link = SequencedLink()
        fun e(seq: Long) = Envelope(PROTOCOL_VERSION, seq, ContinueRound(seq.toInt()))
        link.accept(e(1)).map { it.seq } shouldBe listOf(1L)
        link.accept(e(1)).shouldBeEmpty()
        link.accept(e(3)).shouldBeEmpty()
        link.accept(e(3)).shouldBeEmpty()
        link.accept(e(2)).map { it.seq } shouldBe listOf(2L, 3L)
        link.accept(e(2)).shouldBeEmpty()
        link.accept(e(4)).map { it.seq } shouldBe listOf(4L)
    }

    @Test
    fun `§16_1 anfitriao recusa convidado de outra versao com mensagem clara`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val guest = GuestSession(network.transport(), "Bia", codec = ProtocolCodec(version = PROTOCOL_VERSION + 1))
        guest.join(sessionScope, "m")
        advanceUntilIdle()
        guest.status.value shouldBe GuestStatus.Refused(VERSION_MISMATCH_MESSAGE)
        host.roster.value.size shouldBe 1
    }

    @Test
    fun `§16_1 convidado recusa anfitriao de outra versao com mensagem clara`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião", codec = ProtocolCodec(version = PROTOCOL_VERSION + 1))
        host.open(sessionScope)
        val guest = GuestSession(network.transport(), "Bia")
        guest.join(sessionScope, "m")
        advanceUntilIdle()
        guest.status.value shouldBe GuestStatus.Refused(VERSION_MISMATCH_MESSAGE)
        guest.rules.value shouldBe null
    }

    @Test
    fun `§16_2 §16_5 sala com token so para o dono, assento vago e nomes repetidos`() = runTest {
        val network = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        network.tap = { sent += it; listOf(it) }
        var n = 0
        val host = HostSession(network.transport(), "m", rules, "Anfitrião", newToken = { "token-${++n}-secreto" })
        host.open(sessionScope)
        val ana = GuestSession(network.transport(), "Ana").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        val ana2 = GuestSession(network.transport(), "Ana").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        ana.status.value shouldBe GuestStatus.Lobby
        ana.rules.value shouldBe rules
        ana.token shouldBe "token-1-secreto"
        ana2.token shouldBe "token-2-secreto"

        ana.chooseSeat(Seat(2))
        advanceUntilIdle()
        ana2.chooseSeat(Seat(2)) // ocupado
        ana2.chooseSeat(Seat(0)) // do anfitrião
        advanceUntilIdle()
        ana2.roster.value.first { it.id == ana2.playerId }.seat shouldBe null
        ana2.chooseSeat(Seat(1))
        advanceUntilIdle()
        ana.roster.value.map { it.name to it.seat } shouldBe listOf("Anfitrião" to Seat(0), "Ana" to Seat(2), "Ana" to Seat(1))
        ana.roster.value.map { it.joinOrder } shouldBe listOf(0, 1, 2)

        // O token de cada um só vai a ele mesmo (no Welcome); a sala nunca o traz.
        sent.filter { it.to.value != "e1" }.forEach { d ->
            val text = d.bytes.decodeToString()
            val own = if (d.to.value == "e2") "token-1-secreto" else "token-2-secreto"
            listOf("token-1-secreto", "token-2-secreto").filter { it != own }.forEach { text shouldNotContain it }
        }

        host.startGame() shouldBe mapOf(Seat(2) to ana.playerId, Seat(1) to ana2.playerId)
        advanceUntilIdle()
        ana.status.value shouldBe GuestStatus.Playing
        // Depois do início ninguém mais entra (entrada no meio do jogo: etapa 4).
        val late = GuestSession(network.transport(), "Caio")
        runCatching { late.join(sessionScope, "m") }.isFailure shouldBe true // a mesa deixou de ser anunciada
        late.status.value.shouldBeInstanceOf<GuestStatus.Connecting>()
    }
}
