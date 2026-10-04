package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * §13.1 desistência: o outro lado vence e a partida em andamento nunca é pontuada, mesmo com o laço rodando em
 * outra thread ([Dispatchers.Default]) no exato momento da desistência.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameControllerResignTest {

    private fun bots(mode: GameMode, seed: Long): List<SeatPlayer> = mode.seats.map { seat ->
        BotSeatPlayer(createBot(Difficulty.FACIL, Random(botSeed(seed, seat.index))))
    }

    /** O fim de jogo por desistência: totais = soma do histórico (só partidas já encerradas), outro lado vence. */
    private fun GameSnapshot.shouldBeConsistentResignation(mode: GameMode) {
        stage shouldBe Stage.GAME_OVER
        resigned shouldBe true
        winner shouldBe mode.sides.first { it != mode.sideOf(viewerSeat) }
        totals shouldBe mode.sides.map { side -> history.sumOf { record -> record.scores.first { it.side == side }.total } }
        humanLegal shouldBe emptyList()
    }

    @Test
    fun `§13_1 desistencia em momentos aleatorios com o laco em outra thread nunca pontua a partida em andamento`() {
        repeat(150) { iteration ->
            val mode = if (iteration % 2 == 0) GameMode.INDIVIDUAL else GameMode.DUPLAS
            val seed = 1_000L + iteration
            // Alvo altíssimo: o jogo só termina pela desistência; sem humano não há esperas, só CPU no laço.
            val controller = GameController(
                GameConfig(mode, Difficulty.FACIL, targetScore = 1_000_000), seed, bots(mode, seed),
                computeDispatcher = Dispatchers.Default, botDelayMillis = 0,
            )
            runBlocking {
                val job = launch(Dispatchers.Default) { controller.run() }
                Thread.sleep(Random(seed).nextLong(0, 25))
                val accepted = controller.resign()
                val published = controller.state.value
                withTimeout(10_000) { job.join() }
                accepted shouldBe true
                published.shouldBeConsistentResignation(mode)
                // Nada sobrescreveu o fim de jogo e nenhuma partida foi pontuada depois da desistência.
                controller.state.value shouldBe published
                controller.currentMatch.history shouldBe published.history
                controller.currentMatch.totals shouldBe published.totals
            }
        }
    }

    @Test
    fun `§13_1 desistencia no meio da aplicacao de uma jogada (outra thread) nao e sobrescrita pelo laco`() {
        // O `observe` dos bots roda depois do `play` e antes da publicação: um bot que trava ali segura o laço no
        // meio da aplicação, enquanto a desistência chega de outra thread.
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val blockingObserver = object : BotPlayer {
            private val delegate = createBot(Difficulty.FACIL, Random(3))
            override fun chooseAction(view: PlayerView, legal: List<Action>): Action = delegate.chooseAction(view, legal)
            override fun observe(event: PublicEvent) {
                if (armed.compareAndSet(true, false)) {
                    inside.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        val mode = GameMode.INDIVIDUAL
        val controller = GameController(
            GameConfig(mode, Difficulty.FACIL, 1_000_000), 3,
            listOf(BotSeatPlayer(blockingObserver), BotSeatPlayer(createBot(Difficulty.FACIL, Random(4)))),
            computeDispatcher = Dispatchers.Default, botDelayMillis = 0,
        )
        runBlocking {
            val job = launch(Dispatchers.Default) { controller.run() }
            inside.await(10, TimeUnit.SECONDS) shouldBe true
            var accepted = false
            val resigner = thread { accepted = controller.resign() }
            // A desistência ou espera a jogada terminar de ser aplicada, ou (sem a trava) já publicou o fim.
            Thread.sleep(150)
            release.countDown()
            resigner.join(10_000)
            withTimeout(10_000) { job.join() }
            accepted shouldBe true
            controller.state.value.shouldBeConsistentResignation(mode)
            controller.currentMatch.history shouldBe controller.state.value.history
        }
    }

    @Test
    fun `§13_1 desistencia com o bot pensando em outra thread descarta a jogada dele`() {
        val thinking = CountDownLatch(1)
        val release = CountDownLatch(1)
        var decisions = 0
        val slowBot = object : BotPlayer {
            private val delegate = createBot(Difficulty.FACIL, Random(1))
            override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
                decisions++
                thinking.countDown()
                release.await(10, TimeUnit.SECONDS) // busca de CPU que não vê o cancelamento
                return delegate.chooseAction(view, legal)
            }
        }
        val mode = GameMode.INDIVIDUAL
        val controller = GameController(
            GameConfig(mode, Difficulty.FACIL, 3000), 7, listOf(BotSeatPlayer(slowBot), BotSeatPlayer(slowBot)),
            computeDispatcher = Dispatchers.Default, botDelayMillis = 0,
        )
        runBlocking {
            val job = launch(Dispatchers.Default) { controller.run() }
            thinking.await(10, TimeUnit.SECONDS) shouldBe true
            val roundBefore = controller.currentMatch.currentRound
            controller.resign() shouldBe true
            val published = controller.state.value
            release.countDown()
            withTimeout(10_000) { job.join() }
            decisions shouldBe 1
            controller.currentMatch.currentRound shouldBe roundBefore
            controller.state.value shouldBe published
            published.shouldBeConsistentResignation(mode)
        }
    }

    @Test
    fun `§13_1 e §3_5 desistencia na espera da encenacao das trocas da distribuicao`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var checked = false
        for (seed in 1L..60L) {
            val human = HumanPlayer()
            val controller = GameController(
                GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), seed,
                listOf(human, BotSeatPlayer(createBot(Difficulty.FACIL, Random(seed)))),
                computeDispatcher = dispatcher, botDelayMillis = 0,
            )
            val job = launch { controller.run() }
            advanceUntilIdle()
            if (controller.state.value.view.redThreeLog.none { it.atDeal }) {
                job.cancel()
                continue
            }
            // O laço espera a encenação: ninguém jogou.
            controller.state.value.turnEvents.all { it.isEmpty() } shouldBe true
            controller.resign() shouldBe true
            advanceUntilIdle()
            job.isCompleted shouldBe true
            controller.state.value.shouldBeConsistentResignation(GameMode.INDIVIDUAL)
            controller.state.value.totals shouldBe listOf(0, 0)
            // A espera foi desfeita: a confirmação tardia da encenação não reabre o jogo.
            controller.dealPresentationDone(1) shouldBe false
            checked = true
            break
        }
        checked shouldBe true
    }

    @Test
    fun `§13_1 desistencia no fim de partida mantem a partida ja encerrada e nao inicia outra`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val human = HumanPlayer()
        val controller = GameController(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1_000_000), 5,
            listOf(human, BotSeatPlayer(createBot(Difficulty.FACIL, Random(5)))),
            computeDispatcher = dispatcher, botDelayMillis = 0,
        )
        // Sem confinamento: roda a cada emissão (o `advanceUntilIdle` não espera tarefas do backgroundScope).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { if (it.stage == Stage.PLAYING) controller.presentationDone(it.roundNumber, it.view.redThreeLog.size) }
        }
        val job = launch { controller.run() }
        var guard = 0
        while (controller.state.value.stage == Stage.PLAYING && guard++ < 5_000) {
            advanceUntilIdle()
            val state = controller.state.value
            if (state.isHumanTurn) {
                val legal = state.humanLegal
                controller.submit(legal.firstOrNull { it is Action.Discard } ?: legal.first(), state.humanRequestId)
            }
        }
        advanceUntilIdle()
        val roundOver = controller.state.value
        roundOver.stage shouldBe Stage.ROUND_OVER
        controller.resign() shouldBe true
        advanceUntilIdle()
        job.isCompleted shouldBe true
        val ended = controller.state.value
        ended.shouldBeConsistentResignation(GameMode.INDIVIDUAL)
        // A partida 1 já tinha terminado e sido pontuada antes da desistência: fica no histórico; nenhuma outra.
        ended.history shouldBe roundOver.history
        ended.totals shouldBe roundOver.totals
        controller.currentMatch.roundNumber shouldBe 1
        controller.continueToNextRound() shouldBe false
    }

    @Test
    fun `§13_1 em duplas a dupla adversaria vence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mode = GameMode.DUPLAS
        val human = HumanPlayer()
        val players = GameController.playersFor(GameConfig(mode, Difficulty.FACIL, 3000), 9, human)
        val controller = GameController(
            GameConfig(mode, Difficulty.FACIL, 3000), 9, players, computeDispatcher = dispatcher, botDelayMillis = 0,
        )
        // Sem confinamento: roda a cada emissão (o `advanceUntilIdle` não espera tarefas do backgroundScope).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { if (it.stage == Stage.PLAYING) controller.presentationDone(it.roundNumber, it.view.redThreeLog.size) }
        }
        val job = launch { controller.run() }
        advanceUntilIdle()
        controller.state.value.isHumanTurn shouldBe true
        controller.resign() shouldBe true
        advanceUntilIdle()
        job.isCompleted shouldBe true
        controller.state.value.winner shouldBe Side(1)
        controller.state.value.shouldBeConsistentResignation(mode)
        mode.sideOf(Seat(2)) shouldBe Side(0)
    }

}
