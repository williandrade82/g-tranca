package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** §14.4, §16.2, §16.7 mesa montada: convite, pedido de entrada, escolha de posições e Wi-Fi do anfitrião. */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbySessionTest {
    private val pool = listOf("bolt", "domino", "turbo", "zen")
    private fun rules(mode: GameMode = GameMode.DUPLAS, stake: Int = 0) = TableRules("Mesa de Ana", mode, RuleSet.DEFAULT, 1000, stake, "facil")

    private class Table(val network: MemoryNetwork, val host: HostSession, val tokens: MutableList<String>)

    private suspend fun TestScope.table(others: List<Participant>, rules: TableRules = rules(), network: MemoryNetwork = MemoryNetwork()): Table {
        val tokens = mutableListOf<String>()
        var n = 0
        val host = HostSession(
            network.transport(), "m", rules, "Ana", newToken = { "tok-${n++}-secreto".also { tokens += it } },
            lobby = LobbySetup(others, pool, seed = SEED),
        )
        host.open(sessionScope)
        runCurrent()
        return Table(network, host, tokens)
    }

    /** O convidado acha a mesa pela própria chave e conecta (como a interface fará ao ver o anúncio). */
    private suspend fun TestScope.invitee(t: Table, key: String, name: String, canAfford: suspend (Int) -> Boolean = { true }): GuestSession {
        val transport = t.network.transport()
        val advert = transport.discover().first().invitationFor(key).shouldNotBeNull()
        return GuestSession(transport, name, canAfford = canAfford).also {
            it.join(sessionScope, advert.tableId, inviteKey = key)
            runCurrent()
        }
    }

    private fun nearby(key: String, name: String) = Participant.Nearby(PresenceAdvert(key, name))

    @Test
    fun `§16_2 presenca de jogador proximo e distinta do anuncio de mesa`() = runTest {
        val network = MemoryNetwork()
        val bia = network.transport()
        val ana = network.transport()
        bia.advertisePresence(PresenceAdvert("k-bia", "Bia"))
        ana.discoverPresence().first() shouldBe listOf(PresenceAdvert("k-bia", "Bia"))
        ana.discover().first().shouldBeEmpty() // presença não é mesa
        bia.discoverPresence().first().shouldBeEmpty() // não vê a si mesmo
        bia.stopPresence()
        ana.discoverPresence().first().shouldBeEmpty()
    }

    @Test
    fun `§16_2 convite aceito, rotacao vista por todos, confirmacao trava e o jogo comeca 2 s depois`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia"), Participant.Robot("zen")))
        // O convidado aparece na sala desde a montagem; a mesa anuncia a chave dele.
        t.host.roster.value.map { it.name to it.invited } shouldBe listOf("Ana" to false, "Bia" to true)
        t.network.adverts().single().invited shouldBe listOf("k-bia")
        val bia = invitee(t, "k-bia", "Bia")
        bia.status.value shouldBe GuestStatus.Invited
        val invite = bia.invite.value.shouldNotBeNull()
        invite.rules shouldBe t.host.rules
        invite.seating.occupants.take(3) shouldBe listOf(SeatOccupant.Human(0), SeatOccupant.Human(1), SeatOccupant.Robot("zen"))
        // §16.2 o convite (e a sala) nunca leva o token de ninguém.
        t.tokens.forEach { Json.encode(invite) shouldNotContain it }
        bia.acceptInvite()
        runCurrent()
        bia.status.value shouldBe GuestStatus.Lobby
        bia.token shouldBe t.tokens[1]
        bia.rules.value shouldBe t.host.rules // recebidas uma vez, no convite

        // §16.2 o anfitrião também escolhe posição: rotaciona e troca com o robô à esquerda (assento 3).
        t.host.rotate(RotateDirection.LEFT) shouldBe true
        runCurrent()
        t.host.hostSeat shouldBe Seat(3)
        bia.seating.value shouldBe t.host.seating.value
        // Bia confirma; a rotação da Ana passa a pulá-la.
        bia.confirmSeat()
        runCurrent()
        t.host.seating.value!!.rotationTarget(0, RotateDirection.RIGHT) shouldBe Seat(0)
        t.host.confirmPosition() shouldBe true
        runCurrent()
        // §16.2 todos os humanos confirmaram: robôs confirmam, a mesa sai da lista e o início fica para +2 s.
        bia.startsIn.value shouldBe START_DELAY_MILLIS
        bia.seating.value!!.locked shouldBe true
        t.network.adverts().single().listed shouldBe false
        t.host.gameStart.value.shouldBeNull()
        advanceTimeBy(START_DELAY_MILLIS - 1)
        runCurrent()
        t.host.gameStart.value.shouldBeNull()
        advanceTimeBy(2)
        runCurrent()
        t.host.gameStart.value shouldBe mapOf(Seat(1) to bia.playerId)
        bia.status.value shouldBe GuestStatus.Playing
        // §14.5 os robôs do jogo são exatamente os da escolha de posições (nada é sorteado de novo).
        bia.robots.value shouldBe t.host.robots
        t.host.robots shouldBe t.host.seating.value!!.robots
        t.host.robots[2] shouldBe "zen"
    }

    @Test
    fun `§16_2 §16_15 convite sem resposta e aceito sozinho em 10 s`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")))
        val bia = invitee(t, "k-bia", "Bia")
        advanceTimeBy(INVITE_TIMEOUT_MILLIS - 1)
        runCurrent()
        bia.status.value shouldBe GuestStatus.Invited
        advanceTimeBy(2)
        runCurrent()
        bia.status.value shouldBe GuestStatus.Lobby
        t.host.roster.value.single { it.id == bia.playerId }.invited shouldBe false
    }

    @Test
    fun `§16_2 recusa do convite informa o motivo aos dois lados e um robo nao usado assume`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia"), Participant.Robot("zen")))
        val notices = mutableListOf<TableNotice>()
        val job = sessionScope.launch { t.host.lobbyNotices.collect { notices += it } }
        runCurrent()
        val bia = invitee(t, "k-bia", "Bia")
        bia.refuseInvite()
        runCurrent()
        bia.status.value shouldBe GuestStatus.Refused(refusalMessage(RefusalReason.INVITE_REFUSED), RefusalReason.INVITE_REFUSED)
        val notice = notices.single()
        notice.refusal shouldBe RefusalReason.INVITE_REFUSED
        notice.seat shouldBe Seat(1)
        val seating = t.host.seating.value!!
        seating.occupants[1] shouldBe SeatOccupant.Robot(notice.persona!!)
        seating.robots.values.toSet().size shouldBe 3 // perfis diferentes
        t.host.roster.value.map { it.name } shouldBe listOf("Ana")
        job.cancel()
    }

    @Test
    fun `§16_2 §14_6 saldo menor que a aposta recusa o convite sozinho`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")), rules(stake = 100))
        val notices = mutableListOf<TableNotice>()
        val job = sessionScope.launch { t.host.lobbyNotices.collect { notices += it } }
        runCurrent()
        val bia = invitee(t, "k-bia", "Bia", canAfford = { it <= 50 })
        runCurrent()
        (bia.status.value as GuestStatus.Refused).reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
        notices.single().refusal shouldBe RefusalReason.INSUFFICIENT_BALANCE
        t.host.seating.value!!.occupants[1].shouldBeInstanceOf<SeatOccupant.Robot>()
        job.cancel()
    }

    @Test
    fun `§16_2 convidado fora do alcance vira robo depois do prazo do convite`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")))
        val notices = mutableListOf<TableNotice>()
        val job = sessionScope.launch { t.host.lobbyNotices.collect { notices += it } }
        advanceTimeBy(INVITE_TIMEOUT_MILLIS + 1)
        runCurrent()
        notices.single().refusal shouldBe RefusalReason.OUT_OF_RANGE
        t.network.adverts().single().invited.shouldBeEmpty()
        // Quem chega depois com a chave vencida é recusado com o motivo.
        val late = GuestSession(t.network.transport(), "Bia")
        late.join(sessionScope, "m", inviteKey = "k-bia")
        runCurrent()
        (late.status.value as GuestStatus.Refused).reason shouldBe RefusalReason.OUT_OF_RANGE
        job.cancel()
    }

    @Test
    fun `§16_2 pedido de entrada - aceito entra no lugar do primeiro robo, recusado ve o motivo, sem robo mesa cheia`() = runTest {
        val t = table(emptyList(), rules(GameMode.INDIVIDUAL))
        t.network.adverts().single().listed shouldBe true
        val caio = GuestSession(t.network.transport(), "Caio").also { it.join(sessionScope, "m") }
        val duda = GuestSession(t.network.transport(), "Duda").also { it.join(sessionScope, "m") }
        runCurrent()
        caio.status.value shouldBe GuestStatus.WaitingApproval
        val requests = t.host.joinRequests.value
        requests.map { it.name } shouldBe listOf("Caio", "Duda")
        t.host.refuseJoin(requests[1].requestId)
        runCurrent()
        duda.status.value shouldBe GuestStatus.Refused(refusalMessage(RefusalReason.HOST_REFUSED), RefusalReason.HOST_REFUSED)
        t.host.approveJoin(requests[0].requestId).shouldNotBeNull().seat shouldBe Seat(1)
        runCurrent()
        caio.status.value shouldBe GuestStatus.Lobby
        caio.seating.value!!.occupants shouldBe listOf(SeatOccupant.Human(0), SeatOccupant.Human(caio.playerId))
        // §16.2 sem robô a mesa sai da lista; quem pede é recusado com "mesa cheia".
        t.network.adverts().single().listed shouldBe false
        val eva = GuestSession(t.network.transport(), "Eva").also { it.join(sessionScope, "m") }
        runCurrent()
        (eva.status.value as GuestStatus.Refused).reason shouldBe RefusalReason.TABLE_FULL
    }

    @Test
    fun `§16_2 abandono antes do inicio vira robo e, se era o ultimo sem confirmar, as posicoes travam`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")))
        val bia = invitee(t, "k-bia", "Bia")
        bia.acceptInvite()
        runCurrent()
        t.host.confirmPosition() shouldBe true
        runCurrent()
        t.host.seating.value!!.locked shouldBe false
        bia.leave()
        runCurrent()
        val seating = t.host.seating.value!!
        seating.humans shouldBe listOf(0)
        seating.locked shouldBe true
        advanceTimeBy(START_DELAY_MILLIS + 1)
        runCurrent()
        t.host.gameStart.value shouldBe emptyMap()
    }

    @Test
    fun `§16_2 sair do anfitriao desfaz a mesa e avisa os convidados`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")))
        val bia = invitee(t, "k-bia", "Bia")
        bia.acceptInvite()
        runCurrent()
        t.host.disband()
        runCurrent()
        bia.status.value shouldBe GuestStatus.TableEnded(CloseReason.HOST_LEFT)
        t.network.adverts().shouldBeEmpty()
    }

    @Test
    fun `§16_7 Wi-Fi desligado pelo anfitriao - humanos remotos viram robos e ninguem tenta voltar nem assumir`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia"), nearby("k-caio", "Caio")))
        val bia = invitee(t, "k-bia", "Bia")
        val caio = invitee(t, "k-caio", "Caio")
        bia.acceptInvite(); caio.acceptInvite()
        runCurrent()
        t.host.confirmPosition(); bia.confirmSeat(); caio.confirmSeat()
        runCurrent()
        advanceTimeBy(START_DELAY_MILLIS + 1)
        runCurrent()
        t.host.successors.value shouldBe listOf(bia.playerId, caio.playerId)
        // Bia guarda o estado completo (é a 1ª sucessora): sem o aviso, ela assumiria quando a mesa sumisse.
        val match = com.gtranca.engine.startMatch(GameMode.DUPLAS, 1000, kotlin.random.Random(1), RuleSet.DEFAULT)
        t.host.sendSuccessorState(bia.playerId, SuccessorState(1, match, emptyList(), emptyList(), t.host.membersForSuccessor(), null, t.host.nextPlayerId())) shouldBe true
        runCurrent()
        bia.holdsSuccessorState shouldBe true
        // A primeira tentativa do aviso se perde: o reenvio entrega antes de a mesa sumir.
        var lost = 0
        t.network.tap = { d -> if (d.bytes.decodeToString().contains("\"closed\"") && lost++ == 0) emptyList() else listOf(d) }
        val offline = sessionScope.async { t.host.goOffline() }
        advanceTimeBy(ReliableLink.RESEND_AFTER_MILLIS * 3)
        runCurrent()
        val seats = offline.await()
        lost shouldBeGreaterThan 0
        seats shouldBe mapOf(bia.seat!! to bia.playerId, caio.seat!! to caio.playerId)
        runCurrent()
        // §16.10 não se aplica: a mesa foi encerrada de propósito.
        advanceTimeBy(GuestSession.ABSENT_LIMIT_MILLIS * 2)
        runCurrent()
        bia.status.value shouldBe GuestStatus.TableEnded(CloseReason.HOST_OFFLINE)
        caio.status.value shouldBe GuestStatus.TableEnded(CloseReason.HOST_OFFLINE)
        bia.holdsSuccessorState shouldBe false
        bia.takeOver.value.shouldBeNull()
        t.network.adverts().shouldBeEmpty()
    }

    @Test
    fun `§16_7 Wi-Fi ligado num jogo so com robos - vira anfitriao e pode liberar assento de robo`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession.forRobotGame(network.transport(), "m", rules(), "Ana", Seat(2), mapOf(0 to "bolt", 1 to "zen", 3 to "turbo"))
        host.open(sessionScope)
        runCurrent()
        host.committed.value shouldBe true
        network.adverts().single().listed shouldBe false
        host.hostSeat shouldBe Seat(2)
        host.openSeat(Seat(1))
        runCurrent()
        network.adverts().single().let { it.listed shouldBe true; it.inProgress shouldBe true }
        val caio = GuestSession(network.transport(), "Caio").also { it.join(sessionScope, "m") }
        runCurrent()
        val request = host.joinRequests.value.single()
        request.seat shouldBe Seat(1)
        host.approveJoin(request.requestId).shouldNotBeNull()
        runCurrent()
        caio.status.value shouldBe GuestStatus.Playing
        caio.seat shouldBe Seat(1)
        host.robots[0] shouldBe "bolt"
    }

    @Test
    fun `§16_2 §14_6 entrada aprovada sem saldo sai sozinha com o motivo e o robo volta ao lugar`() = runTest {
        val t = table(emptyList(), rules(stake = 100))
        val notices = mutableListOf<TableNotice>()
        val job = sessionScope.launch { t.host.lobbyNotices.collect { notices += it } }
        val caio = GuestSession(t.network.transport(), "Caio", canAfford = { it <= 50 }).also { it.join(sessionScope, "m") }
        runCurrent()
        t.host.approveJoin(t.host.joinRequests.value.single().requestId).shouldNotBeNull()
        runCurrent()
        caio.status.value shouldBe GuestStatus.Refused(refusalMessage(RefusalReason.INSUFFICIENT_BALANCE), RefusalReason.INSUFFICIENT_BALANCE)
        notices.single().refusal shouldBe RefusalReason.INSUFFICIENT_BALANCE
        t.host.seating.value!!.humans shouldBe listOf(0)
        // A mesa não fica presa esperando por ele: o anfitrião confirma e as posições travam.
        t.host.confirmPosition() shouldBe true
        runCurrent()
        t.host.seating.value!!.locked shouldBe true
        job.cancel()
    }

    @Test
    fun `§16_2 §14_6 o convite espera a leitura do saldo antes de responder`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")), rules(stake = 100))
        val balance = kotlinx.coroutines.CompletableDeferred<Int>()
        val bia = invitee(t, "k-bia", "Bia", canAfford = { it <= balance.await() })
        bia.status.value shouldBe GuestStatus.Invited
        balance.complete(10)
        runCurrent()
        (bia.status.value as GuestStatus.Refused).reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
    }

    @Test
    fun `§16_2 nome em branco no pedido de entrada vira Jogador N`() = runTest {
        val t = table(emptyList())
        val g = GuestSession(t.network.transport(), "   ").also { it.join(sessionScope, "m") }
        runCurrent()
        val info = t.host.approveJoin(t.host.joinRequests.value.single().requestId).shouldNotBeNull()
        runCurrent()
        info.name shouldBe "Jogador ${info.id}"
        g.roster.value.single { it.id == g.playerId }.name shouldBe "Jogador ${g.playerId}"
    }

    @Test
    fun `§16_7 §16_9 convidado que desliga o Wi-Fi do jogo desiste - assento VAGO e o jogo segue`() = runTest {
        val t = table(listOf(nearby("k-bia", "Bia")))
        val bia = invitee(t, "k-bia", "Bia")
        bia.acceptInvite()
        runCurrent()
        t.host.confirmPosition(); bia.confirmSeat()
        runCurrent()
        advanceTimeBy(START_DELAY_MILLIS + 1)
        runCurrent()
        t.host.game = object : HostGamePort {
            override fun propose(seat: Seat, requestId: Long, action: com.gtranca.engine.Action): ActionRejected? = null
            override fun resign(seat: Seat): Boolean = true
        }
        // A interface chama resign() ao desligar o Wi-Fi depois da confirmação "Desistir da mesa?".
        bia.resign()
        runCurrent()
        bia.status.value shouldBe GuestStatus.Resigned
        t.host.roster.value.single { it.id == bia.playerId }.resigned shouldBe true
    }

    private companion object {
        const val SEED = 987654321987L
    }

    @Test
    fun `§16_4 §16_2 nenhuma mensagem da sala leva a semente da mesa nem o token de outro jogador`() = runTest {
        val network = MemoryNetwork()
        val sent = mutableListOf<Delivery>()
        network.tap = { sent += it; listOf(it) }
        val t = table(listOf(nearby("k-bia", "Bia"), nearby("k-caio", "Caio")), network = network)
        val bia = invitee(t, "k-bia", "Bia")
        val caio = invitee(t, "k-caio", "Caio")
        bia.acceptInvite(); caio.acceptInvite()
        runCurrent()
        t.host.rotate(RotateDirection.RIGHT)
        caio.rotate(RotateDirection.LEFT)
        runCurrent()
        t.host.confirmPosition(); bia.confirmSeat(); caio.confirmSeat()
        runCurrent()
        advanceTimeBy(START_DELAY_MILLIS + 1)
        runCurrent()
        bia.status.value shouldBe GuestStatus.Playing
        val tokenOf = mapOf(bia.playerId to bia.token!!, caio.playerId to caio.token!!)
        sent.size shouldBeGreaterThan 10
        sent.forEach { d ->
            val text = d.bytes.decodeToString()
            text shouldNotContain SEED.toString()
            text shouldNotContain t.tokens[0] // o do anfitrião nunca sai
            // Cada token só no Welcome do próprio dono.
            tokenOf.forEach { (id, token) ->
                if (text.contains(token)) text shouldContain "\"you\":$id"
            }
        }
    }

    @Test
    fun `§16_2 sair ou cair nos 2 s antes do inicio - robo assume e o convidado nao entra no jogo`() = runTest {
        val network = MemoryNetwork()
        val t = table(listOf(nearby("k-bia", "Bia"), nearby("k-caio", "Caio")), network = network)
        val bia = invitee(t, "k-bia", "Bia")
        val caioTransport = network.transport()
        val caio = GuestSession(caioTransport, "Caio").also { it.join(sessionScope, "m", inviteKey = "k-caio") }
        runCurrent()
        bia.acceptInvite(); caio.acceptInvite()
        runCurrent()
        t.host.confirmPosition(); bia.confirmSeat(); caio.confirmSeat()
        runCurrent()
        t.host.seating.value!!.locked shouldBe true
        val biaSeat = bia.seat!!
        val caioSeat = caio.seat!!
        bia.leave()
        network.drop(caioTransport.id)
        runCurrent()
        advanceTimeBy(START_DELAY_MILLIS + 1)
        runCurrent()
        t.host.gameStart.value shouldBe emptyMap()
        t.host.robots.size shouldBe 3
        (biaSeat.index in t.host.robots) shouldBe true
        (caioSeat.index in t.host.robots) shouldBe true
        (bia.status.value == GuestStatus.Playing) shouldBe false
        (caio.status.value == GuestStatus.Playing) shouldBe false
        t.host.roster.value.map { it.id } shouldBe listOf(0)
    }

    private object Json {
        fun encode(payload: Payload): String = ProtocolCodec().encode(1, payload).decodeToString()
    }
}
