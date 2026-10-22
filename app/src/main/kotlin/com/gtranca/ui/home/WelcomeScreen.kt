package com.gtranca.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.Spacing

/**
 * §14.1 boas-vindas (docs/telas.md 3.0): aparece no lugar do Início enquanto o perfil não tem nome. O único botão abre o
 * Perfil; com o nome salvo, o Início volta a aparecer.
 */
@Composable
fun WelcomeScreen(onProfile: () -> Unit) {
    GBackground(Modifier.fillMaxSize(), royal = true, rays = true) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.xl).testTag("welcome-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            LogoTitle(stringResource(R.string.home_title))
            Text(
                stringResource(R.string.welcome_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = GColors.Gold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.welcome_text),
                Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = GColors.Champagne,
                textAlign = TextAlign.Center,
            )
            GButton(
                onProfile,
                Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 56.dp).testTag("welcome-profile"),
                kind = GButtonKind.Primary,
            ) { Text(stringResource(R.string.welcome_go_profile), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
