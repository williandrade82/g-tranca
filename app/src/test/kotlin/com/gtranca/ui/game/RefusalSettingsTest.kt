package com.gtranca.ui.game

import com.gtranca.data.RefusalAlert
import com.gtranca.engine.model.ActionError
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Perfil, "Ações impedidas" (docs/telas.md 3.6 e 4): o que a mesa faz com cada mensagem de jogada recusada. */
class RefusalSettingsTest {
    private val rejected = UiMessage.Rejected(ActionError.MUST_DRAW_FIRST)

    @Test
    fun `explicar sempre abre o modal`() {
        RefusalAlert.EXPLAIN.handling(rejected) shouldBe MessageHandling.MODAL
        RefusalAlert.EXPLAIN.handling(UiMessage.SelectOneCardToDiscard) shouldBe MessageHandling.MODAL
    }

    @Test
    fun `aviso discreto mostra so a faixa`() {
        RefusalAlert.SUBTLE.handling(rejected) shouldBe MessageHandling.SNACK
        RefusalAlert.SUBTLE.handling(UiMessage.SelectOneCardToDiscard) shouldBe MessageHandling.SNACK
    }

    @Test
    fun `nao avisar nao mostra nada`() {
        RefusalAlert.NONE.handling(rejected) shouldBe MessageHandling.HIDDEN
        RefusalAlert.NONE.handling(UiMessage.SelectOneCardToDiscard) shouldBe MessageHandling.HIDDEN
    }

    @Test
    fun `mostre suas cartas nao e recusa de regra e sempre aparece como faixa`() {
        RefusalAlert.entries.forEach { it.handling(UiMessage.ShowCardsToPlay) shouldBe MessageHandling.SNACK }
    }
}
