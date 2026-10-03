package com.gtranca.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.ai.Difficulty
import com.gtranca.game.GameConfig
import com.gtranca.ui.cards.CardBack
import com.gtranca.ui.cards.CardSize
import com.gtranca.ui.cards.PlayingCard
import com.gtranca.engine.model.Card

/** Início (§14): modo, dificuldade (padrão médio) e pontuação-alvo (padrão 3000, inteiro positivo). */
@Composable
fun HomeScreen(viewModel: HomeViewModel, onStart: (GameConfig) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card.parse("AS"), size = CardSize.LARGE, describe = false)
                PlayingCard(Card.parse("KH"), size = CardSize.LARGE, describe = false)
                CardBack(size = CardSize.LARGE, describe = false)
            }
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)

            Section(stringResource(R.string.home_mode)) {
                Choice(stringResource(R.string.mode_individual), selected = true, enabled = true, tag = "mode-individual") {}
                Choice(stringResource(R.string.mode_duplas_soon), selected = false, enabled = false, tag = "mode-duplas") {}
            }

            Section(stringResource(R.string.home_difficulty)) {
                Difficulty.entries.forEach { difficulty ->
                    Choice(
                        text = stringResource(difficulty.labelRes()),
                        selected = state.difficulty == difficulty,
                        enabled = true,
                        tag = "difficulty-${difficulty.id}",
                    ) { viewModel.onDifficultyChange(difficulty) }
                }
            }

            OutlinedTextField(
                value = state.targetText,
                onValueChange = viewModel::onTargetChange,
                label = { Text(stringResource(R.string.home_target)) },
                isError = state.targetError,
                supportingText = if (state.targetError) {
                    { Text(stringResource(R.string.home_target_error)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("target-field"),
            )

            Button(
                onClick = { state.toConfig()?.let(onStart) },
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new-game"),
            ) { Text(stringResource(R.string.home_new_game)) }
        }
    }
}

fun Difficulty.labelRes(): Int = when (this) {
    Difficulty.FACIL -> R.string.difficulty_facil
    Difficulty.MEDIO -> R.string.difficulty_medio
    Difficulty.DIFICIL -> R.string.difficulty_dificil
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text, Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}
