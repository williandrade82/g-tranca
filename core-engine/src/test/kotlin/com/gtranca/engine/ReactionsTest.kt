package com.gtranca.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §17.8 b3 Reações: as cinco reações e os 3 usos por partida. */
class ReactionsTest {
    @Test
    fun `§17_8 sao cinco reacoes - Aplausos, Amei, Pensativo, Nervoso e Frango`() {
        Reaction.entries.map { it.code } shouldBe listOf("clap", "love", "tired", "angry", "chicken")
        Reaction.ofCode("love") shouldBe Reaction.LOVE
        Reaction.ofCode("nope") shouldBe null
    }

    @Test
    fun `§17_8 cada partida com o b3 da 3 reacoes de qualquer tipo`() {
        Reactions.USES_PER_ROUND shouldBe 3
        (0..3).map { Reactions.remaining(it) } shouldBe listOf(3, 2, 1, 0)
        Reactions.remaining(7) shouldBe 0
    }

    @Test
    fun `§17_8 so envia com o b3 na partida e com usos restantes`() {
        Reactions.canUse(listOf("b3"), 0) shouldBe true
        Reactions.canUse(listOf("b1", "b3"), 2) shouldBe true
        Reactions.canUse(listOf("b3"), 3) shouldBe false
        Reactions.canUse(listOf("b1", "b2"), 0) shouldBe false
        Reactions.canUse(emptyList(), 0) shouldBe false
    }
}
