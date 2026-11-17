package com.gtranca.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 17/11/2026, bug relatado pelo usuário com prints (conjunto, "Novo jogo" e lixo todos presos na tela até tocar
 * no botão): [TableDragState.payload] usava a política de igualdade PADRÃO (estrutural) do `mutableStateOf`.
 * `onDragEnd` escreve `payload.copy(target = target)` com o MESMO alvo que `onDrag` já tinha escrito pouco antes
 * (o normal, já que o jogador está parado em cima do alvo ao soltar) — estruturalmente IGUAL ao que já estava
 * lá, então a escrita virava NO-OP e a referência antiga continuava armazenada. [TableDragState.confirmDrop]
 * decide se ainda é dono do arrasto por igualdade de REFERÊNCIA (`payload !== held`, fora do `try/finally`) —
 * com a referência velha, achava que não era mais dono e desistia antes de despachar ou limpar o overlay, que
 * ficava preso até outro arrasto sobrescrever. Fix: `referentialEqualityPolicy()`.
 */
class TableDragStateTest {

    private val card = Card(Rank.THREE, Suit.CLUBS)

    @Test
    fun `escrever um payload estruturalmente igual ainda confirma o drop`() = runTest {
        val state = TableDragState(this, { listOf(card) }, { emptySet() })
        val p = TableDragPayload(listOf(card), Rect.Zero, target = DropTarget.Discard)
        state.payload = p
        // Mesmo valor estrutural que `p` -- o caso real de `onDragEnd` (`payload.copy(target = target)` quando
        // `onDrag` já tinha escrito o MESMO alvo pouco antes).
        val held = p.copy(target = DropTarget.Discard)
        state.payload = held

        var dispatched = false
        state.confirmDrop(
            held = held,
            cards = listOf(card),
            target = DropTarget.Discard,
            topLeftFor = { Offset.Zero },
            cardPx = Size.Zero,
            anchors = null,
            stillValid = { true },
            dispatch = { dispatched = true },
        )

        advanceUntilIdle()
        dispatched shouldBe true
    }
}
