package com.gtranca

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gtranca.data.GameData
import com.gtranca.data.GameResult
import com.gtranca.data.Settings
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** DataStore com arquivo corrompido (no aparelho, com o mesmo criador do app): valores padrão, sem derrubar o app. */
@RunWith(AndroidJUnit4::class)
class DataStoreCorruptionTest {

    private fun corruptFile(name: String): File {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "corrupt-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "$name.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x55, 0x00, 0x13)) }
    }

    @Test
    fun preferenciasCorrompidasViramPadraoEVoltamAGravar() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = GameData.settingsAt(corruptFile("settings"), scope)
            runBlocking {
                assertEquals(Settings(), settings.settings.first())
                settings.setLastMode(GameMode.DUPLAS)
                assertEquals(GameMode.DUPLAS, settings.settings.first().lastMode)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun estatisticasCorrompidasViramZeroEVoltamAContar() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stats = GameData.statsAt(corruptFile("stats"), scope)
            runBlocking {
                assertTrue(stats.stats.first().lines.isEmpty())
                assertTrue(stats.record("x", GameMode.INDIVIDUAL, "medio", GameResult.WIN))
                assertEquals(1, stats.stats.first().line(GameMode.INDIVIDUAL, "medio").games)
            }
        } finally {
            scope.cancel()
        }
    }
}
