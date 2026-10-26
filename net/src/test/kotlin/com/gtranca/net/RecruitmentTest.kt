package com.gtranca.net

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** §16.2 recrutamento: lugares, convite pela conexão de espera, Chamado/Recrutado, mover, remover e Começar Partida. */
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
        free: Boolean = true,
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
        if (free) scope.launch { r.setFree(true) }
        return Device(t, r, events, notices, logs, scope, photos)
    }

    private val rulesDuplas = testRules(GameMode.DUPLAS, stake = 100)

    private suspend fun Device.presenceOf(key: String) = transport.discoverPresence().first { l -> l.any { it.key == key } }.single { it.key == key }

    private val TestScope.currentTime get() = testScheduler.currentTime

    /** Ana abre a seleção de jogadores; os demais ficam livres na tela 1. */
    private suspend fun TestScope.recruiter(network: MemoryNetwork, vararg others: Pair<String, String>): Pair<Device, List<Device>> {
        val ana = device(network, "ka", "Ana", free = false)
        val rest = others.map { (k, n) -> device(network, k, n) }
        runCurrent()
        ana.r.openTable(rulesDuplas, "Ana")
        runCurrent()
        return ana to rest
    }

    @Test
    fun `§16_2 a lista da tela 1 mostra so quem esta recrutando e a da tela 2 so quem esta livre, sem o proprio jogador`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana", free = false)
        val bia = device(net, "kb", "Bia")
        val caio = device(net, "kc", "Caio")
        val fora = device(net, "kf", "Fora", free = false) // em outra tela (Início, Perfil...)
        runCurrent()
        ana.r.recruiters.value shouldBe emptyList()
        ana.r.free.value.map { it.key }.toSet() shouldBe setOf("kb", "kc")
        ana.r.openTable(rulesDuplas, "Ana")
        runCurrent()
        // Ana recruta: aparece para os livres, e ela passa a ver só os livres (Fora não aparece).
        bia.r.recruiters.value.map { it.key } shouldBe listOf("ka")
        caio.r.recruiters.value.map { it.key } shouldBe listOf("ka")
        ana.r.free.value.map { it.key }.toSet() shouldBe setOf("kb", "kc")
        fora.r.recruiters.value.map { it.key } shouldBe listOf("ka")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 chamar - o convite chega na hora pela conexao de espera, sem reanunciar, com aposta, tipo e posicao`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.logs.any { "standby" in it } shouldBe true
        val before = currentTime
        ana.r.call(2, ana.presenceOf("kb")) shouldBe true
        runCurrent()
        val invited = bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        currentTime shouldBe before // nada esperou busca nem anúncio
        invited.hostName shouldBe "Ana"
        invited.table.rules.stake shouldBe 100
        invited.table.rules.mode shouldBe GameMode.DUPLAS
        invited.table.rules.rulesTitle shouldBe "Clássica"
        invited.you shouldBe 2
        invited.table.layout.relationOf(invited.you) shouldBe SeatRelation.PARTNER // o lugar da frente
        // Na lista da Ana o lugar já mostra o Chamado.
        ana.r.layout.value!!.slots[2] shouldBe Slot.Called("kb", "Bia")
        ana.r.layout.value!!.canStart shouldBe false
        ana.logs.any { it.startsWith("rec call kb invite sent via link") } shouldBe true
        advanceTimeBy(20_000)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>() // ainda dentro dos 25 s
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 posicao do convite - Adversario nos lugares 1 e 3 e no individual`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia", "kc" to "Caio")
        ana.r.call(1, ana.presenceOf("kb"))
        ana.r.call(3, ana.presenceOf("kc"))
        runCurrent()
        val bia = others[0].r.status.value as RecruitStatus.Invited
        bia.table.layout.relationOf(bia.you) shouldBe SeatRelation.OPPONENT
        val caio = others[1].r.status.value as RecruitStatus.Invited
        caio.table.layout.relationOf(caio.you) shouldBe SeatRelation.OPPONENT
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 chamado aparece Recrutado para os outros recrutadores e nao pode ser chamado por eles`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val caio = device(net, "kc", "Caio", free = false)
        caio.r.openTable(rulesDuplas, "Caio")
        runCurrent()
        ana.r.call(1, ana.presenceOf("kb")) shouldBe true
        runCurrent()
        caio.presenceOf("kb").recruitedBy shouldBe "ka"
        caio.r.call(1, caio.presenceOf("kb")) shouldBe false
        ana.r.removeSeat(1) shouldBe true // cancela o convite
        runCurrent()
        others.single().r.status.value shouldBe RecruitStatus.Free
        others.single().events shouldBe emptyList() // cancelar não gera aviso
        caio.presenceOf("kb").recruitedBy shouldBe null
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 recusar - o lugar volta a VAGO, o recrutador e avisado com o motivo e o jogador pode ser chamado de novo`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(3, ana.presenceOf("kb"))
        runCurrent()
        bia.r.refuse()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.r.layout.value!!.slots[3] shouldBe Slot.Vacant
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = true, reason = RefusalReason.INVITE_REFUSED)
        ana.presenceOf("kb").recruitedBy shouldBe null
        ana.r.call(1, ana.presenceOf("kb")) shouldBe true
        runCurrent()
        (bia.r.status.value as RecruitStatus.Invited).you shouldBe 1
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §14_6 saldo menor que a aposta - recusa automatica com o motivo e o lugar volta a VAGO`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana", free = false)
        val bia = device(net, "kb", "Bia", canAfford = { it <= 50 })
        runCurrent()
        ana.r.openTable(rulesDuplas, "Ana")
        runCurrent()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        // O recrutado vê o aviso (o convite não fica na tela nem um instante).
        bia.events.single() shouldBe RecruitEvent.Refused("Ana", RefusalReason.INSUFFICIENT_BALANCE)
        ana.notices.single().reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
        ana.r.layout.value!!.slots[2] shouldBe Slot.Vacant
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 aceitar - vira Recrutado nos dois lados, ve os lugares ao vivo e Sair da mesa devolve o lugar`() = runTest {
        val net = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        net.tap = { sent += it; listOf(it) }
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept() shouldBe true
        runCurrent()
        ana.r.layout.value!!.slots[2] shouldBe Slot.Recruited("kb", "Bia")
        val joined = bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        joined.table.layout.slots[2] shouldBe Slot.Recruited("kb", "Bia")
        ana.presenceOf("kb").recruitedBy shouldBe "ka" // continua Recrutado para os outros
        // O recrutador põe um robô: aparece ao vivo no recrutado (depois do debounce).
        ana.r.placeRobot(1, "bolt") shouldBe true
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Joined).table.layout.slots[1] shouldBe Slot.Robot("bolt")
        ana.logs.any { it.startsWith("rec seats v=") } shouldBe true
        bia.logs.any { it.startsWith("rec seats v=") && it.endsWith("apply") } shouldBe true
        bia.r.leave()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = false, reason = null)
        ana.r.layout.value!!.slots[2] shouldBe Slot.Vacant
        sent.forEach { it.bytes.decodeToString() shouldNotContain "token" }
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 mover e remover - so o recrutador move, o Chamado acompanha a posicao e Recrutado removido ve tirou voce da mesa`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia", "kc" to "Caio")
        val (bia, caio) = others
        ana.r.call(1, ana.presenceOf("kb"))
        ana.r.call(2, ana.presenceOf("kc"))
        runCurrent()
        caio.r.accept()
        runCurrent()
        // Mover o Chamado (Bia, lugar 1) para o lugar da frente (3 -> vago): a posição do convite acompanha.
        ana.r.move(1, 3) shouldBe true
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Invited).you shouldBe 3
        ana.r.move(0, 1) shouldBe false // o recrutador não se move
        ana.r.move(3, 2) shouldBe true // troca com o Recrutado
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        (caio.r.status.value as RecruitStatus.Joined).you shouldBe 3
        (bia.r.status.value as RecruitStatus.Invited).table.layout.relationOf((bia.r.status.value as RecruitStatus.Invited).you) shouldBe SeatRelation.PARTNER
        // Remover o Recrutado.
        ana.r.removeSeat(3) shouldBe true
        runCurrent()
        caio.r.status.value shouldBe RecruitStatus.Free
        caio.events.single() shouldBe RecruitEvent.Removed("Ana")
        ana.r.removeSeat(0) shouldBe false
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 montagem numerada - varias mudancas seguidas viram um envio e o recrutado so aplica versao maior`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.logs.clear()
        ana.r.placeRobot(1, "bolt")
        advanceTimeBy(20)
        ana.r.placeRobot(3, "zen")
        advanceTimeBy(20)
        ana.r.move(1, 3) // troca os dois robôs
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        ana.logs.count { it.startsWith("rec seats v=") && "send" in it } shouldBe 1
        (bia.r.status.value as RecruitStatus.Joined).table.layout.robots shouldBe mapOf(1 to "zen", 3 to "bolt")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 sincronizacao perdida ou fora de ordem - o recrutado termina com a mais nova`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        val held = mutableListOf<Delivery>()
        var holdFirst = true
        var dropOnce = true
        net.tap = { d ->
            val text = d.bytes.decodeToString()
            when {
                "recruitSync" in text && holdFirst -> { holdFirst = false; held += d; emptyList() } // atrasada
                "recruitSync" in text && dropOnce && held.isNotEmpty() -> { dropOnce = false; emptyList() } // perdida
                else -> listOf(d)
            }
        }
        ana.r.placeRobot(1, "bolt")
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        ana.r.placeRobot(3, "zen")
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        held.size shouldBe 1
        net.deliver(held.single()) // chega depois da mais nova
        advanceTimeBy(ReliableLink.RESEND_AFTER_MILLIS * 3)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Joined).table.layout.robots shouldBe mapOf(1 to "bolt", 3 to "zen")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 o recrutador volta, sai ou desliga o Wi-Fi - Chamado cancela sem aviso e Recrutado ve Mesa encerrada`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia", "kc" to "Caio")
        val (bia, caio) = others
        ana.r.call(1, ana.presenceOf("kb"))
        ana.r.call(2, ana.presenceOf("kc"))
        runCurrent()
        caio.r.accept()
        runCurrent()
        ana.r.closeHosting()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events shouldBe emptyList()
        caio.r.status.value shouldBe RecruitStatus.Free
        caio.events.single() shouldBe RecruitEvent.Closed("Ana")
        ana.r.layout.value.shouldBeNull() // sempre VAGO ao abrir de novo
        ana.r.openTable(rulesDuplas, "Ana")
        ana.r.layout.value!!.slots.drop(1).all { it == Slot.Vacant } shouldBe true
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 queda da conexao - o recrutado continua, reconecta e recebe os lugares inteiros de novo`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        net.drop(bia.transport.id)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>() // não saiu
        advanceTimeBy(Recruitment.CONTACT_BACKOFF_MILLIS[0] + 100)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        bia.logs.count { it.startsWith("rec seats v=") && it.endsWith("apply") } shouldBe 2 // a do aceite e a reenviada inteira, versão nova
        ana.r.layout.value!!.slots[2] shouldBe Slot.Recruited("kb", "Bia")
        advanceTimeBy(Recruitment.REJOIN_MILLIS * 2)
        runCurrent()
        bia.events shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §16_15 queda sem volta - depois de 8 s o lugar volta a VAGO e os dois sao avisados`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
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
        ana.r.layout.value!!.slots[2] shouldBe Slot.Vacant
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 Comecar Partida - so com tudo preenchido e sem Chamado, Completar com robos e deterministico`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.start("mesa1", TEST_PERSONAS, 1).shouldBeNull() // lugares VAGO
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        ana.r.fillWithRobots(TEST_PERSONAS, seed = 5)
        runCurrent()
        ana.r.layout.value!!.isFull shouldBe true
        ana.r.start("mesa1", TEST_PERSONAS, 1).shouldBeNull() // Chamado pendente
        bia.r.accept()
        runCurrent()
        val robots = ana.r.layout.value!!.robots
        robots.keys shouldBe setOf(1, 3)
        robots.values.toSet().size shouldBe 2
        val plan = ana.r.start("mesa1", TEST_PERSONAS, 1).shouldNotBeNull()
        runCurrent()
        plan.layout.robots shouldBe robots // os robôs do início são exatamente os da tela
        bia.events.single() shouldBe RecruitEvent.Start("mesa1", "kb", PREPARE_MILLIS)
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 de ponta a ponta - chamar, aceitar, mover, completar e comecar termina em GameStarted com os lugares da tela`() = runTest {
        val net = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        net.tap = { sent += it; listOf(it) }
        val (ana, others) = recruiter(net, "kb" to "Bia", "kc" to "Caio")
        val (bia, caio) = others
        ana.r.call(1, ana.presenceOf("kb"))
        ana.r.call(2, ana.presenceOf("kc"))
        runCurrent()
        bia.r.accept(); caio.r.accept()
        runCurrent()
        ana.r.move(1, 3) // Bia vai ao lugar 3
        ana.r.fillWithRobots(TEST_PERSONAS, seed = 987654321987L)
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        val layout = ana.r.layout.value!!
        layout.slots[3] shouldBe Slot.Recruited("kb", "Bia")
        layout.slots[2] shouldBe Slot.Recruited("kc", "Caio")
        layout.slots[1].shouldBeInstanceOf<Slot.Robot>()
        val plan = ana.r.start("mesa1", TEST_PERSONAS, seed = 987654321987L).shouldNotBeNull()
        runCurrent()
        val startBia = bia.events.filterIsInstance<RecruitEvent.Start>().single()
        val startCaio = caio.events.filterIsInstance<RecruitEvent.Start>().single()
        // A mesa do jogo: o anfitrião abre, os recrutados entram com a própria chave e o início sai depois do "Prepare-se!".
        // O recrutamento pára de ler os eventos antes de a sessão da mesa começar a ler (um leitor por transporte).
        ana.scope.cancel(); bia.scope.cancel(); caio.scope.cancel()
        runCurrent()
        val host = HostSession(ana.transport, startBia.tableId, rulesDuplas, "Ana", plan = plan)
        host.open(sessionScope)
        val gb = GuestSession(bia.transport, "Bia").also { it.join(sessionScope, startBia.tableId, inviteKey = startBia.inviteKey) }
        val gc = GuestSession(caio.transport, "Caio").also { it.join(sessionScope, startCaio.tableId, inviteKey = startCaio.inviteKey) }
        runCurrent()
        host.gameStart.value.shouldBeNull()
        advanceTimeBy(PREPARE_MILLIS + 1)
        runCurrent()
        runCurrent()
        host.gameStart.value shouldBe mapOf(Seat(3) to gb.playerId, Seat(2) to gc.playerId)
        gb.status.value shouldBe GuestStatus.Playing
        gb.robots.value shouldBe layout.robots
        gc.robots.value shouldBe layout.robots
        // Nem a semente nem o token de outro jogador viajam nas mensagens.
        val tokens = listOfNotNull(gb.token, gc.token)
        sent.forEach { d ->
            val text = d.bytes.decodeToString()
            text shouldNotContain "987654321987"
            tokens.forEach { tk -> if (text.contains(tk)) text.contains("\"you\":${if (tk == gb.token) gb.playerId else gc.playerId}") shouldBe true }
        }
    }

    @Test
    fun `§16_2 inicio perdido - o recrutado entra quando a mesa aparece com a chave dele`() = runTest {
        val net = MemoryNetwork()
        net.tap = { d -> if ("recruitStart" in d.bytes.decodeToString()) emptyList() else listOf(d) } // o início nunca chega
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        ana.r.fillWithRobots(TEST_PERSONAS, 1)
        val plan = ana.r.start("mesa1", TEST_PERSONAS, 1).shouldNotBeNull()
        runCurrent()
        bia.events shouldBe emptyList()
        ana.scope.cancel()
        val host = HostSession(ana.transport, "mesa1", rulesDuplas, "Ana", plan = plan)
        host.open(sessionScope)
        runCurrent()
        bia.events shouldBe listOf(RecruitEvent.Start("mesa1", "kb", PREPARE_MILLIS))
    }

    @Test
    fun `§16_2 as fotos vao pela conexao de espera, nos dois sentidos, antes do convite`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana", photo = "FOTOANA", free = false)
        val bia = device(net, "kb", "Bia", photo = "FOTOBIA")
        runCurrent()
        ana.r.openTable(rulesDuplas, "Ana")
        runCurrent()
        ana.photos.photos.value shouldBe mapOf("kb" to "FOTOBIA")
        bia.photos.photos.value shouldBe mapOf("ka" to "FOTOANA")
        advanceTimeBy(60_000)
        ana.r.call(1, ana.presenceOf("kb"))
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 recrutador nao recruta recrutador - dois na Selecao de jogadores nao se conectam`() = runTest {
        val net = MemoryNetwork()
        val ana = device(net, "ka", "Ana", free = false)
        val caio = device(net, "kc", "Caio", free = false)
        runCurrent()
        ana.r.openTable(rulesDuplas, "Ana")
        caio.r.openTable(rulesDuplas, "Caio")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        ana.logs.none { it.startsWith("rec connected") || it.startsWith("rec standby") } shouldBe true
        caio.logs.none { it.startsWith("rec connected") || it.startsWith("rec standby") } shouldBe true
        ana.r.free.value shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_4 §16_2 a chave de presenca de um recrutado nunca chega aos outros nas mensagens de lugares`() = runTest {
        val net = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        net.tap = { sent += it; listOf(it) }
        val ana = device(net, "ka", "Ana", free = false)
        val bia = device(net, "key-bia-123", "Bia")
        val caio = device(net, "key-caio-456", "Caio")
        runCurrent()
        ana.r.openTable(rulesDuplas, "Ana")
        runCurrent()
        ana.r.call(1, ana.presenceOf("key-bia-123"))
        ana.r.call(2, ana.presenceOf("key-caio-456"))
        runCurrent()
        bia.r.accept(); caio.r.accept()
        advanceTimeBy(Recruitment.SYNC_DEBOUNCE_MILLIS + 1)
        runCurrent()
        // Cada um vê o nome do outro e o estado, mas só a própria chave.
        val seenByBia = (bia.r.status.value as RecruitStatus.Joined).table.layout.slots
        seenByBia[1] shouldBe Slot.Recruited("key-bia-123", "Bia")
        seenByBia[2] shouldBe Slot.Recruited("", "Caio")
        // Nenhuma mensagem de lugares ao Bia traz a chave do Caio (e vice-versa).
        sent.filter { it.to == bia.transport.id && ("recruitSync" in it.bytes.decodeToString() || "recruitInvite" in it.bytes.decodeToString()) }
            .forEach { it.bytes.decodeToString() shouldNotContain "key-caio-456" }
        sent.filter { it.to == caio.transport.id && ("recruitSync" in it.bytes.decodeToString() || "recruitInvite" in it.bytes.decodeToString()) }
            .forEach { it.bytes.decodeToString() shouldNotContain "key-bia-123" }
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §16_15 convite sem resposta em 25 s vale como recusa - volta a Free, o recrutador e avisado e o lugar fica VAGO`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        val invited = bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        invited.remainingMillis(bia.r.nowMillis()) shouldBe INVITE_TIMEOUT_MILLIS
        advanceTimeBy(10_000)
        runCurrent()
        (bia.r.status.value as RecruitStatus.Invited).remainingMillis(bia.r.nowMillis()) shouldBe INVITE_TIMEOUT_MILLIS - 10_000
        advanceTimeBy(INVITE_TIMEOUT_MILLIS - 10_000 - 1)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>() // o aceite nunca é automático
        advanceTimeBy(2)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        bia.events.single() shouldBe RecruitEvent.Refused("Ana", RefusalReason.NO_RESPONSE)
        ana.notices.single() shouldBe RecruitNotice("kb", "Bia", refused = true, reason = RefusalReason.NO_RESPONSE)
        ana.r.layout.value!!.slots[2] shouldBe Slot.Vacant
        // Pode ser chamado de novo, com 25 s novos.
        ana.r.call(1, ana.presenceOf("kb")) shouldBe true
        runCurrent()
        (bia.r.status.value as RecruitStatus.Invited).remainingMillis(bia.r.nowMillis()) shouldBe INVITE_TIMEOUT_MILLIS
        ana.scope.cancel()
    }

    @Test
    fun `§16_15 o chamado que respondeu a tempo nao e recusado depois`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        advanceTimeBy(24_000)
        bia.r.accept() shouldBe true
        advanceTimeBy(60_000)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Joined>()
        ana.r.layout.value!!.slots[2] shouldBe Slot.Recruited("kb", "Bia")
        ana.notices.shouldNotBeNull()
        bia.events shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_15 recrutador devolve o lugar sozinho quando a resposta nunca chega, depois de 25 s mais a folga`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        // A conexão segue viva, mas nada que o recrutado diga (nem recusa) chega ao recrutador.
        net.tap = { d -> if (d.from == bia.transport.id && "recruitReply" in d.bytes.decodeToString()) emptyList() else listOf(d) }
        bia.r.refuse()
        advanceTimeBy(INVITE_TIMEOUT_MILLIS + INVITE_HOST_GRACE_MILLIS - 1)
        ana.r.layout.value!!.slots[2].shouldBeInstanceOf<Slot.Called>()
        advanceTimeBy(2)
        runCurrent()
        ana.r.layout.value!!.slots[2] shouldBe Slot.Vacant
        ana.notices.last().reason shouldBe RefusalReason.NO_RESPONSE
        ana.scope.cancel()
    }

    @Test
    fun `§16_15 conexao aberta que fica muda e dada como caida em cerca de 1 s e o jogador sai das listas`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.free.value.map { it.key } shouldBe listOf("kb")
        net.tap = { d -> if (d.from == bia.transport.id || d.to == bia.transport.id) emptyList() else listOf(d) } // sumiu sem avisar
        advanceTimeBy(Recruitment.DEAD_AFTER_MILLIS + Recruitment.HEARTBEAT_MILLIS * 2)
        runCurrent()
        ana.logs.any { it.startsWith("heartbeat dead") } shouldBe true
        ana.r.free.value shouldBe emptyList()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 aviso explicito de saida - o outro tira o jogador das listas na hora`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.free.value.map { it.key } shouldBe listOf("kb")
        bia.r.shutdown()
        runCurrent()
        ana.r.free.value shouldBe emptyList()
        ana.logs.any { it.startsWith("rec bye kb") } shouldBe true
        ana.scope.cancel()
    }

    /** Segura as mensagens entre os dois aparelhos por [millis] (jitter do rádio) e as entrega depois, em ordem. */
    private suspend fun TestScope.jitter(net: MemoryNetwork, millis: Long, a: MemoryTransport, b: MemoryTransport) {
        val held = mutableListOf<Delivery>()
        var holding = true
        net.tap = { d ->
            if (holding && ((d.from == a.id && d.to == b.id) || (d.from == b.id && d.to == a.id))) { held += d; emptyList() } else listOf(d)
        }
        advanceTimeBy(millis)
        holding = false
        held.forEach { net.deliver(it) }
        runCurrent()
    }

    @Test
    fun `§16_2 §16_15 atraso de 2 s do radio nao derruba a conexao viva - o convite nao some e reaparece`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        val states = mutableListOf<RecruitStatus>()
        sessionScope.launch { bia.r.status.collect { states += it } }
        runCurrent()
        jitter(net, 2_000, ana.transport, bia.transport)
        advanceTimeBy(1_000)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        states.none { it == RecruitStatus.Free } shouldBe true // nunca fechou no meio
        ana.logs.none { it.startsWith("heartbeat dead") } shouldBe true
        bia.logs.none { it.startsWith("heartbeat dead") } shouldBe true
        ana.r.layout.value!!.slots[2].shouldBeInstanceOf<Slot.Called>()
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 atraso de 2 s do radio nao derruba o recrutado nem os lugares`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        bia.r.accept()
        runCurrent()
        val states = mutableListOf<RecruitStatus>()
        sessionScope.launch { bia.r.status.collect { states += it } }
        runCurrent()
        jitter(net, 2_000, ana.transport, bia.transport)
        advanceTimeBy(1_000)
        runCurrent()
        states.none { it == RecruitStatus.Free } shouldBe true
        ana.r.layout.value!!.slots[2] shouldBe Slot.Recruited("kb", "Bia")
        ana.scope.cancel()
    }

    @Test
    fun `§16_2 §16_15 conexao do convite que cai de verdade mantem o modal 1,5 s e o convite reenviado atualiza no lugar`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        val states = mutableListOf<RecruitStatus>()
        sessionScope.launch { bia.r.status.collect { states += it } }
        runCurrent()
        net.drop(bia.transport.id) // a conexão cai e o rádio logo volta a conectar
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>() // ainda na tela
        advanceTimeBy(Recruitment.CONTACT_BACKOFF_MILLIS[0] + 200)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        states.none { it == RecruitStatus.Free } shouldBe true // não piscou
        // Se a conexão não volta, o convite fecha depois de 1,5 s.
        net.block(bia.transport.id)
        net.drop(bia.transport.id)
        advanceTimeBy(Recruitment.INVITE_LOSS_GRACE_MILLIS - 1)
        runCurrent()
        bia.r.status.value.shouldBeInstanceOf<RecruitStatus.Invited>()
        advanceTimeBy(2)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.scope.cancel()
    }

    @Test
    fun `§16_15 convite reenviado depois da queda mantem o prazo original e nao reabre um modal ja respondido`() = runTest {
        val net = MemoryNetwork()
        val (ana, others) = recruiter(net, "kb" to "Bia")
        val bia = others.single()
        ana.r.call(2, ana.presenceOf("kb"))
        runCurrent()
        val first = bia.r.status.value as RecruitStatus.Invited
        advanceTimeBy(10_000)
        net.drop(bia.transport.id)
        advanceTimeBy(Recruitment.CONTACT_BACKOFF_MILLIS[0] + 200)
        runCurrent()
        val again = bia.r.status.value as RecruitStatus.Invited
        again.deadlineMillis shouldBe first.deadlineMillis // o prazo não renova
        again.remainingMillis(bia.r.nowMillis()) shouldBe INVITE_TIMEOUT_MILLIS - 10_000 - Recruitment.CONTACT_BACKOFF_MILLIS[0] - 200
        // Recusou: um reenvio atrasado do mesmo convite não reabre o modal.
        bia.r.refuse()
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        advanceTimeBy(3_000)
        runCurrent()
        bia.r.status.value shouldBe RecruitStatus.Free
        ana.scope.cancel()
    }
}
