package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedEvent
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.data.SettingsRepository
import com.gtranca.data.StatsRepository
import com.gtranca.engine.model.Seat

/** Conversões entre os tipos do jogo e os DTOs do `:data` (que não depende do `:ai`). */
fun PublicEvent.toSaved(): SavedEvent = SavedEvent(seat.index, action, takenFromDiscard)

fun SavedEvent.toPublic(): PublicEvent = PublicEvent(Seat(seat), action, takenFromDiscard)

fun GameConfig.toSaved(): SavedConfig = SavedConfig(mode, difficulty.id, targetScore)

fun SavedConfig.toConfig(): GameConfig = GameConfig(mode, Difficulty.fromId(difficultyId), targetScore)

fun SavedGame.toRestored(): RestoredGame = RestoredGame(match, events.map { it.toPublic() }, botRandomCalls)

fun savedGameOf(gameId: String, config: GameConfig, gameSeed: Long, snapshot: SaveSnapshot): SavedGame = SavedGame(
    gameId = gameId,
    config = config.toSaved(),
    gameSeed = gameSeed,
    match = snapshot.match,
    events = snapshot.events.map { it.toSaved() },
    botRandomCalls = snapshot.botRandomCalls,
)

/** O que o jogo em andamento grava. Interface para os testes. */
interface GamePersistence {
    /** Grava (substitui) o jogo salvo. */
    suspend fun save(game: SavedGame)

    /**
     * Fim do jogo (§13, §13.1): apaga o jogo salvo e depois registra a estatística (uma vez por [gameId]). Nessa
     * ordem, repetir (ex.: o processo morreu no meio e o jogo foi retomado) nunca conta o jogo duas vezes.
     */
    suspend fun finish(gameId: String, config: SavedConfig, result: GameResult)

    /** Ordem da mão preferida. */
    suspend fun saveHandSort(id: String)
}

/** Persistência real, no `:data`. */
class DataGamePersistence(
    private val savedGames: SavedGameStore,
    private val stats: StatsRepository,
    private val settings: SettingsRepository,
) : GamePersistence {
    constructor(data: GameData) : this(data.savedGames, data.stats, data.settings)

    override suspend fun save(game: SavedGame) = savedGames.save(game)

    override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) {
        savedGames.clear()
        stats.record(gameId, config.mode, config.difficultyId, result)
    }

    override suspend fun saveHandSort(id: String) = settings.setHandSort(id)
}
