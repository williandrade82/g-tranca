package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * §9.5 / §11.1: sem morto disponível (um foi pego pelo outro lado e o outro virou monte), o lado que ainda não pegou
 * morto pode bater, se tiver canastra. A batida soma os +100 (§12.1) e o lado ainda perde os −100 do morto (§12.2).
 */
class NoMortoGoOutTest {

    /** Sem morto disponível para o lado 0: o morto 0 foi pego pelo lado 1 e o morto 1 virou monte. */
    private fun RoundStateBuilder.noMortoAvailable() {
        mortoTaken(0, 1)
        mortoBecameStock(1)
    }

    @Test
    fun `§9_5 sem morto disponivel e com canastra o lado bate baixando todas as cartas`() {
        val s = round {
            hand(0, "KS KD KC")
            hand(1, "9C 9D")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, create("KS KD KC"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        s.hand(0).shouldBeEmpty()
    }

    @Test
    fun `§9_5 sem morto disponivel e com canastra o lado bate descartando a ultima carta`() {
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 2C")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        s.discardPile.last() shouldBe c("KS")
    }

    @Test
    fun `§9_5 sem morto disponivel e com canastra o lado bate ao pegar o lixo e baixar tudo`() {
        val s = round {
            hand(0, "7S 8S")
            hand(1, "9C 9D")
            discard("6S")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.AWAITING_DRAW
        }
        // O topo (6♠) vai ao conjunto novo 6♠ 7♠ 8♠ e a mão fica vazia: batida.
        val result = s.act(0, takeNew("7S 8S"))
        result.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        result.hand(0).shouldBeEmpty()
    }

    @Test
    fun `§9_5 a canastra formada pela propria jogada tambem permite bater sem morto`() {
        val s = round {
            hand(0, "9H")
            hand(1, "9C 9D")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H")
            phase = Phase.PLAYING
        }.act(0, addTo(0, "9H"))
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `§9_5 sem morto disponivel e sem canastra o jogador continua sem poder ficar sem cartas`() {
        val baixar = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            noMortoAvailable()
            phase = Phase.PLAYING
        }
        baixar.check(0, create("5H 6H 7H")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
        val descartar = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            noMortoAvailable()
            phase = Phase.PLAYING
        }
        descartar.check(0, discardCard("KS")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
    }

    @Test
    fun `§9_5 havendo morto disponivel ele continua sendo pego antes de qualquer batida`() {
        // O lado tem canastra, mas ainda há morto: ficar sem cartas é pegar o morto (§9.2), não bater.
        val s = round {
            hand(0, "KS KD KC")
            hand(1, "9C 9D")
            mortoTaken(0, 1) // o morto 1 continua disponível
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, create("KS KD KC"))
        s.phase shouldBe Phase.PLAYING
        s.result shouldBe null
        (s.hand(0).size == 11) shouldBe true
    }

    @Test
    fun `§9_5 deixar 1 carta e valido sem morto disponivel quando o lado pode bater`() {
        val s = round {
            hand(0, "5S 6S 7S KS")
            hand(1, "9C 9D")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }
        s.check(0, create("5S 6S 7S")).shouldBeOk()
        // E sem canastra continua proibido: precisam sobrar 2 cartas.
        val semCanastra = round {
            hand(0, "5S 6S 7S KS")
            hand(1, "9C 9D")
            noMortoAvailable()
            phase = Phase.PLAYING
        }
        semCanastra.check(0, create("5S 6S 7S")) shouldFailWith ActionError.MUST_KEEP_CARD_TO_DISCARD
    }

    @Test
    fun `§9_5 em duplas o parceiro tambem bate sem morto disponivel`() {
        val s = round(GameMode.DUPLAS) {
            hand(0, "5S")
            hand(1, "9C 9D")
            hand(2, "KS KD KC")
            hand(3, "7C 8C")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H") // canastra do lado 0 (parceiros 0 e 2)
            phase = Phase.PLAYING
            current = 2
        }.act(2, create("KS KD KC"))
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(2))
    }

    @Test
    fun `§9_5 §12 a batida sem morto soma 100 da batida e perde 100 do morto nao pego`() {
        val s = round {
            hand(0, "KS KD KC")
            hand(1, "9C 9D")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, create("KS KD KC"))
        val winner = scoreRound(s)[0]
        winner.goOut shouldBe 100
        winner.mortoNotTaken shouldBe -100
        // Os dois se compensam: o total é o das canastras e das cartas da mesa, sem o bônus líquido.
        winner.total shouldBe winner.cleanCanastas.points + winner.dirtyCanastas.points + winner.redThrees.points +
            winner.tableCards.points + winner.hand.points
        // O outro lado, sem morto também (o morto 0 foi do lado 1): −100 só dele.
        scoreRound(s)[1].goOut shouldBe 0
    }

    @Test
    fun `§8 §9_5 3 vermelho sem reposicao deixando 1 carta segue se o lado pode bater sem morto`() {
        // Sem monte nem morto (o 3 vermelho não é reposto) o lado com canastra pode bater descartando a última carta.
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("3H")
            noMortoAvailable()
            meld(0, "4H 5H 6H 7H 8H 9H")
        }.act(0, Action.DrawFromStock)
        s.phase shouldBe Phase.PLAYING
        s.result shouldBe null
        s.act(0, discardCard("KS")).result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `§8 §9_5 3 vermelho sem reposicao deixando 1 carta sem canastra encerra sem vencedor`() {
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("3H")
            noMortoAvailable()
        }.act(0, Action.DrawFromStock)
        s.result shouldBe RoundResult.NoWinner
    }
}
