package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.PublicEvent
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedEvent
import com.gtranca.data.SavedGame
import com.gtranca.data.SavedGameStore
import com.gtranca.data.WalletRepository
import com.gtranca.engine.Coins
import com.gtranca.data.SettingsRepository
import com.gtranca.data.StatsRepository
import com.gtranca.engine.model.Seat

/** Conversões entre os tipos do jogo e os DTOs do `:data` (que não depende do `:ai`). */
fun PublicEvent.toSaved(): SavedEvent = SavedEvent(seat.index, action, takenFromDiscard, takenTop)

fun SavedEvent.toPublic(): PublicEvent = PublicEvent(Seat(seat), action, takenFromDiscard, takenTop)

fun GameConfig.toSaved(): SavedConfig = SavedConfig(mode, difficulty.id, targetScore, stake)

fun SavedConfig.toConfig(): GameConfig = GameConfig(mode, Difficulty.fromId(difficultyId), targetScore, stake = stake)

fun SavedGame.toRestored(): RestoredGame = RestoredGame(match, events.map { it.toPublic() }, botRandomCalls, playerName)

fun savedGameOf(gameId: String, config: GameConfig, gameSeed: Long, snapshot: SaveSnapshot, playerName: String? = null): SavedGame = SavedGame(
    gameId = gameId,
    config = config.toSaved(),
    gameSeed = gameSeed,
    match = snapshot.match,
    events = snapshot.events.map { it.toSaved() },
    botRandomCalls = snapshot.botRandomCalls,
    playerName = playerName,
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

    /** §14.6 debita da carteira a aposta de um jogo que começa; devolve se debitou (sem carteira: sempre). */
    suspend fun debitStake(stake: Int): Boolean = true
}

/** Persistência real, no `:data`. */
class DataGamePersistence(
    private val savedGames: SavedGameStore,
    private val stats: StatsRepository,
    private val settings: SettingsRepository,
    private val wallet: WalletRepository? = null,
) : GamePersistence {
    constructor(data: GameData) : this(data.savedGames, data.stats, data.settings, data.wallet)

    override suspend fun save(game: SavedGame) = savedGames.save(game)

    override suspend fun finish(gameId: String, config: SavedConfig, result: GameResult) {
        savedGames.clear()
        // §14.6 quem vence recebe o prêmio (todos apostam o mesmo valor); derrota e desistência perdem a aposta.
        val payout = if (result == GameResult.WIN && config.stake > 0) Coins.humanPayout(config.mode.seatCount, config.stake, won = true) else 0
        stats.recordBet(gameId, config.mode, config.difficultyId, result, stake = config.stake, payout = payout)
    }

    override suspend fun debitStake(stake: Int): Boolean = wallet?.debit(stake) ?: true

    override suspend fun saveHandSort(id: String) = settings.setHandSort(id)
}
