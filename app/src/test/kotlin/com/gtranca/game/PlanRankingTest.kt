package com.gtranca.game

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Phase
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlanRankingTest {

    private fun c(text: String) = Card.parse(text)

    private fun view(hand: List<Card>, pile: List<Card>, table: SideTable) = PlayerView(
        mode = GameMode.INDIVIDUAL, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
        phase = Phase.AWAITING_DRAW, result = null, hand = hand, handSizes = listOf(hand.size, 11),
        discardPile = pile, stockSize = 30, mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available),
        mortoSizes = listOf(11, 11), redThrees = listOf(emptyList(), emptyList()),
        tables = listOf(table, SideTable()),
    )

    @Test
    fun `sem coringa e fechando canastra limpa com a mao vem antes de acrescentar o topo ao jogo da mesa`() {
        // §5 caso relatado: o topo (5♠) cabia direto no grupo de 5 da mesa, mas com 6♠–J♠ da mão ele fecha uma
        // canastra limpa. A sugestão que considera a mão vem primeiro; a com coringa, por último.
        val table = (SideTable().createMeld(listOf(c("5H"), c("5D"), c("5C"))) as RuleResult.Ok).value
        val hand = listOf(c("6S"), c("7S"), c("8S"), c("9S"), c("TS"), c("JS"), c("2C"))
        val view = view(hand, listOf(c("KD"), c("5S")), table)
        val addToTable = Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(0)))
        val run = Action.TakeDiscardPile(DiscardPlan.NewMeld(listOf(c("6S"), c("7S"))))
        val withWild = Action.TakeDiscardPile(DiscardPlan.NewMeld(listOf(c("6S"), c("2C"))))

        PlanRanking.rank(listOf(addToTable, withWild, run), view) shouldBe listOf(run, addToTable, withWild)
    }
}
