package com.gtranca

import androidx.test.platform.app.InstrumentationRegistry
import com.gtranca.data.GameData
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Começa cada teste sem jogo salvo e com o modo individual como último escolhido (§14). Deve rodar antes da regra
 * que abre a activity (`order` menor).
 */
class CleanDataRule : ExternalResource() {
    override fun before() {
        val data = GameData.get(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            data.savedGames.clear()
            data.settings.setLastMode(GameMode.INDIVIDUAL)
        }
    }
}
