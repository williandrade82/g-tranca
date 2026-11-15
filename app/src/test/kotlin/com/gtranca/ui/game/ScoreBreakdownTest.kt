package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.HandPenalty
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideScore
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.NO_DEAL_GUESS
import com.gtranca.game.botSeed
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §12 a tela de pontos mostra linhas que somam exatamente o total de cada lado. */
class ScoreBreakdownTest {

    @Test
    fun `§12_1 e §12_2 linhas exibidas somam o total da partida e as secoes os seus subtotais`() = runTest {
        var checked = 0
        for ((mode, seed) in listOf(GameMode.INDIVIDUAL to 11L, GameMode.INDIVIDUAL to 12L, GameMode.DUPLAS to 21L)) {
            val controller = GameController(
                GameConfig(mode, Difficulty.MEDIO, targetScore = 3000, rules = NO_DEAL_GUESS), seed,
                mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(seed, it.index)))) },
                computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            )
            controller.run()
            for (record in controller.state.value.history) {
                for (score in record.scores) {
                    ScoreBreakdown.sum(score) shouldBe score.total
                    ScoreBreakdown.subtotal(score, ScoreSection.TABLE) shouldBe score.tableCards.points
                    ScoreBreakdown.subtotal(score, ScoreSection.HAND) shouldBe score.hand.points
                    // Todas as linhas aparecem, cada uma numa só seção.
                    ScoreBreakdown.lines(score).keys shouldBe ScoreItem.entries.toSet()
                    checked++
                }
            }
        }
        (checked > 10) shouldBe true
    }

    /** Um [SideScore] sem nenhum ponto especial, para isolar o item sob teste. */
    private fun bareScore(dealGuessBonus: Int) = SideScore(
        side = Side(0),
        redThrees = ScoreLine.ZERO,
        cleanCanastas = ScoreLine.ZERO,
        dirtyCanastas = ScoreLine.ZERO,
        goOut = 0,
        mortoNotTaken = 0,
        hand = HandPenalty(ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO),
        dealGuessBonus = dealGuessBonus,
    )

    @Test
    fun `§3_6 §12_1 DEAL_GUESS_BONUS mostra a contagem de palpites certos e soma no total`() {
        // Sem nenhum acerto: linha zerada, mas continua aparecendo (coluna de quantidade em "0", não vazia).
        val none = bareScore(dealGuessBonus = 0)
        ScoreBreakdown.lines(none)[ScoreItem.DEAL_GUESS_BONUS] shouldBe ScoreBreakdown.Line(0, 0)
        ScoreBreakdown.sum(none) shouldBe none.total

        // Um acerto (mão OU morto): +100, contagem 1.
        val one = bareScore(dealGuessBonus = 100)
        ScoreBreakdown.lines(one)[ScoreItem.DEAL_GUESS_BONUS] shouldBe ScoreBreakdown.Line(1, 100)
        ScoreBreakdown.sum(one) shouldBe one.total
        one.total shouldBe 100

        // Os dois acertos do mesmo lado: +200, contagem 2.
        val two = bareScore(dealGuessBonus = 200)
        ScoreBreakdown.lines(two)[ScoreItem.DEAL_GUESS_BONUS] shouldBe ScoreBreakdown.Line(2, 200)
        ScoreBreakdown.sum(two) shouldBe two.total
        two.total shouldBe 200

        // A linha nova não quebra a regra de que todo item aparece, cada um numa só seção.
        ScoreBreakdown.lines(one).keys shouldBe ScoreItem.entries.toSet()
    }

    @Test
    fun `pontos usam o sinal de menos tipografico em todo lugar`() {
        signed(200) shouldBe "+200"
        signed(-95) shouldBe "\u221295"
        signed(0) shouldBe "0"
        plainPoints(1704) shouldBe "1704"
        plainPoints(-61) shouldBe "\u221261"
        plainPoints(Int.MIN_VALUE) shouldBe "\u22122147483648"
    }
}

