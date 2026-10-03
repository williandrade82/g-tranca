package com.gtranca

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fluxo Início → Mesa → compra. */
@RunWith(AndroidJUnit4::class)
class HomeToTableTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun handSize(): Int =
        rule.onAllNodes(isHandCard, useUnmergedTree = true).fetchSemanticsNodes().size

    private val isHandCard = SemanticsMatcher("carta da mão") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("hand-card-") == true
    }

    @Test
    fun alvoInvalidoDesabilitaNovoJogo() {
        rule.onNodeWithTag("target-field").performTextReplacement("0")
        rule.onNodeWithText("Informe um número inteiro positivo").assertExists()
        rule.onNodeWithTag("new-game").assertIsNotEnabled()
        rule.onNodeWithTag("target-field").performTextReplacement("1500")
        rule.onNodeWithTag("new-game").assertIsEnabled()
    }

    @Test
    fun novoJogoAbreAMesaEOHumanoCompra() {
        rule.onNodeWithTag("difficulty-facil").performClick()
        rule.onNodeWithTag("new-game").performClick()
        rule.onNodeWithTag("table-screen").assertExists()

        // A vez chega ao humano (o bot pode começar): a compra fica habilitada.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasTestTag("action-draw") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val before = handSize()
        before shouldBeAtLeast 1
        rule.onNodeWithTag("action-draw").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("action-discard").fetchSemanticsNodes().isNotEmpty()
        }
        handSize() shouldBeAtLeast before + 1
    }

    @Test
    fun duplasAbreAMesaComParceiroEAdversariosEOHumanoJoga() {
        rule.onNodeWithTag("mode-duplas").performClick()
        rule.onNodeWithTag("difficulty-facil").performClick()
        rule.onNodeWithTag("new-game").performClick()
        rule.onNodeWithTag("table-screen").assertExists()
        rule.onNodeWithText("Parceiro").assertExists()
        rule.onNodeWithText("Adversário à esquerda").assertExists()
        rule.onNodeWithText("Adversário à direita").assertExists()
        rule.onNodeWithText("Jogos da sua dupla").assertExists()

        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodes(hasTestTag("action-draw") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        // Só a mão do humano é desenhada (a do parceiro nunca aparece): 11 cartas no começo da partida,
        // ou menos/mais conforme 3 vermelhos e compras já feitas — nunca as 44 da mesa.
        handSize() shouldBeAtMost 20
        val before = handSize()
        rule.onNodeWithTag("action-draw").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("action-discard").fetchSemanticsNodes().isNotEmpty()
        }
        handSize() shouldBeAtLeast before + 1
        // Descarta a primeira carta: a vez passa ao adversário à direita (§4.2), depois ao parceiro.
        rule.onAllNodes(isHandCard, useUnmergedTree = true)[0].performClick()
        rule.onNodeWithTag("action-discard").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Vez do parceiro…").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("seat-thinking-2", useUnmergedTree = true).assertExists()
    }

    private infix fun Int.shouldBeAtMost(max: Int) {
        check(this <= max) { "Esperado ≤ $max, obtido $this" }
    }

    private infix fun Int.shouldBeAtLeast(min: Int) {
        check(this >= min) { "Esperado ≥ $min, obtido $this" }
    }
}
