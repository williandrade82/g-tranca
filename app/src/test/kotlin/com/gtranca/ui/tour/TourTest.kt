package com.gtranca.ui.tour

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Tour guiado (docs/telas.md 3.16): navegação e quando a mesa o mostra sozinha. */
class TourTest {
    @Test
    fun `o balao do tour tem contraste AA entre texto e fundo`() {
        (TourColors.contrast(TourColors.text, TourColors.background) >= 4.5) shouldBe true
    }

    @Test
    fun `Proximo, Voltar e o fim do tour`() {
        var state: TourState? = TourState(3)
        state!!.back() shouldBe TourState(3, 0) // no primeiro, Voltar fica nele
        state = state.next()
        state shouldBe TourState(3, 1)
        state!!.back() shouldBe TourState(3, 0)
        state = state.next()!!
        state.isLast shouldBe true
        state.next() shouldBe null // Concluir fecha
    }

    @Test
    fun `a mesa so mostra o tour na vez do jogador, depois das animacoes iniciais`() {
        Tours.tableReady(isHumanTurn = true, dealPending = false, animating = false, swapShowing = false, endScreen = false) shouldBe true
        Tours.tableReady(isHumanTurn = false, dealPending = false, animating = false, swapShowing = false, endScreen = false) shouldBe false
        Tours.tableReady(isHumanTurn = true, dealPending = true, animating = false, swapShowing = false, endScreen = false) shouldBe false
        Tours.tableReady(isHumanTurn = true, dealPending = false, animating = true, swapShowing = false, endScreen = false) shouldBe false
        Tours.tableReady(isHumanTurn = true, dealPending = false, animating = false, swapShowing = true, endScreen = false) shouldBe false
        Tours.tableReady(isHumanTurn = true, dealPending = false, animating = false, swapShowing = false, endScreen = true) shouldBe false
    }

    @Test
    fun `os passos apontam para elementos distintos e incluem o icone do tour`() {
        listOf(Tours.tablesSteps, Tours.tableSteps).forEach { steps ->
            steps.map { it.target }.distinct().size shouldBe steps.size
            steps.map { it.target } shouldContain Tours.BUTTON
        }
    }

    @Test
    fun `os passos seguem a tela de cima para baixo`() {
        Tours.tablesSteps.map { it.target } shouldBe listOf(Tours.BUTTON, "tables-wifi", "participants", "table-options-panel", "choose-seats")
        Tours.tableSteps.map { it.target } shouldBe listOf(
            "score", Tours.BUTTON, "table-options", "seats", "stock", "mortos", "discard-pile", "hand", "action-bar",
        )
    }
}
