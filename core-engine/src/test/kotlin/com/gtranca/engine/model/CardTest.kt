package com.gtranca.engine.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CardTest {

    @Test
    fun `notacao curta faz ida e volta para todas as 104 cartas`() {
        // Notação de testes/logs: "7H", "TH" (10), sufixo ' para o 2º baralho.
        Deck.standard().forEach { card ->
            Card.parse(card.toString()) shouldBe card
        }
    }

    @Test
    fun `parse reconhece valor, naipe e baralho`() {
        Card.parse("7H") shouldBe Card(Rank.SEVEN, Suit.HEARTS, 0)
        Card.parse("TH") shouldBe Card(Rank.TEN, Suit.HEARTS, 0)
        Card.parse("QS'") shouldBe Card(Rank.QUEEN, Suit.SPADES, 1)
        Card.parse("AD").toString() shouldBe "AD"
        Card.parse("2C'").toString() shouldBe "2C'"
    }

    @Test
    fun `parse rejeita notacao invalida`() {
        listOf("", "1H", "7X", "10H", "7H''", "7h").forEach { text ->
            shouldThrow<IllegalArgumentException> { Card.parse(text) }
        }
    }

    @Test
    fun `carta exige baralho 0 ou 1`() {
        shouldThrow<IllegalArgumentException> { Card(Rank.SEVEN, Suit.HEARTS, 2) }
    }

    @Test
    fun `copas e ouros sao vermelhos, espadas e paus sao pretos`() {
        // §6.5 3 vermelho = copas ou ouros; §6.6 3 preto = espadas ou paus
        Suit.HEARTS.isRed.shouldBeTrue()
        Suit.DIAMONDS.isRed.shouldBeTrue()
        Suit.SPADES.isRed.shouldBeFalse()
        Suit.CLUBS.isRed.shouldBeFalse()
    }

    @Test
    fun `classifica 3 vermelho, 3 preto e coringa`() {
        // §6.3 o 2 é sempre coringa; §6.5 3 vermelho; §6.6 3 preto
        Card.parse("3H").isRedThree.shouldBeTrue()
        Card.parse("3D'").isRedThree.shouldBeTrue()
        Card.parse("3S").isRedThree.shouldBeFalse()
        Card.parse("3S").isBlackThree.shouldBeTrue()
        Card.parse("3C'").isBlackThree.shouldBeTrue()
        Card.parse("3H").isBlackThree.shouldBeFalse()
        Card.parse("2C").isWild.shouldBeTrue()
        Card.parse("2H'").isWild.shouldBeTrue()
        Card.parse("AC").isWild.shouldBeFalse()
        Rank.TWO.isWild.shouldBeTrue()
        Rank.THREE.isThree.shouldBeTrue()
        Rank.FOUR.isThree.shouldBeFalse()
    }
}
