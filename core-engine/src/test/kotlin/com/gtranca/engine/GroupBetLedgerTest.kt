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
    fun `§16_13 o premio e dividido por assento vencedor e a parte de robo se perde`() {
        val all = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3))
        all.payouts(side0Wins) shouldBe mapOf(0 to 200, 2 to 200)
        all.lost(side0Wins) shouldBe 0
        // Anfitrião vencedor com robô parceiro: só a parte dele; a do robô se perde.
        val withRobots = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1))
        withRobots.payouts(side0Wins) shouldBe mapOf(0 to 200)
        withRobots.lost(side0Wins) shouldBe 200
        withRobots.payouts(side1Wins) shouldBe mapOf(1 to 200)
    }

    @Test
    fun `§16_13 1x1 o vencedor leva o premio inteiro`() {
        val duel = GroupBetLedger.start(100, 2, mapOf(0 to 0, 1 to 1)).forfeit(1)
        duel.payouts(listOf(true, false)) shouldBe mapOf(0 to 200)
    }

    @Test
    fun `§16_9 §16_13 a parte do assento que desistiu no lado vencedor se perde, nem parceiro nem anfitriao`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)).forfeit(2)
        ledger.payouts(side0Wins) shouldBe mapOf(0 to 200)
        ledger.lost(side0Wins) shouldBe 200
        ledger.forfeit(1).forfeit(3).payouts(side1Wins) shouldBe emptyMap()
    }

    @Test
    fun `§16_9 quem desistiu e voltou pelo token recupera a aposta`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 2 to 2)).forfeit(2).restore(2, 2)
        ledger.payouts(side0Wins) shouldBe mapOf(0 to 200, 2 to 200)
        // Outro jogador no assento não recupera nada.
        GroupBetLedger.start(100, 4, mapOf(2 to 2)).forfeit(2).restore(2, 9).seats[2].forfeited shouldBe true
    }

    @Test
    fun `§16_7 quem entra no lugar de quem saiu herda sem debito e no lugar de robo debita`() {
        val ledger = GroupBetLedger.start(100, 4, mapOf(0 to 0, 1 to 1)).forfeit(1)
        val (inherited, debit) = ledger.occupy(1, 7)
        debit shouldBe 0
        inherited.seats[1] shouldBe SeatStake(holder = 7)
        val (joined, robotDebit) = inherited.occupy(3, 8)
        robotDebit shouldBe 100
        joined.payouts(side1Wins) shouldBe mapOf(7 to 200, 8 to 200)
    }

    @Test
    fun `§14_6 §16_13 a soma recebida mais a soma perdida e a soma das apostas`() = runBlocking {
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
            ledger.payouts(winners).values.sum() + ledger.lost(winners) shouldBe 4 * stake
        }
    }
}
