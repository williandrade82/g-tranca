package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Ordem da mão: só apresentação (nenhuma regra é validada aqui). */
class HandOrderTest {

    private fun cards(vararg text: String) = text.map(Card::parse)

    @Test
    fun `sem separar - a mao toda segue o criterio escolhido`() {
        val hand = cards("QD", "3C", "7H", "2S", "8H", "KS", "7S")
        HandOrder.sort(hand, HandSort.BY_SUIT) shouldBe cards("2S", "7S", "KS", "7H", "8H", "3C", "QD")
        HandOrder.sort(hand, HandSort.BY_RANK) shouldBe cards("2S", "3C", "7S", "7H", "8H", "QD", "KS")
    }

    @Test
    fun `separando - 3 pretos e coringas na coluna e o resto no criterio escolhido`() {
        val hand = cards("QD", "3C", "7H", "2S", "8H", "KS", "3S", "2D", "7S")
        val bySuit = HandOrder.split(hand, HandSort.BY_SUIT)
        bySuit.special shouldBe cards("3S", "3C", "2S", "2D")
        bySuit.rest shouldBe cards("7S", "KS", "7H", "8H", "QD")
        val byRank = HandOrder.split(hand, HandSort.BY_RANK)
        byRank.special shouldBe bySuit.special
        byRank.rest shouldBe cards("7S", "7H", "8H", "QD", "KS")
    }

    @Test
    fun `3 vermelho nao e especial e fica com o resto`() {
        HandOrder.split(cards("3H", "3S", "5D"), HandSort.BY_RANK).let {
            it.special shouldBe cards("3S")
            it.rest shouldBe cards("3H", "5D")
        }
    }

    @Test
    fun `mao vazia`() {
        HandOrder.split(emptyList(), HandSort.BY_SUIT) shouldBe CustomHand(emptyList(), emptyList())
        HandOrder.sort(emptyList(), HandSort.BY_RANK) shouldBe emptyList()
    }

    @Test
    fun `ordem e so permutacao da mao, em qualquer modo`() {
        val deck = Deck.standard()
        repeat(300) { seed ->
            val random = Random(seed)
            val hand = deck.shuffled(random).take(random.nextInt(0, 23))
            HandSort.entries.forEach {
                HandOrder.sort(hand, it) shouldContainExactlyInAnyOrder hand
                HandOrder.split(hand, it).all shouldContainExactlyInAnyOrder hand
            }
        }
    }

    @Test
    fun `preferencia grava e le o criterio e a coluna separada, e o antigo CUSTOM vira o padrao`() {
        HandPrefs(HandSort.BY_RANK, separateSpecial = false).id shouldBe "BY_RANK"
        HandPrefs(HandSort.BY_SUIT, separateSpecial = true).id shouldBe "BY_SUIT:SPECIAL"
        HandPrefs.parse("BY_RANK") shouldBe HandPrefs(HandSort.BY_RANK, false)
        HandPrefs.parse("BY_RANK:SPECIAL") shouldBe HandPrefs(HandSort.BY_RANK, true)
        HandPrefs.parse("CUSTOM") shouldBe HandPrefs()
        HandPrefs.parse(null) shouldBe HandPrefs()
        HandPrefs.parse("lixo:xyz") shouldBe HandPrefs()
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
