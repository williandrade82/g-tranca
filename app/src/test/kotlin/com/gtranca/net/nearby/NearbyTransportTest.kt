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
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
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
        api.advertiseStops shouldBe 3
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

    @Test
    fun `§16_2 §16_15 quem para de anunciar sem onLost some da lista em ate 11 s e quem continua nao pisca`() = runTest {
        network.silentStop = true
        val (_, gone) = node()
        val (_, stays) = node()
        val (api, me) = node()
        val bia = com.gtranca.net.PresenceAdvert("kbia", "Bia")
        val caio = com.gtranca.net.PresenceAdvert("kcaio", "Caio")
        gone.advertisePresence(bia)
        stays.advertisePresence(caio)
        val lists = mutableListOf<List<com.gtranca.net.PresenceAdvert>>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { me.discoverPresence().collect { lists += it } }
        runCurrent()
        lists.last().toSet() shouldBe setOf(bia, caio)
        gone.stopPresence()
        runCurrent()
        lists.last().toSet() shouldBe setOf(bia, caio) // o Nearby não avisou
        advanceTimeBy(NearbyTransport.PRESENCE_REFRESH_MILLIS + NearbyTransport.PRESENCE_SWEEP_GRACE_MILLIS + 1)
        runCurrent()
        lists.last() shouldBe listOf(caio)
        // Caio nunca saiu de nenhuma lista emitida depois de aparecer.
        lists.dropWhile { caio !in it }.all { caio in it } shouldBe true
        api.discoveryStarts shouldBe 2
        // Mesas não são varridas (a "mesa sumida" do sucessor, §16.10, depende só do onLost).
        val (_, host) = node()
        host.advertise(advert)
        host.stopAdvertising()
        advanceTimeBy((NearbyTransport.PRESENCE_REFRESH_MILLIS + NearbyTransport.PRESENCE_SWEEP_GRACE_MILLIS) * 2)
        runCurrent()
        me.discover().first().map { it.tableId } shouldBe listOf(advert.tableId)
    }

    @Test
    fun `§16_2 foto do jogador proximo pelo Nearby - conexao curta com a presenca`() = runTest {
        val (_, ana) = node()
        val (_, bia) = node()
        ana.advertisePresence(com.gtranca.net.PresenceAdvert("kana", "Ana"))
        bia.advertisePresence(com.gtranca.net.PresenceAdvert("kbia", "Bia"))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val anaPhotos = com.gtranca.net.PresencePhotos(ana, "FOTO")
        val biaPhotos = com.gtranca.net.PresencePhotos(bia, null)
        scope.launch { anaPhotos.serve() }
        scope.launch { biaPhotos.serve() }
        scope.launch { biaPhotos.fetchAll(bia.discoverPresence()) }
        runCurrent()
        biaPhotos.photos.value shouldBe mapOf("kana" to "FOTO")
        scope.cancel()
    }
}
