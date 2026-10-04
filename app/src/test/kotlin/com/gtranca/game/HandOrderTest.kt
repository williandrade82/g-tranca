package com.gtranca.game

import com.gtranca.engine.model.Card
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import com.gtranca.engine.model.Deck
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Ordem Personalizada da mão: só apresentação (nenhuma regra é validada aqui). */
class HandOrderTest {

    private fun cards(vararg text: String) = text.map(Card::parse)

    @Test
    fun `blocos na ordem - 3 pretos, coringas, soltas, sequencias e mesmo valor`() {
        val hand = cards("QD", "3C", "7H", "2S", "8H", "KS", "KC", "3S", "9H", "2D", "5D")
        val custom = HandOrder.custom(hand)
        custom.special shouldBe cards("3S", "3C", "2S", "2D")
        // Soltas (Q♦, 5♦), depois a sequência 7-8-9♥ e o par de reis.
        custom.rest shouldBe cards("5D", "QD", "7H", "8H", "9H", "KS", "KC")
    }

    @Test
    fun `carta que serve a sequencia e ao par vai para a sequencia e o par sobra so se ainda tiver 2`() {
        // 7♥ serve a 7-8♥ e ao grupo de 7: as sequências se formam primeiro.
        HandOrder.custom(cards("7H", "8H", "7S")).rest shouldBe cards("7S", "7H", "8H")
        // Com mais dois 7, o grupo 7♠ 7♣ ainda se forma com o que sobrou.
        HandOrder.custom(cards("7H", "8H", "7S", "7C")).rest shouldBe cards("7H", "8H", "7S", "7C")
    }

    @Test
    fun `duas copias da mesma carta - uma na sequencia, a outra fica solta`() {
        HandOrder.custom(cards("7H", "7H'", "8H")).rest shouldBe cards("7H'", "7H", "8H")
        // Duas cópias e mais nada: mesmo valor.
        HandOrder.custom(cards("9D", "9D'")).rest shouldBe cards("9D", "9D'")
    }

    @Test
    fun `sequencias mais longas antes - a mais longa leva as cartas disputadas`() {
        // 4-5-6-7♠ (4 cartas) e 7♠' não forma nada; Á♠ e K♠ formam K-A.
        HandOrder.custom(cards("4S", "5S", "6S", "7S", "KS", "AS")).rest shouldBe cards("4S", "5S", "6S", "7S", "KS", "AS")
        // 2 e 3 nunca entram em sequência (§6.2/§6.3 só como apresentação aqui: 4..A).
        HandOrder.custom(cards("3H", "4H", "5H")).let {
            it.special shouldBe emptyList()
            it.rest shouldBe cards("3H", "4H", "5H")
        }
    }

    @Test
    fun `mao vazia`() {
        HandOrder.custom(emptyList()) shouldBe CustomHand(emptyList(), emptyList())
        HandOrder.sort(emptyList(), HandSort.BY_RANK) shouldBe emptyList()
    }

    @Test
    fun `ordem e so permutacao da mao, em qualquer modo`() {
        val deck = Deck.standard()
        repeat(300) { seed ->
            val random = Random(seed)
            val hand = deck.shuffled(random).take(random.nextInt(0, 23))
            HandSort.entries.forEach { HandOrder.sort(hand, it) shouldContainExactlyInAnyOrder hand }
        }
    }

    @Test
    fun `coluna fixa ganha mais uma carta de largura quando ha mais 2-3 que linhas`() {
        // 7 cartas por linha sem a coluna; cada coluna tira uma do fluxo.
        val perRow = { columns: Int -> 7 - columns }
        HandOrder.specialColumns(0, 10, perRow = perRow) shouldBe 0
        // 10 cartas em 6 por linha = 2 linhas: até 2 especiais cabem em 1 coluna.
        HandOrder.specialColumns(2, 10, perRow = perRow) shouldBe 1
        // 3 especiais: 1 coluna dá só 2 linhas; com 2 colunas (5 por linha, 2 linhas) cabem 4.
        HandOrder.specialColumns(3, 10, perRow = perRow) shouldBe 2
        // Mão só de especiais: uma coluna de várias linhas não basta (1 linha), cresce até o limite.
        HandOrder.specialColumns(5, 0, perRow = perRow) shouldBe 3
    }
}
