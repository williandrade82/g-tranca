package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.ui.game.shownTurnEvents
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * §3.5 a troca de 3 vermelho do início da vez de outro assento (robô ou humano remoto) aparece na pílula dele numa vista
 * própria, antes da compra: as vistas publicadas uma por jogada (multijogador, §16.6) trazem a troca já no detalhe da vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnStartRedThreeViewTest {

    @Test
    fun `§3_5 a vista da passagem de vez ja mostra a troca do inicio da vez, antes da compra`() = runTest {
        var checked = 0
        for (seed in 1L..40L) {
            val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, targetScore = 1000)
            val controller = GameController(
                config, seed, GameMode.DUPLAS.seats.map { BotSeatPlayer(createBot(Difficulty.FACIL, Random(botSeed(seed, it.index)))) },
                computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0, pacing = PacingPolicy.FREE,
            )
            val seen = mutableListOf<GameSnapshot>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { controller.updatesOf(Seat(0)).collect { seen += it } }
            controller.run()
            job.cancel()
            seen.zipWithNext().forEach { (before, after) ->
                if (before.roundNumber != after.roundNumber || after.stage != Stage.PLAYING || after.view.phase == com.gtranca.engine.model.Phase.FINISHED) return@forEach
                val fresh = after.view.redThreeLog.drop(before.view.redThreeLog.size).filter { it.atTurnStart && it.seat != Seat(0) }
                fresh.forEach { laid ->
                    // a vez passou ao assento: a troca já está no detalhe dele e a vez dele ainda não tem jogada
                    after.view.currentSeat shouldBe laid.seat
                    (laid.card in after.turnRedThrees[laid.seat.index]) shouldBe true
                    after.view.phase shouldBe com.gtranca.engine.model.Phase.AWAITING_DRAW
                    after.shownTurnEvents(laid.seat.index) shouldBe emptyList()
                    checked++
                }
            }
        }
        (checked > 0) shouldBe true
    }
}
