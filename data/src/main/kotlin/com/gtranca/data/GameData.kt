package com.gtranca.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Acesso único à persistência do app: jogo salvo (arquivo JSON no armazenamento interno), configurações e
 * estatísticas (DataStore Preferences, um arquivo cada). Uma instância por processo ([get]): o DataStore exige um só
 * objeto por arquivo.
 */
class GameData private constructor(context: Context, isKnownDifficulty: (String) -> Boolean) {

    val savedGames: SavedGameStore = FileSavedGameStore(File(context.filesDir, SAVED_GAME_FILE), isKnownDifficulty)

    val settings: SettingsRepository = DataStoreSettingsRepository(preferencesStore(context.preferencesDataStoreFile(SETTINGS_FILE)))

    val stats: StatsRepository = DataStoreStatsRepository(preferencesStore(context.preferencesDataStoreFile(STATS_FILE)))

    companion object {
        const val SAVED_GAME_FILE = "saved_game.json"
        private const val SETTINGS_FILE = "settings"
        private const val STATS_FILE = "stats"

        /**
         * DataStore Preferences em [file]. Arquivo corrompido é trocado por um vazio (valores padrão) em vez de
         * derrubar o app.
         */
        fun preferencesStore(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )

        /** Preferências num arquivo DataStore em [file] (mesmo criador do app; para testes no aparelho). */
        fun settingsAt(file: File, scope: CoroutineScope): SettingsRepository =
            DataStoreSettingsRepository(preferencesStore(file, scope))

        /** Estatísticas num arquivo DataStore em [file] (mesmo criador do app; para testes no aparelho). */
        fun statsAt(file: File, scope: CoroutineScope): StatsRepository = DataStoreStatsRepository(preferencesStore(file, scope))

        @Volatile
        private var instance: GameData? = null

        /**
         * A instância do processo. [isKnownDifficulty]: dificuldades que o app sabe jogar (um jogo salvo com outra é
         * ignorado); vale o da primeira chamada — o app sempre passa o mesmo.
         */
        fun get(context: Context, isKnownDifficulty: (String) -> Boolean): GameData = instance ?: synchronized(this) {
            instance ?: GameData(context.applicationContext, isKnownDifficulty).also { instance = it }
        }
    }
}
