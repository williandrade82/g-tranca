package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import java.io.IOException
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resultado de um jogo do ponto de vista do humano. A desistência (§13.1) conta como derrota. */
enum class GameResult { WIN, LOSS, RESIGNATION }

/** Grupo das estatísticas: modo e dificuldade (identificador, ex.: "medio"). */
data class StatsKey(val mode: GameMode, val difficultyId: String)

/** Contagens de um grupo. [losses] não inclui as desistências; [defeats] soma as duas. */
data class StatsLine(val games: Int = 0, val wins: Int = 0, val losses: Int = 0, val resignations: Int = 0) {
    val defeats: Int get() = losses + resignations

    /** Porcentagem de vitórias (0 sem jogos). */
    val winPercent: Int get() = if (games == 0) 0 else (wins * 100 + games / 2) / games
}

data class GameStats(val lines: Map<StatsKey, StatsLine> = emptyMap()) {
    fun line(mode: GameMode, difficultyId: String): StatsLine = lines[StatsKey(mode, difficultyId)] ?: StatsLine()
}

interface StatsRepository {
    val stats: Flow<GameStats>

    /**
     * Registra o fim do jogo [gameId]. Cada jogo conta uma única vez: devolve `false` (e não muda nada) se esse id
     * já foi registrado — inclusive depois de restaurar um jogo salvo.
     */
    suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean

    /** Zera as estatísticas (os ids já registrados continuam lembrados, para não contar de novo). */
    suspend fun reset()
}

/** Estatísticas no DataStore Preferences: um contador por (modo, dificuldade, campo) e os ids recentes já contados. */
class DataStoreStatsRepository(private val store: DataStore<Preferences>) : StatsRepository {

    /** Arquivo ilegível: estatísticas zeradas (nunca derruba o app). */
    override val stats: Flow<GameStats> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        val lines = mutableMapOf<StatsKey, StatsLine>()
        prefs.asMap().forEach { (key, value) ->
            val parts = key.name.split(':')
            if (parts.size != 4 || parts[0] != PREFIX || value !is Int) return@forEach
            val mode = GameMode.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
            val statsKey = StatsKey(mode, parts[2])
            val line = lines[statsKey] ?: StatsLine()
            lines[statsKey] = when (parts[3]) {
                GAMES -> line.copy(games = value)
                WINS -> line.copy(wins = value)
                LOSSES -> line.copy(losses = value)
                RESIGNATIONS -> line.copy(resignations = value)
                else -> line
            }
        }
        GameStats(lines)
    }

    override suspend fun record(gameId: String, mode: GameMode, difficultyId: String, result: GameResult): Boolean {
        var recorded = false
        store.edit { prefs ->
            val ids = prefs[RECORDED_IDS].orEmpty().split(',').filter { it.isNotEmpty() }
            if (gameId in ids) return@edit
            fun bump(field: String) {
                val key = counter(mode, difficultyId, field)
                prefs[key] = (prefs[key] ?: 0) + 1
            }
            bump(GAMES)
            bump(
                when (result) {
                    GameResult.WIN -> WINS
                    GameResult.LOSS -> LOSSES
                    GameResult.RESIGNATION -> RESIGNATIONS
                },
            )
            prefs[RECORDED_IDS] = (ids + gameId).takeLast(MAX_REMEMBERED_IDS).joinToString(",")
            recorded = true
        }
        return recorded
    }

    override suspend fun reset() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("$PREFIX:") }.forEach { prefs.remove(it) }
        }
    }

    private companion object {
        const val PREFIX = "stats"
        const val GAMES = "games"
        const val WINS = "wins"
        const val LOSSES = "losses"
        const val RESIGNATIONS = "resignations"

        /** Basta lembrar os últimos ids: só há um jogo salvo por vez, então só o atual pode ser recontado. */
        const val MAX_REMEMBERED_IDS = 50
        val RECORDED_IDS = stringPreferencesKey("recorded_game_ids")

        fun counter(mode: GameMode, difficultyId: String, field: String): Preferences.Key<Int> =
            intPreferencesKey("$PREFIX:${mode.name}:$difficultyId:$field")
    }
}
