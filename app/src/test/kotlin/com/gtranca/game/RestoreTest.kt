package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.data.FileSavedGameStore
import com.gtranca.data.GameResult
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.ui.game.EndScreen
import com.gtranca.ui.game.GameViewModel
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Jogo salvo e retomado: mesmo `Match`, bots com a memória reconstruída pelos eventos públicos. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Bot que registra a memória que recebe. */
    private class SpyBot(seed: Long, difficulty: Difficulty) : BotPlayer {
        private val delegate = createBot(difficulty, Random(seed))
        val observed = mutableListOf<PublicEvent>()
        var newRounds = 0
        var decisions = 0
        override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
            decisions++
            return delegate.chooseAction(view, legal)
        }
        override fun observe(event: PublicEvent) {
            delegate.observe(event)
            observed += event
        }
        override fun onNewRound() {
            delegate.onNewRound()
            newRounds++
        }
    }

    private val mode = GameMode.DUPLAS
    private val seed = 21L
    private val config = GameConfig(mode, Difficulty.MEDIO, targetScore = 1000)

    /** Joga só com bots e devolve as fotos salvas (todas), passando pelo JSON do arquivo salvo. */
    private suspend fun kotlinx.coroutines.test.TestScope.savesOfABotGame(): List<SavedGame> {
        val saves = mutableListOf<SavedGame>()
        val controller = GameController(
            config, seed,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(seed, it.index)))) },
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            onSave = { snapshot ->
                val json = FileSavedGameStore.encode(savedGameOf("id", config, seed, snapshot))
                saves += FileSavedGameStore.decode(json)!!
            },
        )
        controller.run()
        return saves
    }

    @Test
    fun `jogo so de bots salvo no meio de uma partida e retomado segue ate o fim com o historico intacto`() = runTest(dispatcher) {
        val saves = savesOfABotGame()
        // Uma foto no meio da 2ª partida (com eventos, partida em andamento).
        val saved = saves.first { it.match.roundNumber == 2 && it.events.size >= 10 && !it.match.currentRoundRecorded }
        saved.config shouldBe SavedConfig(mode, "medio", 1000)

        val spies = mode.seats.map { SpyBot(botSeed(saved.gameSeed, it.index), Difficulty.MEDIO) }
        val restored = GameController(
            saved.config.toConfig(), saved.gameSeed, spies.map { BotSeatPlayer(it) },
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            restored = saved.toRestored(),
        )
        restored.state.value.view.hand shouldBe saved.match.currentRound.handOf(Seat(0))
        // A memória dos bots é reconstruída antes de qualquer decisão: exatamente os eventos da partida atual.
        var checkedMemory = false
        spies.all { it.decisions == 0 } shouldBe true
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            restored.state.collect {
                if (!checkedMemory && spies.any { spy -> spy.decisions > 0 || spy.observed.size > saved.events.size }) return@collect
                if (!checkedMemory && spies.all { it.newRounds == 1 && it.observed.size == saved.events.size }) {
                    spies.forEach { it.observed shouldBe saved.events.map { e -> e.toPublic() } }
                    checkedMemory = true
                }
            }
        }
        restored.run()
        collector.cancel()
        checkedMemory shouldBe true
        val final = restored.state.value
        final.stage shouldBe Stage.GAME_OVER
        // As partidas encerradas antes de salvar continuam iguais no histórico.
        final.history.take(saved.match.history.size) shouldBe saved.match.history
        (final.history.size > saved.match.history.size) shouldBe true
    }

    @Test
    fun `jogo retomado no fim de uma partida espera a confirmacao e distribui a proxima`() = runTest(dispatcher) {
        val saved = savesOfABotGame().first { it.match.isAwaitingNextRound }
        val human = HumanPlayer()
        val restored = GameController(
            saved.config.toConfig(), saved.gameSeed,
            GameController.playersFor(saved.config.toConfig(), saved.gameSeed, human),
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            restored = saved.toRestored(),
        )
        restored.state.value.stage shouldBe Stage.ROUND_OVER
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            restored.state.collect { if (it.stage == Stage.PLAYING) restored.presentationDone(it.roundNumber, it.view.redThreeLog.size) }
        }
        val job = launch { restored.run() }
        advanceUntilIdle()
        restored.state.value.stage shouldBe Stage.ROUND_OVER
        restored.state.value.history shouldBe saved.match.history
        restored.continueToNextRound() shouldBe true
        advanceUntilIdle()
        restored.state.value.roundNumber shouldBe saved.match.history.size + 1
        restored.state.value.stage shouldBe Stage.PLAYING
        job.cancel()
    }

    /** Persistência falsa: registra as gravações, em ordem. */
    private class FakePersistence : GamePersistence {
        val saves = mutableListOf<SavedGame>()
        val finishes = mutableListOf<Pair<String, GameResult>>()
        val log = mutableListOf<String>()
        override suspend fun save(game: SavedGame) {
            saves += game
            log += "save"
        }
        override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) {
            finishes += gameId to result
            log += "finish"
        }
        override suspend fun saveHandSort(id: String) = Unit
    }

    @Test
    fun `§13_1 o jogo e salvo desde o inicio e a cada acao, e o fim e registrado uma vez depois das gravacoes`() = runTest(dispatcher) {
        val store = FakePersistence()
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            gameId = "jogo-x", persistence = store, ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        store.saves.shouldNotBeEmpty()
        store.saves.first().gameId shouldBe "jogo-x"
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
        val before = store.saves.size
        vm.onDraw()
        advanceUntilIdle()
        (store.saves.size > before) shouldBe true
        // O salvo tem a ação do humano no log da partida atual.
        store.saves.last().events.last().action shouldBe Action.DrawFromStock

        vm.onResign()
        vm.onConfirmResign()
        advanceUntilIdle()
        store.finishes shouldBe listOf("jogo-x" to GameResult.RESIGNATION)
        store.log.last() shouldBe "finish"
        // Mais nada depois do fim (nem uma segunda contagem).
        advanceUntilIdle()
        store.finishes.size shouldBe 1
    }

    @Test
    fun `retomar pelo ViewModel - mesma mao e mesa, sem reencenar trocas, e no fim de partida volta aos pontos`() = runTest(dispatcher) {
        val saves = savesOfABotGame()
        val midRound = saves.first { it.match.roundNumber == 1 && it.events.size >= 6 && !it.match.currentRoundRecorded }
        val vm = GameViewModel(
            midRound.config.toConfig(), gameSeed = midRound.gameSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
            swapAnimationMillis = 0, animationMillis = 0, restored = midRound.toRestored(), gameId = midRound.gameId,
            persistence = FakePersistence(), ioDispatcher = dispatcher,
        )
        val first = vm.uiState.value
        first.reveal.shouldBeNull()
        first.banner.shouldBeNull()
        first.snapshot.view.hand shouldBe midRound.match.currentRound.handOf(Seat(0))
        first.snapshot.view.tables shouldBe midRound.match.currentRound.tables
        first.snapshot.totals shouldBe midRound.match.totals

        val atRoundOver = saves.first { it.match.isAwaitingNextRound }
        val vm2 = GameViewModel(
            atRoundOver.config.toConfig(), gameSeed = atRoundOver.gameSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
            swapAnimationMillis = 0, animationMillis = 0, restored = atRoundOver.toRestored(), gameId = atRoundOver.gameId,
            persistence = FakePersistence(), ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        vm2.uiState.value.endScreen shouldBe EndScreen.ROUND_POINTS
        vm2.uiState.value.snapshot.history shouldBe atRoundOver.match.history
        vm2.uiState.value.snapshot.history.last().scores.shouldNotBeEmpty()
        vm2.uiState.value.snapshot.view.hand.shouldNotBeNull()
    }
}
