package com.gtranca.net

import com.gtranca.engine.Action
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        val other = PROTOCOL_VERSION + 1
        ProtocolCodec().decode("""{"v":$other,"seq":1,"p":{"t":"novidade"}}""".encodeToByteArray()) shouldBe Decoded.VersionMismatch(other)
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
        val t = plannedTable(mapOf(1 to "Bia"), guestCodec = ProtocolCodec(version = PROTOCOL_VERSION + 1))
        // A resposta do anfitrião vem na versão dele: o convidado mostra as duas versões.
        t.guests.getValue(1).status.value shouldBe GuestStatus.Refused(versionMismatchMessage(PROTOCOL_VERSION, PROTOCOL_VERSION + 1))
        t.host.roster.value.map { it.name } shouldBe listOf("Anfitrião") // o recrutado nunca entrou: um robô assume no início
    }

    @Test
    fun `§16_1 convidado recusa anfitriao de outra versao com mensagem clara`() = runTest {
        val t = plannedTable(mapOf(1 to "Bia"), hostCodec = ProtocolCodec(version = PROTOCOL_VERSION + 1))
        t.guests.getValue(1).status.value shouldBe GuestStatus.Refused(versionMismatchMessage(PROTOCOL_VERSION + 1, PROTOCOL_VERSION))
        t.guests.getValue(1).rules.value shouldBe null
    }

    @Test
    fun `§16_2 §16_5 token so para o dono, lugares do plano e depois do inicio ninguem entra sem vaga aberta`() = runTest {
        val sent = mutableListOf<Delivery>()
        val network = MemoryNetwork()
        network.tap = { sent += it; listOf(it) }
        var n = -1 // o anfitrião também recebe um token (o 0), para voltar como jogador comum (§16.12)
        val t = plannedTable(mapOf(3 to "Ana", 2 to "Ana"), network = network, newToken = { "token-${++n}-secreto" }, start = false)
        runCurrent()
        val ana = t.guests.getValue(2)
        val ana2 = t.guests.getValue(3)
        // §16.2 cada recrutado já está no lugar que o recrutador deu a ele (a ordem de entrada segue a dos lugares); nomes repetidos valem.
        ana.roster.value.map { it.name to it.seat } shouldBe listOf("Anfitrião" to Seat(0), "Ana" to Seat(2), "Ana" to Seat(3))
        ana.roster.value.map { it.joinOrder } shouldBe listOf(0, 1, 2)
        ana.token shouldNotBe ana2.token
        // O token de cada um só vai a ele mesmo (no Welcome); a sala nunca o traz.
        val owner = mapOf(ana.token!! to ana.playerId, ana2.token!! to ana2.playerId)
        sent.forEach { d ->
            val text = d.bytes.decodeToString()
            owner.forEach { (tk, id) -> if (text.contains(tk)) text shouldContain "\"you\":$id" }
        }
        advanceUntilIdle()
        t.host.gameStart.value shouldBe mapOf(Seat(2) to ana.playerId, Seat(3) to ana2.playerId)
        ana.status.value shouldBe GuestStatus.Playing
        // §16.7 depois do início a mesa segue anunciada (para o reingresso), mas fora da lista, e sem vaga aberta ninguém entra.
        network.adverts().single().listed shouldBe false
        val late = GuestSession(network.transport(), "Caio")
        late.join(sessionScope, "m")
        advanceUntilIdle()
        late.status.value shouldBe GuestStatus.Refused("O jogo desta mesa já começou.")
    }

    @Test
    fun `§16_2 antes do inicio so entra quem foi recrutado`() = runTest {
        val t = plannedTable(mapOf(1 to "Bia"), start = false)
        val stranger = GuestSession(t.network.transport(), "Intruso")
        stranger.join(sessionScope, "m")
        runCurrent()
        stranger.status.value.shouldBeInstanceOf<GuestStatus.Refused>()
        val wrongKey = GuestSession(t.network.transport(), "Outro")
        wrongKey.join(sessionScope, "m", inviteKey = "k9")
        runCurrent()
        (wrongKey.status.value as GuestStatus.Refused).reason shouldBe RefusalReason.OUT_OF_RANGE
        t.host.roster.value.map { it.name } shouldBe listOf("Anfitrião", "Bia")
    }

    @Test
    fun `§16_2 nome em branco vira Jogador N`() = runTest {
        val t = plannedTable(mapOf(1 to "   ", 2 to "Bia"))
        val bia = t.guests.getValue(2)
        bia.rename(" ")
        advanceUntilIdle()
        t.host.roster.value.map { it.name } shouldBe listOf("Anfitrião", "Jogador ${t.guests.getValue(1).playerId}", "Jogador ${bia.playerId}")
    }

    @Test
    fun `§16_2 §16_15 recrutado que nao entra em 10 s vira robo sorteado e o anfitriao e avisado`() = runTest {
        val notices = mutableListOf<TableNotice>()
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Ana", 3 to "Bia"))
        val host = HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan)
        host.open(sessionScope)
        val job = sessionScope.launch { host.lobbyNotices.collect { notices += it } }
        // Só a Ana entra; a Bia nunca se conecta.
        val ana = GuestSession(network.transport(), "Ana").also { it.join(sessionScope, "m", inviteKey = "k1") }
        advanceTimeBy(plan.waitMillis - 1)
        runCurrent()
        host.gameStart.value shouldBe null
        advanceTimeBy(2)
        runCurrent()
        host.gameStart.value shouldBe mapOf(Seat(1) to ana.playerId)
        host.roster.value.map { it.name } shouldBe listOf("Anfitrião", "Ana")
        host.robots.keys shouldBe setOf(2, 3)
        host.robots.values.toSet().size shouldBe 2
        notices.single().let { it.seat to it.refusal } shouldBe (Seat(3) to RefusalReason.OUT_OF_RANGE)
        ana.robots.value shouldBe host.robots
        job.cancel()
    }

    @Test
    fun `§16_2 §16_15 todos entram - o jogo comeca depois do Prepare-se de 1,5 s, nao antes`() = runTest {
        val t = plannedTable(mapOf(1 to "Ana"), start = false)
        advanceTimeBy(PREPARE_MILLIS - 1)
        runCurrent()
        t.host.gameStart.value shouldBe null
        t.guests.getValue(1).status.value shouldBe GuestStatus.Lobby
        advanceTimeBy(2)
        runCurrent()
        t.host.gameStart.value shouldBe mapOf(Seat(1) to t.guests.getValue(1).playerId)
        t.guests.getValue(1).status.value shouldBe GuestStatus.Playing
    }

    @Test
    fun `§16_2 sair antes do inicio deixa o lugar para um robo sorteado`() = runTest {
        val t = plannedTable(mapOf(1 to "Ana", 2 to "Bia"), start = false)
        t.guests.getValue(1).leave()
        advanceUntilIdle()
        t.host.gameStart.value shouldBe mapOf(Seat(2) to t.guests.getValue(2).playerId)
        t.host.robots.keys shouldBe setOf(1, 3)
    }

    @Test
    fun `§16_2 o anfitriao que sai antes do inicio desfaz a mesa e o recrutado ve Mesa encerrada`() = runTest {
        val t = plannedTable(mapOf(1 to "Ana"), start = false)
        t.host.disband()
        runCurrent()
        t.guests.getValue(1).status.value shouldBe GuestStatus.TableEnded(CloseReason.HOST_LEFT)
        t.network.adverts() shouldBe emptyList()
    }

    /** §16.4, §16.10 quem acabou de deixar de ser sucessor nunca recebe outro estado completo. */
    @Test
    fun `§16_10 sucessor rebaixado nao recebe mais o estado completo`() = runTest {
        val t = plannedTable(mapOf(1 to "A"))
        val host = t.host
        val a = t.guests.getValue(1)
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

    /** §16.10 quem foi marcado indisponível volta a contar como sucessor ao reingressar. */
    @Test
    fun `§16_10 indisponivel volta a ser sucessor no reingresso`() = runTest {
        val t = plannedTable(mapOf(1 to "A"))
        val host = t.host
        val a = t.guests.getValue(1)
        host.markUnavailable(a.playerId)
        host.successors.value.shouldBeEmpty()
        // A conexão cai e ele volta sozinho pelo token (a mesa segue anunciada, §16.7).
        t.network.drop(t.transports.getValue(1).id)
        advanceTimeBy(3_000)
        a.status.value shouldBe GuestStatus.Playing
        host.successors.value shouldBe listOf(a.playerId)
    }

    /** §16.2 os robôs vão do anfitrião a todos, por assento: no início e a quem volta com o jogo em andamento. */
    @Test
    fun `§16_2 robos por assento no inicio e no reingresso`() = runTest {
        val t = plannedTable(mapOf(1 to "A"))
        val host = t.host
        val a = t.guests.getValue(1)
        // Os robôs são exatamente os do plano (nada é sorteado de novo): lugares 2 e 3.
        host.robots shouldBe t.plan.layout.robots
        host.robots.keys shouldBe setOf(2, 3)
        a.robots.value shouldBe host.robots
        // O anfitrião troca um robô (§16.8) e o convidado volta pelo token: recebe a lista atual.
        host.robots = mapOf(2 to host.robots.getValue(2), 3 to "sobrio")
        t.network.drop(t.transports.getValue(1).id)
        advanceTimeBy(3_000)
        a.status.value shouldBe GuestStatus.Playing
        a.robots.value shouldBe mapOf(2 to t.plan.layout.robots.getValue(2), 3 to "sobrio")
    }
}
