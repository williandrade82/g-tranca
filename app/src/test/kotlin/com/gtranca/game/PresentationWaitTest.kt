package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * §4.3/§6.5 com humano à mesa, depois de uma ação que fez alguém trocar 3 vermelho, o controlador espera a interface
 * encenar as trocas antes da próxima ação: a ordem vista é a real e o descarte é o último movimento de quem joga.
 */
class PresentationWaitTest {

    /** Controlador parado na espera da encenação de uma troca de 3 vermelho do bot (assento 1). */
    private class Waiting(val controller: GameController, val job: Job, val logSize: Int, val round: Int)

    /**
     * Joga (o humano compra e descarta; confirma as encenações da distribuição e do humano) até o bot trocar 3 vermelho
     * durante a partida; devolve o controlador parado nessa espera, ou `null` se não aconteceu.
     */
    private fun TestScope.untilBotSwap(seed: Long): Waiting? {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val human = HumanPlayer()
        val controller = GameController(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1_000_000), seed,
            listOf(human, BotSeatPlayer(createBot(Difficulty.FACIL, Random(seed)))),
            computeDispatcher = dispatcher, botDelayMillis = 0,
        )
        val job = launch { controller.run() }
        repeat(200) {
            advanceUntilIdle()
            val s = controller.state.value
            if (s.stage != Stage.PLAYING) {
                job.cancel()
                return null
            }
            val log = s.view.redThreeLog
            val last = log.lastOrNull()
            when {
                s.isHumanTurn -> human.submit(s.humanLegal.firstOrNull { it is Action.Discard } ?: s.humanLegal.first())
                last != null && !last.atDeal && last.seat == Seat(1) && s.thinkingSeat == Seat(1) ->
                    return Waiting(controller, job, log.size, s.roundNumber)
                // Distribuição ou troca do próprio humano: confirma a encenação.
                else -> controller.presentationDone(s.roundNumber, log.size)
            }
        }
        job.cancel()
        return null
    }

    @Test
    fun `§6_5 troca do bot - a acao seguinte dele so e aplicada depois da confirmacao da encenacao`() = runTest {
        var checked = false
        for (seed in 1L..200L) {
            val waiting = untilBotSwap(seed) ?: continue
            val controller = waiting.controller
            val before = controller.state.value
            // Parado logo depois da compra que trouxe o 3 vermelho: o bot ainda não baixou nem descartou.
            before.turnEvents[1].last().action shouldBe Action.DrawFromStock
            advanceUntilIdle()
            controller.state.value shouldBe before
            // Confirmação de outro tamanho de registro ou de outra partida é recusada; a exata, aceita uma vez.
            controller.presentationDone(waiting.round, waiting.logSize - 1) shouldBe false
            controller.presentationDone(waiting.round + 1, waiting.logSize) shouldBe false
            controller.presentationDone(waiting.round, waiting.logSize) shouldBe true
            controller.presentationDone(waiting.round, waiting.logSize) shouldBe false
            advanceUntilIdle()
            // Agora o bot segue a vez (baixas e descarte), depois da troca encenada.
            val after = controller.state.value
            val continued = after.turnEvents[1].size > before.turnEvents[1].size || after.isHumanTurn || after.stage != Stage.PLAYING
            continued shouldBe true
            waiting.job.cancel()
            checked = true
            break
        }
        checked shouldBe true
    }

    @Test
    fun `§13_1 desistencia durante a espera da encenacao de uma troca`() = runTest {
        var checked = false
        for (seed in 1L..200L) {
            val waiting = untilBotSwap(seed) ?: continue
            val controller = waiting.controller
            controller.resign() shouldBe true
            advanceUntilIdle()
            waiting.job.isCompleted shouldBe true
            controller.state.value.stage shouldBe Stage.GAME_OVER
            controller.state.value.winner shouldBe Side(1)
            // A confirmação tardia não reabre o jogo.
            controller.presentationDone(waiting.round, waiting.logSize) shouldBe false
            checked = true
            break
        }
        checked shouldBe true
    }

    @Test
    fun `sem humano nao ha espera - jogo so de bots termina sem confirmacoes`() = runTest {
        val mode = GameMode.DUPLAS
        val controller = GameController(
            GameConfig(mode, Difficulty.MEDIO, targetScore = 1000), 21,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(21, it.index)))) },
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
        )
        controller.run()
        controller.state.value.stage shouldBe Stage.GAME_OVER
        // Houve trocas durante as partidas (o registro tem entradas fora da distribuição), e nada esperou por elas.
        controller.currentMatch.history.isNotEmpty() shouldBe true
    }
}
