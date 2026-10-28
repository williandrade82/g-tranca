package com.gtranca

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ui.tables.RefreshButton
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §16.15 o botão de atualizar chama a busca em rajada uma vez e fica desabilitado enquanto ela roda (5 s). */
@RunWith(AndroidJUnit4::class)
class RefreshButtonTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tocarAtualizaUmaVezEDesabilitaDuranteARajada() {
        var calls = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { GTrancaTheme { RefreshButton("refresh-players") { calls++ } } }
        rule.onNodeWithTag("refresh-players").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("refresh-players").assertIsNotEnabled().performClick()
        check(calls == 1) { "chamadas: $calls" }
        rule.mainClock.advanceTimeBy(5_200)
        rule.onNodeWithTag("refresh-players").assertIsEnabled()
    }
}
