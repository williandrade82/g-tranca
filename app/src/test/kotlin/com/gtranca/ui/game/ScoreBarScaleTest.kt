package com.gtranca.ui.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §17.11.1 escala da barra de progressão do b4 (lados por `Side.index`: 0 e 1). */
class ScoreBarScaleTest {

    @Test
    fun `§17_11_1 comeca em 500 sem marcos`() {
        val s = ScoreBarScale()
        s.max shouldBe 500
        s.marks shouldBe emptyList()
    }

    @Test
    fun `§17_11_1 chegar a 500 leva a 750`() {
        val s = ScoreBarScale().advance(listOf(-100, 500))
        s.max shouldBe 750
        s.marks shouldBe listOf(500)
    }

    @Test
    fun `§17_11_1 chegar a 750 leva a 1000`() {
        val s = ScoreBarScale().advance(listOf(750, 20))
        s.max shouldBe 1000
        s.marks shouldBe listOf(500, 750)
    }

    @Test
    fun `§17_11_1 1000 so muda ao passar e depois acompanha o maior`() {
        ScoreBarScale().advance(listOf(1000, 0)).level shouldBe 2
        val s = ScoreBarScale().advance(listOf(1150, 900))
        s.level shouldBe 3
        s.max shouldBe 1150
        s.marks shouldBe listOf(500, 750, 1000)
        s.advance(listOf(1600, 0)).max shouldBe 1600
    }

    @Test
    fun `§17_11_1 a escala nunca volta`() {
        val s = ScoreBarScale().advance(listOf(1300, 0)).advance(listOf(200, -100))
        s.level shouldBe 3
        s.max shouldBe 1300
        ScoreBarScale().advance(listOf(600, 0)).advance(listOf(-95, 0)).max shouldBe 750
    }

    @Test
    fun `§17_11_1 estrela so no lado que alcancou o marco, linha fina no outro`() {
        val s = ScoreBarScale().advance(listOf(800, 520))
        s.hasStar(0, 500) shouldBe true
        s.hasStar(0, 750) shouldBe true
        s.hasStar(1, 500) shouldBe true
        s.hasStar(1, 750) shouldBe false
    }

    @Test
    fun `§17_11_1 a estrela fica mesmo se o lado cair depois`() {
        val s = ScoreBarScale().advance(listOf(0, 600)).advance(listOf(0, 100))
        s.hasStar(1, 500) shouldBe true
        s.hasStar(0, 500) shouldBe false
    }

    @Test
    fun `§17_11_1 negativo ocupa a metade pelo valor absoluto e satura`() {
        val s = ScoreBarScale()
        s.fraction(-100) shouldBe 0.2f
        s.fraction(250) shouldBe 0.5f
        s.fraction(-900) shouldBe 1f
    }

    @Test
    fun `§17_11_2 barra do jogo vai de 0 ao alvo, sem negativos, cheia acima do alvo`() {
        ScoreBarScale.gameFraction(500, 2000) shouldBe 0.25f
        ScoreBarScale.gameFraction(-150, 2000) shouldBe 0f
        ScoreBarScale.gameFraction(2600, 2000) shouldBe 1f
    }

    @Test
    fun `§17_11_1 marcacoes escorregam quando o teto sobe`() {
        ScoreBarScale().advance(listOf(500, 0)).markFraction(500) shouldBe 500f / 750f
        ScoreBarScale().advance(listOf(750, 0)).markFraction(750) shouldBe 0.75f
    }
}
