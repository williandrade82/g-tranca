package com.gtranca.ui.game

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * §3.6, bug relatado 2026-11-14: a faixa verde da barra de palpite (`DealGuessGauge`) só aparecia quando o
 * indicador estava **abaixo** do 1º toque (`stage >= start`). Causa: `DealGuessModal` pré-calculava
 * `lo = minOf(start, stage)` para os campos de texto e passava esse `lo` (sempre `<= stage`, por construção) como
 * se fosse o toque bruto para a barra — então, quando o indicador voltava a subir acima do toque inicial
 * (`stage < start`), `lo` degenerava para `== stage` a cada recomposição (`minOf(start, stage) == stage` nesse
 * caso) e a faixa nunca tinha altura. `dealGuessBand` corrige isso tomando `start` sempre bruto, nos dois sentidos.
 */
class DealGuessGaugeTest {

    @Test
    fun `§3_6 sem toque ainda, a faixa nao existe`() {
        dealGuessBand(start = null, stage = 10, totalStages = 100).shouldBeNull()
    }

    @Test
    fun `§3_6 a faixa verde tem altura com o indicador abaixo do toque (stage maior que start)`() {
        val band = dealGuessBand(start = 10, stage = 30, totalStages = 100)!!
        band.first shouldBe 0.10f
        band.second shouldBe 0.30f
        (band.second > band.first) shouldBe true
    }

    @Test
    fun `§3_6 BUG a faixa verde tambem tem altura com o indicador acima do toque (stage menor que start)`() {
        // Regressão: antes da correção, o chamador passava `lo = minOf(start, stage)` no lugar de `start` — nesse
        // sentido `lo` sempre valia `stage`, então a faixa calculada a partir dele tinha altura zero.
        val band = dealGuessBand(start = 30, stage = 10, totalStages = 100)!!
        band.first shouldBe 0.10f
        band.second shouldBe 0.30f
        (band.second > band.first) shouldBe true
    }

    @Test
    fun `§3_6 totalStages zero nao quebra (sem faixa)`() {
        dealGuessBand(start = 5, stage = 5, totalStages = 0).shouldBeNull()
    }

    @Test
    fun `§3_6 indicador no mesmo estagio do toque nao desenha faixa (altura zero)`() {
        val band = dealGuessBand(start = 20, stage = 20, totalStages = 100)!!
        band.first shouldBe band.second
    }
}
