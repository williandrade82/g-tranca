package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StockExhaustedTest {

    @Test
    fun `monte vazio com morto disponivel o morto vira monte`() {
        // §10.1 morto disponível se torna o novo monte (só um deles)
        val s = round {
            hand(0, "KS QD")
            stock("")
        }.act(0, Action.DrawFromStock)
        s.mortoStatus[0] shouldBe MortoStatus.BecameStock
        s.mortos[0].shouldBeEmpty()
        s.mortoStatus[1] shouldBe MortoStatus.Available
        s.hand(0) shouldContain c("4C'")
        s.stock shouldContainExactly cards("5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' AC'")
    }

    @Test
    fun `se o primeiro morto ja foi pego o outro vira monte`() {
        // §10.1 apenas um morto disponível vira monte
        val s = round {
            hand(0, "KS QD")
            stock("")
            mortoTaken(0, 1)
        }.act(0, Action.DrawFromStock)
        s.mortoStatus[1] shouldBe MortoStatus.BecameStock
        s.hand(0) shouldContain c("4D'")
    }

    @Test
    fun `sem monte nem morto nao se compra e pode-se pegar o lixo ou recusar`() {
        // §10.2 compra da jogada: pode pegar o lixo; se não quiser, partida termina sem vencedor
        val s = round {
            hand(0, "7S 7D KS QD")
            hand(1, "9C 9D")
            stock("")
            mortoTaken(0, 0)
            mortoBecameStock(1)
            discard("7H")
        }
        s.check(0, Action.DrawFromStock) shouldFailWith ActionError.STOCK_EXHAUSTED
        s.check(0, takeNew("7S 7D")).shouldBeOk()
        s.legalActions(0) shouldContain Action.DeclineDraw
        s.legalActions(0) shouldNotContain Action.DrawFromStock

        val ended = s.act(0, Action.DeclineDraw)
        ended.phase shouldBe Phase.FINISHED
        ended.result shouldBe RoundResult.NoWinner
    }

    @Test
    fun `recusar a compra so vale com monte e mortos esgotados`() {
        // §10.2 a recusa só existe na situação de monte esgotado sem morto disponível
        val withStock = round {
            hand(0, "7S 7D KS")
            discard("7H")
        }
        withStock.check(0, Action.DeclineDraw) shouldFailWith ActionError.DECLINE_NOT_ALLOWED
        withStock.legalActions(0) shouldNotContain Action.DeclineDraw

        val withMorto = round {
            hand(0, "7S 7D KS")
            discard("7H")
            stock("")
        }
        withMorto.check(0, Action.DeclineDraw) shouldFailWith ActionError.DECLINE_NOT_ALLOWED
    }

    @Test
    fun `fim automatico sem vencedor quando o proximo nao pode comprar nem pegar o lixo`() {
        // §10.2 / §11.2 monte esgotado, sem morto disponível e sem possibilidade de pegar o lixo
        val s = round {
            hand(0, "KS QD")
            hand(1, "4C 5C")
            stock("")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.NoWinner
        s.discardPile.last() shouldBe c("KS")
    }

    @Test
    fun `partida continua se o proximo pode pegar o lixo`() {
        // §10.2 o jogador pode pegar o lixo, se for permitido
        val s = round {
            hand(0, "KS QD")
            hand(1, "KC KH 9D 8D")
            stock("")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.phase shouldBe Phase.AWAITING_DRAW
        s.result shouldBe null
        s.legalActions(1) shouldContain Action.DeclineDraw
        s.legalActions(1).takeNewPlans() shouldContain cards("KC KH").toSet()
    }
}
