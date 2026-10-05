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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.ui.game.plainPoints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import com.gtranca.ui.theme.GDialog
import com.gtranca.ai.Difficulty
import com.gtranca.game.GameConfig
import com.gtranca.ui.cards.CardBack
import com.gtranca.ui.cards.CardSize
import com.gtranca.ui.cards.PlayingCard
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode

/** Início (§14): modo, dificuldade (padrão médio) e pontuação-alvo (padrão 3000, inteiro positivo). */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStart: (GameConfig) -> Unit,
    onContinue: () -> Unit = {},
    onStats: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Ao voltar para esta tela, o jogo salvo pode ter mudado (jogado, terminado).
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Novo jogo confirmado: o salvo antigo já foi apagado.
    LaunchedEffect(state.pendingStart) {
        state.pendingStart?.let { config ->
            viewModel.onStartHandled()
            onStart(config)
        }
    }
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
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

            // Jogo salvo: "Continuar" em destaque, com modo, dificuldade e placar.
            state.saved?.let { saved ->
                val description = savedDescription(saved)
                Button(
                    onClick = onContinue,
                    enabled = state.ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("continue"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.home_continue), style = MaterialTheme.typography.titleMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }

            Section(stringResource(R.string.home_mode)) {
                Choice(stringResource(R.string.mode_individual), state.mode == GameMode.INDIVIDUAL, enabled = true, tag = "mode-individual") {
                    viewModel.onModeChange(GameMode.INDIVIDUAL)
                }
                Choice(stringResource(R.string.mode_duplas), state.mode == GameMode.DUPLAS, enabled = true, tag = "mode-duplas") {
                    viewModel.onModeChange(GameMode.DUPLAS)
                }
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
                isError = state.targetError != null,
                supportingText = when (state.targetError) {
                    null -> null
                    TargetError.INVALID -> {
                        { Text(stringResource(R.string.home_target_error)) }
                    }
                    TargetError.TOO_LARGE -> {
                        { Text(stringResource(R.string.home_target_too_large, HomeUiState.MAX_TARGET)) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("target-field"),
            )

            val newGameModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new-game")
            val startNew = { viewModel.onNewGame()?.let(onStart) }
            if (state.saved == null) {
                Button(onClick = { startNew() }, enabled = state.canStart, modifier = newGameModifier) {
                    Text(stringResource(R.string.home_new_game))
                }
            } else {
                OutlinedButton(onClick = { startNew() }, enabled = state.canStart, modifier = newGameModifier) {
                    Text(stringResource(R.string.home_new_game))
                }
            }
            TextButton(onStats, Modifier.heightIn(min = 48.dp).testTag("stats")) {
                Text(stringResource(R.string.home_stats))
            }
        }
    }

    if (state.confirmNewGame) {
        GDialog(
            modifier = Modifier.testTag("new-game-dialog"),
            onDismissRequest = viewModel::onDismissNewGame,
            title = { Text(stringResource(R.string.new_game_confirm_title)) },
            text = { Text(stringResource(R.string.new_game_confirm_text)) },
            confirmButton = {
                TextButton(viewModel::onConfirmNewGame, Modifier.testTag("new-game-confirm")) {
                    Text(stringResource(R.string.new_game_confirm))
                }
            },
            dismissButton = { TextButton(viewModel::onDismissNewGame) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "Duplas · Médio · partida 2 · Nós 214 × 700 Eles" (individual: "Você … Adversário"). */
@Composable
private fun savedDescription(saved: SavedSummary): String {
    val mode = stringResource(if (saved.mode == GameMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual)
    val own = stringResource(if (saved.mode == GameMode.DUPLAS) R.string.score_us else R.string.side_you)
    val other = stringResource(if (saved.mode == GameMode.DUPLAS) R.string.score_them else R.string.side_opponent)
    return stringResource(
        R.string.home_saved_summary,
        mode,
        stringResource(saved.difficulty.labelRes()),
        saved.roundNumber,
        own,
        plainPoints(saved.ownTotal),
        plainPoints(saved.otherTotal),
        other,
    )
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
