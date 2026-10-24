package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

/**
 * §16 o [GameController] com vários humanos: uma vista e um pedido por assento, fluxo de eventos por jogada, ritmo livre
 * (sem esperar a interface) e troca de jogador no meio do jogo. O jogo contra robôs (um humano ou nenhum) segue igual:
 * ver [GameControllerTest] e [GameControllerResignTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameControllerMultiHumanTest {

    private fun bot(seed: Long, seat: Seat): BotPlayer = createBot(Difficulty.FACIL, Random(botSeed(seed, seat.index)))

    private fun botsOnly(mode: GameMode, seed: Long): List<SeatPlayer> = mode.seats.map { BotSeatPlayer(bot(seed, it)) }

    /** Faz as vezes de cada humano: joga o que o mesmo bot (mesma semente) jogaria, quando o pedido dele abre. */
    private fun TestScope.drive(controller: GameController, humans: Set<Seat>, seed: Long) {
        humans.forEach { seat ->
            val driver = bot(seed, seat)
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                controller.stateOf(seat).collect { snapshot ->
                    // §16.11 com humanos, a próxima partida espera a confirmação (aqui, a do primeiro humano que a vê).
                    if (snapshot.stage == Stage.ROUND_OVER) controller.continueToNextRound()
                    if (snapshot.humanLegal.isNotEmpty()) {
                        controller.submit(seat, driver.chooseAction(snapshot.view, snapshot.humanLegal), snapshot.humanRequestId)
                    }
                }
            }
        }
    }

    /** §16.6 regressão: humanos que jogam o que os bots jogariam dão o mesmo jogo (mesma semente, mesmo resultado). */
    @ParameterizedTest(name = "{0} semente {1}, humanos {2}")
    @CsvSource(
        "INDIVIDUAL, 11, '0 1'",
        "INDIVIDUAL, 12, '1'",
        "DUPLAS, 21, '0 1 2 3'",
        "DUPLAS, 22, '0 2'",
    )
    fun `humanos remotos jogando o que os bots jogariam reproduzem o jogo so de bots`(mode: GameMode, seed: Long, humanSeats: String) = runTest {
        val config = GameConfig(mode, Difficulty.FACIL, targetScore = 1000)
        val reference = GameController(config, seed, botsOnly(mode, seed), computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0)
        reference.run()

        val humans = humanSeats.split(' ').map { Seat(it.toInt()) }.toSet()
        val players = GameController.playersForTable(
            config, seed, humans.associateWith { HumanPlayer() },
            botFactory = { seat, _ -> bot(seed, seat) },
        )
        // Os bots restantes recebem a mesma semente por assento (o `botFactory` ignora o `Random` do controlador).
        val controller = GameController(
            config, seed, players, viewerSeat = humans.first(),
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 700, pacing = PacingPolicy.FREE,
        )
        drive(controller, humans, seed)
        controller.run()

        controller.state.value.stage shouldBe Stage.GAME_OVER
        controller.state.value.totals shouldBe reference.state.value.totals
        controller.state.value.winner shouldBe reference.state.value.winner
        controller.currentMatch.history.size shouldBe reference.currentMatch.history.size
    }

    @Test
    fun `§16_4 cada humano recebe so a propria mao e o pedido e so de quem tem a vez`() = runTest {
        val seed = 7L
        val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, targetScore = 3000)
        val humans = GameController.playersForTable(config, seed, config.mode.seats.associateWith { HumanPlayer() })
        val controller = GameController(
            config, seed, humans, computeDispatcher = StandardTestDispatcher(testScheduler), pacing = PacingPolicy.FREE,
        )
        val job = launch { controller.run() }
        advanceUntilIdle()

        val round = controller.currentMatch.currentRound
        val onTurn = round.currentSeat
        config.mode.seats.forEach { seat ->
            val snapshot = controller.stateOf(seat).value
            snapshot.view.seat shouldBe seat
            snapshot.viewerSeat shouldBe seat
            snapshot.view.hand shouldBe round.handOf(seat)
            // Os demais assentos só aparecem como tamanhos (a vista nunca traz a mão dos outros).
            snapshot.view.handSizes shouldBe config.mode.seats.map { round.handOf(it).size }
            if (seat == onTurn) {
                snapshot.humanLegal.shouldNotBeEmpty()
                snapshot.humanRequestId shouldBe controller.stateOf(onTurn).value.humanRequestId
            } else {
                snapshot.humanLegal shouldBe emptyList()
                snapshot.humanRequestId shouldBe 0L
            }
        }
        job.cancel()
    }

    @Test
    fun `§16_1 jogada de assento sem o pedido em aberto e recusada e explain e por assento`() = runTest {
        val seed = 9L
        val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, targetScore = 3000)
        val humans = GameController.playersForTable(config, seed, config.mode.seats.associateWith { HumanPlayer() })
        val controller = GameController(
            config, seed, humans, computeDispatcher = StandardTestDispatcher(testScheduler), pacing = PacingPolicy.FREE,
        )
        val job = launch { controller.run() }
        advanceUntilIdle()

        val onTurn = controller.currentMatch.currentRound.currentSeat
        val other = Seat((onTurn.index + 1) % 4)
        val id = controller.stateOf(onTurn).value.humanRequestId
        // Fora da vez (e com a ação legal de outro): recusada, sem alterar o jogo.
        controller.submit(other, Action.DrawFromStock, id) shouldBe false
        controller.submit(other, Action.DrawFromStock, controller.stateOf(other).value.humanRequestId) shouldBe false
        // Pedido errado do assento certo: recusado.
        controller.submit(onTurn, Action.DrawFromStock, id + 1) shouldBe false
        controller.explain(other, Action.DrawFromStock, id) shouldBe null
        controller.currentMatch.currentRound.currentSeat shouldBe onTurn

        controller.submit(onTurn, Action.DrawFromStock, id) shouldBe true
        advanceUntilIdle()
        controller.stateOf(onTurn).value.turnEvents[onTurn.index].map { it.action } shouldContain Action.DrawFromStock
        job.cancel()
    }

    @Test
    fun `§16_6 cada jogada processada sai uma vez no fluxo de eventos, em ordem`() = runTest {
        val seed = 3L
        val config = GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, targetScore = 1000)
        val controller = GameController(
            config, seed, botsOnly(config.mode, seed), computeDispatcher = StandardTestDispatcher(testScheduler), pacing = PacingPolicy.FREE,
        )
        val received = mutableListOf<GameEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { received += it } }
        controller.run()

        received.first() shouldBe GameEvent.RoundStarted(1)
        val actions = received.filterIsInstance<GameEvent.ActionApplied>()
        actions.shouldNotBeEmpty()
        received.filterIsInstance<GameEvent.RoundScored>().map { it.roundNumber } shouldBe
            controller.currentMatch.history.map { it.number }
        (received.last() as GameEvent.GameFinished).winner shouldBe controller.state.value.winner
        // Cada jogada aparece uma vez, na partida em que foi feita.
        actions.map { it.roundNumber }.distinct() shouldContainExactly (1..controller.currentMatch.history.size).toList()
    }

    @Test
    fun `§16_9 trocar o humano da vez por um robo abandona o pedido dele e o jogo continua ate o fim`() = runTest {
        val seed = 5L
        val config = GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, targetScore = 1000)
        val humans = GameController.playersForTable(config, seed, config.mode.seats.associateWith { HumanPlayer() })
        val controller = GameController(
            config, seed, humans, computeDispatcher = StandardTestDispatcher(testScheduler), pacing = PacingPolicy.FREE,
        )
        val events = mutableListOf<GameEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { events += it } }
        val job = launch { controller.run() }
        advanceUntilIdle()

        val first = controller.currentMatch.currentRound.currentSeat
        controller.stateOf(first).value.humanLegal.shouldNotBeEmpty()
        val oldRequest = controller.stateOf(first).value.humanRequestId

        controller.replaceWithBot(first, bot(seed, first)) shouldBe true
        advanceUntilIdle()
        // O antigo dono não tem mais pedido; a jogada dele (do pedido antigo) é recusada.
        controller.submit(first, Action.DrawFromStock, oldRequest) shouldBe false
        controller.stateOf(first).value.humanLegal shouldBe emptyList()
        events shouldContain GameEvent.SeatReplaced(first, byBot = true)
        // O robô jogou a vez inteira e o outro humano recebeu o pedido dele.
        val second = Seat(1 - first.index)
        controller.stateOf(second).value.humanLegal.shouldNotBeEmpty()

        // Sem humanos, o jogo corre sozinho até o fim (sem esperar a próxima partida).
        controller.replaceWithBot(second, bot(seed, second)) shouldBe true
        advanceUntilIdle()
        job.isCompleted shouldBe true
        controller.state.value.stage shouldBe Stage.GAME_OVER
        events.last().let { (it as GameEvent.GameFinished).resigned } shouldBe false
    }

    @Test
    fun `§16_7 um humano pode ocupar o assento de um robo no meio da partida`() = runTest {
        val seed = 13L
        val config = GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, targetScore = 3000)
        val human = HumanPlayer()
        val controller = GameController(
            config, seed, GameController.playersForTable(config, seed, mapOf(Seat(0) to human)),
            computeDispatcher = StandardTestDispatcher(testScheduler), pacing = PacingPolicy.FREE,
        )
        val job = launch { controller.run() }
        advanceUntilIdle()
        controller.stateOf(Seat(0)).value.humanLegal.shouldNotBeEmpty()

        val newcomer = HumanPlayer()
        controller.replacePlayer(Seat(1), newcomer) shouldBe true
        // O novo humano já tem a vista do assento 1, com a mão dele e nenhuma ação enquanto não é a vez dele.
        val view = controller.stateOf(Seat(1)).value.view
        view.seat shouldBe Seat(1)
        view.hand shouldBe controller.currentMatch.currentRound.handOf(Seat(1))
        // Um mesmo HumanPlayer não ocupa dois assentos.
        runCatching { controller.replacePlayer(Seat(0), newcomer) }.isFailure shouldBe true
        job.cancel()
    }
}
