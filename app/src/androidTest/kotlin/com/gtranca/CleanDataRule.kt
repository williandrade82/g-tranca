package com.gtranca

import androidx.test.platform.app.InstrumentationRegistry
import com.gtranca.game.WriteQueue
import com.gtranca.game.appData
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Começa cada teste sem jogo salvo e com o modo individual como último escolhido (§14). Deve rodar antes da regra
 * que abre a activity (`order` menor).
 */
class CleanDataRule : ExternalResource() {
    override fun before() {
        val data = appData(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            // Pela fila do app: depois das gravações que um teste anterior deixou pendentes.
            WriteQueue.app.run {
                data.savedGames.clear()
                data.settings.setLastMode(GameMode.INDIVIDUAL)
            }
        }
    }
}
