package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GoOutTest {

    @Test
    fun `batida baixando todas as cartas`() {
        // §11.1 lado com morto + canastra; fica sem cartas baixando tudo (sem descarte)
        val s = round {
            hand(0, "KS KD KC")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, create("KS KD KC"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        s.hand(0).shouldBeEmpty()
        s.discardPile.shouldBeEmpty()
    }

    @Test
    fun `canastra formada pela propria jogada que esvazia a mao conta`() {
        // §11.1 canastra avaliada depois da jogada; §7.1 6 cartas
        val s = round {
            hand(0, "9H")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H")
            phase = Phase.PLAYING
        }.act(0, addTo(0, "9H"))
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `batida descartando a ultima carta`() {
        // §11.1 fica sem cartas descartando a última
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 2C")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        s.discardPile.last() shouldBe c("KS")
    }

    @Test
    fun `batida ao pegar o lixo e baixar todas as cartas`() {
        // §11.1 baixando todas as cartas, inclusive levando o topo do lixo à mesa
        val s = round {
            hand(0, "7S 7D")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H")
            discard("7H")
        }.act(0, takeNew("7S 7D"))
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `sem canastra nao se bate`() {
        // §11.1 exige pelo menos uma canastra do lado; §8 ficar sem cartas é ilegal nos demais casos
        val s = round {
            hand(0, "KS KD KC")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H")
            phase = Phase.PLAYING
        }
        s.check(0, create("KS KD KC")) shouldFailWith ActionError.NO_CANASTA_TO_GO_OUT
        val oneCard = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            phase = Phase.PLAYING
        }
        oneCard.check(0, discardCard("KS")) shouldFailWith ActionError.NO_CANASTA_TO_GO_OUT
    }

    @Test
    fun `canastra do outro lado nao serve para bater`() {
        // §11.1 seu lado tem pelo menos uma canastra
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(1, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }
        s.check(0, discardCard("KS")) shouldFailWith ActionError.NO_CANASTA_TO_GO_OUT
    }

    @Test
    fun `partida encerrada nao aceita acoes`() {
        // §11 a batida encerra a partida
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.check(1, Action.DrawFromStock) shouldFailWith ActionError.ROUND_FINISHED
        s.legalActions(0).shouldBeEmpty()
        s.legalActions(1).shouldBeEmpty()
    }
}
