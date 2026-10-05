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

/** Fluxos Início → Mesa: compra, duplas e desistência. */
@RunWith(AndroidJUnit4::class)
class HomeToTableTest {

    @get:Rule(order = 0)
    val clean = CleanDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val isHandCard = SemanticsMatcher("carta da mão") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("hand-card-") == true
    }

    private fun handSize(): Int = rule.onAllNodes(isHandCard, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** §6.5 confirma a encenação de 3 vermelho do humano, se estiver na tela (a semente do jogo é aleatória). */
    private fun confirmRedThrees(): Boolean {
        if (!exists("red-three-confirm")) return false
        rule.onNodeWithTag("red-three-confirm").performClick()
        rule.waitForIdle()
        return true
    }

    /** Espera até a condição, confirmando no caminho as encenações de 3 vermelho. */
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis) {
            confirmRedThrees()
            condition()
        }
    }

    private fun canDraw(): Boolean = rule.onAllNodes(hasTestTag("action-draw") and isEnabled()).fetchSemanticsNodes().isNotEmpty()

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

        // A vez chega ao humano (o bot pode começar; trocas de 3 vermelho são encenadas antes).
        waitFor(30_000) { canDraw() }
        val before = handSize()
        before shouldBeAtLeast 1
        rule.onNodeWithTag("action-draw").performClick()
        waitFor(5_000) { exists("action-discard") && !exists("red-three-dialog") }
        handSize() shouldBeAtLeast before + 1
    }

    @Test
    fun duplasAbreAMesaComParceiroEAdversariosEOHumanoJoga() {
        rule.onNodeWithTag("mode-duplas").performClick()
        rule.onNodeWithTag("difficulty-facil").performClick()
        rule.onNodeWithTag("new-game").performClick()
        rule.onNodeWithTag("table-screen").assertExists()
        rule.onNodeWithText("Parceiro").assertExists()
        rule.onNodeWithText("Adv. esquerda").assertExists()
        rule.onNodeWithText("Adv. direita").assertExists()
        rule.onNodeWithText("Jogos da sua dupla").assertExists()

        waitFor(60_000) { canDraw() }
        // Só a mão do humano é desenhada (a do parceiro nunca aparece).
        handSize() shouldBeAtMost 20
        val before = handSize()
        rule.onNodeWithTag("action-draw").performClick()
        waitFor(5_000) { exists("action-discard") && !exists("red-three-dialog") }
        handSize() shouldBeAtLeast before + 1
        // Descarta a primeira carta: a vez passa ao adversário à direita (§4.2), depois ao parceiro.
        rule.onAllNodes(isHandCard, useUnmergedTree = true)[0].performClick()
        rule.onNodeWithTag("action-discard").performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("Vez do parceiro…").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("seat-thinking-2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun desistirPedeConfirmacaoECancelarVoltaAoJogo() {
        rule.onNodeWithTag("new-game").performClick()
        rule.onNodeWithTag("table-screen").assertExists()
        waitFor(30_000) { canDraw() }

        // §13.1 cancelar: o jogo continua exatamente como estava.
        rule.onNodeWithTag("action-resign").performClick()
        rule.onNodeWithText("Desistir do jogo?").assertExists()
        rule.onNodeWithTag("resign-cancel").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("resign-dialog").fetchSemanticsNodes().isEmpty() shouldBe true
        canDraw() shouldBe true

        // Confirmar: fim de jogo com o outro lado vencedor.
        rule.onNodeWithTag("action-resign").performClick()
        rule.onNodeWithTag("resign-confirm").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { exists("game-over") }
        rule.onNodeWithText("Você desistiu. O adversário venceu.").assertExists()
    }

    private infix fun Boolean.shouldBe(expected: Boolean) {
        check(this == expected) { "Esperado $expected, obtido $this" }
    }

    private infix fun Int.shouldBeAtMost(max: Int) {
        check(this <= max) { "Esperado ≤ $max, obtido $this" }
    }

    private infix fun Int.shouldBeAtLeast(min: Int) {
        check(this >= min) { "Esperado ≥ $min, obtido $this" }
    }
}
