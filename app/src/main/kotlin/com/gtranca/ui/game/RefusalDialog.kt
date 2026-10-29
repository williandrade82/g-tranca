package com.gtranca.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.data.RefusalAlert
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.Spacing

/**
 * Modal de jogada não permitida (docs/telas.md 4): explica o que aconteceu, qual regra da mesa causa a recusa e o que
 * fazer. Não fecha sozinho; só um dos três botões de *Ações impedidas* (ou voltar/tocar fora) o dispensa. O conteúdo vem
 * de [MoveRefusal].
 */
@Composable
fun RefusalDialog(content: RefusalContent, onChoose: (RefusalAlert) -> Unit, onDismiss: () -> Unit) {
    GDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("refusal-dialog"),
        title = {
            Text(
                refusalString(content.title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                RefusalSection(R.string.refusal_section_happened, listOf(content.happened))
                if (content.rule.isNotEmpty()) RefusalSection(R.string.refusal_section_rule, content.rule)
                if (content.tip.isNotEmpty()) RefusalSection(R.string.refusal_section_tip, content.tip)
            }
        },
        confirmButton = {
            // "Continuar avisando" só fecha; as outras duas gravam a preferência (Perfil, Ações impedidas) e fecham.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("refusal-keep")) {
                    Text(stringResource(R.string.refusal_keep), style = MaterialTheme.typography.titleMedium)
                }
                GButton(
                    { onChoose(RefusalAlert.SUBTLE); onDismiss() },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("refusal-subtle"),
                    kind = GButtonKind.Secondary,
                ) { Text(stringResource(R.string.refusal_subtle)) }
                GButton(
                    { onChoose(RefusalAlert.NONE); onDismiss() },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("refusal-none"),
                    kind = GButtonKind.Text,
                ) { Text(stringResource(R.string.refusal_none)) }
            }
        },
    )
}

@Composable
private fun RefusalSection(headingRes: Int, paragraphs: List<RefusalText>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(headingRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = GColors.Gold,
            modifier = Modifier.semantics { heading() },
        )
        paragraphs.forEach { Text(refusalString(it), style = MaterialTheme.typography.bodyMedium, color = GColors.Ivory) }
    }
}

/** Texto do [RefusalText], trocando o [RuleLabel] pelo nome da regra da mesa. */
@Composable
private fun refusalString(text: RefusalText): String {
    val custom = stringResource(R.string.rules_custom)
    val args = text.args.map {
        when (it) {
            is RuleLabel.Named -> it.name
            RuleLabel.Custom -> custom
            else -> it
        }
    }
    return stringResource(text.res, *args.toTypedArray())
}
