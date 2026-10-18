package com.gtranca.ui.game

import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PileLayoutTest {

    @Test
    fun `§5_6 lixo que cabe usa o passo normal`() {
        pileOffsets(3, available = 300f, cardWidth = 40f, maxStep = 18f) shouldBe listOf(0f, 18f, 36f)
    }

    @Test
    fun `§5_6 lixo cheio espreme as cartas, mais as antigas, e a ultima fica inteira na linha`() {
        for (count in listOf(10, 20, 40, 80)) {
            val offsets = pileOffsets(count, available = 300f, cardWidth = 40f, maxStep = 18f)
            offsets.size shouldBe count
            // Todas à vista e na ordem; a última inteira (borda direita dentro da linha).
            (offsets.last() + 40f).shouldBeLessThanOrEqual(300f + 0.01f)
            val gaps = offsets.zipWithNext { a, b -> b - a }
            gaps.all { it > 0f } shouldBe true
            // O espaço só cresce das mais antigas para as mais novas.
            gaps.zipWithNext().all { (a, b) -> a <= b } shouldBe true
            gaps.all { it <= 18f * 100 } shouldBe true
        }
    }

    @Test
    fun `§5_6 lixo vazio ou de uma carta`() {
        pileOffsets(0, 300f, 40f, 18f) shouldBe emptyList()
        pileOffsets(1, 300f, 40f, 18f) shouldBe listOf(0f)
    }
}
