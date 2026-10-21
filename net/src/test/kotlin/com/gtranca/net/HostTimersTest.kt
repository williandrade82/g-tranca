package com.gtranca.net

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Tempos de §16.15 aceitos provisoriamente em 21/10/2026 (plano N16, revisar com produto). */
@OptIn(ExperimentalCoroutinesApi::class)
class HostTimersTest {

    private val rules = TableRules("Mesa", GameMode.DUPLAS, RuleSet.DEFAULT, 1000, 0, "facil")

    /** Mesa herdada pelo jogador 1; o 0 era o anfitrião e o 2 é outro humano (ambos fora). */
    private fun resumed(provisionalRank: Int) = ResumedTable(
        hostId = 1,
        members = listOf(
            MemberRecord(PlayerInfo(0, "Ana", 0, Seat(0), confirmed = true), "t0"),
            MemberRecord(PlayerInfo(1, "Bia", 1, Seat(1), confirmed = true), "t1"),
            MemberRecord(PlayerInfo(2, "Caio", 2, Seat(2), confirmed = true), "t2"),
        ),
        nextPlayerId = 3,
        provisionalRank = provisionalRank,
        previousHostId = 0,
    )

    @Test
    fun `§16_10 §16_15 provisorio com outros humanos sem reingresso oferece assumir mesmo assim apos 30 s`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Bia", resumed = resumed(provisionalRank = 1))
        host.open(sessionScope)
        advanceTimeBy(HostSession.PROVISIONAL_FORCE_MILLIS - 1)
        runCurrent()
        host.committed.value shouldBe false
        host.canForceCommit.value shouldBe false
        host.forceCommit() shouldBe false
        advanceTimeBy(2)
        runCurrent()
        host.canForceCommit.value shouldBe true
        host.committed.value shouldBe false
        host.forceCommit() shouldBe true
        host.committed.value shouldBe true
        host.canForceCommit.value shouldBe false
        network.adverts().single().provisionalRank shouldBe 0
        host.close()
    }

    @Test
    fun `§16_11 §16_15 mesa aberta para quem estava fora ate 5 min e depois fecha`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Bia", resumed = resumed(provisionalRank = 0))
        host.open(sessionScope)
        runCurrent()
        host.finish(mapOf(2 to listOf(Rename("fim"))))
        runCurrent()
        host.resultsPending.value shouldBe true
        advanceTimeBy(HostSession.FINISHED_OPEN_MILLIS - 1)
        runCurrent()
        host.deliveryEnded.value shouldBe false
        network.adverts().size shouldBe 1
        advanceTimeBy(2)
        runCurrent()
        host.deliveryEnded.value shouldBe true
        host.resultsPending.value shouldBe false
        network.adverts().size shouldBe 0
        host.close()
    }

    @Test
    fun `§16_11 todos receberam - a entrega encerra depois da folga`() = runTest {
        val network = MemoryNetwork()
        val host = HostSession(network.transport(), "m", rules, "Bia", resumed = resumed(provisionalRank = 0))
        host.open(sessionScope)
        runCurrent()
        host.finish()
        runCurrent()
        host.resultsPending.value shouldBe false
        advanceTimeBy(HostSession.FINISHED_GRACE_MILLIS - 1)
        runCurrent()
        host.deliveryEnded.value shouldBe false
        advanceTimeBy(2)
        runCurrent()
        host.deliveryEnded.value shouldBe true
        host.close()
    }
}
