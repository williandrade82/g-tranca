package com.gtranca.net

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §16.15 qualidade da conexão do convidado com o anfitrião (1 a 3 barras). */
class LinkQualityTest {
    @Test
    fun `classificacao pelos limiares de tempo e reenvios`() {
        LinkQuality.classify(null, 0) shouldBe 3
        LinkQuality.classify(120.0, 0) shouldBe 3
        LinkQuality.classify(500.0, 0) shouldBe 3
        LinkQuality.classify(300.0, 1) shouldBe 2
        LinkQuality.classify(900.0, 0) shouldBe 2
        LinkQuality.classify(1_500.0, 2) shouldBe 2
        LinkQuality.classify(1_600.0, 0) shouldBe 1
        LinkQuality.classify(200.0, 3) shouldBe 1
    }

    @Test
    fun `media movel e reenvios que se recuperam com medidas boas`() {
        val meter = LinkQualityMeter()
        meter.level() shouldBe 3
        meter.onRtt(100)
        meter.level() shouldBe 3
        repeat(3) { meter.onResend() }
        meter.level() shouldBe 1
        repeat(3) { meter.onRtt(100) }
        meter.level() shouldBe 3
        repeat(10) { meter.onRtt(2_000) }
        meter.level() shouldBe 1
    }
}
