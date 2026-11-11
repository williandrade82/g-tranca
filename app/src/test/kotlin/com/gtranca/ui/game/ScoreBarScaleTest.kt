package com.gtranca.ui.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §17.11.1 escala da barra de progressão do b4. */
class ScoreBarScaleTest {

    @Test
    fun `§17_11_1 comeca em 500 sem estrelas`() {
        val s = ScoreBarScale()
        s.max shouldBe 500
        s.stars shouldBe emptyList()
    }

    @Test
    fun `§17_11_1 chegar a 500 leva a 1000 com a estrela do 500`() {
        val s = ScoreBarScale().advance(listOf(-100, 500))
        s.max shouldBe 1000
        s.stars shouldBe listOf(500)
    }

    @Test
    fun `§17_11_1 chegar a 1000 leva a 1200`() {
        val s = ScoreBarScale().advance(listOf(1000, 20))
        s.max shouldBe 1200
        s.stars shouldBe listOf(500, 1000)
    }

    @Test
    fun `§17_11_1 1200 so muda ao passar e depois acompanha o maior`() {
        ScoreBarScale().advance(listOf(1200)).level shouldBe 2
        val s = ScoreBarScale().advance(listOf(1350, 900))
        s.level shouldBe 3
        s.max shouldBe 1350
        s.stars shouldBe listOf(500, 1000, 1200)
        s.advance(listOf(1600)).max shouldBe 1600
    }

    @Test
    fun `§17_11_1 a escala nunca volta`() {
        val s = ScoreBarScale().advance(listOf(1300)).advance(listOf(200, -100))
        s.level shouldBe 3
        s.max shouldBe 1300
        ScoreBarScale().advance(listOf(600)).advance(listOf(-95)).max shouldBe 1000
    }

    @Test
    fun `§17_11_1 negativo ocupa a metade pelo valor absoluto e satura`() {
        val s = ScoreBarScale()
        s.fraction(-100) shouldBe 0.2f
        s.fraction(250) shouldBe 0.5f
        s.fraction(-900) shouldBe 1f
    }

    @Test
    fun `§17_11_1 estrelas escorregam quando o teto sobe`() {
        ScoreBarScale().advance(listOf(500)).starFraction(500) shouldBe 0.5f
        ScoreBarScale().advance(listOf(1000)).starFraction(1000) shouldBe 1000f / 1200f
    }
}
