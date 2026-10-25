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

    /** §6.5 a troca de 3 vermelho do humano é só um aviso que fecha sozinho: não há o que confirmar. */
    private fun confirmRedThrees(): Boolean = false

    /** Espera até a condição, confirmando no caminho as encenações de 3 vermelho. */
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis) {
            confirmRedThrees()
            condition()
        }
    }

    private fun canDraw(): Boolean = rule.onAllNodes(hasTestTag("action-draw") and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun alvoInvalidoDesabilitaEscolherPosicoes() {
        openTables()
        rule.onNodeWithTag("target-field").performTextReplacement("0")
        rule.onNodeWithText("Informe um número inteiro positivo").assertExists()
        rule.onNodeWithTag("choose-seats").assertIsNotEnabled()
        rule.onNodeWithTag("target-field").performTextReplacement("1500")
        rule.onNodeWithTag("choose-seats").assertIsEnabled()
    }

    /** §14.4 "Novo jogo" abre a montagem da mesa ("Mesas disponíveis"). */
    private fun openTables() {
        rule.onNodeWithTag("new-game").performClick()
        rule.waitUntil(10_000) { exists("tables-screen") }
    }

    /** §16.2 "Escolher posições" → a mesa só com robôs: confirmar e, 2 s depois, o jogo. */
    private fun chooseSeatsAndStart() {
        rule.onNodeWithTag("choose-seats").performClick()
        rule.waitUntil(10_000) { exists("seat-picker-confirm") }
        rule.onNodeWithTag("seat-picker-confirm").performClick()
        rule.waitUntil(10_000) { exists("table-screen") }
    }

    @Test
    fun montagemComRobosEscolhidosLevaAEscolhaDePosicoesEAoJogo() {
        openTables()
        rule.onNodeWithTag("participant-self").assertExists()
        rule.onNodeWithTag("participant-bot-atleta").performClick()
        rule.onNodeWithTag("participant-bot-jovem").performClick()
        // 3 participantes: duplas, sem escolha.
        rule.onNodeWithTag("mode-individual").assertIsNotEnabled()
        rule.onNodeWithTag("choose-seats").performClick()
        rule.waitUntil(10_000) { exists("seat-picker") }
        rule.onNodeWithTag("seat-slot-1").assertExists()
        rule.onNodeWithTag("seat-picker-confirm").performClick()
        rule.waitUntil(10_000) { exists("table-screen") }
    }

    @Test
    fun novoJogoAbreAMesaEOHumanoCompra() {
        openTables()
        rule.onNodeWithTag("mode-individual").performClick()
        rule.onNodeWithTag("difficulty-facil").performClick()
        chooseSeatsAndStart()

        // A vez chega ao humano (o bot pode começar; trocas de 3 vermelho são encenadas antes).
        waitFor(30_000) { canDraw() }
        val before = handSize()
        before shouldBeAtLeast 1
        rule.onNodeWithTag("action-draw").performClick()
        waitFor(5_000) { exists("action-discard") && !exists("red-three-banner") }
        handSize() shouldBeAtLeast before + 1
    }

    @Test
    fun duplasAbreAMesaComParceiroEAdversariosEOHumanoJoga() {
        openTables()
        rule.onNodeWithTag("mode-duplas").performClick()
        rule.onNodeWithTag("difficulty-facil").performClick()
        chooseSeatsAndStart()
        rule.onNodeWithText("Parceiro").assertExists()
        rule.onNodeWithText("Adv. esq.").assertExists()
        rule.onNodeWithText("Adv. dir.").assertExists()
        rule.onNodeWithText("Jogos da sua dupla").assertExists()

        waitFor(60_000) { canDraw() }
        // Só a mão do humano é desenhada (a do parceiro nunca aparece).
        handSize() shouldBeAtMost 20
        val before = handSize()
        rule.onNodeWithTag("action-draw").performClick()
        waitFor(5_000) { exists("action-discard") && !exists("red-three-banner") }
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
        openTables()
        rule.onNodeWithTag("mode-individual").performClick()
        chooseSeatsAndStart()
        waitFor(30_000) { canDraw() }

        // §13.1 cancelar: o jogo continua exatamente como estava.
        rule.onNodeWithTag("table-options").performClick()
        rule.onNodeWithTag("action-resign").performClick()
        rule.onNodeWithText("Desistir do jogo?").assertExists()
        rule.onNodeWithTag("resign-cancel").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("resign-dialog").fetchSemanticsNodes().isEmpty() shouldBe true
        canDraw() shouldBe true

        // Confirmar: fim de jogo com o outro lado vencedor.
        rule.onNodeWithTag("table-options").performClick()
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
