package com.gtranca.ui.profile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.1 recorte da foto do perfil: quadrado central e redução por amostragem. */
class SquareCropTest {

    @Test
    fun `§14_1 recorte quadrado a partir do centro`() {
        SquareCrop.center(4000, 3000) shouldBe SquareCrop(500, 0, 3000)
        SquareCrop.center(3000, 4000) shouldBe SquareCrop(0, 500, 3000)
        SquareCrop.center(512, 512) shouldBe SquareCrop(0, 0, 512)
        SquareCrop.center(101, 100) shouldBe SquareCrop(0, 0, 100)
    }

    @Test
    fun `§14_1 amostragem nunca deixa o menor lado abaixo do alvo`() {
        SquareCrop.sampleSize(4000, 3000, 512) shouldBe 4
        SquareCrop.sampleSize(1024, 1024, 512) shouldBe 2
        SquareCrop.sampleSize(1023, 2000, 512) shouldBe 1
        SquareCrop.sampleSize(300, 300, 512) shouldBe 1
    }
}
