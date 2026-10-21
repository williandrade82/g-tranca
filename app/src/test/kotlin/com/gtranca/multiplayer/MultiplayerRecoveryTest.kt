package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.Stage
import com.gtranca.net.Decoded
import com.gtranca.net.GuestSession
import com.gtranca.net.GuestStatus
import com.gtranca.net.NoticeKind
import com.gtranca.net.PauseReason
import com.gtranca.net.ProtocolCodec
import com.gtranca.net.SuccessorAck
import com.gtranca.net.TableNotice
import com.gtranca.net.TableStage
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/** §16.5, §16.7, §16.8, §16.10 (plano, etapa 4): queda, reingresso, robô, recrutamento e sucessão pela rede em memória. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerRecoveryTest {

    private fun TestScope.local(mode: GameMode, seed: Long): GameController {
        val config = GameConfig(mode, Difficulty.FACIL, targetScore = 1000)
        return GameController(config, seed, mode.seats.map { BotSeatPlayer(testBot(seed, it)) }, computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0)
    }

    private fun TestScope.collectNotices(guests: Map<Seat, GuestSession>): Map<Seat, MutableList<TableNotice>> {
        val notices = ConcurrentHashMap<Seat, MutableList<TableNotice>>()
        guests.forEach { (seat, guest) ->
            notices[seat] = mutableListOf()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { guest.notices.collect { notices.getValue(seat) += it } }
        }
        return notices
    }

    private fun typeOf(bytes: ByteArray): Any? = (ProtocolCodec().decode(bytes) as? Decoded.Ok)?.envelope?.p

    /** §16.5 a conexão do convidado cai no meio do jogo e ele reingressa sozinho pelo token: o jogo é o mesmo da partida local. */
    @Test
    fun `§16_5 queda curta do convidado e reingresso automatico nao mudam o jogo`() = runTest {
        val seed = 91L
        val reference = local(GameMode.DUPLAS, seed).also { it.run() }
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        var drops = 0
        val scanner = LeakScanner(seed)
        network.tap = scanner.tap { d ->
            val t = table
            // A cada 150 mensagens ao convidado do assento 2, a conexão dele cai (a mensagem em trânsito se perde).
            if (t != null && d.to == t.endpoints.getValue(Seat(2)).id && ++count % 150 == 0) {
                drops++
                network.drop(d.to)
                emptyList()
            } else {
                listOf(d)
            }
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        table = t
        scanner.registerTable(t)
        scanner.watch(this, t.host)
        val notices = collectNotices(t.guests)
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        (drops > 0) shouldBe true
        // §16.4 a vista completa do reingresso e os avisos também passaram pela varredura.
        scanner.failures.shouldBeEmpty()
        t.host.controller.currentMatch.history shouldBe reference.currentMatch.history
        t.host.controller.currentMatch.winner shouldBe reference.currentMatch.winner
        t.guests.getValue(Seat(2)).view.value!!.stage shouldBe TableStage.GAME_OVER
        t.guests.getValue(Seat(2)).view.value!!.history shouldBe reference.currentMatch.history
        notices.getValue(Seat(1)).map { it.kind } shouldContain NoticeKind.DISCONNECTED
        notices.getValue(Seat(1)).map { it.kind } shouldContain NoticeKind.RECONNECTED
    }

    /**
     * §16.8 queda longa: o jogo segue até a vez de quem caiu e pausa; o anfitrião escolhe aguardar (todos são avisados);
     * o jogador volta com o token ("Continuar", §16.12), a pausa fecha e o jogo segue igual ao local. Token desconhecido ou
     * de outro assento é recusado (§16.5).
     */
    @Test
    fun `§16_8 queda na vez pausa, aguardar e reingresso com token retomam e token alheio e recusado`() = runTest {
        val seed = 92L
        val reference = local(GameMode.DUPLAS, seed).also { it.run() }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)))
        val notices = collectNotices(t.guests)
        val away = t.guests.getValue(Seat(3))
        val token = away.token!!
        driveGuests(t, seed, guests = mapOf(Seat(1) to t.guests.getValue(Seat(1))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        // Fora do alcance: cai e não consegue voltar sozinho.
        val awayEndpoint = t.endpoints.getValue(Seat(3)).id
        t.network.block(awayEndpoint)
        t.network.drop(awayEndpoint)
        advanceUntilIdle()
        away.status.value shouldBe GuestStatus.Closed
        t.host.decision.value shouldBe HostDecision(Seat(3), t.hostSession.playerAt(Seat(3))!!.id, PauseReason.DISCONNECTED)
        t.guests.getValue(Seat(1)).paused.value?.seat shouldBe Seat(3)
        t.host.waitForPlayer()
        advanceUntilIdle()
        notices.getValue(Seat(1)).map { it.kind } shouldContain NoticeKind.WAITING
        t.guests.getValue(Seat(1)).paused.value?.hostChoice shouldBe NoticeKind.WAITING

        // §16.5 token desconhecido e token de outro assento são recusados.
        val stranger = GuestSession(t.network.transport(), "Intruso").also { it.join(t.scope, "mesa-1", token = "nao-existe") }
        val wrongSeat = GuestSession(t.network.transport(), "Outro").also { it.join(t.scope, "mesa-1", token = token, seat = Seat(2)) }
        advanceUntilIdle()
        stranger.status.value.shouldBeInstanceOf<GuestStatus.Refused>()
        wrongSeat.status.value.shouldBeInstanceOf<GuestStatus.Refused>()
        t.host.decision.value?.seat shouldBe Seat(3)

        // Volta com o token certo: a pausa fecha e ele joga do ponto em que parou.
        val back = GuestSession(t.network.transport(), "Convidado 3")
        driveGuests(t, seed, guests = mapOf(Seat(3) to back))
        back.join(t.scope, "mesa-1", token = token, seat = Seat(3))
        game.join()
        advanceUntilIdle()
        back.status.value shouldBe GuestStatus.Playing
        back.playerId shouldBe away.playerId
        t.host.controller.currentMatch.history shouldBe reference.currentMatch.history
        back.view.value!!.stage shouldBe TableStage.GAME_OVER
        notices.getValue(Seat(1)).map { it.kind } shouldContain NoticeKind.RECONNECTED
    }

    /**
     * §16.8 opção 2 na queda: o robô assume na hora e quem caiu perde a aposta (retida no assento) e o direito de voltar.
     * §16.7 um assento de robô liberado pelo anfitrião: quem entra (aprovado) debita a aposta da mesa.
     */
    @Test
    fun `§16_8 robo na queda e §16_7 entrada no lugar de um robo com debito`() = runTest {
        val seed = 93L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1)), stake = 250)
        val game = launch { t.host.run() }
        runCurrent()
        // §16.7 o anfitrião libera o assento 2 (robô de origem) e aprova a entrada: quem entra debita a aposta.
        t.host.openRobotSeat(Seat(2))
        val newcomer = GuestSession(t.network.transport(), "Nova")
        driveGuests(t, seed, guests = mapOf(Seat(2) to newcomer))
        newcomer.join(t.scope, "mesa-1")
        runCurrent()
        newcomer.status.value shouldBe GuestStatus.WaitingApproval
        t.host.approveJoin(t.hostSession.joinRequests.value.single().requestId) shouldBe true
        runCurrent()
        newcomer.seat shouldBe Seat(2)
        newcomer.stakeDebit.value shouldBe 250
        t.host.ledger.value.seats[2].holder shouldBe newcomer.playerId

        // §16.8 o convidado do assento 1 cai e não volta: na vez dele o anfitrião põe um robô.
        val guest = t.guests.getValue(Seat(1))
        val token = guest.token!!
        t.network.block(t.endpoints.getValue(Seat(1)).id)
        t.network.drop(t.endpoints.getValue(Seat(1)).id)
        driveHost(t, seed)
        advanceUntilIdle()
        t.host.decision.value?.seat shouldBe Seat(1)
        t.host.replaceWithRobot() shouldBe true
        // Substituído: perde a aposta (retida no assento) e o token não vale mais; sem ninguém para voltar, a mesa
        // nem é mais anunciada.
        t.host.ledger.value.seats[1].forfeited shouldBe true
        t.hostSession.playerAt(Seat(1)) shouldBe null
        // §16.8 o robô substituto é sempre um personagem que não estava na mesa.
        val personas = t.host.robotPersonas.value
        personas[1] shouldNotBe null
        personas.filterKeys { it != 1 }.values.contains(personas[1]) shouldBe false
        t.network.unblock(t.endpoints.getValue(Seat(1)).id)
        val late = GuestSession(t.network.transport(), "Convidado 1")
        runCatching { late.join(t.scope, "mesa-1", token = token, seat = Seat(1)) }.isFailure shouldBe true
        game.join()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        newcomer.view.value!!.stage shouldBe TableStage.GAME_OVER
        newcomer.payout.value!!.stake shouldBe 250
    }

    /**
     * §16.10 o anfitrião cai de vez: o 1º sucessor (o 1º a entrar, com o estado completo) assume depois da carência, os
     * demais reingressam pelo token no novo anfitrião e são avisados; o assento do antigo anfitrião, quando chega a vez
     * dele, pausa e o novo anfitrião põe um robô. O jogo vai até o fim a partir do estado em que estava.
     */
    @Test
    fun `§16_10 queda do anfitriao - o 1o sucessor assume e o jogo continua`() = runTest {
        val seed = 94L
        val network = com.gtranca.net.MemoryNetwork()
        var table: TestTable? = null
        var count = 0
        network.tap = { d ->
            val t = table
            if (t != null && d.from == t.hostTransport.id && ++count == 400) network.kill(d.from)
            listOf(d)
        }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        table = t
        val notices = collectNotices(t.guests)
        val successor = t.guests.getValue(Seat(1))
        driveGuests(t, seed)
        driveHost(t, seed)
        val oldGame = launch { t.host.run() }
        val state = successor.takeOver.first { it != null }!!
        oldGame.cancel()
        val historyAtDrop = state.match.history
        successor.status.value shouldBe GuestStatus.TakingOver
        // Os outros dois nunca guardaram o estado completo (§16.4).
        t.guests.getValue(Seat(3)).holdsSuccessorState shouldBe false

        successor.release()
        val newHost = MultiplayerHost.takeOver(
            t.endpoints.getValue(Seat(1)), "mesa-1", successor.rules.value!!, successor.playerId, state, t.scope,
            botFactory = { seat, _ -> testBot(seed, seat) }, computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        newHost.hostSeat shouldBe Seat(1)
        driveHostOf(newHost, seed)
        // §16.10, §16.12 o antigo anfitrião volta (outra conexão) com o token dele, como jogador comum.
        val oldHostBack = GuestSession(t.network.transport(), "Anfitrião")
        driveGuests(t, seed, guests = mapOf(Seat(0) to oldHostBack))
        oldHostBack.join(t.scope, "mesa-1", token = t.hostSession.hostToken, seat = Seat(0))
        newHost.run()
        advanceUntilIdle()
        newHost.controller.state.value.stage shouldBe Stage.GAME_OVER
        newHost.controller.currentMatch.history.take(historyAtDrop.size) shouldBe historyAtDrop
        oldHostBack.status.value shouldBe GuestStatus.Playing
        oldHostBack.roster.value.single { it.seat == Seat(0) }.isHost shouldBe false
        oldHostBack.roster.value.single { it.seat == Seat(1) }.isHost shouldBe true
        oldHostBack.view.value!!.stage shouldBe TableStage.GAME_OVER
        listOf(Seat(2), Seat(3)).forEach { seat ->
            val g = t.guests.getValue(seat)
            g.status.value shouldBe GuestStatus.Playing
            g.view.value!!.stage shouldBe TableStage.GAME_OVER
            notices.getValue(seat).any { it.kind == NoticeKind.HOST_CHANGED && it.playerId == successor.playerId } shouldBe true
        }
    }

    /**
     * §16.9, §16.10 o anfitrião desiste passando o cargo a um convidado à escolha (aqui o 3º, que não era sucessor): o
     * escolhido assume com o estado do momento; o assento do antigo anfitrião fica "VAGO" (aposta perdida) até a vez dele.
     */
    @Test
    fun `§16_10 anfitriao desiste e passa o cargo ao escolhido`() = runTest {
        val seed = 95L
        val network = com.gtranca.net.MemoryNetwork()
        var handing = false
        val fullStateTo = mutableSetOf<String>()
        network.tap = { d -> if (handing && typeOf(d.bytes) is com.gtranca.net.SuccessorState) fullStateTo += d.to.value; listOf(d) }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), stake = 100, network = network)
        val chosen = t.guests.getValue(Seat(3))
        driveGuests(t, seed)
        val oldGame = launch { t.host.run() }
        advanceUntilIdle()
        // O jogo pára na vez do anfitrião; ele desiste e passa o cargo.
        handing = true
        t.host.handOver(newHostId = chosen.playerId, resign = true) shouldBe chosen.playerId
        // §16.4 na passagem do cargo o estado completo vai só ao escolhido.
        fullStateTo shouldBe setOf(t.endpoints.getValue(Seat(3)).id.value)
        handing = false
        // O escolhido assume na hora (sem deixar o relógio correr: os outros aguardam a mesa nova).
        runCurrent()
        oldGame.isCancelled shouldBe true
        val state = chosen.takeOver.value!!
        state.ledger!!.seats[0].forfeited shouldBe true
        chosen.release()
        val newHost = MultiplayerHost.takeOver(
            t.endpoints.getValue(Seat(3)), "mesa-1", chosen.rules.value!!, chosen.playerId, state, t.scope,
            botFactory = { seat, _ -> testBot(seed, seat) }, computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        driveHostOf(newHost, seed)
        val game = launch { newHost.run() }
        advanceUntilIdle()
        // A vez era do antigo anfitrião, que desistiu: "VAGO", com a decisão do novo anfitrião aberta.
        newHost.decision.value shouldBe HostDecision(Seat(0), 0, PauseReason.VACANT)
        t.guests.getValue(Seat(1)).roster.value.single { it.id == 0 }.resigned shouldBe true
        newHost.replaceWithRobot() shouldBe true
        game.join()
        advanceUntilIdle()
        newHost.controller.state.value.stage shouldBe Stage.GAME_OVER
        t.guests.getValue(Seat(1)).view.value!!.stage shouldBe TableStage.GAME_OVER
        // O prêmio do assento 0 (se o lado venceu) vai ao parceiro humano do assento 2; nada ao antigo anfitrião.
        newHost.payouts.value!!.containsKey(0) shouldBe false
    }

    /**
     * §16.10 o 1º sucessor que não confirma dentro do tempo limite é marcado indisponível e o 2º sobe; sem nenhum
     * sucessor, o jogo segue e o anfitrião é avisado.
     */
    @Test
    fun `§16_10 sucessor sem confirmacao e trocado e sem sucessor o jogo segue com aviso`() = runTest {
        val seed = 96L
        val network = com.gtranca.net.MemoryNetwork()
        var mute = emptySet<String>()
        network.tap = { d -> if (d.from.value in mute && typeOf(d.bytes) is SuccessorAck) emptyList() else listOf(d) }
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(2), Seat(3)), network = network)
        val ids = listOf(Seat(1), Seat(2), Seat(3)).map { t.hostSession.playerAt(it)!!.id }
        t.hostSession.successors.value shouldBe ids.take(2)
        mute = setOf(t.endpoints.getValue(Seat(1)).id.value)
        driveGuests(t, seed)
        driveHost(t, seed)
        t.host.run()
        advanceUntilIdle()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        t.hostSession.successors.value shouldBe listOf(ids[1], ids[2])
        t.guests.getValue(Seat(3)).roster.value.single { it.id == ids[2] }.successorRank shouldBe 2

        // Mesa 1×1 com um convidado que nunca confirma: sem sucessor, o jogo segue e o anfitrião vê o aviso.
        val network2 = com.gtranca.net.MemoryNetwork()
        network2.tap = { d -> if (typeOf(d.bytes) is SuccessorAck) emptyList() else listOf(d) }
        val solo = openTable(GameMode.INDIVIDUAL, seed, listOf(Seat(1)), network = network2)
        driveGuests(solo, seed)
        driveHost(solo, seed)
        solo.host.run()
        advanceUntilIdle()
        solo.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        solo.host.noSuccessor.value shouldBe true
    }

    /**
     * §16.9 (decisão de 2026-10-21) assento VAGO: o anfitrião escolhe aguardar e quem desistiu volta com o token; joga de
     * novo no assento, mas a aposta continua perdida (retida no assento; ver N14 no plano).
     */
    @Test
    fun `§16_9 assento VAGO - aguardar e quem desistiu volta pelo token`() = runTest {
        val seed = 97L
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1), Seat(3)), stake = 100)
        val quitter = t.guests.getValue(Seat(1))
        val token = quitter.token!!
        driveGuests(t, seed, guests = mapOf(Seat(3) to t.guests.getValue(Seat(3))))
        driveHost(t, seed)
        val game = launch { t.host.run() }
        runCurrent()
        quitter.resign()
        advanceUntilIdle()
        t.host.decision.value shouldBe HostDecision(Seat(1), quitter.playerId, PauseReason.VACANT)
        t.host.waitForPlayer()
        advanceUntilIdle()
        t.guests.getValue(Seat(3)).paused.value?.hostChoice shouldBe NoticeKind.WAITING
        val back = GuestSession(t.network.transport(), "Volta")
        driveGuests(t, seed, guests = mapOf(Seat(1) to back))
        back.join(t.scope, "mesa-1", token = token, seat = Seat(1))
        game.join()
        advanceUntilIdle()
        back.status.value shouldBe GuestStatus.Playing
        back.playerId shouldBe quitter.playerId
        back.stakeDebit.value shouldBe null
        // §16.9 quem volta pelo token recupera a aposta.
        t.host.ledger.value.seats[1].forfeited shouldBe false
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
        back.view.value!!.stage shouldBe TableStage.GAME_OVER
    }

    /** §13.1, §16.3, §16.9 no 1×1 entre humanos, a desistência do **anfitrião** dá a vitória ao convidado, que leva 2A. */
    @Test
    fun `§16_9 §13_1 anfitriao desiste no 1x1 e o convidado vence`() = runTest {
        val seed = 98L
        val t = openTable(GameMode.INDIVIDUAL, seed, listOf(Seat(1)), stake = 100)
        val guest = t.guests.getValue(Seat(1))
        val finished = mutableListOf<com.gtranca.net.GameFinished>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            guest.gameEvents.collect { if (it is com.gtranca.net.GameFinished) finished += it }
        }
        val game = launch { t.host.run() }
        runCurrent()
        t.host.handOver(resign = true) shouldBe null
        game.join()
        advanceUntilIdle()
        val state = t.host.controller.state.value
        state.resigned shouldBe true
        state.winner shouldBe GameMode.INDIVIDUAL.sideOf(Seat(1))
        finished shouldBe listOf(com.gtranca.net.GameFinished(GameMode.INDIVIDUAL.sideOf(Seat(1)), resigned = true))
        guest.payout.value shouldBe com.gtranca.net.Payout(stake = 100, received = 200)
    }
}
