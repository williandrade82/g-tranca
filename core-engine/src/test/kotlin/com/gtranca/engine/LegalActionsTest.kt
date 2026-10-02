package com.gtranca.engine

import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test

class LegalActionsTest {

    private fun com.gtranca.engine.model.RoundState.allLegalValidate(seat: Int) =
        legalActions(seat).forEach { check(seat, it).shouldBeOk() }

    @Test
    fun `fora da vez nao ha acoes`() {
        round { hand(0, "KS QD"); hand(1, "9C 9D") }.legalActions(1).shouldBeEmpty()
    }

    @Test
    fun `com lixo vazio a unica acao e comprar`() {
        // §3.4 / §5.5 lixo vazio: compra obrigatoriamente do monte
        round { hand(0, "KS QD") }.legalActions(0) shouldContainExactly listOf(Action.DrawFromStock)
    }

    @Test
    fun `lista planos minimos para pegar o lixo`() {
        // §5.1 conjunto novo com topo + 2 cartas da mão; acréscimo do topo a conjunto do lado
        val s = round {
            hand(0, "7S 7D 2C KS")
            meld(0, "4H 5H 6H")
            discard("9C 7H")
        }
        val legal = s.legalActions(0)
        legal shouldContain Action.DrawFromStock
        legal.takeAddPlans() shouldContain (0 to emptySet())
        legal.takeNewPlans() shouldContain cards("7S 7D").toSet()
        legal.filterIsInstance<Action.CreateMeld>().shouldBeEmpty()
        s.allLegalValidate(0)
    }

    @Test
    fun `lista acrescimo do topo que precisa de cartas da mao`() {
        // §5.1 acréscimo do topo junto com cartas da mão
        val s = round {
            hand(0, "7H 8H KS")
            meld(0, "4H 5H 6H")
            discard("9H")
        }
        s.legalActions(0).takeAddPlans() shouldContain (0 to cards("7H 8H").toSet())
        s.allLegalValidate(0)
    }

    @Test
    fun `lista conjunto novo do lixo valido so com 3 cartas da mao`() {
        // §5.1 + §6.4: 7-8-9♥ seria continuação de 4-5-6♥; 6♥'-7-8-9♥ é permitido
        val s = round {
            hand(1, "6H' 7H 8H KS")
            meld(1, "4H 5H 6H")
            discard("9H")
            current = 1
        }
        s.check(1, takeNew("7H 8H")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
        s.legalActions(1).takeNewPlans() shouldContain cards("6H' 7H 8H").toSet()
        s.allLegalValidate(1)
    }

    @Test
    fun `na jogada lista conjuntos de 3, acrescimos de 1 carta e descartes`() {
        // §4.3 etapa 2 e 3
        val s = round {
            hand(0, "5H 6H 7H 7S 7D 2C KS")
            meld(0, "9C TC JC")
            phase = Phase.PLAYING
        }
        val legal = s.legalActions(0)
        legal.createPlans() shouldContain cards("5H 6H 7H").toSet()
        legal.createPlans() shouldContain cards("7H 7S 7D").toSet()
        legal.addPlans() shouldContain (0 to cards("2C").toSet())
        legal.filterIsInstance<Action.Discard>().map { it.card } shouldContainExactlyInAnyOrder
            cards("5H 6H 7H 7S 7D 2C KS")
        legal shouldNotContain Action.DrawFromStock
        s.allLegalValidate(0)
    }

    @Test
    fun `acoes que esvaziariam a mao ilegalmente nao sao listadas`() {
        // §8 / §9.5
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            phase = Phase.PLAYING
        }
        s.legalActions(0).filterIsInstance<Action.CreateMeld>().shouldBeEmpty()
    }
}
