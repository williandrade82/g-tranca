package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
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
                GameConfig(mode, Difficulty.MEDIO, targetScore = 3000), seed,
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
}
