package com.gtranca.net

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * §16.2, §16.15 entrada confiável do recrutado na mesa do jogo: busca de 30 s com tentativas, falhas com causa,
 * handshake Pronto, robô só depois de 30 s (e com aviso ao recrutado).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryReliabilityTest {

    @Test
    fun `§16_2 §16_15 o recrutado entra mesmo se o anuncio da mesa so aparece 12 s depois`() = runTest {
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        val host = HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan)
        val bia = GuestSession(network.transport(), "Bia")
        sessionScope.launch { bia.join(sessionScope, "m", inviteKey = "k1") }
        advanceTimeBy(12_000)
        runCurrent()
        bia.status.value shouldBe GuestStatus.Connecting
        host.open(sessionScope)
        advanceUntilIdle()
        bia.status.value shouldBe GuestStatus.Playing
        host.gameStart.value shouldBe mapOf(Seat(1) to bia.playerId)
        host.robots.keys.contains(1) shouldBe false
    }

    @Test
    fun `§16_2 §16_15 sem anuncio da mesa a busca dura 30 s e a falha e NOT_FOUND`() = runTest {
        val network = MemoryNetwork()
        val logs = mutableListOf<String>()
        val g = GuestSession(network.transport(), "Bia", log = { logs += it })
        var failure: JoinFailure? = null
        sessionScope.launch { try { g.join(sessionScope, "m", inviteKey = "k1") } catch (e: JoinFailure) { failure = e } }
        advanceTimeBy(29_999)
        runCurrent()
        failure shouldBe null
        advanceTimeBy(2)
        runCurrent()
        failure!!.kind shouldBe JoinFailureKind.NOT_FOUND
        logs shouldContain "join attempt 1"
    }

    @Test
    fun `§16_2 §16_15 a mesa vista mas com conexao que falha tenta de novo e termina em CONNECT_FAILED, nunca NOT_FOUND`() = runTest {
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan).open(sessionScope)
        val logs = mutableListOf<String>()
        val t = network.transport()
        network.jam(t.id)
        val g = GuestSession(t, "Bia", log = { logs += it })
        var failure: JoinFailure? = null
        sessionScope.launch { try { g.join(sessionScope, "m", inviteKey = "k1") } catch (e: JoinFailure) { failure = e } }
        advanceTimeBy(31_000)
        runCurrent()
        failure!!.kind shouldBe JoinFailureKind.CONNECT_FAILED
        logs.count { it.startsWith("join attempt") } shouldBe logs.count { it.startsWith("connect failed") }
        (logs.count { it.startsWith("connect failed") } >= 3) shouldBe true
    }

    @Test
    fun `§16_2 §16_15 conexao que falha por 5 s e volta entra na mesa com a espera crescente`() = runTest {
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        val host = HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan)
        host.open(sessionScope)
        val t = network.transport()
        network.jam(t.id)
        val g = GuestSession(t, "Bia")
        sessionScope.launch { g.join(sessionScope, "m", inviteKey = "k1") }
        advanceTimeBy(5_000)
        runCurrent()
        network.unjam(t.id)
        advanceUntilIdle()
        g.status.value shouldBe GuestStatus.Playing
        host.gameStart.value shouldBe mapOf(Seat(1) to g.playerId)
    }

    @Test
    fun `§16_2 §16_15 conectou mas o anfitriao nao responde em 20 s - a falha e TIMEOUT`() = runTest {
        val network = MemoryNetwork()
        // Anuncia a mesa e aceita a conexão, mas ninguém responde (anfitrião travado).
        val silent = network.transport()
        silent.advertise(TableAdvert("m", "Mesa", "Anfitrião", hostId = 1))
        val g = GuestSession(network.transport(), "Bia")
        sessionScope.launch { g.join(sessionScope, "m", inviteKey = "k1") }
        advanceTimeBy(19_999)
        runCurrent()
        g.status.value shouldBe GuestStatus.Connecting
        advanceTimeBy(2)
        runCurrent()
        g.status.value shouldBe GuestStatus.Failed(JoinFailureKind.TIMEOUT)
    }

    @Test
    fun `§16_2 §16_15 conectado mas sem Pronto o recrutado nao conta - em 30 s vira robo, o anfitriao e avisado e o recrutado tambem`() = runTest {
        val network = MemoryNetwork()
        // O Pronto da Bia nunca chega ao anfitrião.
        network.tap = { d -> if ((ProtocolCodec().decode(d.bytes) as? Decoded.Ok)?.envelope?.p == Ready) emptyList() else listOf(d) }
        val notices = mutableListOf<TableNotice>()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        val host = HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan)
        host.open(sessionScope)
        sessionScope.launch { host.lobbyNotices.collect { notices += it } }
        val bia = GuestSession(network.transport(), "Bia").also { it.join(sessionScope, "m", inviteKey = "k1") }
        advanceTimeBy(plan.waitMillis - 1)
        runCurrent()
        host.gameStart.value shouldBe null // conectada, mas sem Pronto: o Prepare-se segue esperando
        bia.status.value shouldBe GuestStatus.Lobby
        advanceTimeBy(2)
        runCurrent()
        host.gameStart.value shouldBe emptyMap()
        host.robots.keys.contains(1) shouldBe true
        notices.single().let { it.seat to it.refusal } shouldBe (Seat(1) to RefusalReason.OUT_OF_RANGE)
        bia.status.value shouldBe GuestStatus.Refused(SEAT_LOST_MESSAGE)
    }

    @Test
    fun `§16_2 §16_15 com o Pronto recebido o jogo comeca logo depois do Prepare-se, sem esperar os 30 s`() = runTest {
        val t = plannedTable(mapOf(1 to "Bia"), start = false)
        advanceTimeBy(PREPARE_MILLIS + 100)
        runCurrent()
        t.host.gameStart.value shouldBe mapOf(Seat(1) to t.guests.getValue(1).playerId)
    }

    @Test
    fun `§16_2 §16_15 quem chega depois de virar robo recebe o aviso de que perdeu o lugar`() = runTest {
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        val host = HostSession(network.transport(), "m", testRules(), "Anfitrião", plan = plan)
        host.open(sessionScope)
        advanceTimeBy(plan.waitMillis + 1_000)
        runCurrent()
        host.gameStart.value shouldBe emptyMap()
        val late = GuestSession(network.transport(), "Bia").also { it.join(sessionScope, "m", inviteKey = "k1") }
        advanceUntilIdle()
        late.status.value.shouldBeInstanceOf<GuestStatus.Refused>().message shouldBe SEAT_LOST_MESSAGE
    }

    @Test
    fun `§16_2 §16_15 enquanto ha recrutado sem Pronto o anfitriao reafirma o anuncio da mesa`() = runTest {
        val network = MemoryNetwork()
        val plan = planFor(GameMode.DUPLAS, mapOf(1 to "Bia"))
        val hostTransport = network.transport()
        val host = HostSession(hostTransport, "m", testRules(), "Anfitrião", plan = plan)
        host.open(sessionScope)
        runCurrent()
        // Algo apagou o anúncio (falha do rádio): o recrutado ainda pendente faz o anfitrião anunciar de novo.
        val watcher = network.transport()
        network.adverts().size shouldBe 1
        hostTransport.stopAdvertising()
        network.adverts().size shouldBe 0
        advanceTimeBy(4_000)
        runCurrent()
        network.adverts().size shouldBe 1
        watcher.discover().first().single().tableId shouldBe "m"
    }

    // ---- Recruitment ---------------------------------------------------------------------------------------------

    @Test
    fun `§16_2 §16_15 depois do inicio a presenca do recrutador nao anuncia por cima da mesa`() = runTest {
        val network = MemoryNetwork()
        val counting = CountingTransport(network.transport())
        val rules = testRules(GameMode.INDIVIDUAL)
        val photos = PresencePhotos(counting, "ka", null)
        val ana = Recruitment(counting, sessionScope, "ka", "Ana", photos, canAfford = { true }, clock = { testScheduler.currentTime })
        sessionScope.launch { ana.run() }
        val bia = Recruitment(network.transport(), sessionScope, "kb", "Bia", PresencePhotos(network.transport(), "kb", null), canAfford = { true }, clock = { testScheduler.currentTime })
        sessionScope.launch { bia.run() }
        sessionScope.launch { bia.setFree(true) }
        runCurrent()
        ana.openTable(rules, "Ana")
        runCurrent()
        val seen = network.transport().discoverPresence().first { l -> l.any { it.key == "kb" } }.single { it.key == "kb" }
        ana.call(1, seen)
        runCurrent()
        bia.accept()
        runCurrent()
        val plan = ana.plan(TEST_PERSONAS, 1).shouldNotBeNullPlan()
        val before = counting.presenceAdverts
        ana.sendStart("m", plan)
        ana.shutdown()
        advanceTimeBy(5_000)
        runCurrent()
        counting.presenceAdverts shouldBe before
    }

    private class Recruited(
        val ana: Recruitment,
        val anaTransport: MemoryTransport,
        val anaRun: kotlinx.coroutines.Job,
        val bia: Recruitment,
        val biaEvents: MutableList<RecruitEvent>,
        val plan: TablePlan,
    )

    /** Ana recruta a Bia (duas pessoas, lugar 1) e está pronta para começar. */
    private suspend fun kotlinx.coroutines.test.TestScope.recruitedPair(network: MemoryNetwork): Recruited {
        val anaT = network.transport()
        val ana = Recruitment(anaT, sessionScope, "ka", "Ana", PresencePhotos(anaT, "ka", null), canAfford = { true }, clock = { testScheduler.currentTime })
        val anaRun = sessionScope.launch { ana.run() }
        val biaT = network.transport()
        val bia = Recruitment(biaT, sessionScope, "kb", "Bia", PresencePhotos(biaT, "kb", null), canAfford = { true }, clock = { testScheduler.currentTime })
        sessionScope.launch { bia.run() }
        val events = mutableListOf<RecruitEvent>()
        sessionScope.launch { bia.events.collect { events += it } }
        sessionScope.launch { bia.setFree(true) }
        runCurrent()
        ana.openTable(testRules(GameMode.INDIVIDUAL), "Ana")
        runCurrent()
        ana.call(1, anaT.discoverPresence().first { l -> l.any { it.key == "kb" } }.single { it.key == "kb" })
        runCurrent()
        bia.accept()
        runCurrent()
        val plan = ana.plan(TEST_PERSONAS, 1).shouldNotBeNullPlan()
        return Recruited(ana, anaT, anaRun, bia, events, plan)
    }

    @Test
    fun `§16_2 §16_15 nenhum RecruitBye sai antes do RecruitStart, mesmo com o shutdown da presenca no meio do caminho`() = runTest {
        val network = MemoryNetwork()
        val order = mutableListOf<String>()
        network.tap = { d ->
            when ((ProtocolCodec().decode(d.bytes) as? Decoded.Ok)?.envelope?.p) {
                is RecruitBye -> order += "bye"
                is RecruitStart -> order += "start"
                is RecruitLeave -> order += "leave"
                is RecruitEnd -> order += "end"
                else -> Unit
            }
            listOf(d)
        }
        val r = recruitedPair(network)
        r.anaRun.cancel() // o host largou a leitura dos eventos
        // O hub encerra a presença (shutdown) enquanto a mesa ainda abre; só depois sai o início.
        r.ana.shutdown()
        runCurrent()
        order.isEmpty() shouldBe true
        r.ana.sendStart("m", r.plan)
        advanceTimeBy(5_000)
        runCurrent()
        order.first() shouldBe "start"
        r.biaEvents.filterIsInstance<RecruitEvent.Start>().single().tableId shouldBe "m"
    }

    @Test
    fun `§16_2 §16_15 ao fechar a mesa o recrutador avisa a saida as conexoes de espera e elas o tiram da lista na hora`() = runTest {
        val network = MemoryNetwork()
        val byes = mutableListOf<String>()
        network.tap = { d ->
            (ProtocolCodec().decode(d.bytes) as? Decoded.Ok)?.envelope?.p?.let { if (it is RecruitBye) byes += it.key }
            listOf(d)
        }
        val anaT = network.transport()
        val ana = Recruitment(anaT, sessionScope, "ka", "Ana", PresencePhotos(anaT, "ka", null), canAfford = { true }, clock = { testScheduler.currentTime })
        sessionScope.launch { ana.run() }
        val biaT = network.transport()
        val bia = Recruitment(biaT, sessionScope, "kb", "Bia", PresencePhotos(biaT, "kb", null), canAfford = { true }, clock = { testScheduler.currentTime })
        sessionScope.launch { bia.run() }
        sessionScope.launch { bia.setFree(true) }
        runCurrent()
        ana.openTable(testRules(GameMode.INDIVIDUAL), "Ana")
        runCurrent()
        byes.isEmpty() shouldBe true
        ana.closeHosting()
        runCurrent()
        byes shouldBe listOf("ka")
    }

    private fun TablePlan?.shouldNotBeNullPlan(): TablePlan = this ?: error("plano ausente")

    private class CountingTransport(private val inner: MemoryTransport) : LocalTransport by inner {
        var presenceAdverts = 0
        override suspend fun advertisePresence(presence: PresenceAdvert) {
            presenceAdverts++
            inner.advertisePresence(presence)
        }
    }
}
