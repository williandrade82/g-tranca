package com.gtranca

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        rule.waitUntil(10_000) { exists("table-screen") }
    }

    @Test
    fun estatisticasMostramModosEDificuldadesEPermitemZerar() {
        rule.onNodeWithTag("stats").performClick()
        rule.waitUntil(5_000) { exists("stats-screen") }
        rule.onNodeWithTag("stats-individual").assertExists()
        rule.onNodeWithTag("stats-duplas").assertExists()
        rule.onNodeWithTag("stats-reset").performClick()
        rule.onNodeWithText("Zerar estatísticas?").assertExists()
        rule.onNodeWithTag("stats-reset-confirm").performClick()
        rule.waitForIdle()
    }
}
