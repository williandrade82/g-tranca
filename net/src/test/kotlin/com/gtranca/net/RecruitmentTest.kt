package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import io.kotest.matchers.collections.shouldContain
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

/** §16.2 recrutamento imediato: convite pela conexão de espera, "Recrutado", montagem versionada, queda, saída e início. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecruitmentTest {

    private class Device(
        val transport: MemoryTransport,
        val r: Recruitment,
        val events: MutableList<RecruitEvent>,
        val notices: MutableList<RecruitNotice>,
        val logs: MutableList<String>,
        val scope: CoroutineScope,
        val photos: PresencePhotos,
    )

    private fun TestScope.device(
        network: MemoryNetwork,
        key: String,
        name: String,
        scope: CoroutineScope = sessionScope,
        canAfford: suspend (Int) -> Boolean = { true },
        photo: String? = null,
    ): Device {
        val t = network.transport()
        val logs = mutableListOf<String>()
        var rec: Recruitment? = null
        val photos = PresencePhotos(t, key, photo, reserved = { rec?.ownsEndpoint(it) == true })
        val r = Recruitment(t, scope, key, name, photos, canAfford = canAfford, log = { logs += it }, clock = { testScheduler.currentTime })
        rec = r
        val events = mutableListOf<RecruitEvent>()
        val notices = mutableListOf<RecruitNotice>()
        scope.launch { r.run() }
        scope.launch { r.events.collect { events += it } }
        scope.launch { r.notices.collect { notices += it } }
        return Device(t, r, events, notices, logs, scope, photos)
    }

    private fun config(stake: Int = 0, target: Int = 3000) =
        RecruitConfig("Ana", GameMode.DUPLAS, "facil", stake, RuleSet.DEFAULT, target, listOf(RecruitParticipant("Ana"), RecruitParticipant("Bia", accepted = false)))

    private suspend fun Device.presenceOf(key: String) = transport.discoverPresence().first { l -> l.any { it.key == key } }.single { it.key == key }

    private val TestScope.currentTime get() = testScheduler.currentTime

    @Test
    fun `§16_2 o convite chega na hora pela conexao de espera, sem reanunciar a presenca, e sem prazo`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        // Bia já está ligada ao anfitrião (espera) antes de ser marcada.
        ana.logs.any { "standby" in it } shouldBe true
        val before = currentTime
        ana.r.mark(ana.presenceOf("kb")) shouldBe true
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        currentTime shouldBe before // nenhum tempo virtual: nada esperou busca nem anúncio
        ana.logs.any { it.startsWith("rec mark kb") && "invite sent via link" in it } shouldBe true
        advanceTimeBy(60_000)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana") // sem prazo
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 sem conexao de espera o convite vai pela reserva do anuncio`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        runCurrent() // ana não está com a montagem aberta: ninguém conecta
        ana.r.mark(ana.presenceOf("kb")) shouldBe true
        runCurrent()
        ana.logs.any { "advert fallback" in it } shouldBe true
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 marcado aparece Recrutado para os outros anfitrioes e desmarcar cancela o convite`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        val caio = device(net, "kc", "Caio")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        caio.presenceOf("kb").recruitedBy shouldBe "ka"
        caio.r.mark(caio.presenceOf("kb")) shouldBe false
        ana.r.unmark("kb")
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events shouldBe emptyList() // cancelar não gera aviso
        caio.presenceOf("kb").recruitedBy shouldBe null
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 recusar volta a ficar disponivel e o anfitriao e avisado com o motivo`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.refuse()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.r.recruits.value shouldBe emptyList()
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = true, reason = RefusalReason.INVITE_REFUSED)
        ana.presenceOf("kb").recruitedBy shouldBe null
        // Pode ser marcado de novo, ainda pela mesma conexão.
        ana.r.mark(ana.presenceOf("kb")) shouldBe true
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 aceitou - ve a montagem ao vivo, so leitura, e sair da mesa avisa o anfitriao`() = runTest {
        val net = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        net.tap = { sent += it; listOf(it) }
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        ana.r.updateConfig(config())
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept() shouldBe true
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        ana.r.recruits.value shouldBe listOf(Recruit("kb", "Bia", accepted = true))
        (bia.r.status.value as RecruitStatus.Joined).config shouldBe config()
        ana.r.updateConfig(config(target = 1500))
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Joined).config!!.targetScore shouldBe 1500
        ana.presenceOf("kb").recruitedBy shouldBe "ka" // continua Recrutado para os outros
        bia.r.leave()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = false, reason = null)
        ana.r.recruits.value shouldBe emptyList()
        sent.forEach { it.bytes.decodeToString() shouldNotContain "token" }
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 montagem numerada - varias mudancas seguidas viram um envio e a versao so avanca`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.logs.clear()
        // Digitando a pontuação-alvo: 5 mudanças em 100 ms.
        listOf(1, 15, 150, 1500, 15000).forEach { ana.r.updateConfig(config(target = it)); advanceTimeBy(20) }
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        ana.logs.count { it.startsWith("rec cfg send") } shouldBe 1
        (bia.r.status.value as RecruitStatus.Joined).config!!.targetScore shouldBe 15000
        bia.logs.any { it.startsWith("rec cfg apply") } shouldBe true
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 montagem perdida ou fora de ordem - o recrutado termina com a mais nova`() = runTest {
        val net = MemoryNetwork()
        val held = mutableListOf<Delivery>()
        var holdFirst = true
        var dropOnce = true
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        net.tap = { d ->
            val text = d.bytes.decodeToString()
            when {
                "recruitSync" in text && holdFirst -> { holdFirst = false; held += d; emptyList() } // atrasada
                "recruitSync" in text && dropOnce && held.isNotEmpty() -> { dropOnce = false; emptyList() } // perdida
                else -> listOf(d)
            }
        }
        ana.r.updateConfig(config(target = 1000))
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        ana.r.updateConfig(config(target = 2000))
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        held.size shouldBe 1
        net.deliver(held.single()) // chega depois da mais nova
        advanceTimeBy(ReliableLink.RESEND_AFTER_MILLIS * 3)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Joined).config!!.targetScore shouldBe 2000
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 desmarcar quem aceitou - tirou voce da mesa`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.r.unmark("kb")
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Removed("Ana")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §14_6 aposta acima do saldo do recrutado - sai sozinho e o anfitriao e avisado`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia", canAfford = { it <= 50 })
        ana.r.setHosting(true)
        ana.r.updateConfig(config(stake = 10))
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        ana.r.updateConfig(config(stake = 100))
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.InsufficientBalance("Ana")
        ana.notices.single().reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 anfitriao sai ou desliga o Wi-Fi - mesa encerrada e o recrutado fica livre`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.r.closeHosting()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Closed("Ana")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 queda da conexao - o recrutado continua na mesa, reconecta e recebe a montagem inteira de novo`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        ana.r.updateConfig(config())
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        advanceTimeBy(Recruitment.CONFIG_DEBOUNCE_MILLIS + 1)
        runCurrent()
        bia.logs.count { it.startsWith("rec cfg apply") } shouldBe 1
        net.drop(bia.transport.id)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>() // não saiu
        advanceTimeBy(Recruitment.CONTACT_BACKOFF_MILLIS[0] + 100)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        bia.logs.count { it.startsWith("rec cfg apply") } shouldBe 2 // reenviada inteira, com versão nova
        ana.r.recruits.value shouldBe listOf(Recruit("kb", "Bia", accepted = true))
        advanceTimeBy(Recruitment.REJOIN_MILLIS * 2)
        runCurrent()
        bia.events shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §16_15 queda sem volta - depois de 8 s o recrutado sai com mesa encerrada e o anfitriao e avisado`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        net.block(bia.transport.id)
        net.drop(bia.transport.id)
        runCurrent()
        advanceTimeBy(Recruitment.REJOIN_MILLIS - 1)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        advanceTimeBy(2)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Closed("Ana")
        ana.notices.single().reason shouldBe RefusalReason.OUT_OF_RANGE
        ana.r.recruits.value shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 Escolher posicoes - quem aceitou entra direto, o pendente vira robo`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana")
        val bia = device(net, "kb", "Bia")
        val caio = device(net, "kc", "Caio")
        ana.r.setHosting(true)
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
        ana.scope.cancel()
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

    @Test
    fun `§16_2 inicio perdido - o recrutado entra quando a mesa aparece com a chave dele`() = runTest {
        val net = MemoryNetwork()
        net.tap = { d -> if ("recruitStart" in d.bytes.decodeToString()) emptyList() else listOf(d) } // o início nunca chega
        val ana = device(net, "ka", "Ana", scope = sessionScope)
        val bia = device(net, "kb", "Bia", scope = sessionScope)
        ana.r.setHosting(true)
        runCurrent()
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        val accepted = ana.r.start("mesa1")
        runCurrent()
        bia.events shouldBe emptyList()
        ana.scope.cancel()
        val host = HostSession(
            ana.transport, "mesa1", TableRules("Mesa de Ana", GameMode.DUPLAS, RuleSet.DEFAULT, 3000, 0, "facil"), "Ana",
            lobby = LobbySetup(accepted.map { Participant.Nearby(it) }, listOf("bolt", "domino", "turbo", "zen"), seed = 1),
        )
        host.open(sessionScope)
        runCurrent()
        bia.events shouldContain RecruitEvent.Start("mesa1", "kb")
    }

    @Test
    fun `§16_2 as fotos vao pela conexao de espera, nos dois sentidos, antes do convite`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana", photo = "FOTOANA")
        val bia = device(net, "kb", "Bia", photo = "FOTOBIA")
        ana.r.setHosting(true)
        runCurrent()
        ana.photos.photos.value shouldBe mapOf("kb" to "FOTOBIA")
        bia.photos.photos.value shouldBe mapOf("ka" to "FOTOANA")
        // A conexão segue de pé (nenhuma troca de fotos avulsa a desconecta) e o convite chega por ela.
        advanceTimeBy(60_000)
        ana.r.mark(ana.presenceOf("kb"))
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Invited("ka", "Ana")
    }
}
