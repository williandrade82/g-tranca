package com.gtranca

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.multiplayer.MultiplayerTable
import com.gtranca.net.MemoryNetwork
import com.gtranca.net.TableRules
import com.gtranca.ui.multiplayer.SeatPickerScreen
import com.gtranca.ui.theme.GTrancaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §16.2 "Escolha sua posição" (docs/telas.md 3.13) com a mesa no transporte em memória. */
@RunWith(AndroidJUnit4::class)
class SeatPickerScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val rules = TableRules("Mesa da família", GameMode.DUPLAS, RuleSet.CLASSICA, 3000, 50, Difficulty.MEDIO.id, listOf("jovem", "sobrio", "atleta"))

    @Test
    fun anfitriao_fica_na_posicao_1_sem_rotacao_e_inicia() {
        val network = MemoryNetwork()
        val host = MultiplayerTable(network.transport(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        runBlocking { host.host(rules, "Ana", "mesa-1") }
        rule.setContent { GTrancaTheme { SeatPickerScreen(host, 1000, {}, {}) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("seat-picker-start")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("seat-slot-0").assertIsDisplayed()
        rule.onNode(hasTestTag("seat-slot-0") and hasText("Ana", substring = true)).assertIsDisplayed()
        rule.onNodeWithTag("seat-picker-rotate-left").assertIsDisplayed()
        rule.onNodeWithTag("seat-picker-rules").assertIsDisplayed()
    }

    @Test
    fun convidado_entra_no_primeiro_vago_confirma_e_aguarda() {
        val network = MemoryNetwork()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = MultiplayerTable(network.transport(), scope)
        val guest = MultiplayerTable(network.transport(), scope)
        runBlocking {
            host.host(rules, "Ana", "mesa-1")
            guest.join("mesa-1", "Beto")
        }
        rule.setContent { GTrancaTheme { SeatPickerScreen(guest, 1000, {}, {}) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("seat-picker-confirm")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag("seat-slot-1") and hasText("Beto", substring = true)).assertIsDisplayed()
        rule.onNodeWithTag("seat-picker-confirm").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("seat-picker-waiting")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("seat-picker-waiting").assertIsNotEnabled()
    }
}
