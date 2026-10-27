package com.gtranca.multiplayer

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.Stage
import com.gtranca.net.CallNotice
import com.gtranca.net.GuestSession
import com.gtranca.net.GuestStatus
import com.gtranca.net.HostSession
import com.gtranca.net.RefusalReason
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * §16.7 a porta de entrada no meio do jogo: o anfitrião **chama** um jogador livre para o assento aberto (docs/telas.md
 * 3.15, modal *Chamar jogador*). Chamar é a aprovação do anfitrião; o chamado só entra se aceitar o convite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerCallTest {

    /** Mesa de 4 com o convidado do assento 1 fora (a vez dele pausa o jogo) e a vaga aberta para recrutamento (§16.8, opção 3). */
    private suspend fun TestScope.openPausedSeat(seed: Long, gate: MutableStateFlow<Boolean>): TestTable {
        val t = openTable(GameMode.DUPLAS, seed, listOf(Seat(1)), stake = 100)
        driveHostGated(t, seed, gate)
        // O jogo fica parado na pausa em vários testes: no fundo, para o runTest não esperar por ele ao fim.
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        val endpoint = t.endpoints.getValue(Seat(1)).id
        t.network.block(endpoint)
        t.network.drop(endpoint)
        t.guests.getValue(Seat(1)).release()
        t.host.decision.first { it?.seat == Seat(1) }
        gate.value = false
        t.host.openSeatForRecruit()
        return t
    }

    private fun TestScope.collectNotices(t: TestTable): List<CallNotice> {
        val notices = mutableListOf<CallNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.hostSession.callNotices.collect { notices += it } }
        return notices
    }

    private suspend fun TestScope.candidate(t: TestTable, seed: Long, seat: Seat, key: String = "livre1"): GuestSession {
        val guest = GuestSession(t.network.transport(), "Nova")
        driveGuests(t, seed, guests = mapOf(seat to guest))
        guest.join(t.scope, "mesa-1", inviteKey = key)
        return guest
    }

    /** §16.7 no lugar de quem saiu o chamado herda a aposta retida: o convite mostra débito 0 e nada é debitado. */
    @Test
    fun `§16_7 chamado no lugar de quem saiu herda a aposta e entra jogando`() = runTest {
        val seed = 210L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        t.network.adverts().single().invited shouldBe listOf("livre1")
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.status.value.shouldBeInstanceOf<GuestStatus.Called>().offer.let {
            it.debit shouldBe 0
            it.seat shouldBe Seat(1)
        }
        t.hostSession.seatCalls.value.single().connected shouldBe true
        guest.answerCall(true)
        runCurrent()
        gate.value = true
        advanceUntilIdle()
        guest.seat shouldBe Seat(1)
        guest.stakeDebit.value shouldBe 0
        t.host.ledger.value.seats[1].let { it.holder to it.forfeited } shouldBe (guest.playerId to false)
        t.hostSession.seatCalls.value.shouldBeEmpty()
        t.hostSession.openSeats.value.shouldBeEmpty()
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.7, §16.13 no lugar de um robô de origem o convite mostra a aposta cheia e, aceito, ela é debitada. */
    @Test
    fun `§16_7 chamado no lugar de um robo debita a aposta`() = runTest {
        val seed = 211L
        val t = openTable(GameMode.INDIVIDUAL, seed, emptyList(), stake = 100)
        val gate = MutableStateFlow(false)
        driveHostGated(t, seed, gate)
        val game = launch { t.host.run() }
        runCurrent()
        t.host.openRobotSeat(Seat(1))
        t.hostSession.callPlayer(Seat(1), "livre1", "Rival") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.status.value.shouldBeInstanceOf<GuestStatus.Called>().offer.debit shouldBe 100
        guest.answerCall(true)
        runCurrent()
        gate.value = true
        game.join()
        advanceUntilIdle()
        guest.stakeDebit.value shouldBe 100
        guest.seat shouldBe Seat(1)
        guest.payout.value!!.stake shouldBe 100
        t.host.controller.state.value.stage shouldBe Stage.GAME_OVER
    }

    /** §16.7 recusa só limpa a chamada: a vaga continua aberta e o anfitrião vê o motivo. */
    @Test
    fun `§16_7 chamado que recusa deixa a vaga aberta e avisa o anfitriao`() = runTest {
        val seed = 212L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        val notices = collectNotices(t)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.answerCall(false)
        runCurrent()
        guest.status.value shouldBe GuestStatus.Declined
        notices.single().let { it.reason to it.seat } shouldBe (RefusalReason.INVITE_REFUSED to Seat(1))
        t.hostSession.seatCalls.value.shouldBeEmpty()
        t.hostSession.openSeats.value shouldBe setOf(Seat(1))
        t.network.adverts().single().invited.shouldBeEmpty()
        // O anfitrião pode chamar outro para a mesma vaga.
        t.hostSession.callPlayer(Seat(1), "livre2", "Outro") shouldBe true
    }

    /** §16.7, §16.15 saldo menor que o débito: a recusa automática diz o motivo ao anfitrião. */
    @Test
    fun `§16_7 saldo insuficiente chega ao anfitriao como motivo`() = runTest {
        val seed = 213L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        val notices = collectNotices(t)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.answerCall(false, RefusalReason.INSUFFICIENT_BALANCE)
        runCurrent()
        notices.single().reason shouldBe RefusalReason.INSUFFICIENT_BALANCE
        t.hostSession.openSeats.value shouldBe setOf(Seat(1))
    }

    /** §16.15 o chamado que nunca conecta: 30 s depois a chamada acaba e a vaga continua aberta. */
    @Test
    fun `§16_15 chamado que nao conecta perde a chamada em 30 s`() = runTest {
        val seed = 214L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        val notices = collectNotices(t)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        advanceTimeBy(HostSession.CALL_CONNECT_MILLIS - 1)
        t.hostSession.seatCalls.value.size shouldBe 1
        advanceTimeBy(2)
        runCurrent()
        t.hostSession.seatCalls.value.shouldBeEmpty()
        notices.single().reason shouldBe RefusalReason.NO_RESPONSE
        t.hostSession.openSeats.value shouldBe setOf(Seat(1))
    }

    /** §16.15 chamado que conectou e não responde: 27 s depois do convite a chamada acaba. */
    @Test
    fun `§16_15 chamado que conecta e nao responde perde a chamada em 27 s`() = runTest {
        val seed = 215L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        val notices = collectNotices(t)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.status.value.shouldBeInstanceOf<GuestStatus.Called>()
        advanceTimeBy(HostSession.CALL_REPLY_MILLIS + 1)
        runCurrent()
        notices.single().reason shouldBe RefusalReason.NO_RESPONSE
        t.hostSession.seatCalls.value.shouldBeEmpty()
        t.hostSession.openSeats.value shouldBe setOf(Seat(1))
    }

    /** §16.5, §16.7 o dono volta pelo token durante a chamada: a vaga fecha e o chamado vê "A vaga foi preenchida.". */
    @Test
    fun `§16_7 dono volta durante a chamada e o chamado ve a vaga preenchida`() = runTest {
        val seed = 216L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        val away = t.guests.getValue(Seat(1))
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        guest.status.value.shouldBeInstanceOf<GuestStatus.Called>()
        val owner = GuestSession(t.network.transport(), "Convidado 1")
        driveGuests(t, seed, guests = mapOf(Seat(1) to owner))
        owner.join(t.scope, "mesa-1", token = away.token, seat = Seat(1))
        runCurrent()
        guest.status.value shouldBe GuestStatus.Refused(HostSession.SEAT_TAKEN_MESSAGE)
        t.hostSession.seatCalls.value.shouldBeEmpty()
        t.network.adverts().single().invited.shouldBeEmpty()
    }

    /** §16.7 o anfitrião põe um robô durante a chamada: ela cai e o chamado vê "A vaga foi preenchida.". */
    @Test
    fun `§16_7 robo no lugar derruba a chamada`() = runTest {
        val seed = 217L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        t.host.replaceWithRobot() shouldBe true
        runCurrent()
        guest.status.value shouldBe GuestStatus.Refused(HostSession.SEAT_TAKEN_MESSAGE)
        t.hostSession.seatCalls.value.shouldBeEmpty()
    }

    /** §16.7 o anfitrião cancela a chamada pendente: o chamado é avisado e a vaga continua aberta. */
    @Test
    fun `§16_7 anfitriao cancela a chamada`() = runTest {
        val seed = 218L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val guest = candidate(t, seed, Seat(1))
        runCurrent()
        t.hostSession.cancelCall("livre1")
        runCurrent()
        guest.status.value shouldBe GuestStatus.Refused(HostSession.CALL_CANCELLED_MESSAGE)
        t.hostSession.seatCalls.value.shouldBeEmpty()
        t.hostSession.openSeats.value shouldBe setOf(Seat(1))
    }

    /** §16.7 só se chama para vaga aberta, uma chamada por assento e no máximo 3 ao mesmo tempo. */
    @Test
    fun `§16_7 regras da chamada - vaga aberta, uma por assento`() = runTest {
        val seed = 219L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        // Assento fechado (o do parceiro): sem vaga não há chamada.
        t.hostSession.callPlayer(Seat(2), "livre9", "Fechado") shouldBe false
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        t.hostSession.callPlayer(Seat(1), "livre2", "Outro") shouldBe false
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe false
        t.hostSession.seatCalls.value.map { it.key } shouldBe listOf("livre1")
    }

    /** §16.2, §16.15 quem chega com chave que ninguém chamou continua recebendo "o lugar passou para um robô". */
    @Test
    fun `§16_7 chave que ninguem chamou continua recusada`() = runTest {
        val seed = 220L
        val gate = MutableStateFlow(true)
        val t = openPausedSeat(seed, gate)
        t.hostSession.callPlayer(Seat(1), "livre1", "Nova") shouldBe true
        val intruder = candidate(t, seed, Seat(1), key = "intruso")
        runCurrent()
        intruder.status.value.shouldBeInstanceOf<GuestStatus.Refused>()
        t.hostSession.seatCalls.value.single().connected shouldBe false
    }
}
