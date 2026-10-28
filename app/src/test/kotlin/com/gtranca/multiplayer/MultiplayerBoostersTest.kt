package com.gtranca.multiplayer

import com.gtranca.engine.Reaction
import com.gtranca.engine.SeatBoosters
import com.gtranca.engine.TableBoosters
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.net.PROTOCOL_VERSION
import com.gtranca.net.ReactionShown
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * §17.3, §17.4, §17.8 boosters na mesa multijogador: o anfitrião guarda o estado de cada **assento**, o convidado registra a
 * escolha do seletor e as reações vão a todos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerBoostersTest {

    @Test
    fun `§17_4 a versao do protocolo subiu para 18`() {
        PROTOCOL_VERSION shouldBe 18
    }

    @Test
    fun `§17_3 o convidado confirma o seletor e o anfitriao registra no assento e devolve o estado`() = runTest {
        val t = openTable(GameMode.DUPLAS, 41, listOf(Seat(1), Seat(2)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        val guest = t.guests.getValue(Seat(1))
        guest.chooseBoosters(1, listOf("b1", "b3"))
        advanceUntilIdle()
        t.host.boosters.value.of(1) shouldBe SeatBoosters(listOf("b1", "b3"), 0, 1)
        guest.seatBoosters.value shouldBe SeatBoosters(listOf("b1", "b3"), 0, 1)
        // o assento do outro convidado continua sem escolha
        t.host.boosters.value.of(2) shouldBe SeatBoosters()
    }

    @Test
    fun `§17_3 escolha de outra partida ou repetida nao vale`() = runTest {
        val t = openTable(GameMode.DUPLAS, 42, listOf(Seat(1)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        val guest = t.guests.getValue(Seat(1))
        guest.chooseBoosters(5, listOf("b1")) // partida que não está em andamento
        advanceUntilIdle()
        t.host.boosters.value.of(1) shouldBe SeatBoosters()
        guest.chooseBoosters(1, listOf("b2"))
        guest.chooseBoosters(1, listOf("b1", "b3")) // segunda confirmação da mesma partida: vale a primeira
        advanceUntilIdle()
        t.host.boosters.value.of(1).boosters shouldBe listOf("b2")
    }

    @Test
    fun `§17_8 a reacao do convidado passa pelo anfitriao e chega a todos, 3 usos por partida`() = runTest {
        val t = openTable(GameMode.DUPLAS, 43, listOf(Seat(1), Seat(2)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        val sender = t.guests.getValue(Seat(1))
        val other = t.guests.getValue(Seat(2))
        val seenByHost = mutableListOf<ReactionShown>()
        val seenByOther = mutableListOf<ReactionShown>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.reactions.collect { seenByHost += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { other.reactions.collect { seenByOther += it } }
        sender.chooseBoosters(1, listOf("b3"))
        advanceUntilIdle()
        repeat(4) { sender.sendReaction(1, Reaction.entries[it].code) }
        advanceUntilIdle()
        // a 4ª foi recusada
        seenByHost.map { it.reaction } shouldBe listOf("clap", "love", "tired")
        seenByOther.map { it.seat } shouldBe listOf(Seat(1), Seat(1), Seat(1))
        sender.seatBoosters.value!!.reactionsLeft shouldBe 0
        t.host.boosters.value.of(1).reactionsUsed shouldBe 3
    }

    @Test
    fun `§17_8 sem o b3 no assento a reacao e ignorada`() = runTest {
        val t = openTable(GameMode.DUPLAS, 44, listOf(Seat(1)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        val guest = t.guests.getValue(Seat(1))
        val seen = mutableListOf<ReactionShown>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.reactions.collect { seen += it } }
        guest.chooseBoosters(1, listOf("b1"))
        guest.sendReaction(1, "love")
        advanceUntilIdle()
        seen shouldBe emptyList()
    }

    @Test
    fun `§17_4 o anfitriao tambem tem o seu assento - confirma e usa reacao direto`() = runTest {
        val t = openTable(GameMode.DUPLAS, 45, listOf(Seat(1)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        t.host.registerBoosters(t.host.hostSeat, 1, listOf("b3"))
        t.host.boosters.value.of(0).confirmedFor(1) shouldBe true
        t.host.sendReaction(t.host.hostSeat, 1, Reaction.CHICKEN) shouldBe true
        t.host.boosters.value.of(0).reactionsLeft shouldBe 2
    }

    @Test
    fun `§17_4 o estado dos assentos vai no estado do sucessor e volta igual`() {
        val table = TableBoosters().confirm(1, listOf("b1"), 1).confirm(2, listOf("b3"), 1).useReaction(2)!!
        val state = com.gtranca.net.SuccessorState(
            stateSeq = 7, match = com.gtranca.engine.startMatch(GameMode.DUPLAS, 3000, kotlin.random.Random(1)),
            events = emptyList(), botRandomCalls = emptyList(), members = emptyList(), nextPlayerId = 3, boosters = table,
        )
        val codec = com.gtranca.net.ProtocolCodec()
        val decoded = codec.decode(codec.encode(1, state)) as com.gtranca.net.Decoded.Ok
        (decoded.envelope.p as com.gtranca.net.SuccessorState).boosters shouldBe table
    }

    @Test
    fun `§17_4 o anfitriao so aceita ids do catalogo - id inventado fica de fora do assento`() = runTest {
        val t = openTable(GameMode.DUPLAS, 47, listOf(Seat(1)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        t.guests.getValue(Seat(1)).chooseBoosters(1, listOf("zzz", "b3", "b3", "b9"))
        advanceUntilIdle()
        t.host.boosters.value.of(1).boosters shouldBe listOf("b3")
    }

    @Test
    fun `§17_4 quem entra num assento ja confirmado recebe o estado dele sem novo seletor`() = runTest {
        val t = openTable(GameMode.DUPLAS, 48, listOf(Seat(1)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { t.host.run() }
        runCurrent()
        t.guests.getValue(Seat(1)).chooseBoosters(1, listOf("b1", "b3"))
        advanceUntilIdle()
        // o assento guarda o estado mesmo sem o ocupante: quem (re)entra o recebe pelo anfitrião
        t.host.onSeatAttached(Seat(1))
        advanceUntilIdle()
        t.guests.getValue(Seat(1)).seatBoosters.value shouldBe SeatBoosters(listOf("b1", "b3"), 0, 1)
        t.guests.getValue(Seat(1)).seatBoosters.value!!.confirmedFor(1) shouldBe true
    }
}
