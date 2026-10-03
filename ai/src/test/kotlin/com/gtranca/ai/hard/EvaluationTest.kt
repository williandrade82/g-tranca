package com.gtranca.ai.hard

import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.cards
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.dealRound
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.scoreRound
import com.gtranca.engine.viewFor
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Avaliação dos mundos sorteados ([Evaluation]): na partida encerrada é exatamente a pontuação do motor
 * (§12.1/§12.2), do ponto de vista do lado pedido; a recompensa fica sempre em [0, 1].
 */
class EvaluationTest {

    private fun table(vararg melds: String): SideTable =
        melds.fold(SideTable()) { t, meld -> t.createMeld(cards(meld)).getOrThrow() }

    /** Diferença de §12 calculada pelo motor: lado [side] menos o outro. */
    private fun engineDiff(state: RoundState, side: Side): Int {
        val scores = scoreRound(state)
        return scores[side.index].total - scores[1 - side.index].total
    }

    @Test
    fun `partida encerrada no individual - diferenca exata, com sinal e lado corretos`() {
        // Lado 0 bateu (§11.1): canastra limpa +200 (§7.2), batida +100 (§12.1); 4♣ na mão −8 (§12.2) ⇒ 292.
        // Lado 1: 3 vermelho +100 (§12.1); sem morto −100 e K♠ Q♠ na mão −20 (§12.2) ⇒ −20. Diferença 312.
        val state = RoundState(
            mode = GameMode.INDIVIDUAL,
            hands = listOf(cards("4C"), cards("KS QS")),
            stock = emptyList(),
            discardPile = emptyList(),
            mortos = listOf(emptyList(), emptyList()),
            redThrees = listOf(emptyList(), cards("3H")),
            tables = listOf(table("4H 5H 6H 7H 8H 9H"), SideTable()),
            firstSeat = Seat(0),
            currentSeat = Seat(0),
            phase = Phase.FINISHED,
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.BecameStock),
            result = RoundResult.GoOut(Side(0), Seat(0)),
        )
        Evaluation.scoreDiff(state, Side(0)) shouldBe 312.0
        Evaluation.scoreDiff(state, Side(1)) shouldBe -312.0
        Evaluation.scoreDiff(state, Side(0)) shouldBe engineDiff(state, Side(0)).toDouble()
        Evaluation.reward(state, Side(0), scale = 600.0) shouldBe (0.5 + 312.0 / 1200.0)
        Evaluation.reward(state, Side(1), scale = 600.0) shouldBe (0.5 - 312.0 / 1200.0)
    }

    @Test
    fun `partida encerrada em duplas - soma os parceiros e usa o lado do assento`() {
        // §12.3 o lado 0 (assentos 0 e 2) bateu pelo assento 2. Lado 0: canastra suja +100, batida +100,
        // mãos 7♦ (−8) e 2♣ (−10) ⇒ 182. Lado 1 (assentos 1 e 3): canastra limpa +200, morto pego, mãos
        // A♦ (−10) e 3♠ (−5) ⇒ 185. Diferença −3 para o lado 0.
        val state = RoundState(
            mode = GameMode.DUPLAS,
            hands = listOf(cards("7D"), cards("AD"), cards("2C"), cards("3S")),
            stock = emptyList(),
            discardPile = emptyList(),
            mortos = listOf(emptyList(), emptyList()),
            redThrees = listOf(emptyList(), emptyList()),
            tables = listOf(table("5S 6S 7S 8S 9S 2D"), table("JC JC' JD JH JS JS'")),
            firstSeat = Seat(0),
            currentSeat = Seat(2),
            phase = Phase.FINISHED,
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Taken(Side(1))),
            result = RoundResult.GoOut(Side(0), Seat(2)),
        )
        Evaluation.scoreDiff(state, Side(0)) shouldBe -3.0
        Evaluation.scoreDiff(state, Side(1)) shouldBe 3.0
        Evaluation.scoreDiff(state, Side(1)) shouldBe engineDiff(state, Side(1)).toDouble()
    }

    @Test
    fun `em partidas reais, a diferenca da partida encerrada e a do motor e a recompensa fica em 0 a 1`() {
        for (mode in GameMode.entries) {
            for (seed in 0L until 5L) {
                var state = dealRound(mode, Random(seed))
                val bots = List(mode.seatCount) { MediumBot(Random(seed + it)) }
                while (state.phase != Phase.FINISHED) {
                    for (side in mode.sides) {
                        Evaluation.reward(state, side, scale = 600.0).shouldBeBetween(0.0, 1.0, 0.0)
                        Evaluation.reward(state, side, scale = 1.0).shouldBeBetween(0.0, 1.0, 0.0)
                    }
                    val seat = state.currentSeat
                    val legal = RoundEngine.legalActions(state, seat)
                    val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
                    val taken = if (action is Action.TakeDiscardPile) state.discardPile.dropLast(1) else emptyList()
                    state = RoundEngine.apply(state, seat, action)
                    bots.forEach { it.observe(PublicEvent(seat, action, taken)) }
                }
                withClue("modo $mode, semente $seed") {
                    for (side in mode.sides) {
                        Evaluation.scoreDiff(state, side) shouldBe engineDiff(state, side).toDouble()
                        Evaluation.reward(state, side, scale = 1.0).shouldBeBetween(0.0, 1.0, 0.0)
                    }
                    Evaluation.scoreDiff(state, Side(0)) shouldBe -Evaluation.scoreDiff(state, Side(1))
                }
            }
        }
    }
}
