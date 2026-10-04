package com.gtranca.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File

/**
 * Acesso único à persistência do app: jogo salvo (arquivo JSON no armazenamento interno), configurações e
 * estatísticas (DataStore Preferences, um arquivo cada). Uma instância por processo ([get]): o DataStore exige um só
 * objeto por arquivo.
 */
class GameData private constructor(context: Context) {

    val savedGames: SavedGameStore = FileSavedGameStore(File(context.filesDir, SAVED_GAME_FILE))

    val settings: SettingsRepository = DataStoreSettingsRepository(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SETTINGS_FILE) },
    )

    val stats: StatsRepository = DataStoreStatsRepository(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(STATS_FILE) },
    )

    companion object {
        const val SAVED_GAME_FILE = "saved_game.json"
        private const val SETTINGS_FILE = "settings"
        private const val STATS_FILE = "stats"

        @Volatile
        private var instance: GameData? = null

        fun get(context: Context): GameData =
            instance ?: synchronized(this) { instance ?: GameData(context.applicationContext).also { instance = it } }
    }
}
