package com.gtranca.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CountingRandomTest {

    private fun sample(r: Random) = List(40) { listOf(r.nextInt(), r.nextInt(7), r.nextLong(), r.nextDouble(), r.nextBoolean()) }

    @Test
    fun `sorteia exatamente o mesmo que Random(seed) (o sim e o app continuam iguais)`() {
        sample(CountingRandom(42)) shouldBe sample(Random(42))
    }

    @Test
    fun `retomado com o contador salvo continua de onde parou`() {
        val original = CountingRandom(7)
        sample(original)
        val resumed = CountingRandom(7, skip = original.calls)
        resumed.calls shouldBe original.calls
        sample(resumed) shouldBe sample(original)
    }
}
