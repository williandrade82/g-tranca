package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DiscardTest {

    private fun playing(handText: String) = round {
        hand(0, handText)
        hand(1, "9C 9D")
        phase = Phase.PLAYING
    }

    @Test
    fun `descarta-se somente carta da mao`() {
        // §8 descarte de uma carta da mão
        playing("KS QD").check(0, discardCard("JH")) shouldFailWith ActionError.CARD_NOT_IN_HAND
    }

    @Test
    fun `coringa e 3 preto podem ser descartados`() {
        // §8 coringas e 3 pretos podem ser descartados
        val s = playing("2C 3S KS")
        s.check(0, discardCard("2C")).shouldBeOk()
        s.check(0, discardCard("3S")).shouldBeOk()
        s.act(0, discardCard("2C")).discardPile.last() shouldBe c("2C")
    }

    @Test
    fun `3 vermelho nao pode ser descartado`() {
        // §6.5 / §8 o 3 vermelho não pode ser descartado (cenário artificial: nunca fica na mão)
        playing("3H KS").check(0, discardCard("3H")) shouldFailWith ActionError.CANNOT_DISCARD_RED_THREE
    }

    @Test
    fun `nao se descarta a ultima carta sem morto disponivel nem batida`() {
        // §8 ficar sem cartas só se resultar em morto ou batida; §9.5 sem morto disponível
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            phase = Phase.PLAYING
        }
        s.check(0, discardCard("KS")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
    }

    @Test
    fun `nao se baixa todas as cartas sem morto disponivel nem batida`() {
        // §8 / §9.5 baixar tudo também é ficar sem cartas
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 7H")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
    }

    @Test
    fun `nao se pega o lixo ficando sem cartas sem morto disponivel nem batida`() {
        // §8 / §9.5 vale para qualquer ação que esvazie a mão
        val s = round {
            hand(0, "7S 7D")
            hand(1, "9C 9D")
            discard("7H")
            mortoTaken(0, 1)
            mortoTaken(1, 1)
        }
        s.check(0, takeNew("7S 7D")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
    }
}
