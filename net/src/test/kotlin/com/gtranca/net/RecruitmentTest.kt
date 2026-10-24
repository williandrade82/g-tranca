package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** §16.2 recrutamento imediato: convite ao marcar, "Recrutado" para os outros, montagem sincronizada, saída e início. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecruitmentTest {

    private class Device(val transport: MemoryTransport, val r: Recruitment, val events: MutableList<RecruitEvent>, val notices: MutableList<RecruitNotice>)

    private fun TestScope.device(network: MemoryNetwork, key: String, name: String, scope: CoroutineScope, canAfford: suspend (Int) -> Boolean = { true }): Device {
        val t = network.transport()
        val r = Recruitment(t, scope, key, name, canAfford = canAfford)
        val events = mutableListOf<RecruitEvent>()
        val notices = mutableListOf<RecruitNotice>()
        scope.launch { r.run() }
        scope.launch { r.events.collect { events += it } }
        scope.launch { r.notices.collect { notices += it } }
        return Device(t, r, events, notices)
    }

    private fun config(stake: Int = 0, mode: GameMode = GameMode.DUPLAS) =
        RecruitConfig("Ana", mode, "facil", stake, RuleSet.DEFAULT, 3000, listOf(RecruitParticipant("Ana"), RecruitParticipant("Bia", accepted = false)))

    private suspend fun Device.presenceOf(key: String) = transport.discoverPresence().first().single { it.key == key }

    @Test
    fun `§16_2 marcar envia o convite na hora, sem prazo, e o marcado aparece como Recrutado para os outros`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        val caio = device(net, "kc", "Caio", scope)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb")) shouldBe true
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        // Sem prazo: continua convidado.
        advanceTimeBy(60_000)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        // Recrutado (pendente) para os outros anfitriões: não pode ser marcado.
        caio.presenceOf("kb").recruitedBy shouldBe "ka"
        caio.r.mark(caio.presenceOf("kb")) shouldBe false
        // Desmarcar antes da resposta cancela o convite.
        ana.r.unmark("kb")
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        caio.presenceOf("kb").recruitedBy shouldBe null
        scope.cancel()
    }

    @Test
    fun `§16_2 recusar volta a ficar disponivel e o anfitriao e avisado com o motivo`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.refuse()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.r.recruits.value shouldBe emptyList()
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = true, reason = RefusalReason.INVITE_REFUSED)
        ana.presenceOf("kb").recruitedBy shouldBe null
        scope.cancel()
    }

    @Test
    fun `§16_2 aceitou - ve a montagem do anfitriao ao vivo, so leitura, e sai da mesa avisando`() = runTest {
        val net = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        net.tap = { sent += it; listOf(it) }
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        runCurrent()
        ana.r.updateConfig(config())
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept() shouldBe true
        runCurrent()
        ana.r.recruits.value shouldBe listOf(Recruit("kb", "Bia", accepted = true))
        (bia.r.status.value as RecruitStatus.Joined).config shouldBe config()
        ana.r.updateConfig(config().copy(targetScore = 1500))
        runCurrent()
        (bia.r.status.value as RecruitStatus.Joined).config!!.targetScore shouldBe 1500
        // Continua "Recrutado" para os outros, mesmo aceito.
        ana.presenceOf("kb").recruitedBy shouldBe "ka"
        bia.r.leave()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = false, reason = null)
        ana.r.recruits.value shouldBe emptyList()
        // Nada além da montagem, das respostas e dos avisos trafega (sem fotos, sem tokens).
        sent.forEach { it.bytes.decodeToString() shouldNotContain "token" }
        scope.cancel()
    }

    @Test
    fun `§16_2 anfitriao desmarca quem aceitou - tirou voce da mesa`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.r.unmark("kb")
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Removed("Ana")
        scope.cancel()
    }

    @Test
    fun `§16_2 §14_6 aposta acima do saldo do recrutado - sai sozinho e o anfitriao e avisado`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope, canAfford = { it <= 50 })
        runCurrent()
        ana.r.updateConfig(config(stake = 10))
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        ana.r.updateConfig(config(stake = 100))
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.InsufficientBalance("Ana")
        ana.notices.single().reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
        scope.cancel()
    }

    @Test
    fun `§16_2 anfitriao sai ou desliga o Wi-Fi - mesa encerrada e o recrutado fica livre`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.r.closeHosting()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Closed("Ana")
        scope.cancel()
    }

    @Test
    fun `§16_2 Escolher posicoes - quem aceitou entra direto, o pendente vira robo`() = runTest {
        val net = MemoryNetwork()
        val scope = sessionScope
        val ana = device(net, "ka", "Ana", scope)
        val bia = device(net, "kb", "Bia", scope)
        val caio = device(net, "kc", "Caio", scope)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        ana.r.mark(ana.presenceOf("kc"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        val accepted = ana.r.start("mesa1")
        runCurrent()
        accepted shouldBe listOf(PresenceAdvert("kb", "Bia"))
        bia.events.single() shouldBe RecruitEvent.Start("mesa1", "kb")
        caio.r.status.value shouldBe RecruitStatus.Free // convite cancelado
        // A mesa: Bia entra com a própria chave e vai direto à escolha de posições.
        scope.cancel()
        val host = HostSession(
            ana.transport, "mesa1", TableRules("Mesa de Ana", GameMode.DUPLAS, RuleSet.DEFAULT, 3000, 0, "facil"), "Ana",
            lobby = LobbySetup(accepted.map { Participant.Nearby(it) }, listOf("bolt", "domino", "turbo", "zen"), seed = 1),
        )
        host.open(sessionScope)
        val guest = GuestSession(bia.transport, "Bia")
        guest.join(sessionScope, "mesa1", inviteKey = "kb")
        runCurrent()
        guest.status.value shouldBe GuestStatus.Lobby
        guest.seating.value.shouldNotBeNull().humans.size shouldBe 2
    }
}
