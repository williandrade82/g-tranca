package com.gtranca

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ui.home.WelcomeScreen
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Títulos de destaque em telas estreitas e fonte grande: diminuem até caber, nunca quebram no meio da palavra. */
@RunWith(AndroidJUnit4::class)
class TitleFitTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun logotipoDasBoasVindasCabeNumaLinhaEm320dpComFonteGrande() {
        var size by mutableStateOf(320 to 1.5f)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, size.second)) {
                GTrancaTheme { Box(Modifier.width(size.first.dp).height(640.dp)) { WelcomeScreen {} } }
            }
        }
        for (width in listOf(320, 360, 411)) for (font in listOf(1f, 1.3f, 1.5f)) {
            size = width to font
            rule.waitForIdle()
            val node = rule.onAllNodesWithText("TRANCA-BOT", useUnmergedTree = true).fetchSemanticsNodes().first()
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.single()
            check(layout.lineCount == 1 && !layout.didOverflowWidth) { "TRANCA-BOT quebrou ou passou da largura em ${width}dp, fonte $font" }
        }
    }
}
