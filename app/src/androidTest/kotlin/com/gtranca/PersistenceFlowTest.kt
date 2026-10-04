package com.gtranca

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameResult
import com.gtranca.engine.model.GameMode
import com.gtranca.game.WriteQueue
import com.gtranca.game.appData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Jogo salvo: "Continuar" no início, confirmação antes de um novo jogo e a tela de estatísticas. */
@RunWith(AndroidJUnit4::class)
class PersistenceFlowTest {

    @get:Rule(order = 0)
    val clean = CleanDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** As cartas da mão do humano, pela etiqueta de cada uma ("hand-card-…"), ordenadas. */
    private fun handCards(): List<String> = rule.onAllNodes(
        SemanticsMatcher("carta da mão") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("hand-card-") },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.sorted()

    @Test
    fun jogoSalvoAparecePraContinuarENovoJogoPedeConfirmacao() {
        // Sem jogo salvo: não há "Continuar".
        rule.waitForIdle()
        check(!exists("continue")) { "Continuar sem jogo salvo" }

        rule.onNodeWithTag("difficulty-facil").performClick()
        rule.onNodeWithTag("new-game").performClick()
        rule.waitUntil(30_000) {
            if (exists("red-three-confirm")) rule.onNodeWithTag("red-three-confirm").performClick()
            rule.onAllNodes(hasTestTag("action-draw") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("action-draw").performClick()
        rule.waitForIdle()
        rule.waitUntil(10_000) { handCards().isNotEmpty() }
        val hand = handCards()

        // Volta ao início: o jogo fica salvo.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Voltar ao início?").assertExists()
        rule.onNodeWithText("Sair").performClick()
        rule.waitUntil(10_000) { exists("continue") }
        rule.onNodeWithText("Individual · Fácil", substring = true).assertExists()

        // Novo jogo com jogo salvo: confirmação; cancelar não começa nada.
        rule.onNodeWithTag("new-game").performClick()
        rule.onNodeWithText("O jogo salvo será perdido.").assertExists()
        rule.onNodeWithText("Cancelar").performClick()
        rule.waitForIdle()
        check(exists("continue")) { "o jogo salvo sumiu ao cancelar" }

        // Continuar volta à mesa do mesmo jogo.
        rule.onNodeWithTag("continue").performClick()
        rule.waitUntil(10_000) { exists("table-screen") && handCards().isNotEmpty() }
        // O mesmo jogo: a mesma mão de antes de sair.
        assertEquals(hand, handCards())
    }

    @Test
    fun estatisticasMostramModosEDificuldadesEPermitemZerar() {
        // Uma vitória já contada (individual, fácil): a única linha com porcentagem.
        val stats = appData(InstrumentationRegistry.getInstrumentation().targetContext).stats
        runBlocking {
            WriteQueue.app.run {
                stats.reset()
                stats.record(UUID.randomUUID().toString(), GameMode.INDIVIDUAL, Difficulty.FACIL.id, GameResult.WIN)
            }
        }
        rule.onNodeWithTag("stats").performClick()
        rule.waitUntil(5_000) { exists("stats-screen") }
        rule.onNodeWithTag("stats-individual").assertExists()
        rule.onNodeWithTag("stats-duplas").assertExists()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("stats-reset").performClick()
        rule.onNodeWithText("Zerar estatísticas?").assertExists()
        rule.onNodeWithTag("stats-reset-confirm").performClick()
        // Zerar zera a contagem que existia.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("100%").fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { stats.stats.first() }.lines.isEmpty())
    }
}
