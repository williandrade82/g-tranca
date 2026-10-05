package com.gtranca.ui.home

import com.gtranca.ui.sound.SoundButton

import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import com.gtranca.ui.theme.Elevation
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GChoiceChip
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.Spacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.gtranca.ui.theme.StatsIcon
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
    onProfile: () -> Unit = {},
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
    GBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(-Spacing.sm)) {
                Fan(Card.parse("AS"), -12f)
                Fan(Card.parse("KH"), 0f)
                Fan(null, 12f)
            }
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(GColors.Shadow, Offset(0f, 6f), 12f),
                ),
                color = GColors.Yellow,
            )
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = GColors.OnTable,
                textAlign = TextAlign.Center,
            )

            // Jogo salvo: "Continuar" em destaque, com modo, dificuldade e placar.
            state.saved?.let { saved ->
                val description = savedDescription(saved)
                GButton(
                    onClick = onContinue,
                    enabled = state.ready,
                    kind = GButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("continue"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.home_continue), style = MaterialTheme.typography.titleMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }

            GPanel(Modifier.fillMaxWidth()) {
                Section(stringResource(R.string.home_mode)) {
                    Chip(stringResource(R.string.mode_individual), state.mode == GameMode.INDIVIDUAL, "mode-individual") {
                        viewModel.onModeChange(GameMode.INDIVIDUAL)
                    }
                    Chip(stringResource(R.string.mode_duplas), state.mode == GameMode.DUPLAS, "mode-duplas") {
                        viewModel.onModeChange(GameMode.DUPLAS)
                    }
                }

                Section(stringResource(R.string.home_difficulty)) {
                    Difficulty.entries.forEach { difficulty ->
                        Chip(stringResource(difficulty.labelRes()), state.difficulty == difficulty, "difficulty-${difficulty.id}") {
                            viewModel.onDifficultyChange(difficulty)
                        }
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
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("target-field"),
                )
            }

            val newGameModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("new-game")
            val startNew = { viewModel.onNewGame()?.let(onStart) }
            GButton(
                onClick = { startNew() },
                enabled = state.canStart,
                kind = if (state.saved == null) GButtonKind.Primary else GButtonKind.Secondary,
                modifier = newGameModifier,
            ) {
                Text(stringResource(R.string.home_new_game), style = MaterialTheme.typography.titleMedium)
            }
            // Perfil e Estatísticas lado a lado: os dois cabem na tela sem rolar.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                GButton(onProfile, Modifier.heightIn(min = 48.dp).testTag("profile"), kind = GButtonKind.Text) {
                    Text(stringResource(R.string.home_profile))
                }
                GButton(onStats, Modifier.heightIn(min = 48.dp).testTag("stats"), kind = GButtonKind.Text) {
                    StatsIcon(GColors.OnTable)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.home_stats))
                }
            }
        }
        // Por cima da rolagem (senão a coluna rolável engole o toque): sempre à vista, mesmo com jogo salvo.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopEnd) { SoundButton(GColors.OnTable) }
    }

    if (state.confirmNewGame) {
        GMessage(
            modifier = Modifier.testTag("new-game-dialog"),
            title = stringResource(R.string.new_game_confirm_title),
            text = stringResource(R.string.new_game_confirm_text),
            illustration = MessageIllustration.Cards,
            tone = MessageTone.Danger,
            confirmText = stringResource(R.string.new_game_confirm),
            onConfirm = viewModel::onConfirmNewGame,
            confirmTag = "new-game-confirm",
            dismissText = stringResource(R.string.cancel),
            onDismiss = viewModel::onDismissNewGame,
            onDismissRequest = viewModel::onDismissNewGame,
        )
    }
}

/** Carta do leque do topo: levemente inclinada, com sombra. `null` = verso. */
@Composable
private fun Fan(card: Card?, angle: Float) {
    Box(Modifier.rotate(angle).shadow(Elevation.dialog, MaterialTheme.shapes.small)) {
        if (card != null) PlayingCard(card, size = CardSize.LARGE, describe = false)
        else CardBack(size = CardSize.LARGE, describe = false)
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
private fun Section(title: String, chips: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = chips)
    }
}

@Composable
private fun RowScope.Chip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.weight(1f).testTag(tag))
}
