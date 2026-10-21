package com.gtranca.engine

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** §16.9, §16.13 apostas em grupo na mesa multijogador. Ids: anfitrião 0, convidados 1..3. Lado 0 = assentos 0 e 2. */
class GroupBetLedgerTest {

    private val side0Wins = listOf(true, false, true, false)
    private val side1Wins = listOf(false, true, false, true)

    @Test
    fun `§16_13 quatro humanos - cada vencedor recebe 2A e os robos de origem nao pagam`() {
        val all = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3))
        all.payouts(side0Wins, hostId = 0) shouldBe mapOf(0 to 200, 2 to 200)
        val withRobots = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1))
        withRobots.payouts(side0Wins, hostId = 0) shouldBe mapOf(0 to 200)
        withRobots.payouts(side1Wins, hostId = 0) shouldBe mapOf(1 to 200)
    }

    @Test
    fun `§16_9 prêmio do assento que desistiu vai ao parceiro humano`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)).forfeit(2)
        ledger.payouts(side0Wins, hostId = 0) shouldBe mapOf(0 to 400)
        // Se o lado perde, quem desistiu não recebe nada (a aposta já foi debitada).
        ledger.payouts(side1Wins, hostId = 0) shouldBe mapOf(1 to 200, 3 to 200)
    }

    @Test
    fun `§16_9 sem parceiro humano o premio do assento que desistiu vai ao anfitriao atual`() {
        // O parceiro do assento 1 (o 3) é robô de origem.
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2)).forfeit(1)
        ledger.payouts(side1Wins, hostId = 0) shouldBe mapOf(0 to 200)
        // O dono da mesa é o anfitrião atual (depois de uma sucessão).
        ledger.payouts(side1Wins, hostId = 2) shouldBe mapOf(2 to 200)
    }

    @Test
    fun `§16_9 mesa so com o anfitriao - vence e leva tudo, perde e perde so a sua aposta`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)).forfeit(1).forfeit(2).forfeit(3)
        ledger.payouts(side0Wins, hostId = 0) shouldBe mapOf(0 to 400)
        ledger.payouts(side1Wins, hostId = 0) shouldBe emptyMap()
        val alone = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1)).forfeit(1)
        alone.payouts(side1Wins, hostId = 0) shouldBe emptyMap()
        alone.payouts(side0Wins, hostId = 0) shouldBe mapOf(0 to 200)
    }

    @Test
    fun `§16_7 quem entra no lugar de quem saiu herda sem debito e no lugar de robo debita`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1)).forfeit(1)
        val (inherited, debit) = ledger.occupy(1, 7)
        debit shouldBe 0
        inherited.seats[1] shouldBe SeatStake(holder = 7)
        val (joined, robotDebit) = inherited.occupy(3, 8)
        robotDebit shouldBe 100
        joined.payouts(side1Wins, hostId = 0) shouldBe mapOf(7 to 200, 8 to 200)
    }

    @Test
    fun `§14_6 §16_13 a soma do que se recebe, mais o que cabe aos robos de origem, e a soma das apostas`() = runBlocking {
        checkAll(
            Arb.list(Arb.element(listOf<Int?>(null, 0, 1, 2, 3)), 4..4),
            Arb.list(Arb.boolean(), 4..4),
            Arb.boolean(),
            Arb.element(listOf(50, 100, 250, 500)),
        ) { holders, forfeits, side0, stake ->
            val humans = holders.withIndex().filter { it.value != null }.associate { it.index to it.value!! }
            var ledger = GroupBetLedger.start(stake, 4, humans)
            forfeits.forEachIndexed { i, f -> if (f) ledger = ledger.forfeit(i) }
            val winners = if (side0) side0Wins else side1Wins
            val robots = (0 until 4).filter { holders[it] == null }.sumOf { Coins.payouts(List(4) { stake }, winners)[it] }
            val paid = ledger.payouts(winners, hostId = 0)
            val onlyHostLost = ledger.seats.none { it.holder != null && !it.forfeited && it.holder != 0 } &&
                (0 until 4).none { ledger.seats[it].holder == 0 && !ledger.seats[it].forfeited && winners[it] }
            // Fora da exceção da mesa só com o anfitrião que perde, nada se cria nem se perde.
            if (!onlyHostLost) paid.values.sum() + robots shouldBe 4 * stake else paid shouldBe emptyMap()
        }
    }
}
