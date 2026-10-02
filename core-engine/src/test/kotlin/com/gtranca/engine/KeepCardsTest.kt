package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KeepCardsTest {

    // ---------- §8 manter cartas para descartar ----------

    @Test
    fun `sem morto disponivel nao se baixa deixando so 1 carta`() {
        // §8 ex.: sem morto disponível e sem canastra, com 3 cartas não pode baixar 2 delas
        val s = round {
            hand(0, "4H 5H KS")
            hand(1, "9C 9D")
            meld(0, "6H 7H 8H")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            phase = Phase.PLAYING
        }
        s.check(0, addTo(0, "4H 5H")) shouldFailWith ActionError.MUST_KEEP_CARD_TO_DISCARD
        s.check(0, addTo(0, "5H")).shouldBeOk()
        s.legalActions(0).addPlans() shouldNotContain (0 to cards("4H 5H").toSet())
    }

    @Test
    fun `conjunto novo que deixa 1 carta e proibido sem morto nem batida`() {
        // §8 vale para baixar conjuntos
        val s = round {
            hand(0, "5H 6H 7H KS")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 7H")) shouldFailWith ActionError.MUST_KEEP_CARD_TO_DISCARD
        s.legalActions(0).filterIsInstance<Action.CreateMeld>() shouldBe emptyList()
    }

    @Test
    fun `lado com morto e sem canastra tambem precisa manter 2 cartas`() {
        // §8 ficar sem cartas não é permitido (não há batida sem canastra, §11.1)
        val s = round {
            hand(0, "5H 6H 7H KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 7H")) shouldFailWith ActionError.MUST_KEEP_CARD_TO_DISCARD
    }

    @Test
    fun `pegar o lixo deixando 1 carta e proibido sem morto nem batida`() {
        // §8 vale para pegar o lixo
        val s = round {
            hand(0, "7S 7D KS")
            hand(1, "9C 9D")
            discard("7H")
            mortoTaken(0, 1)
            mortoBecameStock(1)
        }
        s.check(0, takeNew("7S 7D")) shouldFailWith ActionError.MUST_KEEP_CARD_TO_DISCARD
        // com mais cartas no lixo, a mão fica com 2 e a jogada vale
        val bigger = round {
            hand(0, "7S 7D KS")
            hand(1, "9C 9D")
            discard("9H 7H")
            mortoTaken(0, 1)
            mortoBecameStock(1)
        }
        bigger.check(0, takeNew("7S 7D")).shouldBeOk()
    }

    @Test
    fun `deixar 1 carta e valido quando o lado ainda pode pegar morto`() {
        // §8 deixar 1 carta só é válido se ficar sem cartas for permitido: aqui resultaria em morto (§9.3)
        val s = round {
            hand(0, "5H 6H 7H KS")
            hand(1, "9C 9D")
            phase = Phase.PLAYING
        }
        val after = s.act(0, create("5H 6H 7H"))
        after.hand(0) shouldContainExactly cards("KS")
        after.check(0, discardCard("KS")).shouldBeOk()
    }

    @Test
    fun `deixar 1 carta e valido quando o lado pode bater`() {
        // §8 + §11.1 lado com morto e canastra pode ficar sem cartas
        val s = round {
            hand(0, "5S 6S 7S KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }
        s.check(0, create("5S 6S 7S")).shouldBeOk()
    }

    @Test
    fun `deixar 1 carta e valido quando a propria jogada forma a canastra`() {
        // §8 + §11.1 canastra avaliada depois da jogada
        val s = round {
            hand(0, "9H KS")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H")
            phase = Phase.PLAYING
        }
        s.check(0, addTo(0, "9H")).shouldBeOk()
    }

    // ---------- §8 última carta sem reposição / §11.2 ----------

    @Test
    fun `3 vermelho sem reposicao deixando 1 carta sem poder bater encerra sem vencedor`() {
        // §8 última carta sem reposição; §11.2 fim sem vencedor
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("3H")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.act(0, Action.DrawFromStock)
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H")
        s.hand(0) shouldContainExactly cards("KS")
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.NoWinner
    }

    @Test
    fun `3 vermelho sem reposicao com o lado sem morto encerra sem vencedor`() {
        // §8 sem morto disponível (o 3 vermelho não foi reposto) e sem morto do lado: não pode bater
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("3H")
            mortoTaken(0, 1)
            mortoBecameStock(1)
        }.act(0, Action.DrawFromStock)
        s.result shouldBe RoundResult.NoWinner
    }

    @Test
    fun `3 vermelho sem reposicao deixando 1 carta mas podendo bater segue a jogada`() {
        // §8 se puder bater descartando essa carta, a jogada segue
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("3H")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            meld(0, "4H 5H 6H 7H 8H 9H")
        }.act(0, Action.DrawFromStock)
        s.phase shouldBe Phase.PLAYING
        s.result shouldBe null
        s.act(0, discardCard("KS")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `3 vermelho sem reposicao com 2 cartas na mao segue a jogada`() {
        // §8 a regra vale só quando sobra 1 carta
        val s = round {
            hand(0, "KS QD")
            hand(1, "9C 9D")
            stock("3H")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.act(0, Action.DrawFromStock)
        s.phase shouldBe Phase.PLAYING
    }
}
