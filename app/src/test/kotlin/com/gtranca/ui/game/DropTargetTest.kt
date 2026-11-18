package com.gtranca.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gtranca.engine.model.MeldId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 16/11/2026, arrastar a seleção até a mesa: [dropTarget] só lê o que `addableMelds`/`canCreateNew`/
 * `canDiscardSelected` (todos de `legalActions`) já autorizam — nunca decide legalidade por conta própria. */
class DropTargetTest {

    private val meldRect = Rect(0f, 0f, 100f, 100f)
    private val newMeldRect = Rect(200f, 0f, 300f, 100f)
    private val discardRect = Rect(0f, 200f, 100f, 300f)
    private val viewport = Rect(0f, 0f, 400f, 400f)
    private val opponentMeldRect = Rect(150f, 150f, 250f, 250f)
    private val anchors = mapOf(
        AnimAnchor.Meld(0, 1) to meldRect,
        AnimAnchor.Meld(1, 1) to opponentMeldRect, // mesmo id (1), lado do adversário: cada lado numera a partir de 0
        AnimAnchor.NewMeldSlot(0) to newMeldRect,
        AnimAnchor.DiscardPile to discardRect,
    )

    @Test
    fun `mesmo id do adversario nao conta, mesmo com o id em addableMelds`() {
        dropTarget(opponentMeldRect.center, anchors, viewport, 0, setOf(MeldId(1)), canCreateNew = false, canDiscardSelected = false) shouldBe null
    }

    @Test
    fun `solta em cima de um conjunto addable`() {
        dropTarget(meldRect.center, anchors, viewport, 0, setOf(MeldId(1)), canCreateNew = false, canDiscardSelected = false) shouldBe
            DropTarget.Meld(1)
    }

    @Test
    fun `conjunto existe mas nao esta em addableMelds - nao e alvo`() {
        dropTarget(meldRect.center, anchors, viewport, 0, emptySet(), canCreateNew = false, canDiscardSelected = false) shouldBe null
    }

    @Test
    fun `novo jogo so e alvo com canCreateNew`() {
        dropTarget(newMeldRect.center, anchors, viewport, 0, emptySet(), canCreateNew = true, canDiscardSelected = false) shouldBe
            DropTarget.NewMeld
        dropTarget(newMeldRect.center, anchors, viewport, 0, emptySet(), canCreateNew = false, canDiscardSelected = false) shouldBe null
    }

    @Test
    fun `lixo so e alvo com canDiscardSelected`() {
        dropTarget(discardRect.center, anchors, viewport, 0, emptySet(), canCreateNew = false, canDiscardSelected = true) shouldBe
            DropTarget.Discard
        dropTarget(discardRect.center, anchors, viewport, 0, emptySet(), canCreateNew = false, canDiscardSelected = false) shouldBe null
    }

    @Test
    fun `fora de qualquer area nao e alvo`() {
        dropTarget(Offset(350f, 350f), anchors, viewport, 0, setOf(MeldId(1)), canCreateNew = true, canDiscardSelected = true) shouldBe null
    }

    @Test
    fun `ponto fora do viewport nunca e alvo, mesmo em cima de um conjunto addable`() {
        // Um conjunto rolado pra fora da área visível guarda a posição real sem recorte (comentário de
        // `Modifier.anchor`); o dedo nunca estaria ali de verdade se o ponto cai fora do viewport.
        val outside = Rect(0f, 500f, 400f, 900f)
        dropTarget(meldRect.center, anchors, outside, 0, setOf(MeldId(1)), canCreateNew = false, canDiscardSelected = false) shouldBe null
    }

    @Test
    fun `sem viewport (null) nao bloqueia`() {
        dropTarget(meldRect.center, anchors, null, 0, setOf(MeldId(1)), canCreateNew = false, canDiscardSelected = false) shouldBe
            DropTarget.Meld(1)
    }
}
