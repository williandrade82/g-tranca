package com.gtranca.engine

import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Cenários reproduzidos pelo rules-auditor (C1, I1, I2). */
class AuditFindingsTest {

    // ---------- C1: §10.2 / §11.2 fim sem vencedor só se não existir NENHUM plano válido de lixo ----------

    @Test
    fun `C1 nao encerra sem vencedor se o proximo pode pegar o lixo com o coringa correndo`() {
        // §10.2 o jogador pode pegar o lixo; §5.1 acréscimo com carta da mão; §6.3 coringa corre
        val s = round {
            hand(0, "7H KS QD JC")
            hand(1, "9H 4C")
            meld(0, "5H 6H 2C")
            stock("")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            current = 1
            phase = Phase.PLAYING
        }.act(1, discardCard("9H"))
        s.phase shouldBe Phase.AWAITING_DRAW
        s.result shouldBe null
        s.check(0, takeAdd(0, "7H")).shouldBeOk()
        s.legalActions(0).takeAddPlans() shouldContain (0 to cards("7H").toSet())
    }

    @Test
    fun `C1 nao encerra sem vencedor se o proximo pode pegar o lixo e bater`() {
        // §10.2 + §5.1 + §11.1 pegar o lixo acrescentando topo e a última carta da mão, batendo
        val s = round {
            hand(0, "9H")
            hand(1, "8H 4C")
            meld(0, "4H 5H 6H 7H")
            meld(0, "4S 5S 6S 7S 8S 9S TS")
            stock("")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            current = 1
            phase = Phase.PLAYING
        }.act(1, discardCard("8H"))
        s.phase shouldBe Phase.AWAITING_DRAW
        s.legalActions(0).takeAddPlans() shouldContain (0 to cards("9H").toSet())
        s.act(0, takeAdd(0, "9H")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    // ---------- I1: §5.1 planos de lixo válidos listados ----------

    @Test
    fun `I1 plano de lixo em que o coringa do conjunto corre e listado`() {
        // §5.1 + §6.3 mesa 5-6-W(7)♥, topo 9♥, mão com 7♥: 5-6-7-W(8)-9
        val s = round {
            hand(0, "7H KS QD")
            meld(0, "5H 6H 2C")
            discard("9H")
        }
        s.check(0, takeAdd(0, "7H")).shouldBeOk()
        s.legalActions(0).takeAddPlans() shouldContain (0 to cards("7H").toSet())
    }

    @Test
    fun `I1 plano de lixo que precisa de mais cartas que o minimo e listado`() {
        // §5.1 + §11.1 lado com morto sem canastra: 4-5-6-7♥ + topo 8♥ + 9♥ 10♥ forma a canastra de 7 e bate
        val s = round {
            hand(0, "9H TH")
            meld(0, "4H 5H 6H 7H")
            mortoTaken(0, 0)
            discard("8H")
        }
        s.legalActions(0).takeAddPlans() shouldContain (0 to cards("9H TH").toSet())
        s.act(0, takeAdd(0, "9H TH")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    // ---------- I2: §8 + §11.1 batida formando a canastra com as últimas cartas ----------

    @Test
    fun `I2 acrescimo de varias cartas que forma a canastra e bate e listado`() {
        // §11.1 + §8 lado com morto sem canastra; 4-5-6-7♥ + 8♥ 9♥ 10♥ = canastra de 7 e mão vazia
        val s = round {
            hand(0, "8H 9H TH")
            hand(1, "KC KD")
            meld(0, "4H 5H 6H 7H")
            mortoTaken(0, 0)
            phase = Phase.PLAYING
        }
        s.legalActions(0).addPlans() shouldContain (0 to cards("8H 9H TH").toSet())
        s.act(0, addTo(0, "8H 9H TH")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `I2 conjunto novo de 7 cartas que bate e listado`() {
        // §11.1 + §7.1 canastra de 7 baixada de uma vez esvaziando a mão
        val s = round {
            hand(0, "4S 5S 6S 7S 8S 9S TS")
            hand(1, "KC KD")
            mortoTaken(0, 0)
            phase = Phase.PLAYING
        }
        s.legalActions(0).createPlans() shouldContain cards("4S 5S 6S 7S 8S 9S TS").toSet()
        s.act(0, create("4S 5S 6S 7S 8S 9S TS")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }
}
