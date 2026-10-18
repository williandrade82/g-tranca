package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.Seat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §3.5, §6.5 os 3 vermelhos de cada vez aparecem no detalhe da última jogada do assento. */
class TurnRedThreesTest {
    private fun c(text: String) = Card.parse(text)

    private fun laid(seat: Int, card: String, atTurnStart: Boolean, chained: Boolean = false) =
        RedThreeLaid(Seat(seat), c(card), atTurnStart = atTurnStart, chained = chained)

    private val draw = true

    @Test
    fun `§3_5 os 3 vermelhos do inicio da vez entram na vez do jogador quando ele comeca a jogar`() {
        val reds = TurnRedThrees(4)
        reds.reset(listOf(laid(1, "3H", true), laid(1, "3D", true)))
        reds.current shouldBe List(4) { emptyList() } // ainda não jogou
        reds.record(Seat(1), startsTurn = draw, newEntries = emptyList())
        reds.current[1] shouldBe listOf(c("3H"), c("3D"))
    }

    @Test
    fun `§3_5 os do inicio da vez do proximo ficam pendentes ate ele jogar`() {
        val reds = TurnRedThrees(4)
        reds.reset(emptyList())
        // o assento 1 descarta e, na passagem de vez, o assento 2 baixa o 3 vermelho da mão
        reds.record(Seat(1), startsTurn = false, newEntries = listOf(laid(2, "3H", true)))
        reds.current[2] shouldBe emptyList()
        reds.record(Seat(2), startsTurn = draw, newEntries = emptyList())
        reds.current[2] shouldBe listOf(c("3H"))
    }

    @Test
    fun `§6_5 o 3 vermelho baixado durante a jogada entra na vez de quem jogou`() {
        val reds = TurnRedThrees(2)
        reds.reset(emptyList())
        reds.record(Seat(0), startsTurn = draw, newEntries = listOf(laid(0, "3H", false), laid(0, "3D", false, chained = true)))
        reds.current[0] shouldBe listOf(c("3H"), c("3D"))
        // a vez seguinte do mesmo assento recomeça a lista
        reds.record(Seat(0), startsTurn = draw, newEntries = emptyList())
        reds.current[0] shouldBe emptyList()
    }

    @Test
    fun `§9_4 a vez continua acumulando e a vez nova do outro assento nao mexe na do primeiro`() {
        val reds = TurnRedThrees(2)
        reds.reset(emptyList())
        reds.record(Seat(0), startsTurn = draw, newEntries = listOf(laid(0, "3H", false)))
        reds.record(Seat(0), startsTurn = false, newEntries = listOf(laid(0, "3D", false)))
        reds.current[0] shouldBe listOf(c("3H"), c("3D"))
        reds.record(Seat(1), startsTurn = draw, newEntries = emptyList())
        reds.current[0] shouldBe listOf(c("3H"), c("3D"))
        reds.current[1] shouldBe emptyList()
    }

    @Test
    fun `partida nova zera tudo`() {
        val reds = TurnRedThrees(2)
        reds.reset(emptyList())
        reds.record(Seat(0), startsTurn = draw, newEntries = listOf(laid(0, "3H", false)))
        reds.reset(emptyList())
        reds.current shouldBe List(2) { emptyList() }
    }
}
