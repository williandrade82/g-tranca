package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class PersistenceTest {

    @TempDir
    lateinit var dir: File

    /**
     * DataStore em memória: testa a lógica dos repositórios. (O DataStore em arquivo não consegue renomear por cima do
     * arquivo no Windows da máquina de desenvolvimento; no Android isso não acontece e é código da biblioteca.)
     */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    /** Jogo salvo no meio de uma partida, com eventos públicos (inclusive pegar o lixo). */
    private fun savedGame(mode: GameMode = GameMode.DUPLAS): SavedGame {
        var match = startMatch(mode, targetScore = 1500, random = Random(3))
        val events = mutableListOf<SavedEvent>()
        repeat(40) {
            val round = match.currentRound
            if (round.phase == Phase.FINISHED) return@repeat
            val seat = round.currentSeat
            val action = RoundEngine.legalActions(round, seat).last()
            val taken = if (action is Action.TakeDiscardPile) round.discardPile.dropLast(1) else emptyList()
            match = match.play(seat, action)
            events += SavedEvent(seat.index, action, taken)
        }
        return SavedGame(
            gameId = "jogo-1",
            config = SavedConfig(mode, "medio", 1500),
            gameSeed = 42,
            match = match,
            events = events,
        )
    }

    @Test
    fun `jogo salvo faz ida e volta pelo arquivo`() = runBlocking<Unit> {
        val store = FileSavedGameStore(File(dir, "saved.json"))
        store.load().shouldBeNull()
        val game = savedGame()
        store.save(game)
        store.load() shouldBe game
        // Escrita atômica: o temporário não sobra.
        File(dir, "saved.json.tmp").exists() shouldBe false
        store.clear()
        store.load().shouldBeNull()
    }

    @Test
    fun `arquivo corrompido, vazio, incompleto ou de outra versao vira sem jogo salvo`() = runBlocking<Unit> {
        val file = File(dir, "saved.json")
        val store = FileSavedGameStore(file)
        val valid = FileSavedGameStore.JSON.encodeToString(SavedGame.serializer(), savedGame())
        for (content in listOf("", "{", "não é json", "[]", valid.take(valid.length / 2), valid.replace("\"formatVersion\":1", "\"formatVersion\":99"), "{\"formatVersion\":1}")) {
            file.writeText(content)
            store.load().shouldBeNull()
        }
        // E o app consegue salvar por cima.
        store.save(savedGame())
        store.load() shouldBe savedGame()
    }

    @Test
    fun `escrita interrompida deixa um temporario que nao estraga o jogo salvo`() = runBlocking<Unit> {
        val file = File(dir, "saved.json")
        val store = FileSavedGameStore(file)
        val game = savedGame()
        store.save(game)
        // Simula um processo morto no meio da escrita: temporário pela metade, original intacto.
        File(dir, "saved.json.tmp").writeText("{\"formatVersion\":1,\"gameId\":")
        store.load() shouldBe game
        store.save(game.copy(gameId = "jogo-2"))
        store.load()?.gameId shouldBe "jogo-2"
    }

    @Test
    fun `eventos guardam acao e cartas levadas do lixo`() {
        val event = SavedEvent(2, Action.Discard(Card.parse("7H'")), listOf(Card.parse("3S")))
        val json = FileSavedGameStore.JSON.encodeToString(SavedEvent.serializer(), event)
        FileSavedGameStore.JSON.decodeFromString(SavedEvent.serializer(), json) shouldBe event
    }

    @Test
    fun `§14 configuracoes - ultimo modo escolhido, individual na primeira vez`() = runBlocking<Unit> {
        val settings = DataStoreSettingsRepository(MemoryDataStore())
        settings.settings.first() shouldBe Settings(GameMode.INDIVIDUAL, null)
        settings.setLastMode(GameMode.DUPLAS)
        settings.setHandSort("CUSTOM")
        settings.settings.first() shouldBe Settings(GameMode.DUPLAS, "CUSTOM")
    }

    @Test
    fun `§13_1 estatisticas contam cada jogo uma unica vez e desistencia e derrota`() = runBlocking<Unit> {
        val stats = DataStoreStatsRepository(MemoryDataStore())
        stats.record("a", GameMode.INDIVIDUAL, "medio", GameResult.WIN) shouldBe true
        stats.record("a", GameMode.INDIVIDUAL, "medio", GameResult.WIN) shouldBe false // restaurado e terminado de novo
        stats.record("b", GameMode.INDIVIDUAL, "medio", GameResult.RESIGNATION) shouldBe true
        stats.record("c", GameMode.INDIVIDUAL, "medio", GameResult.LOSS) shouldBe true
        stats.record("d", GameMode.DUPLAS, "dificil", GameResult.WIN) shouldBe true
        val all = stats.stats.first()
        all.line(GameMode.INDIVIDUAL, "medio") shouldBe StatsLine(games = 3, wins = 1, losses = 1, resignations = 1)
        all.line(GameMode.INDIVIDUAL, "medio").defeats shouldBe 2
        all.line(GameMode.INDIVIDUAL, "medio").winPercent shouldBe 33
        all.line(GameMode.DUPLAS, "dificil") shouldBe StatsLine(games = 1, wins = 1)
        all.line(GameMode.DUPLAS, "facil") shouldBe StatsLine()

        stats.reset()
        stats.stats.first().lines shouldBe emptyMap()
        // Zerar não faz um jogo já contado contar de novo.
        stats.record("a", GameMode.INDIVIDUAL, "medio", GameResult.WIN) shouldBe false
    }
}
