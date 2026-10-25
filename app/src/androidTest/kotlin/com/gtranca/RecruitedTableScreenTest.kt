package com.gtranca

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.Persona
import com.gtranca.net.RecruitStatus
import com.gtranca.net.RecruitTable
import com.gtranca.net.Slot
import com.gtranca.net.TableLayout
import com.gtranca.net.TableRules
import com.gtranca.ui.multiplayer.RecruitedTableScreen
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §16.2 *Mesa do recrutado* (docs/telas.md 3.13): só leitura, com os lugares sincronizados e "Sair da mesa". */
@RunWith(AndroidJUnit4::class)
class RecruitedTableScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val rules = TableRules("Mesa de Ana", GameMode.DUPLAS, RuleSet.CLASSICA, 3000, 150, Difficulty.MEDIO.id, rulesTitle = "Clássica")

    private val layout = TableLayout(
        GameMode.DUPLAS,
        listOf(Slot.Host, Slot.Robot("zen"), Slot.Recruited("bia222", "Bia"), Slot.Called("caio33", "Caio")),
    )

    @Test
    fun mostraOsLugaresDoRecrutadorSemListaDeJogadoresESaiDaMesa() {
        var left = false
        val joined = RecruitStatus.Joined("ana111", "Ana", RecruitTable("Ana", rules, layout), you = 2)
        rule.setContent {
            GTrancaTheme { RecruitedTableScreen(joined, Persona("Bia"), emptyMap()) { left = true } }
        }
        rule.onNodeWithTag("recruited-table").assertIsDisplayed()
        // o recrutado se vê embaixo (o lugar 2); o recrutador, na frente dele
        rule.onNodeWithTag("recruited-seat-2").assertIsDisplayed()
        rule.onNodeWithTag("recruited-seat-0").assertIsDisplayed()
        rule.onNodeWithTag("recruited-seat-3-called", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("recruited-summary").assertIsDisplayed()
        rule.onNodeWithTag("recruited-waiting").assertIsDisplayed()
        // só leitura: nada de lista de jogadores próximos
        rule.onNodeWithTag("selection-wifi").assertDoesNotExist()
        rule.onNodeWithTag("recruited-rules-details").performClick()
        rule.onNodeWithTag("rules-details-dialog").assertIsDisplayed()
        rule.onNodeWithTag("rules-details-close").performClick()
        rule.onNodeWithTag("recruited-leave").performClick()
        check(left) { "Sair da mesa não chamou a ação" }
    }
}
