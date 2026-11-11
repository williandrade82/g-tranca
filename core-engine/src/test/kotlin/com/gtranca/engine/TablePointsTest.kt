package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Side
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §17.11 b4 Placar: valor de cada conjunto e total da mesa de um lado, só com o que está na mesa (§12.1). */
class TablePointsTest {

    private fun melds(state: RoundState, side: Int) = state.tableOf(Side(side)).melds.map { it.meld }
    private fun table(state: RoundState, side: Int) = tablePoints(melds(state, side), state.redThreesOf(Side(side)).size)

    @Test
    fun `§17_11 canastra limpa de 4 a 10 vale 256`() {
        val s = round { meld(0, "4H 5H 6H 7H 8H 9H TH") }
        meldPoints(melds(s, 0).single()) shouldBe 256
    }

    @Test
    fun `§17_11 canastra suja vale 100 mais as cartas`() {
        val s = round { meld(0, "4H 5H 6H 7H 8H 9H 2C") }
        meldPoints(melds(s, 0).single()) shouldBe 100 + 6 * 8 + 10
    }

    @Test
    fun `§17_11 conjunto que ainda nao e canastra vale so as cartas`() {
        val s = round { meld(0, "TS JS QS KS AS") }
        meldPoints(melds(s, 0).single()) shouldBe 8 + 4 * 10
    }

    @Test
    fun `§17_11 3 vermelho vale menos 95 sem canastra e 105 quando a 1a canastra fecha`() {
        val without = round {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H")
        }
        table(without, 0) shouldBe -95 + 24
        val with = round {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H TH")
        }
        table(with, 0) shouldBe 105 + 256
    }

    @Test
    fun `§17_11 valores vem do RuleSet da mesa`() {
        val rules = RuleSet.DEFAULT.copy(cleanCanastaPoints = 300, tableFourToTenPoints = 5, redThreePoints = 50, tableRedThreePoints = 1)
        val s = round {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H TH")
        }
        meldPoints(melds(s, 0).single(), rules) shouldBe 300 + 7 * 5
        tablePoints(melds(s, 0), 1, rules) shouldBe 335 + 51
    }

    @Test
    fun `§17_11 mesa vazia vale 0`() {
        table(round { }, 0) shouldBe 0
    }

    @Test
    fun `§17_11 total da mesa e a parte mesa da pontuacao final, sem batida, morto nem mao`() {
        val s = round(GameMode.DUPLAS) {
            redThrees(0, "3H 3D")
            meld(0, "4H 5H 6H 7H 8H 9H TH")
            meld(0, "9D 9C 2H")
            redThrees(1, "3H")
            meld(1, "JS QS KS")
            hand(0, "KD 5C")
            result = RoundResult.NoWinner
            phase = com.gtranca.engine.model.Phase.FINISHED
        }
        scoreRound(s).forEach { score ->
            val tablePart = score.redThrees.points + score.cleanCanastas.points + score.dirtyCanastas.points + score.tableCards.points
            table(s, score.side.index) shouldBe tablePart
        }
    }
}
