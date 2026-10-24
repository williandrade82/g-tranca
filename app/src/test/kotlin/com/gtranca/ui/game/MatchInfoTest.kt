package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.botSeed
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Informações do jogo: a tabela partida a partida vem do histórico público e o acumulado fecha com o placar. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchInfoTest {
    @Test
    fun `§12 §13 tabela das partidas com quem bateu e o acumulado igual ao placar`() = runTest {
        val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, targetScore = 1500)
        val controller = GameController(
            config, 5L, GameMode.DUPLAS.seats.map { BotSeatPlayer(createBot(Difficulty.FACIL, Random(botSeed(5L, it.index)))) },
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
        )
        controller.run()
        val history = controller.state.value.history
        val rows = MatchInfo.rows(history)
        rows.size shouldBe history.size
        rows.map { it.number } shouldBe (1..history.size).toList()
        rows.last().cumulative shouldBe controller.state.value.totals
        rows.zip(history).forEach { (row, record) ->
            row.wentOut shouldBe (record.result as? RoundResult.GoOut)?.seat
            row.totals shouldBe record.scores.sortedBy { it.side.index }.map { it.total }
        }
        MatchInfo.rows(emptyList()) shouldBe emptyList()
    }
}
