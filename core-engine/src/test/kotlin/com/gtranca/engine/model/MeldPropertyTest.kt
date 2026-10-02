package com.gtranca.engine.model

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MeldPropertyTest {

    /** Gera um conjunto válido aleatório (sequência ou grupo, com ou sem coringa). */
    private fun randomMeld(random: Random): Meld {
        while (true) {
            val pool = Deck.standard().shuffled(random)
            val candidate: List<Card> = if (random.nextBoolean()) {
                val suit = Suit.entries.random(random)
                val length = random.nextInt(3, 12)
                val low = random.nextInt(Rank.FOUR.ordinal, Rank.ACE.ordinal - length + 2)
                val ranks = (low until low + length).map { Rank.entries[it] }
                ranks.map { Card(it, suit, random.nextInt(2)) }
            } else {
                val rank = Rank.entries.filter { it >= Rank.FOUR }.random(random)
                pool.filter { it.rank == rank }.take(random.nextInt(3, 9))
            }
            // às vezes troca uma carta natural por um coringa
            val withWild = if (random.nextBoolean()) {
                val i = random.nextInt(candidate.size)
                candidate.toMutableList().also { it[i] = pool.first { c -> c.isWild } }
            } else {
                candidate
            }
            Meld.create(withWild.shuffled(random)).let { if (it is RuleResult.Ok) return it.value }
        }
    }

    private fun assertValid(m: Meld) {
        // §6.3 nunca mais de 1 coringa; §6.2 nunca um 3; ≥3 cartas
        (m.cards.count { it.isWild } <= 1) shouldBe true
        m.cards.none { it.rank.isThree } shouldBe true
        (m.cards.size >= 3) shouldBe true
        m.cards.toSet().size shouldBe m.cards.size
        // recriar a partir das cartas dá o mesmo conjunto (mesmo tipo e mesma posição do coringa)
        Meld.create(m.cards).shouldBeOk() shouldBe m
    }

    @Test
    fun `conjuntos validos continuam validos apos acrescimos validos`(): Unit = runBlocking {
        // §6.1/§6.2/§6.3 invariantes do conjunto; acréscimos (§4.3) e coringa que corre (§6.3)
        var accepted = 0
        var withWild = 0
        checkAll(300, Arb.long()) { seed ->
            val random = Random(seed)
            var m = randomMeld(random)
            assertValid(m)
            repeat(20) {
                val remaining = Deck.standard() - m.cards.toSet()
                // privilegia cartas relevantes (mesmo naipe/número ou coringa) para exercitar acréscimos válidos
                val relevant = remaining.filter { card ->
                    card.isWild || when (val kind = m.kind) {
                        is MeldKind.Sequence -> card.suit == kind.suit
                        is MeldKind.Group -> card.rank == kind.rank
                    }
                }
                val source = if (random.nextInt(5) == 0) remaining else relevant
                val pick = source.shuffled(random).take(random.nextInt(1, 3))
                val result = m.add(pick)
                if (result is RuleResult.Ok) {
                    m = result.value
                    assertValid(m)
                    accepted++
                    if (m.hasWild) withWild++
                }
            }
        }
        // o teste não é vácuo: muitos acréscimos foram aceitos, inclusive com coringa
        (accepted > 500) shouldBe true
        (withWild > 100) shouldBe true
    }

    @Test
    fun `Meld faz ida e volta em JSON`(): Unit = runBlocking {
        checkAll(100, Arb.long()) { seed ->
            val m = randomMeld(Random(seed))
            Json.decodeFromString(Meld.serializer(), Json.encodeToString(Meld.serializer(), m)) shouldBe m
        }
    }
}
