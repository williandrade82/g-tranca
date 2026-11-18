package com.gtranca.ui.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 17/11/2026, arrastar dentro da mão move a seleção inteira junto, na ordem da mão: [blockMove] é a função pura
 * usada tanto pra uma carta só (bloco de 1) quanto pra 2+ cartas, não precisando estar juntas antes. */
class BlockMoveTest {

    private fun card(rank: Int, suit: Suit = Suit.HEARTS) = Card(Rank.entries[rank], suit)

    private val a = card(0)
    private val b = card(1)
    private val c = card(2)
    private val d = card(3)
    private val e = card(4)
    private val order = listOf(a, b, c, d, e)

    @Test
    fun `bloco de 1 move so a carta tocada, igual a mover uma unica carta`() {
        blockMove(order, setOf(c), insertAt = 0) shouldBe listOf(c, a, b, d, e)
        blockMove(order, setOf(a), insertAt = 4) shouldBe listOf(b, c, d, e, a)
    }

    @Test
    fun `selecao nao contigua fica junta no destino, na ordem da mao`() {
        // B e D selecionados (não contíguos); soltar perto de B junta os dois ali.
        blockMove(order, setOf(b, d), insertAt = 1) shouldBe listOf(a, b, d, c, e)
    }

    @Test
    fun `soltar na propria posicao nao muda nada, para uma selecao ja contigua`() {
        blockMove(order, setOf(b, c), insertAt = 1) shouldBe order
        blockMove(order, setOf(b, c), insertAt = 2) shouldBe order
    }

    @Test
    fun `insere no inicio`() {
        blockMove(order, setOf(d, e), insertAt = 0) shouldBe listOf(d, e, a, b, c)
    }

    @Test
    fun `insere no fim`() {
        blockMove(order, setOf(a, b), insertAt = 4) shouldBe listOf(c, d, e, a, b)
    }

    @Test
    fun `indice fora da faixa e recortado`() {
        blockMove(order, setOf(a), insertAt = -5) shouldBe blockMove(order, setOf(a), insertAt = 0)
        blockMove(order, setOf(a), insertAt = 50) shouldBe blockMove(order, setOf(a), insertAt = 4)
    }

    @Test
    fun `selecao vazia ou mao vazia nao muda nada`() {
        blockMove(order, emptySet(), insertAt = 2) shouldBe order
        blockMove(emptyList(), setOf(a), insertAt = 0) shouldBe emptyList()
    }
}
