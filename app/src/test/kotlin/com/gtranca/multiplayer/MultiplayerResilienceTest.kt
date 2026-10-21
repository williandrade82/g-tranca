package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.Stage
import com.gtranca.net.Decoded
import com.gtranca.net.GameFinished
import com.gtranca.net.GuestSession
import com.gtranca.net.GuestStatus
import com.gtranca.net.HostSession
import com.gtranca.net.NoticeKind
import com.gtranca.net.PauseReason
import com.gtranca.net.Payout
import com.gtranca.net.ProtocolCodec
import com.gtranca.net.RoundOver
import com.gtranca.net.SuccessorAck
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Revisão de 21/10/2026 (plano N15): sucessão com evidência e anfitrião provisório (§16.10), reconexão sem limite
 * (§16.5), resultado a quem volta depois do fim (§16.11), entrada no meio do jogo (§16.7) e desistência (§16.9).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerResilienceTest {

    private fun TestScope.local(mode: GameMode, seed: Long): GameController {
        val config = GameConfig(mode, Difficulty.FACIL, targetScore = 1000)
        return GameController(config, seed, mode.seats.map { BotSeatPlayer(testBot(seed, it)) }, computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0)
    }

    private fun typeOf(bytes: ByteArray): Any? = (ProtocolCodec().decode(bytes) as? Decoded.Ok)?.envelope?.p

    /** O anfitrião [host] põe um robô em toda pausa do assento [seat] (humano que não volta). */
    private fun TestScope.robotOnPause(host: MultiplayerHost, seats: List<Seat>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.decision.collect { d -> if (d != null && d.seat in seats) host.replaceWithRobot(d.seat) }
        }
    }

    private suspend fun TestScope.takeOverOf(t: TestTable, seat: Seat, guest: GuestSession, seed: Long): MultiplayerHost {
        val state = guest.takeOver.first { it != null }!!
        guest.release()
        return MultiplayerHost.takeOver(
            t.endpoints.getValue(seat), "mesa-1", guest.rules.value!!, guest.playerId, state, t.scope,
            botFactory = { s, _ -> testBot(seed, s) }, computeDispatcher = StandardTestDispatcher(testScheduler),
            provisionalRank = guest.takeOverRank,
        )
    }

    /**
     * §16.10 partição: o 1º sucessor perde só a **própria** conexão (fora do alcance, sem ver a mesa) e assume como
     * provisório; ao voltar ao alcance ele vê o anfitrião original, volta a convidado e reingressa pelo token. Uma só mesa,
     * nada pago pelo provisório, e o jogo termina igual ao local.
     */
    @Test
    fun `§16_10 particao - o sucessor provisorio ve o original e volta a convidado sem duplicar a mesa`() = runTest {
        val seed = 101L
        val reference = local(GameMode.DUPLAS, seed).also { it.run() }
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.to == t.endpoints.getValue(Seat(1)).id && ++count == 120) {
                network.block(d.to)
                network.drop(d.to)
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network, stake = 100)
        table = t
        val successor = t.guests.getValue(Seat(1))
        t.hostSession.successors.value.first() shouldBe successor.playerId
        val drivers = mutableMapOf<Seat, com.gtranca.ai.BotPlayer>()
        driveGuests(t, seed, drivers = drivers)
        driveHost(t, seed)
        val game = launch { t.host.run() }
        val provisional = takeOverOf(t, Seat(1), successor, seed)
        successor.takeOverRank shouldBe 1
        val provisionalGame = launch { provisional.run() }
        runCurrent()
        // Provisório: anuncia, mas não retoma o jogo.
        provisional.controller.state.value.stage shouldNotBe Stage.GAME_OVER
        t.network.unblock(t.endpoints.getValue(Seat(1)).id)
        runCurrent()
        provisional.superseded.value shouldBe true
        provisionalGame.isCompleted shouldBe true
        // Volta a convidado pelo token no original.
        val back = GuestSession(t.endpoints.getValue(Seat(1)), "Convidado 1")
        driveGuests(t, seed, guests = mapOf(Seat(1) to back), drivers = drivers)
        back.join(t.scope, "mesa-1", token = successor.token, seat = Seat(1))
        game.join()
        advanceUntilIdle()
        back.status.value shouldBe GuestStatus.Playing
        back.playerId shouldBe successor.playerId
        t.host.controller.currentMatch.history shouldBe reference.currentMatch.history
        back.view.value!!.stage shouldBe com.gtranca.net.TableStage.GAME_OVER
        provisional.payouts.value shouldBe null
        t.host.payouts.value shouldNotBe null
        // Nenhum anúncio sobra (o original terminou com todos conectados; o provisório fechou).
        t.network.adverts().shouldBeEmpty()
    }

    /**
     * §16.10 o anfitrião e o 1º sucessor caem juntos: o 2º assume (provisório, depois do dobro do tempo), é confirmado
     * quando o 3º convidado reingressa nele pelo token, e o jogo vai até o fim.
     */
    @Test
    fun `§16_10 o 2o sucessor assume quando o 1o tambem caiu`() = runTest {
        val seed = 102L
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.from == t.hostTransport.id && ++count == 400) {
                network.kill(t.endpoints.getValue(Seat(1)).id)
                network.kill(d.from)
            }
            listOf(d)
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        table = t
        driveGuests(t, seed)
        driveHost(t, seed)
        val oldGame = launch { t.host.run() }
        val second = t.guests.getValue(Seat(2))
        val newHost = takeOverOf(t, Seat(2), second, seed)
        oldGame.cancel()
        second.takeOverRank shouldBe 2
        newHost.hostSeat shouldBe Seat(2)
        driveHostOf(newHost, seed)
        robotOnPause(newHost, listOf(Seat(0), Seat(1)))
        newHost.run()
        advanceUntilIdle()
        newHost.controller.state.value.stage shouldBe Stage.GAME_OVER
        val third = t.guests.getValue(Seat(3))
        third.status.value shouldBe GuestStatus.Playing
        third.view.value!!.stage shouldBe com.gtranca.net.TableStage.GAME_OVER
        third.roster.value.single { it.seat == Seat(2) }.isHost shouldBe true
    }

    /**
     * §16.11, §16.9 o anfitrião desiste sem convidado conectado: desistência do lado dele (§13.1), aposta perdida. O
     * convidado do lado vencedor, fora no fim, volta pelo token só para receber o fim do jogo e o prêmio.
     */
    @Test
    fun `§16_11 quem estava fora no fim recebe o resultado e o premio ao voltar`() = runTest {
        val seed = 103L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1)), stake = 100)
        val guest = t.guests.getValue(Seat(1))
        val token = guest.token!!
        val id = guest.playerId
        val game = launch { t.host.run() }
        runCurrent()
        val endpoint = t.endpoints.getValue(Seat(1)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        guest.release() // aparelho fechado
        runCurrent()
        t.host.handOver(resign = true) shouldBe null
        game.join()
        advanceUntilIdle()
        t.host.controller.state.value.resigned shouldBe true
        t.host.ledger.value.seats[0].forfeited shouldBe true
        // §16.13 o lado vencedor (assentos 1 e 3) divide 4A: o humano recebe 2A; a parte do robô se perde.
        t.host.payouts.value shouldBe mapOf(id to 200)
        // A mesa segue anunciada, fora da lista, só para a entrega.
        t.network.adverts().single().listed shouldBe false

        t.network.unblock(endpoint)
        val back = GuestSession(t.network.transport(), "Convidado 1")
        val finished = mutableListOf<GameFinished>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { back.gameEvents.collect { if (it is GameFinished) finished += it } }
        back.join(t.scope, "mesa-1", token = token, seat = Seat(1))
        advanceUntilIdle()
        back.status.value shouldBe GuestStatus.Playing
        back.payout.value shouldBe Payout(stake = 100, received = 200)
        finished shouldBe listOf(GameFinished(GameMode.DUPLAS.sideOf(Seat(1)), resigned = true))
        back.view.value!!.stage shouldBe com.gtranca.net.TableStage.GAME_OVER
        // Entregue: a mesa deixa de ser anunciada.
        t.network.adverts().shouldBeEmpty()
    }

    /** §16.10 passagem do cargo: sem confirmação do escolhido no tempo limite, vai ao próximo; o escolhido descarta o estado. */
    @Test
    fun `§16_10 passagem do cargo sem confirmacao do escolhido vai ao proximo disponivel`() = runTest {
        val seed = 104L
        val network = com.gtranca.net.MemoryNetwork()
        var mute = ""
        network.tap = { d -> if (d.from.value == mute && typeOf(d.bytes) is SuccessorAck) emptyList() else listOf(d) }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        driveGuests(t, seed)
        val oldGame = launch { t.host.run() }
        advanceUntilIdle()
        val chosen = t.guests.getValue(Seat(3))
        val first = t.guests.getValue(Seat(1))
        mute = t.endpoints.getValue(Seat(3)).id.value
        t.host.handOver(newHostId = chosen.playerId, resign = true) shouldBe first.playerId
        runCurrent()
        oldGame.isCancelled shouldBe true
        chosen.holdsSuccessorState shouldBe false
        chosen.takeOver.value shouldBe null
        first.takeOver.value shouldNotBe null
        first.takeOverRank shouldBe 0
        // §16.10 o 2º sucessor (assento 2) descarta o estado na passagem.
        t.guests.getValue(Seat(2)).holdsSuccessorState shouldBe false
    }

    /** §16.9 Sair durante o jogo vale como desistência: assento VAGO, aposta retida e a pausa na vez dele. */
    @Test
    fun `§16_9 sair durante o jogo e desistir`() = runTest {
        val seed = 105L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)), stake = 100)
        val leaver = t.guests.getValue(Seat(1))
        driveGuests(t, seed, guests = mapOf(Seat(3) to t.guests.getValue(Seat(3))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        runCurrent()
        leaver.leave()
        advanceUntilIdle()
        leaver.status.value shouldBe GuestStatus.Resigned
        t.hostSession.roster.value.single { it.id == leaver.playerId }.resigned shouldBe true
        t.host.ledger.value.seats[1].forfeited shouldBe true
        t.host.decision.value shouldBe HostDecision(Seat(1), leaver.playerId, PauseReason.VACANT)
        // §16.9 "Voltar à mesa" pelo token: recupera a aposta e joga.
        t.host.waitForPlayer()
        driveGuests(t, seed, guests = mapOf(Seat(1) to leaver))
        leaver.returnToTable() shouldBe true
        game.join()
        advanceUntilIdle()
        leaver.status.value shouldBe GuestStatus.Playing
        t.host.ledger.value.seats[1].forfeited shouldBe false
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /**
     * §16.5 reconexão sem limite enquanto a mesa existir: o convidado vê a mesa mas não consegue conectar por 5 minutos;
     * continua tentando (o anfitrião decide pela pausa) e volta sozinho quando a conexão funciona.
     */
    @Test
    fun `§16_5 o convidado tenta voltar sem limite enquanto a mesa existe`() = runTest {
        val seed = 106L
        val reference = local(GameMode.DUPLAS, seed).also { it.run() }
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.to == t.endpoints.getValue(Seat(3)).id && ++count == 150) {
                network.jam(d.to)
                network.drop(d.to)
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)), network = network)
        table = t
        val drivers = mutableMapOf<Seat, com.gtranca.ai.BotPlayer>()
        driveGuests(t, seed, drivers = drivers)
        driveHost(t, seed)
        val game = launch { t.host.run() }
        val away = t.guests.getValue(Seat(3))
        val endpoint = t.endpoints.getValue(Seat(3)).id
        t.host.decision.first { it?.seat == Seat(3) }
        advanceTimeBy(5 * 60_000)
        away.status.value shouldBe GuestStatus.Reconnecting
        t.host.decision.value?.seat shouldBe Seat(3)
        t.network.unjam(endpoint)
        game.join()
        advanceUntilIdle()
        away.status.value shouldBe GuestStatus.Playing
        t.host.controller.currentMatch.history shouldBe reference.currentMatch.history
    }

    /**
     * §16.7, §16.10 o anfitrião cai durante o recrutamento: o novo herda a vaga aberta e a escolha da pausa, e o candidato
     * refaz o pedido sozinho; aprovado, ocupa o assento da vez herdando a aposta.
     */
    @Test
    fun `§16_7 queda do anfitriao durante o recrutamento - vaga e pedido passam ao novo anfitriao`() = runTest {
        val seed = 107L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), stake = 100)
        driveGuests(t, seed, guests = t.guests.filterKeys { it != Seat(3) })
        driveHost(t, seed)
        val oldGame = launch { t.host.run() }
        runCurrent()
        val away = t.guests.getValue(Seat(3))
        val awayEndpoint = t.endpoints.getValue(Seat(3)).id
        t.network.block(awayEndpoint)
        t.network.drop(awayEndpoint)
        away.release()
        t.host.decision.first { it?.seat == Seat(3) }
        t.host.openSeatForRecruit()
        val candidate = GuestSession(t.network.transport(), "Candidata")
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        candidate.status.value shouldBe GuestStatus.WaitingApproval
        // A vaga e a escolha chegaram ao 1º sucessor antes da queda.
        advanceTimeBy(100)
        t.network.kill(t.hostTransport.id)
        val successor = t.guests.getValue(Seat(1))
        val newHost = takeOverOf(t, Seat(1), successor, seed)
        oldGame.cancel()
        driveHostOf(newHost, seed)
        robotOnPause(newHost, listOf(Seat(0)))
        val game = launch { newHost.run() }
        newHost.decision.first { it?.seat == Seat(3) }!!.choice shouldBe NoticeKind.SEAT_OPENED
        val pending = newHost.sessionJoinRequests().first { it.isNotEmpty() }.single()
        pending.name shouldBe "Candidata"
        pending.seat shouldBe Seat(3)
        driveGuests(t, seed, guests = mapOf(Seat(3) to candidate))
        newHost.approveJoin(pending.requestId) shouldBe true
        game.join()
        advanceUntilIdle()
        candidate.seat shouldBe Seat(3)
        candidate.stakeDebit.value shouldBe 0
        newHost.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.5 quem perdeu o fim da partida (a conexão caiu nele) o recebe de novo no reingresso. */
    @Test
    fun `§16_5 o reingresso reenvia o fim de partida nao recebido`() = runTest {
        val seed = 108L
        val reference = local(GameMode.DUPLAS, seed).also { it.run() }
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var cut = false
        val gate = kotlinx.coroutines.flow.MutableStateFlow(true)
        network.tap = { d ->
            val t = table
            if (t != null && !cut && d.to == t.endpoints.getValue(Seat(2)).id && (typeOf(d.bytes) as? RoundOver)?.roundNumber == 1) {
                cut = true
                // O anfitrião fica nas telas de resultado (não distribui a próxima) enquanto o convidado volta.
                gate.value = false
                network.drop(d.to)
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        table = t
        val guest = t.guests.getValue(Seat(2))
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { guest.roundOver.collect { it?.let { o -> seen += o.roundNumber } } }
        driveGuests(t, seed)
        driveHostGated(t, seed, gate)
        val game = launch { t.host.run() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            guest.roundOver.first { it?.roundNumber == 1 }
            gate.value = true
        }
        game.join()
        advanceUntilIdle()
        cut shouldBe true
        seen shouldContain 1
        seen shouldBe seen.distinct()
        t.host.controller.currentMatch.history shouldBe reference.currentMatch.history
    }

    /**
     * §16.7 pedido de entrada aceito enquanto o anfitrião decide a pausa (sem vaga aberta): aprovar = abrir a vaga e
     * aprovar; quem entra no lugar de quem saiu herda a aposta.
     */
    @Test
    fun `§16_7 pedido durante a decisao da pausa`() = runTest {
        val seed = 109L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)), stake = 100)
        driveGuests(t, seed, guests = mapOf(Seat(1) to t.guests.getValue(Seat(1))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        runCurrent()
        val endpoint = t.endpoints.getValue(Seat(3)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        t.guests.getValue(Seat(3)).release()
        t.host.decision.first { it?.seat == Seat(3) }!!.choice shouldBe null
        // §16.7 fora da lista (sem vaga aberta), mas quem sabe da mesa pode pedir o assento da vez.
        t.network.adverts().single().listed shouldBe false
        val candidate = GuestSession(t.network.transport(), "Nova")
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        candidate.status.value shouldBe GuestStatus.WaitingApproval
        val request = t.hostSession.joinRequests.value.single()
        request.seat shouldBe Seat(3)
        driveGuests(t, seed, guests = mapOf(Seat(3) to candidate))
        t.host.approveJoin(request.requestId) shouldBe true
        game.join()
        advanceUntilIdle()
        candidate.seat shouldBe Seat(3)
        candidate.stakeDebit.value shouldBe 0
        t.host.ledger.value.seats[3].holder shouldBe candidate.playerId
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /**
     * §16.7, §16.13 entrar no assento de um robô que substituiu um humano herda a aposta retida (sem débito); a mesa só
     * aparece na lista com a vaga aberta.
     */
    @Test
    fun `§16_7 robo substituto - quem entra herda a aposta retida`() = runTest {
        val seed = 110L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1)), stake = 100)
        val gate = kotlinx.coroutines.flow.MutableStateFlow(true)
        driveHostGated(t, seed, gate)
        val game = launch { t.host.run() }
        runCurrent()
        val endpoint = t.endpoints.getValue(Seat(1)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        t.guests.getValue(Seat(1)).release()
        t.host.decision.first { it?.seat == Seat(1) }
        // O anfitrião segura o jogo na vez dele enquanto recruta.
        gate.value = false
        t.host.replaceWithRobot() shouldBe true
        t.network.adverts().single().listed shouldBe false
        t.host.openRobotSeat(Seat(1))
        t.network.adverts().single().let { it.listed to it.inProgress } shouldBe (true to true)
        val candidate = GuestSession(t.network.transport(), "Nova")
        driveGuests(t, seed, guests = mapOf(Seat(1) to candidate))
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        t.host.approveJoin(t.hostSession.joinRequests.value.single().requestId) shouldBe true
        runCurrent()
        gate.value = true
        candidate.stakeDebit.value shouldBe 0
        t.host.ledger.value.seats[1].let { it.holder to it.forfeited } shouldBe (candidate.playerId to false)
        game.join()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.7, §16.3 na mesa reduzida (anfitrião × robô) o anfitrião libera o robô e a mesa vira 1×1 entre humanos. */
    @Test
    fun `§16_7 mesa reduzida - liberar o robo vira 1x1 humano`() = runTest {
        val seed = 111L
        val t = openTable(GameMode.INDIVIDUAL, seed, emptyList(), stake = 100)
        val gate = kotlinx.coroutines.flow.MutableStateFlow(false)
        driveHostGated(t, seed, gate)
        val game = launch { t.host.run() }
        runCurrent()
        t.host.openRobotSeat(Seat(1))
        val candidate = GuestSession(t.network.transport(), "Rival")
        driveGuests(t, seed, guests = mapOf(Seat(1) to candidate))
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        t.host.approveJoin(t.hostSession.joinRequests.value.single().requestId) shouldBe true
        gate.value = true
        game.join()
        advanceUntilIdle()
        candidate.stakeDebit.value shouldBe 100
        candidate.seat shouldBe Seat(1)
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        candidate.payout.value!!.stake shouldBe 100
    }

    /**
     * §16.5 eventos simultâneos: o dono volta pelo token antes da aprovação do candidato; a vaga fecha e o pedido é
     * recusado com "A vaga foi preenchida.". §16.2 renomear durante o jogo chega a todos.
     */
    @Test
    fun `§16_5 o dono volta antes do candidato e renomear no jogo`() = runTest {
        val seed = 112L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)))
        driveGuests(t, seed, guests = mapOf(Seat(1) to t.guests.getValue(Seat(1))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        runCurrent()
        t.guests.getValue(Seat(1)).rename("Ana Paula")
        val away = t.guests.getValue(Seat(3))
        val endpoint = t.endpoints.getValue(Seat(3)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        away.release()
        t.host.decision.first { it?.seat == Seat(3) }
        t.host.openSeatForRecruit()
        val candidate = GuestSession(t.network.transport(), "Nova")
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        val request = t.hostSession.joinRequests.value.single()
        val owner = GuestSession(t.network.transport(), "Convidado 3")
        driveGuests(t, seed, guests = mapOf(Seat(3) to owner))
        owner.join(t.scope, "mesa-1", token = away.token, seat = Seat(3))
        runCurrent()
        candidate.status.value shouldBe GuestStatus.Refused(HostSession.SEAT_TAKEN_MESSAGE)
        t.host.approveJoin(request.requestId) shouldBe false
        game.join()
        advanceUntilIdle()
        owner.status.value shouldBe GuestStatus.Playing
        owner.playerId shouldBe away.playerId
        owner.roster.value.single { it.seat == Seat(1) }.name shouldBe "Ana Paula"
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.5 quem cai durante a pausa de outro assento recebe, ao voltar, a pausa atual (uma decisão por vez). */
    @Test
    fun `§16_5 queda durante a pausa de outro assento recebe a pausa atual`() = runTest {
        val seed = 113L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)))
        driveGuests(t, seed, guests = mapOf(Seat(1) to t.guests.getValue(Seat(1))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        runCurrent()
        val endpoint = t.endpoints.getValue(Seat(3)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        t.guests.getValue(Seat(3)).release()
        t.host.decision.first { it?.seat == Seat(3) }
        t.host.waitForPlayer()
        runCurrent()
        val guest = t.guests.getValue(Seat(1))
        t.network.drop(t.endpoints.getValue(Seat(1)).id)
        advanceTimeBy(5_000)
        guest.status.value shouldBe GuestStatus.Playing
        guest.paused.value shouldBe com.gtranca.net.Paused(Seat(3), PauseReason.DISCONNECTED, NoticeKind.WAITING)
        t.host.decision.value?.seat shouldBe Seat(3)
        t.host.replaceWithRobot() shouldBe true
        game.join()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.11 quem recebeu o resultado e o prêmio e depois cai não reingressa nem recebe o fim do jogo de novo. */
    @Test
    fun `§16_11 o resultado nao chega duas vezes`() = runTest {
        val seed = 115L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1)), stake = 100)
        val guest = t.guests.getValue(Seat(1))
        var finishedCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { guest.gameEvents.collect { if (it is GameFinished) finishedCount++ } }
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        guest.payout.value shouldNotBe null
        t.network.drop(t.endpoints.getValue(Seat(1)).id)
        advanceUntilIdle()
        guest.status.value shouldBe GuestStatus.Closed
        finishedCount shouldBe 1
    }

    /**
     * §16.10 duas quedas seguidas: o anfitrião cai, o 1º sucessor assume e os demais reingressam nele; depois o novo
     * anfitrião também cai e o novo 1º sucessor assume. O jogo vai até o fim.
     */
    @Test
    fun `§16_10 segunda queda de anfitriao - o novo 1o sucessor assume`() = runTest {
        val seed = 116L
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        var secondHost: com.gtranca.net.EndpointId? = null
        var count2 = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.from == t.hostTransport.id && ++count == 300) network.kill(d.from)
            if (d.from == secondHost && ++count2 == 300) network.kill(d.from)
            listOf(d)
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        table = t
        driveGuests(t, seed)
        driveHost(t, seed)
        val oldGame = launch { t.host.run() }
        val first = takeOverOf(t, Seat(1), t.guests.getValue(Seat(1)), seed)
        oldGame.cancel()
        secondHost = t.endpoints.getValue(Seat(1)).id
        driveHostOf(first, seed)
        robotOnPause(first, listOf(Seat(0)))
        val firstGame = launch { first.run() }
        val second = t.guests.getValue(Seat(2))
        val third = takeOverOf(t, Seat(2), second, seed)
        firstGame.cancel()
        second.takeOverRank shouldBe 1
        driveHostOf(third, seed)
        robotOnPause(third, listOf(Seat(0), Seat(1)))
        third.run()
        advanceUntilIdle()
        third.controller.state.value.stage shouldBe Stage.GAME_OVER
        t.guests.getValue(Seat(3)).view.value!!.stage shouldBe com.gtranca.net.TableStage.GAME_OVER
        t.guests.getValue(Seat(3)).roster.value.single { it.isHost }.seat shouldBe Seat(2)
    }

    /**
     * §16.10 o provisório sem outro humano que confirme (1×1) é confirmado depois do tempo de §16.15; confirmado, ele
     * prevalece: quando o original volta ao alcance, é o original que deixa o cargo, sem pagar nada.
     */
    @Test
    fun `§16_10 confirmado prevalece sobre o original`() = runTest {
        val seed = 117L
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.to == t.endpoints.getValue(Seat(1)).id && ++count == 60) {
                network.block(d.to)
                network.drop(d.to)
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = openTable(GameMode.INDIVIDUAL, seed, listOf(Seat(1)), network = network, stake = 100)
        table = t
        driveGuests(t, seed)
        driveHost(t, seed)
        val original = launch { t.host.run() }
        val guest = t.guests.getValue(Seat(1))
        val provisional = takeOverOf(t, Seat(1), guest, seed)
        guest.takeOverRank shouldBe 1
        val provisionalGame = launch { provisional.run() }
        advanceTimeBy(HostSession.PROVISIONAL_SOLO_MILLIS + 1_000)
        t.network.adverts().none { it.hostId == guest.playerId && it.provisionalRank > 0 } shouldBe true
        t.network.unblock(t.endpoints.getValue(Seat(1)).id)
        runCurrent()
        t.host.superseded.value shouldBe true
        original.isCompleted shouldBe true
        t.host.payouts.value shouldBe null
        provisional.superseded.value shouldBe false
        provisionalGame.cancel()
    }

    /** §16.7 aprovação atômica: o candidato saiu antes da aprovação e o assento continua com o robô (o jogo segue). */
    @Test
    fun `§16_7 aprovacao de quem ja saiu nao muda o assento`() = runTest {
        val seed = 114L
        val t = openTable(GameMode.DUPLAS, seed, emptyList())
        val gate = kotlinx.coroutines.flow.MutableStateFlow(false)
        driveHostGated(t, seed, gate)
        val game = launch { t.host.run() }
        runCurrent()
        t.host.openRobotSeat(Seat(2))
        val candidateTransport = t.network.transport()
        val candidate = GuestSession(candidateTransport, "Nova")
        candidate.join(t.scope, "mesa-1")
        runCurrent()
        val request = t.hostSession.joinRequests.value.single()
        // A sessão recusa (o gancho do jogo devolve falso): nada muda.
        t.hostSession.approveJoin(request.requestId) { false } shouldBe null
        t.hostSession.joinRequests.value.single() shouldBe request
        t.network.drop(candidateTransport.id)
        candidate.release()
        runCurrent()
        t.host.approveJoin(request.requestId) shouldBe false
        gate.value = true
        game.join()
        advanceUntilIdle()
        t.hostSession.playerAt(Seat(2)) shouldBe null
        t.host.decision.value shouldBe null
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }
}
