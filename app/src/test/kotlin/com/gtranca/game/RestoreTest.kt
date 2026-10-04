package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.data.FileSavedGameStore
import com.gtranca.data.GameResult
import com.gtranca.data.GameStats
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.data.StatsRepository
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.ui.game.EndScreen
import com.gtranca.ui.game.GameViewModel
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Jogo salvo e retomado: mesmo `Match`, bots com a memória reconstruída pelos eventos públicos. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreTest {

    private val dispatcher = StandardTestDispatcher()

    /** Escopo da fila de gravações (o "escopo da aplicação" dos testes), no relógio do teste. */
    private val writeScope = CoroutineScope(dispatcher + SupervisorJob())

    private fun writes() = WriteQueue(writeScope)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

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
    private suspend fun TestScope.savesOfABotGame(gameSeed: Long = seed): List<SavedGame> {
        val saves = mutableListOf<SavedGame>()
        val controller = GameController(
            config, gameSeed,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(gameSeed, it.index)))) },
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            onSave = { snapshot ->
                val json = FileSavedGameStore.encode(savedGameOf("id-$gameSeed", config, gameSeed, snapshot))
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

    /** ViewModel retomado de [saved], com o humano no assento 0. */
    private fun restoredVm(saved: SavedGame, persistence: GamePersistence) = GameViewModel(
        saved.config.toConfig(), gameSeed = saved.gameSeed, computeDispatcher = dispatcher, botDelayMillis = 0,
        swapAnimationMillis = 0, animationMillis = 0, restored = saved.toRestored(), gameId = saved.gameId,
        persistence = persistence, writes = writes(),
    )

    /** O lado do humano desiste (§13.1). */
    private fun TestScope.resign(vm: GameViewModel) {
        vm.onResign()
        vm.onConfirmResign()
        advanceUntilIdle()
    }

    /** Confirma as trocas de 3 vermelho do próprio humano que estiverem na tela. */
    private fun TestScope.confirmOwnSwaps(vm: GameViewModel) {
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
    }

    @Test
    fun `§13_1 o jogo e salvo desde o inicio e a cada acao, e o fim e registrado uma vez depois das gravacoes`() = runTest(dispatcher) {
        val store = FakePersistence()
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 3, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
            gameId = "jogo-x", persistence = store, writes = writes(),
        )
        advanceUntilIdle()
        store.saves.shouldNotBeEmpty()
        store.saves.first().gameId shouldBe "jogo-x"
        confirmOwnSwaps(vm)
        val before = store.saves.size
        vm.onDraw()
        advanceUntilIdle()
        (store.saves.size > before) shouldBe true
        // O salvo tem a ação do humano no log da partida atual.
        store.saves.last().events.last().action shouldBe Action.DrawFromStock

        resign(vm)
        store.finishes shouldBe listOf("jogo-x" to GameResult.RESIGNATION)
        store.log.last() shouldBe "finish"
        // Mais nada depois do fim (nem uma segunda contagem).
        advanceUntilIdle()
        store.finishes.size shouldBe 1
    }

    @Test
    fun `§13 §3_5 §6_5 retomar pelo ViewModel - mesma mao e mesa, sem reencenar trocas de 3 vermelho, e no fim de partida volta aos pontos`() = runTest(dispatcher) {
        val saves = savesOfABotGame()
        // Um ponto no meio da partida em que já houve trocas de 3 vermelho: nenhuma pode ser reencenada.
        val midRound = saves.first {
            !it.match.currentRoundRecorded && it.events.size >= 6 && it.match.currentRound.phase != Phase.FINISHED &&
                it.match.currentRound.redThreeLog.isNotEmpty()
        }
        val vm = restoredVm(midRound, FakePersistence())
        val first = vm.uiState.value
        first.snapshot.view.redThreeLog.shouldNotBeEmpty()
        first.reveal.shouldBeNull()
        first.banner.shouldBeNull()
        first.snapshot.view.hand shouldBe midRound.match.currentRound.handOf(Seat(0))
        first.snapshot.view.tables shouldBe midRound.match.currentRound.tables
        first.snapshot.totals shouldBe midRound.match.totals

        val atRoundOver = saves.first { it.match.isAwaitingNextRound }
        val vm2 = restoredVm(atRoundOver, FakePersistence())
        advanceUntilIdle()
        vm2.uiState.value.endScreen shouldBe EndScreen.ROUND_POINTS
        vm2.uiState.value.snapshot.history shouldBe atRoundOver.match.history
        vm2.uiState.value.snapshot.history.last().scores.shouldNotBeEmpty()
        vm2.uiState.value.snapshot.view.hand.shouldNotBeNull()
    }

    @Test
    fun `§13_1 desistir depois de retomar encerra o jogo e registra uma desistencia, sem gravar depois do fim`() = runTest(dispatcher) {
        val saved = savesOfABotGame().first { it.match.roundNumber == 2 && it.events.size >= 4 && !it.match.currentRoundRecorded }
        val store = FakePersistence()
        val vm = restoredVm(saved, store)
        advanceUntilIdle()
        resign(vm)
        vm.uiState.value.snapshot.stage shouldBe Stage.GAME_OVER
        vm.uiState.value.snapshot.resigned shouldBe true
        // §13.1 a partida em andamento não é pontuada.
        vm.uiState.value.snapshot.history shouldBe saved.match.history
        store.finishes shouldBe listOf(saved.gameId to GameResult.RESIGNATION)
        store.log.last() shouldBe "finish"
    }

    /** O humano (assento 0) acabou de trocar um 3 vermelho em [save] (na distribuição ou numa compra). */
    private fun ownSwapJustHappened(previous: SavedGame?, save: SavedGame): Boolean {
        val round = save.match.currentRound
        if (save.match.currentRoundRecorded || round.phase == Phase.FINISHED) return false
        val before = if (previous != null && !previous.match.currentRoundRecorded &&
            previous.match.roundNumber == save.match.roundNumber
        ) {
            previous.match.currentRound.redThreeLog.size
        } else {
            0
        }
        return round.redThreeLog.drop(before).any { it.seat == Seat(0) }
    }

    @Test
    fun `§3_5 §6_5 retomado logo depois de uma troca de 3 vermelho do humano, a mesa nao fica travada`() = runTest(dispatcher) {
        val saved = (seed until seed + 30).firstNotNullOf { gameSeed ->
            val saves = savesOfABotGame(gameSeed)
            saves.indices.firstOrNull { i -> ownSwapJustHappened(saves.getOrNull(i - 1), saves[i]) }?.let(saves::get)
        }
        val vm = restoredVm(saved, FakePersistence())
        advanceUntilIdle()
        val state = vm.uiState.value
        // Nada a confirmar (a troca aconteceu antes de salvar) e o jogo anda até o humano (ou o fim da partida).
        state.reveal.shouldBeNull()
        (state.snapshot.isHumanTurn || state.snapshot.stage != Stage.PLAYING) shouldBe true
    }

    @Test
    fun `§12 §13 partida salva encerrada e ainda nao pontuada e pontuada uma vez so ao retomar`() = runTest(dispatcher) {
        val saved = savesOfABotGame().first { it.match.currentRound.phase == Phase.FINISHED && !it.match.currentRoundRecorded }
        val restored = GameController(
            saved.config.toConfig(), saved.gameSeed,
            GameController.playersFor(saved.config.toConfig(), saved.gameSeed, HumanPlayer()),
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
            restored = saved.toRestored(),
        )
        val job = launch { restored.run() }
        advanceUntilIdle()
        val state = restored.state.value
        state.history.size shouldBe saved.match.history.size + 1
        (state.stage == Stage.ROUND_OVER || state.stage == Stage.GAME_OVER) shouldBe true
        advanceUntilIdle()
        restored.state.value.history.size shouldBe saved.match.history.size + 1
        job.cancel()
    }

    /** Estatísticas com a regra do `:data`: cada jogo (id) conta uma vez. */
    private class DedupStats : StatsRepository {
        val recorded = mutableMapOf<String, GameResult>()
        override val stats: Flow<GameStats> = emptyFlow()
        override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean {
            if (gameId in recorded) return false
            recorded[gameId] = result
            return true
        }
        override suspend fun reset() = recorded.clear()
    }

    private object NoSettings : SettingsRepository {
        override val settings: Flow<Settings> = emptyFlow()
        override suspend fun setLastMode(mode: GameMode) = Unit
        override suspend fun setHandSort(id: String) = Unit
    }

    @Test
    fun `§13_1 estatistica unica ponta a ponta - o jogo retomado com o mesmo id termina e conta uma vez`(@TempDir dir: File) = runTest(dispatcher) {
        val files = FileSavedGameStore(File(dir, "saved_game.json"), ioDispatcher = dispatcher)
        val stats = DedupStats()
        val persistence = DataGamePersistence(files, stats, NoSettings)
        // 1ª sessão: jogo novo, uma jogada, sai (o salvo fica).
        val vm1 = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 5, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0, gameId = "g", persistence = persistence,
            writes = writes(),
        )
        advanceUntilIdle()
        confirmOwnSwaps(vm1)
        vm1.onDraw()
        advanceUntilIdle()
        val saved = files.load().shouldNotBeNull()
        saved.gameId shouldBe "g"
        saved.events.last().action shouldBe Action.DrawFromStock

        // 2ª sessão: retoma e desiste — o salvo some primeiro e o jogo conta uma vez.
        resign(restoredVm(saved, persistence))
        files.load().shouldBeNull()
        stats.recorded shouldBe mapOf("g" to GameResult.RESIGNATION)

        // O processo morreu antes de o salvo sumir do disco: retomado de novo, o mesmo jogo termina outra vez e não
        // conta de novo.
        files.save(saved)
        resign(restoredVm(saved, persistence))
        files.load().shouldBeNull()
        stats.recorded shouldBe mapOf("g" to GameResult.RESIGNATION)
    }

    /** Gravação que falha na primeira vez (disco cheio). */
    private class FailingPersistence : GamePersistence {
        var attempts = 0
        val saves = mutableListOf<SavedGame>()
        val finishes = mutableListOf<GameResult>()
        override suspend fun save(game: SavedGame) {
            if (attempts++ < 1) throw IOException("disco cheio")
            saves += game
        }
        override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) {
            finishes += result
        }
        override suspend fun saveHandSort(id: String) = throw IOException("disco cheio")
    }

    @Test
    fun `falha de E-S ao gravar nao derruba o jogo - a fila segue e o fim ainda e registrado`() = runTest(dispatcher) {
        val store = FailingPersistence()
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 9, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0, gameId = "e", persistence = store,
            writes = writes(),
        )
        advanceUntilIdle()
        vm.onSortChange(HandSort.BY_SUIT)
        confirmOwnSwaps(vm)
        vm.onDraw()
        advanceUntilIdle()
        // A 1ª gravação falhou; o jogo seguiu e as seguintes gravaram.
        (store.attempts >= 2) shouldBe true
        store.saves.shouldNotBeEmpty()
        vm.uiState.value.snapshot.isHumanTurn shouldBe true
        resign(vm)
        store.finishes shouldBe listOf(GameResult.RESIGNATION)
    }
}
