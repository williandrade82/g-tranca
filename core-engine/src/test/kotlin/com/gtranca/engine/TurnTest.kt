package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TurnTest {

    private val start = round {
        hand(0, "5H 6H 7H KS")
        hand(1, "9C TC JC QD")
        stock("8H 4S 4D")
    }

    @Test
    fun `comprar do monte leva o topo do monte para a mao e libera a jogada`() {
        // §4.3 etapa 1: comprar uma carta do monte
        val s = start.act(0, Action.DrawFromStock)
        s.hand(0) shouldContainExactlyInAnyOrder cards("5H 6H 7H KS 8H")
        s.stock shouldContainExactly cards("4S 4D")
        s.phase shouldBe Phase.PLAYING
        s.currentSeat shouldBe Seat(0)
    }

    @Test
    fun `nao se baixa nem descarta antes de comprar`() {
        // §4.3 a compra vem primeiro
        start.check(0, create("5H 6H 7H")) shouldFailWith ActionError.MUST_DRAW_FIRST
        start.check(0, discardCard("KS")) shouldFailWith ActionError.MUST_DRAW_FIRST
        start.check(0, addTo(0, "8H")) shouldFailWith ActionError.MUST_DRAW_FIRST
    }

    @Test
    fun `nao se compra duas vezes`() {
        // §4.3 uma compra por jogada
        val s = start.act(0, Action.DrawFromStock)
        s.check(0, Action.DrawFromStock) shouldFailWith ActionError.ALREADY_DREW
    }

    @Test
    fun `so o jogador da vez age`() {
        // §4.2/§4.3 jogadas em ordem
        start.check(1, Action.DrawFromStock) shouldFailWith ActionError.NOT_YOUR_TURN
    }

    @Test
    fun `apply lanca excecao para acao invalida`() {
        // legalActions/validate são a única fonte de validade; apply rejeita o resto
        shouldThrow<IllegalArgumentException> { start.act(1, Action.DrawFromStock) }
    }

    @Test
    fun `baixar conjunto tira as cartas da mao e poe na mesa do lado`() {
        // §4.3 etapa 2: baixar novos conjuntos
        val s = start.act(0, Action.DrawFromStock).act(0, create("5H 6H 7H"))
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS 8H")
        s.table(0).melds shouldHaveSize 1
        s.table(0).meld(MeldId(0))!!.cards shouldContainExactly cards("5H 6H 7H")
        s.table(1).melds shouldHaveSize 0
    }

    @Test
    fun `acrescentar carta a conjunto do lado`() {
        // §4.3 etapa 2: acrescentar cartas a conjuntos do seu lado
        val s = start.act(0, Action.DrawFromStock).act(0, create("5H 6H 7H")).act(0, addTo(0, "8H"))
        s.table(0).meld(MeldId(0))!!.cards shouldContainExactly cards("5H 6H 7H 8H")
        s.hand(0) shouldContainExactly cards("KS")
    }

    @Test
    fun `so cartas da propria mao podem ser baixadas`() {
        // §4.3 baixar/acrescentar usa cartas da mão
        val s = start.act(0, Action.DrawFromStock)
        s.check(0, create("5H 6H 4H")) shouldFailWith ActionError.CARD_NOT_IN_HAND
        s.check(0, addTo(0, "9C")) shouldFailWith ActionError.CARD_NOT_IN_HAND
    }

    @Test
    fun `erros de conjunto sao propagados`() {
        // §6 regras de conjunto valem nas ações
        val s = round {
            hand(0, "5H 6H 8H 3S KS")
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 8H")) shouldFailWith MeldError.NOT_CONSECUTIVE
        s.check(0, addTo(0, "KS")) shouldFailWith MeldError.MELD_NOT_FOUND
    }

    @Test
    fun `descarte encerra a jogada e passa a vez no sentido horario`() {
        // §4.2 sentido horário; §4.3 etapa 3; §8 descarte vai para o topo do lixo
        val s = start.act(0, Action.DrawFromStock).act(0, discardCard("KS"))
        s.discardPile shouldContainExactly cards("KS")
        s.hand(0) shouldContainExactlyInAnyOrder cards("5H 6H 7H 8H")
        s.currentSeat shouldBe Seat(1)
        s.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `em duplas a vez passa ao proximo assento e o parceiro acrescenta aos jogos do lado`() {
        // §4.2 sentido horário; §6.4 parceiros compartilham os conjuntos
        val s = round(GameMode.DUPLAS) {
            hand(3, "8H KS QS")
            hand(1, "9C 9D")
            meld(1, "5H 6H 7H")
            current = 3
            phase = Phase.PLAYING
        }
        val after = s.act(3, addTo(0, "8H")).act(3, discardCard("KS"))
        after.table(1).meld(MeldId(0))!!.cards shouldContainExactly cards("5H 6H 7H 8H")
        after.currentSeat shouldBe Seat(0)
    }

    @Test
    fun `estado inicial da distribuicao aguarda a compra do primeiro jogador`() {
        // §3.4 / §4.1 o primeiro jogador começa comprando
        val s = dealRound(GameMode.DUPLAS, Random(4))
        s.phase shouldBe Phase.AWAITING_DRAW
        s.result shouldBe null
        RoundEngine.validate(s, s.currentSeat, Action.DrawFromStock).shouldBeOk()
    }
}
