package com.gtranca.net.nearby

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.RuleSet
import com.gtranca.net.EndpointId
import com.gtranca.net.GuestSession
import com.gtranca.net.GuestStatus
import com.gtranca.net.HostSession
import com.gtranca.net.TableAdvert
import com.gtranca.net.TableRules
import com.gtranca.net.TransportEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Etapa 5: o transporte Nearby (§16) sobre um Nearby falso, no relógio virtual. */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyTransportTest {

    private val network = FakeNearbyNetwork()

    private fun TestScope.node(): Pair<FakeNearbyApi, NearbyTransport> {
        val api = network.api()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job(backgroundScope.coroutineContext[Job]))
        return api to NearbyTransport(api, scope, clock = { testScheduler.currentTime })
    }

    private val advert = TableAdvert("abcdefgh", "Mesa", "Ana", hostId = 1)

    private fun TestScope.events(t: NearbyTransport): MutableList<TransportEvent> {
        val list = mutableListOf<TransportEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { t.events.collect { list += it } }
        return list
    }

    @Test
    fun `anuncio igual nao reanuncia e anuncio novo para e reanuncia sem derrubar conexoes`() = runTest {
        val (hostApi, host) = node()
        val (_, guest) = node()
        host.advertise(advert)
        host.advertise(advert)
        hostApi.advertiseStarts shouldBe 1
        hostApi.advertiseStops shouldBe 0
        val guestEvents = events(guest)
        val found = guest.discover().first()
        guest.connect(found.single())
        runCurrent()
        host.advertise(advert.copy(listed = false))
        runCurrent()
        hostApi.advertiseStarts shouldBe 2
        hostApi.advertiseStops shouldBe 1
        guestEvents.filterIsInstance<TransportEvent.Disconnected>() shouldBe emptyList()
        guest.discover().first().single().listed shouldBe false
    }

    @Test
    fun `§16_2 presenca e mesa usam o mesmo anuncio e a busca as separa pelo formato`() = runTest {
        val (api, host) = node()
        val (_, other) = node()
        val presence = com.gtranca.net.PresenceAdvert("abc234", "Ana")
        host.advertisePresence(presence)
        other.discoverPresence().first { it.isNotEmpty() } shouldBe listOf(presence)
        // Presença no ar: parar o anúncio de mesa não a derruba.
        host.stopAdvertising()
        api.advertInfo!!.contentEquals(AdvertCodec.encodePresence(presence)) shouldBe true
        api.advertiseStops shouldBe 0
        // A mesa toma o lugar da presença; a busca a vê como mesa, e a presença some.
        host.advertise(advert)
        runCurrent()
        api.advertiseStops shouldBe 1
        other.discover().first().single().tableId shouldBe advert.tableId
        other.discoverPresence().first() shouldBe emptyList()
        // Parar a presença com a mesa no ar não faz nada; parar a mesa sim.
        host.stopPresence()
        api.advertiseStops shouldBe 1
        host.advertisePresence(presence)
        runCurrent()
        api.advertiseStops shouldBe 2
        other.discoverPresence().first { it.isNotEmpty() } shouldBe listOf(presence)
        host.stopPresence()
        // §16.2 antes de parar, o aviso de "saindo" (uma troca de anúncio a mais).
        api.advertiseStops shouldBe 4
        api.advertInfo shouldBe null
    }

    @Test
    fun `busca recem ligada nao emite a lista vazia antes do aquecimento`() = runTest {
        val (_, host) = node()
        val (_, guest) = node()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            kotlinx.coroutines.delay(1_000)
            host.advertise(advert)
        }
        val start = currentTime
        guest.discover().first().map { it.tableId } shouldContainExactly listOf("abcdefgh")
        (currentTime - start) shouldBeLessThan NearbyTransport.WARM_UP_MILLIS
        // Sem mesa nenhuma: a lista vazia só depois do aquecimento.
        val (_, lonely) = node()
        network.vanish(network.nodes.keys.first())
        val t0 = currentTime
        lonely.discover().first() shouldBe emptyList()
        (currentTime - t0) shouldBeGreaterThanOrEqual NearbyTransport.WARM_UP_MILLIS
    }

    @Test
    fun `uma busca so para varios coletores e ela para depois do ultimo sair`() = runTest {
        val (api, guest) = node()
        val a = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { guest.discover().collect {} }
        val b = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { guest.discover().collect {} }
        runCurrent()
        api.discoveryStarts shouldBe 1
        a.cancel()
        runCurrent()
        b.cancel()
        advanceTimeBy(NearbyTransport.LINGER_MILLIS - 1)
        api.discoveryStops shouldBe 0
        advanceTimeBy(2)
        runCurrent()
        api.discoveryStops shouldBe 1
    }

    @Test
    fun `conexao bytes e desconexao local avisam os dois lados`() = runTest {
        val (_, host) = node()
        val (_, guest) = node()
        val hostEvents = events(host)
        val guestEvents = events(guest)
        host.advertise(advert)
        val endpoint = guest.connect(guest.discover().first().single())
        runCurrent()
        hostEvents.single().shouldBeInstanceOf<TransportEvent.Connected>()
        guestEvents.single() shouldBe TransportEvent.Connected(endpoint)
        guest.send(endpoint, byteArrayOf(1, 2, 3))
        runCurrent()
        (hostEvents.last() as TransportEvent.Received).bytes.toList() shouldBe listOf<Byte>(1, 2, 3)
        guest.disconnect(endpoint)
        runCurrent()
        guestEvents.last() shouldBe TransportEvent.Disconnected(endpoint)
        hostEvents.last().shouldBeInstanceOf<TransportEvent.Disconnected>()
        // Envio a quem caiu é descartado em silêncio (o reenvio é do protocolo).
        guest.send(endpoint, byteArrayOf(9))
    }

    @Test
    fun `mesa fora da busca nao conecta e mensagem acima do limite e recusada`() = runTest {
        val (_, guest) = node()
        shouldThrow<IllegalStateException> { guest.connect(advert) }
        shouldThrow<IllegalArgumentException> { guest.send(EndpointId("x"), ByteArray(1047553)) }
    }

    @Test
    fun `anfitriao caido avisa o convidado e a mesa some da busca`() = runTest {
        val (hostApi, host) = node()
        val (_, guest) = node()
        val guestEvents = events(guest)
        host.advertise(advert)
        val lists = mutableListOf<List<TableAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { guest.discover().collect { lists += it } }
        runCurrent()
        guest.connect(lists.last().single())
        network.vanish(hostApi.id)
        runCurrent()
        guestEvents.last().shouldBeInstanceOf<TransportEvent.Disconnected>()
        lists.last() shouldBe emptyList()
    }

    /** §16.2 a sala funciona de ponta a ponta sobre o transporte Nearby (o mesmo protocolo do transporte em memória). */
    @Test
    fun `convidado entra na sala do anfitriao pelo Nearby`() = runTest {
        val (_, hostTransport) = node()
        val (_, guestTransport) = node()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val rules = TableRules("Mesa", GameMode.DUPLAS, RuleSet(), 1000, stake = 0, difficulty = "facil")
        val host = HostSession(hostTransport, newTableId(), rules, "Ana")
        host.open(scope)
        val guest = GuestSession(guestTransport, "Bia")
        guest.join(scope, host.tableId)
        advanceUntilIdle()
        guest.status.value shouldBe GuestStatus.Lobby
        host.roster.value.map { it.name } shouldContainExactly listOf("Ana", "Bia")
        scope.cancel()
    }

    private val wait = NearbyTransport.PRESENCE_REFRESH_MILLIS

    @Test
    fun `§16_2 §16_15 quem some sem avisar sai da lista depois de duas verificacoes e quem continua nao pisca`() = runTest {
        network.silentStop = true
        val (goneApi, gone) = node()
        val (_, stays) = node()
        val (_, me) = node()
        val bia = com.gtranca.net.PresenceAdvert("kbia", "Bia")
        val caio = com.gtranca.net.PresenceAdvert("kcaio", "Caio")
        gone.advertisePresence(bia)
        stays.advertisePresence(caio)
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        lists.last().toSet() shouldBe setOf(bia, caio)
        goneApi.stopAdvertising() // sumiu sem aviso (aparelho fora do alcance)
        advanceTimeBy(wait + NearbyTransport.PRESENCE_SWEEP_GRACE_MILLIS + 1)
        runCurrent()
        lists.last().toSet() shouldBe setOf(bia, caio) // uma verificação só não basta
        advanceTimeBy(wait)
        runCurrent()
        lists.last() shouldBe listOf(caio)
        lists.dropWhile { caio !in it }.all { caio in it } shouldBe true
        // Mesas não são varridas (a "mesa sumida" do sucessor, §16.10, depende só do onLost).
        val (_, host) = node()
        host.advertise(advert)
        host.stopAdvertising()
        advanceTimeBy(wait * 4)
        runCurrent()
        me.discover().first().map { it.tableId } shouldBe listOf(advert.tableId)
    }

    @Test
    fun `§16_2 §16_15 desligar o Wi-Fi do jogo anuncia saindo por 2 s e os outros tiram o jogador na hora`() = runTest {
        network.silentStop = true
        val (goneApi, gone) = node()
        val (_, me) = node()
        val bia = com.gtranca.net.PresenceAdvert("kbia", "Bia")
        gone.advertisePresence(bia)
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        lists.last() shouldBe listOf(bia)
        val stopping = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { gone.stopPresence() }
        runCurrent()
        lists.last() shouldBe emptyList() // na hora
        goneApi.advertInfo!!.first().toInt() shouldBe AdvertCodec.PRESENCE_LEAVING_FORMAT
        advanceTimeBy(NearbyTransport.PRESENCE_LEAVING_MILLIS + 1)
        runCurrent()
        stopping.isCompleted shouldBe true
        goneApi.advertInfo shouldBe null
    }

    @Test
    fun `§16_2 falha ao religar a busca nao abandona a verificacao`() = runTest {
        network.silentStop = true
        val (goneApi, gone) = node()
        val (api, me) = node()
        gone.advertisePresence(com.gtranca.net.PresenceAdvert("kbia", "Bia"))
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        api.failNextDiscoveryStarts = 2
        goneApi.stopAdvertising()
        advanceTimeBy(wait * 4)
        runCurrent()
        lists.last() shouldBe emptyList()
        (api.discovery != null) shouldBe true // a busca voltou a ficar ligada
        // A busca de mesas também segue viva.
        val (_, host) = node()
        host.advertise(advert)
        runCurrent()
        me.discover().first().map { it.tableId } shouldBe listOf(advert.tableId)
    }

    @Test
    fun `§16_2 reiniciar a busca nao derruba a conexao de espera ja aberta`() = runTest {
        val (_, other) = node()
        val (api, me) = node()
        val bia = com.gtranca.net.PresenceAdvert("kbia", "Bia")
        other.advertisePresence(bia)
        val events = events(me)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect {} }
        runCurrent()
        me.connectPresence(bia)
        advanceTimeBy(wait * 3)
        runCurrent()
        (api.discoveryStarts > 1) shouldBe true
        events.filterIsInstance<TransportEvent.Disconnected>() shouldBe emptyList()
    }

    @Test
    fun `§16_2 foto do jogador proximo pelo Nearby - conexao curta com a presenca`() = runTest {
        val (_, ana) = node()
        val (_, bia) = node()
        ana.advertisePresence(com.gtranca.net.PresenceAdvert("kana", "Ana"))
        bia.advertisePresence(com.gtranca.net.PresenceAdvert("kbia", "Bia"))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val anaPhotos = com.gtranca.net.PresencePhotos(ana, "kana", "FOTO")
        val biaPhotos = com.gtranca.net.PresencePhotos(bia, "kbia", null)
        scope.launch { anaPhotos.serve() }
        scope.launch { biaPhotos.serve() }
        scope.launch { anaPhotos.fetchAll(ana.discoverPresence()) }
        scope.launch { biaPhotos.fetchAll(bia.discoverPresence()) }
        runCurrent()
        biaPhotos.photos.value shouldBe mapOf("kana" to "FOTO")
        anaPhotos.photos.value shouldBe mapOf("kbia" to "")
        scope.cancel()
    }

    private fun presence(key: String, name: String = key, hosting: Boolean = false) = com.gtranca.net.PresenceAdvert(key, name, hosting = hosting)

    @Test
    fun `§16_2 a mesma chave em dois endpoints aparece uma vez so`() = runTest {
        val (_, a1) = node()
        val (_, a2) = node()
        val (_, me) = node()
        a1.advertisePresence(presence("kx", "Xis"))
        a2.advertisePresence(presence("kx", "Xis"))
        runCurrent()
        me.discoverPresence().first { it.isNotEmpty() }.map { it.key } shouldBe listOf("kx")
    }

    @Test
    fun `§16_2 §16_15 quem para e reanuncia dentro da folga nao some da lista dos outros`() = runTest {
        val (hostApi, host) = node()
        val (_, me) = node()
        val bytes = AdvertCodec.encodePresence(presence("kx", "Xis"))
        host.advertisePresence(presence("kx", "Xis"))
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        lists.last().map { it.key } shouldBe listOf("kx")
        // Parar e voltar a anunciar (como a presença faz a cada mudança), com um intervalo de 2 s.
        hostApi.stopAdvertising()
        advanceTimeBy(2_000)
        hostApi.startAdvertising(bytes, object : ConnectionCallback {
            override fun onInitiated(endpointId: String) {}
            override fun onResult(endpointId: String, success: Boolean) {}
            override fun onDisconnected(endpointId: String) {}
        })
        advanceTimeBy(NearbyTransport.PRESENCE_LOST_GRACE_MILLIS * 2)
        runCurrent()
        lists.drop(1).none { it.isEmpty() } shouldBe true // nunca piscou
        lists.last().map { it.key } shouldBe listOf("kx")
        // Parou de vez: sai depois da folga.
        hostApi.stopAdvertising()
        advanceTimeBy(NearbyTransport.PRESENCE_LOST_GRACE_MILLIS - 1)
        runCurrent()
        lists.last().map { it.key } shouldBe listOf("kx")
        advanceTimeBy(2)
        runCurrent()
        lists.last() shouldBe emptyList()
    }

    @Test
    fun `§16_2 chave fixada pelo recrutamento nao e tirada pela varredura`() = runTest {
        network.silentStop = true
        val (goneApi, gone) = node()
        val (_, me) = node()
        gone.advertisePresence(presence("kx", "Xis"))
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        me.pinPresence(setOf("kx"))
        goneApi.stopAdvertising() // sumiu sem aviso
        advanceTimeBy(wait * 6)
        runCurrent()
        lists.last().map { it.key } shouldBe listOf("kx") // fixada: fica
        me.pinPresence(emptySet())
        advanceTimeBy(wait * 3)
        runCurrent()
        lists.last() shouldBe emptyList()
    }

    private fun TestScope.recruiter(transport: NearbyTransport, key: String, name: String, scope: CoroutineScope, logs: MutableList<String>): com.gtranca.net.Recruitment {
        val rec = com.gtranca.net.Recruitment(transport, scope, key, name, com.gtranca.net.PresencePhotos(transport, key, null), log = { logs += it }, clock = { testScheduler.currentTime })
        scope.launch { rec.run() }
        return rec
    }

    @Test
    fun `§16_2 o convite chega pela conexao de espera sem reanunciar a presenca e sobrevive ao reinicio da busca`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job(backgroundScope.coroutineContext[Job]))
        val (hostApi, hostT) = node()
        val (bobApi, bobT) = node()
        val hostLogs = mutableListOf<String>()
        val bobLogs = mutableListOf<String>()
        val ana = recruiter(hostT, "ka", "Ana", scope, hostLogs)
        val bia = recruiter(bobT, "kb", "Bia", scope, bobLogs)
        ana.setHosting(true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        // Bia já está ligada ao anfitrião (espera); o convite não depende do anúncio.
        hostLogs.any { it.startsWith("rec standby") } shouldBe true
        val advertsBefore = hostApi.advertiseStarts
        ana.mark(com.gtranca.net.PresenceAdvert("kb", "Bia")) shouldBe true
        runCurrent()
        bia.status.value shouldBe com.gtranca.net.RecruitStatus.Invited("ka", "Ana")
        hostApi.advertiseStarts shouldBe advertsBefore // nada foi reanunciado para entregar o convite
        hostLogs.any { "invite sent via link" in it } shouldBe true
        // Reinício da busca (a cada 8 s) durante o recrutamento: convite e conexão intactos.
        advanceTimeBy(wait * 4)
        runCurrent()
        (bobApi.discoveryStarts > 1) shouldBe true
        bia.status.value shouldBe com.gtranca.net.RecruitStatus.Invited("ka", "Ana")
        ana.recruits.value.map { it.key } shouldBe listOf("kb")
        bia.accept() shouldBe true
        runCurrent()
        advanceTimeBy(wait * 3)
        runCurrent()
        bia.status.value.shouldBeInstanceOf<com.gtranca.net.RecruitStatus.Joined>()
        ana.recruits.value.single().accepted shouldBe true
        scope.cancel()
    }

    @Test
    fun `§16_2 o anfitriao reanunciar a presenca ao marcar outro nao cancela o convite do primeiro`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job(backgroundScope.coroutineContext[Job]))
        val (_, hostT) = node()
        val (_, bobT) = node()
        val (_, caioT) = node()
        val ana = recruiter(hostT, "ka", "Ana", scope, mutableListOf())
        val bia = recruiter(bobT, "kb", "Bia", scope, mutableListOf())
        val caio = recruiter(caioT, "kc", "Caio", scope, mutableListOf())
        // Sem a montagem aberta: o convite vai pela reserva do anúncio (o caminho que reanuncia a cada marcação).
        runCurrent()
        advanceTimeBy(1_000)
        ana.mark(com.gtranca.net.PresenceAdvert("kb", "Bia")) shouldBe true
        advanceTimeBy(2_000)
        runCurrent()
        bia.status.value shouldBe com.gtranca.net.RecruitStatus.Invited("ka", "Ana")
        ana.mark(com.gtranca.net.PresenceAdvert("kc", "Caio")) shouldBe true
        advanceTimeBy(NearbyTransport.PRESENCE_LOST_GRACE_MILLIS * 2)
        runCurrent()
        bia.status.value shouldBe com.gtranca.net.RecruitStatus.Invited("ka", "Ana") // não piscou
        caio.status.value shouldBe com.gtranca.net.RecruitStatus.Invited("ka", "Ana")
        scope.cancel()
    }
}
