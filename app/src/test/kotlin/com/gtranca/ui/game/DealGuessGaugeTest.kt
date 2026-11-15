package com.gtranca.ui.game

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * §3.6 (ajuste de 15/11/2026, toque único): o indicador agora percorre, ida e volta, só os números de uma faixa
 * FIXA por papel/modo (`dealGuessHandRange`/`dealGuessMortoRange`: 35..55 ou 12..32), nunca mais o baralho
 * inteiro. [dealGuessStageAt] é a função pura (sem Compose) que calcula esse número a partir do tempo decorrido;
 * [dealGuessFillFraction] é o preenchimento visual da barra (do topo até o estágio atual).
 */
class DealGuessGaugeTest {

    private val handDuplas = 35..55
    private val handIndividual = 12..32
    private val morto = 12..32

    @Test
    fun `§3_6 no instante zero o indicador comeca no minimo da faixa`() {
        dealGuessStageAt(0L, handDuplas) shouldBe 35
        dealGuessStageAt(0L, morto) shouldBe 12
    }

    @Test
    fun `§3_6 na metade do 1o meio-ciclo o indicador esta no meio da faixa`() {
        dealGuessStageAt(1_300L, handDuplas, cycleMillis = 2_600) shouldBe 45
    }

    @Test
    fun `§3_6 no fim do 1o meio-ciclo o indicador chega ao maximo da faixa`() {
        dealGuessStageAt(2_600L, handDuplas, cycleMillis = 2_600) shouldBe 55
    }

    @Test
    fun `§3_6 no fim do 2o meio-ciclo o indicador volta ao minimo (ida e volta)`() {
        dealGuessStageAt(5_200L, handDuplas, cycleMillis = 2_600) shouldBe 35
    }

    @Test
    fun `§3_6 o ciclo se repete (3o meio-ciclo igual ao 1o)`() {
        dealGuessStageAt(2_600L + 1_300L, handDuplas, cycleMillis = 2_600) shouldBe
            dealGuessStageAt(1_300L, handDuplas, cycleMillis = 2_600)
    }

    @Test
    fun `§3_6 faixa de um so numero nunca muda (sem span)`() {
        dealGuessStageAt(999L, 22..22) shouldBe 22
    }

    @Test
    fun `§3_6 propriedade o indicador nunca sai da faixa fixa do papel_modo`() = runBlocking {
        checkAll(Arb.long(0L..1_000_000L)) { elapsed ->
            (dealGuessStageAt(elapsed, handDuplas) in handDuplas) shouldBe true
            (dealGuessStageAt(elapsed, handIndividual) in handIndividual) shouldBe true
            (dealGuessStageAt(elapsed, morto) in morto) shouldBe true
        }
    }

    @Test
    fun `§3_6 o preenchimento vai de 0 no minimo a 1 no maximo da faixa`() {
        dealGuessFillFraction(35, handDuplas) shouldBe 0f
        dealGuessFillFraction(55, handDuplas) shouldBe 1f
        dealGuessFillFraction(45, handDuplas) shouldBe 0.5f
    }

    @Test
    fun `§3_6 preenchimento de faixa de um so numero nao quebra (zero)`() {
        dealGuessFillFraction(22, 22..22) shouldBe 0f
    }

    @Test
    fun `§3_6 propriedade o preenchimento nunca sai de 0 a 1`() = runBlocking {
        checkAll(Arb.int(35..55)) { stage ->
            val fill = dealGuessFillFraction(stage, handDuplas)
            (fill in 0f..1f) shouldBe true
        }
    }
}
