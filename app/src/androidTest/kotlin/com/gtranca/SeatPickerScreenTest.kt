package com.gtranca

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.gtranca.net.TableRules
import com.gtranca.ui.multiplayer.LocalSeatPicker
import com.gtranca.ui.multiplayer.SeatPickerActions
import com.gtranca.ui.multiplayer.SeatPickerScreen
import com.gtranca.ui.multiplayer.SeatPickerUi
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §16.2 "Escolha sua posição" (docs/telas.md 3.13) na mesa só com robôs (sem rede). */
@RunWith(AndroidJUnit4::class)
class SeatPickerScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val rules = TableRules("Mesa de Ana", GameMode.DUPLAS, RuleSet.CLASSICA, 3000, 50, Difficulty.MEDIO.id)

    @Test
    fun robos_ja_sentados_rotacao_troca_e_confirmar_trava() {
        val picker = LocalSeatPicker(GameMode.DUPLAS, listOf("jovem"), seed = 1L, startDelayMillis = 60_000)
        rule.setContent {
            val seating by picker.seating.collectAsState()
            GTrancaTheme {
                SeatPickerScreen(
                    SeatPickerUi(seating, LocalSeatPicker.ME, mapOf(LocalSeatPicker.ME to "Ana"), rules = rules),
                    balance = 1000,
                    actions = SeatPickerActions(picker::rotate, picker::confirm, {}, {}),
                    initialName = "Ana",
                )
            }
        }
        // Sem VAGO: os 4 assentos ocupados; o jogador embaixo e o robô escolhido à direita dele.
        rule.onNode(hasTestTag("seat-slot-0") and hasText("Ana", substring = true)).assertIsDisplayed()
        rule.onNode(hasTestTag("seat-slot-1") and hasText("Bolt", substring = true)).assertIsDisplayed()
        // Girar à direita troca com o robô da direita.
        rule.onNodeWithTag("seat-picker-rotate-right").performClick()
        rule.onNode(hasTestTag("seat-slot-1") and hasText("Ana", substring = true)).assertExists()
        rule.onNodeWithTag("seat-picker-confirm").performClick()
        rule.onNodeWithTag("seat-picker-waiting").assertIsNotEnabled()
        // Todos confirmados: as marcas aparecem também nos robôs.
        rule.onNodeWithTag("seat-slot-0-confirmed", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("seat-slot-3-confirmed", useUnmergedTree = true).assertExists()
    }
}
