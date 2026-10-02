package com.gtranca.engine.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DeckTest {

    @Test
    fun `dois baralhos padrao somam 104 cartas`() {
        // §1 2 baralhos sem curingas de fábrica = 104 cartas
        Deck.standard() shouldHaveSize 104
        Deck.SIZE shouldBe 104
    }

    @Test
    fun `cada valor aparece 8 vezes`() {
        // §1 cartas de 2 a A nos quatro naipes, em 2 baralhos
        val byRank = Deck.standard().groupingBy { it.rank }.eachCount()
        byRank.keys shouldBe Rank.entries.toSet()
        byRank.values.forEach { it shouldBe 8 }
    }

    @Test
    fun `cada carta tem exatamente duas copias, uma por baralho`() {
        // §1 2 baralhos padrão
        val deck = Deck.standard()
        deck.toSet() shouldHaveSize 104
        val byRankSuit = deck.groupBy { it.rank to it.suit }
        byRankSuit.size shouldBe 52
        byRankSuit.values.forEach { copies -> copies.map { it.deck } shouldContainExactlyInAnyOrder listOf(0, 1) }
    }

    @Test
    fun `embaralhar usa somente o Random recebido`() {
        // §1 cartas embaralhadas aleatoriamente; reprodutível pela semente
        Deck.shuffled(Random(7)) shouldBe Deck.shuffled(Random(7))
        Deck.shuffled(Random(7)) shouldNotBe Deck.shuffled(Random(8))
        Deck.shuffled(Random(7)) shouldContainExactlyInAnyOrder Deck.standard()
    }
}
