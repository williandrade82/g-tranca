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
        val bytes = ProtocolCodec(version = PROTOCOL_VERSION + 1).encode(1, Hello("Ana"))
        ProtocolCodec().decode(bytes) shouldBe Decoded.VersionMismatch(PROTOCOL_VERSION + 1)
        // Mesmo com um tipo de mensagem que esta versão não conhece.
        ProtocolCodec().decode("""{"v":9,"seq":1,"p":{"t":"novidade"}}""".encodeToByteArray()) shouldBe Decoded.VersionMismatch(9)
        ProtocolCodec().decode("lixo".encodeToByteArray()) shouldBe Decoded.Malformed
        val ok = ProtocolCodec().decode(ProtocolCodec().encode(3, ProposeAction(7, Action.DrawFromStock)))
        (ok as Decoded.Ok).envelope shouldBe Envelope(PROTOCOL_VERSION, 3, ProposeAction(7, Action.DrawFromStock))
    }

    @Test
    fun `§16_5 duplicada e atrasada sao descartadas e a adiantada espera as anteriores`() {
        val link = SequencedLink()
        fun e(seq: Long) = Envelope(PROTOCOL_VERSION, seq, Rename("n$seq"))
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
    fun `§16_2 §16_5 sala com token so para o dono, assento automatico, troca so para vago e nomes repetidos`() = runTest {
        val network = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        network.tap = { sent += it; listOf(it) }
        var n = -1 // o anfitrião também recebe um token (o 0), para voltar como jogador comum (§16.12)
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
        // §16.2 cada um ocupa automaticamente o primeiro assento livre.
        ana.roster.value.map { it.name to it.seat } shouldBe listOf("Anfitrião" to Seat(0), "Ana" to Seat(1), "Ana" to Seat(2))

        // §16.2 só troca para assento vago: nunca com outro jogador nem para o do anfitrião.
        ana.chooseSeat(Seat(2))
        ana2.chooseSeat(Seat(0))
        advanceUntilIdle()
        ana.roster.value.map { it.seat } shouldBe listOf(Seat(0), Seat(1), Seat(2))
        ana.chooseSeat(Seat(3))
        advanceUntilIdle()
        ana2.chooseSeat(Seat(3)) // já ocupado
        advanceUntilIdle()
        ana.roster.value.map { it.name to it.seat } shouldBe listOf("Anfitrião" to Seat(0), "Ana" to Seat(3), "Ana" to Seat(2))
        ana.roster.value.map { it.joinOrder } shouldBe listOf(0, 1, 2)

        // O token de cada um só vai a ele mesmo (no Welcome); a sala nunca o traz.
        sent.filter { it.to.value != "e1" }.forEach { d ->
            val text = d.bytes.decodeToString()
            val own = if (d.to.value == "e2") "token-1-secreto" else "token-2-secreto"
            listOf("token-1-secreto", "token-2-secreto").filter { it != own }.forEach { text shouldNotContain it }
        }

        ana.confirmSeat()
        ana2.confirmSeat()
        advanceUntilIdle()
        host.roster.value.all { it.confirmed } shouldBe true
        host.startGame() shouldBe mapOf(Seat(3) to ana.playerId, Seat(2) to ana2.playerId)
        advanceUntilIdle()
        host.droppedAtStart.value.shouldBeEmpty()
        ana.status.value shouldBe GuestStatus.Playing
        // Depois do início ninguém mais entra (entrada no meio do jogo: etapa 4).
        val late = GuestSession(network.transport(), "Caio")
        runCatching { late.join(sessionScope, "m") }.isFailure shouldBe true // a mesa deixou de ser anunciada
        late.status.value.shouldBeInstanceOf<GuestStatus.Connecting>()
    }

    @Test
    fun `§16_2 quem esta na mesa ocupa o assento e a quinta pessoa recebe mesa cheia`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val guests = (1..3).map { GuestSession(network.transport(), "G$it").also { g -> g.join(sessionScope, "m"); advanceUntilIdle() } }
        guests.map { it.status.value } shouldBe List(3) { GuestStatus.Lobby }
        host.roster.value.map { it.seat } shouldBe listOf(Seat(0), Seat(1), Seat(2), Seat(3))
        // O último a entrar não tem para onde trocar.
        guests.last().chooseSeat(Seat(1))
        advanceUntilIdle()
        host.roster.value.last().seat shouldBe Seat(3)
        // Ninguém confirmou ainda, mas a mesa está cheia.
        val fifth = GuestSession(network.transport(), "Quinto").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        fifth.status.value shouldBe GuestStatus.Refused("Mesa cheia.")
    }

    @Test
    fun `§16_2 nome em branco vira Jogador N`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val a = GuestSession(network.transport(), "   ").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        val b = GuestSession(network.transport(), "Bia").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        b.rename(" ")
        advanceUntilIdle()
        host.roster.value.map { it.name } shouldBe listOf("Anfitrião", "Jogador ${a.playerId}", "Jogador ${b.playerId}")
    }

    @Test
    fun `§16_2 quem nao confirmou a posicao e desconectado no inicio e o anfitriao e avisado`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val ok = GuestSession(network.transport(), "Ana").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        val late = GuestSession(network.transport(), "Bia").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        ok.confirmSeat()
        advanceUntilIdle()
        host.startGame() shouldBe mapOf(Seat(1) to ok.playerId)
        advanceUntilIdle()
        host.droppedAtStart.value.map { it.name to it.id } shouldBe listOf("Bia" to late.playerId)
        late.status.value shouldBe GuestStatus.Closed
        ok.status.value shouldBe GuestStatus.Playing
        ok.roster.value.map { it.name } shouldBe listOf("Anfitrião", "Ana")
    }

    @Test
    fun `§16_2 troca para assento vago depois de confirmar so na sala de espera`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val ana = GuestSession(network.transport(), "Ana").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        ana.confirmSeat()
        advanceUntilIdle()
        // Confirmada, ainda troca para um assento vago (e segue confirmada).
        ana.chooseSeat(Seat(3))
        advanceUntilIdle()
        host.roster.value.single { it.id == ana.playerId }.let { it.seat to it.confirmed } shouldBe (Seat(3) to true)
        host.startGame() shouldBe mapOf(Seat(3) to ana.playerId)
        advanceUntilIdle()
        // Depois do início ninguém troca de assento.
        ana.chooseSeat(Seat(1))
        advanceUntilIdle()
        host.roster.value.single { it.id == ana.playerId }.seat shouldBe Seat(3)
        ana.seat shouldBe Seat(3)
    }

    /** §16.4, §16.10 quem acabou de deixar de ser sucessor nunca recebe outro estado completo. */
    @Test
    fun `§16_10 sucessor rebaixado nao recebe mais o estado completo`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Anfitrião")
        host.open(sessionScope)
        val a = GuestSession(network.transport(), "A").also { it.join(sessionScope, "m") }
        advanceUntilIdle()
        a.confirmSeat()
        advanceUntilIdle()
        host.startGame()
        advanceUntilIdle()
        host.successors.value shouldBe listOf(a.playerId)
        val match = com.gtranca.engine.startMatch(GameMode.DUPLAS, 1000, kotlin.random.Random(1), RuleSet.DEFAULT)
        val state = SuccessorState(1, match, emptyList(), emptyList(), host.membersForSuccessor(), null, host.nextPlayerId())
        host.sendSuccessorState(a.playerId, state) shouldBe true
        advanceUntilIdle()
        a.holdsSuccessorState shouldBe true
        // Rebaixado (sem confirmação a tempo): recebe o descarte e o envio seguinte é barrado sob a trava.
        host.markUnavailable(a.playerId)
        host.sendSuccessorState(a.playerId, state.copy(stateSeq = 2)) shouldBe false
        advanceUntilIdle()
        a.holdsSuccessorState shouldBe false
        // Sair também descarta.
        a.leave()
        a.holdsSuccessorState shouldBe false
    }
}
