package com.gtranca.engine

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** §14.6 moedas e apostas: rateio, carteira e ganho diário. */
class CoinsTest {

    @Test
    fun `§14_6 constantes - tres apostas por nivel, padrao 100, saldo inicial 1000 e ganho diario 200`() {
        // §14.6 três valores por nível dos robôs
        Coins.STAKES_BY_LEVEL shouldBe listOf(listOf(50, 80, 100), listOf(100, 150, 200), listOf(200, 300, 500))
        Coins.DEFAULT_STAKE shouldBe 100
        Coins.INITIAL_BALANCE shouldBe 1_000
        Coins.DAILY_GAIN shouldBe 200
    }

    @Test
    fun `§14_6 individual - o vencedor recebe o dobro da aposta e o perdedor nada`() {
        Coins.payouts(listOf(100, 100), listOf(true, false)) shouldBe listOf(200, 0)
        Coins.payouts(listOf(100, 100), listOf(false, true)) shouldBe listOf(0, 200)
        Coins.humanPayout(2, 100, won = true) shouldBe 200
        Coins.humanPayout(2, 100, won = false) shouldBe 0
    }

    @Test
    fun `§14_6 duplas - cada vencedor recebe o dobro da aposta`() {
        Coins.payouts(listOf(250, 250, 250, 250), listOf(true, false, true, false)) shouldBe listOf(500, 0, 500, 0)
        Coins.humanPayout(4, 250, won = true) shouldBe 500
        Coins.humanPayout(4, 250, won = false) shouldBe 0
    }

    @Test
    fun `§14_6 exemplo com apostas diferentes - 100 e 300 contra 200 e 200`() {
        Coins.payouts(listOf(100, 200, 300, 200), listOf(true, false, true, false)) shouldBe listOf(200, 0, 600, 0)
    }

    @Test
    fun `§14_6 sobra do arredondamento vai pelo maior resto, e no empate ao primeiro`() {
        // prêmio 100 entre 3 vencedores de 100: cada parte exata 33,33; a sobra de 1 moeda vai ao primeiro (restos iguais)
        Coins.payouts(listOf(100, 100, 100, 100), listOf(true, true, true, false)) shouldBe listOf(134, 133, 133, 0)
        // prêmio 10 entre apostas 1 e 2 (soma 3): partes exatas 3,33 e 6,67; chão 3 e 6; a sobra vai ao de maior resto (o segundo)
        Coins.payouts(listOf(1, 2, 10), listOf(true, true, false)) shouldBe listOf(4, 9, 0)
    }

    @Test
    fun `§14_6 sem vencedor todos recebem a aposta de volta e entradas invalidas sao recusadas`() {
        Coins.payouts(listOf(50, 100), listOf(false, false)) shouldBe listOf(50, 100)
        assertThrows<IllegalArgumentException> { Coins.payouts(listOf(50), listOf(true, false)) }
        assertThrows<IllegalArgumentException> { Coins.payouts(listOf(-1, 50), listOf(true, false)) }
    }

    @Test
    fun `§14_6 propriedade - a soma recebida e a soma apostada, perdedores recebem 0 e vencedores ao menos a propria aposta`(): Unit = runBlocking {
        checkAll(Arb.list(Arb.int(1..600), 2..6), Arb.list(Arb.boolean(), 6..6)) { stakes, flags ->
            val winners = flags.take(stakes.size)
            if (winners.any { it }) {
                val result = Coins.payouts(stakes, winners)
                result.sum() shouldBe stakes.sum()
                stakes.indices.forEach { i ->
                    if (!winners[i]) result[i] shouldBe 0
                    if (winners[i]) (result[i] >= stakes[i]) shouldBe true
                }
            }
        }
    }

    @Test
    fun `§14_6 carteira - saldo inicial, debito, credito e saldo nunca negativo`() {
        val wallet = Wallet()
        wallet.balance shouldBe 1_000
        wallet.debit(500)!!.balance shouldBe 500
        wallet.debit(1_001) shouldBe null
        wallet.debit(1_000)!!.balance shouldBe 0
        wallet.credit(200).balance shouldBe 1_200
        assertThrows<IllegalArgumentException> { Wallet(balance = -1) }
        assertThrows<IllegalArgumentException> { wallet.credit(-5) }
        Coins.canJoin(50, 50) shouldBe true
        Coins.canJoin(49, 50) shouldBe false
    }

    @Test
    fun `§14_6 ganho diario - um por dia, sem acumular e sem liberar com o relogio atrasado`() {
        val wallet = Wallet(balance = 30)
        wallet.canClaimDaily(20_000) shouldBe true
        val claimed = wallet.claimDaily(20_000)!!
        claimed.balance shouldBe 230 // nunca abaixo de 200 depois do resgate
        claimed.lastClaimDay shouldBe 20_000
        claimed.claimDaily(20_000) shouldBe null // mesmo dia
        claimed.claimDaily(19_999) shouldBe null // relógio atrasado
        claimed.claimDaily(20_001)!!.balance shouldBe 430 // dia seguinte: +200
        claimed.claimDaily(20_010)!!.balance shouldBe 430 // dias sem resgatar não acumulam
    }

    @Test
    fun `§14_6 aposta mantem o indice ao trocar de nivel e respeita o saldo`() {
        Coins.stakesFor(0) shouldBe listOf(50, 80, 100)
        Coins.stakesFor(2) shouldBe listOf(200, 300, 500)
        // mesmo índice (o 2º) em cada nível
        listOf(0, 1, 2).map { Coins.fitStake(it, 1, 10_000) } shouldBe listOf(80, 150, 300)
        // o que passa do saldo vira o maior valor do nível que o saldo cobre
        Coins.fitStake(2, 2, 350) shouldBe 300
        Coins.fitStake(2, 2, 250) shouldBe 200
        // nem o menor coberto: fica o menor (mesa bloqueada)
        Coins.fitStake(2, 1, 120) shouldBe 200
    }

    @Test
    fun `§14_6 aposta antiga vira o valor mais proximo da tabela do nivel (empate - o menor)`() {
        Coins.nearestStake(0, 250) shouldBe 100
        Coins.nearestStake(0, 500) shouldBe 100
        Coins.nearestStake(1, 50) shouldBe 100
        Coins.nearestStake(1, 250) shouldBe 200
        Coins.nearestStake(2, 100) shouldBe 200
        // 125 é equidistante de 100 e 150: o menor
        Coins.nearestStake(1, 125) shouldBe 100
        Coins.stakeIndexOf(1, 250) shouldBe 2
        Coins.stakeIndexOf(0, 80) shouldBe 1
    }
}
