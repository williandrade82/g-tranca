package com.gtranca.ui.home

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gtranca.ui.theme.GoldBrush
import com.gtranca.ui.theme.goldTitleStyle
import com.gtranca.ui.theme.rememberMotionEnabled
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
import androidx.compose.ui.unit.sp
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
    GBackground(Modifier.fillMaxSize(), royal = true, rays = true) {
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
            CrownOrnament(Modifier.width(120.dp).height(40.dp))
            LogoTitle(stringResource(R.string.home_title))
            Text(
                stringResource(R.string.home_title_tagline),
                style = goldTitleStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp)),
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
                    ModeCard(stringResource(R.string.mode_individual), 1, state.mode == GameMode.INDIVIDUAL, "mode-individual") {
                        viewModel.onModeChange(GameMode.INDIVIDUAL)
                    }
                    ModeCard(stringResource(R.string.mode_duplas_short), 2, state.mode == GameMode.DUPLAS, "mode-duplas") {
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
            // Barra inferior em cápsula: Perfil e Estatísticas lado a lado (cabem na tela sem rolar).
            Row(
                Modifier
                    .background(GColors.Midnight.copy(alpha = 0.6f), CircleShape)
                    .border(1.dp, GColors.Gold.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GButton(onProfile, Modifier.heightIn(min = 48.dp).testTag("profile"), kind = GButtonKind.Text) {
                    ProfileGlyph(GColors.Gold)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.home_profile))
                }
                GButton(onStats, Modifier.heightIn(min = 48.dp).testTag("stats"), kind = GButtonKind.Text) {
                    StatsIcon(GColors.Gold)
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
        Text(title, style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = chips)
    }
}

@Composable
private fun RowScope.Chip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.weight(1f).testTag(tag))
}

/** Logotipo em Cinzel com degradê dourado; um brilho percorre o texto de tempos em tempos. */
@Composable
private fun LogoTitle(text: String) {
    val motion = rememberMotionEnabled()
    val t = if (motion) {
        val v by rememberInfiniteTransition(label = "logo").animateFloat(
            -0.3f, 1.3f,
            infiniteRepeatable(keyframes { durationMillis = 5000; -0.3f at 0; -0.3f at 3600; 1.3f at 5000 }),
            label = "logo-sheen",
        )
        v
    } else -1f
    val base = goldTitleStyle(MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black, textAlign = TextAlign.Center))
    Box {
        Text(text, style = base)
        if (t > -0.3f && t < 1.3f) {
            Text(
                text,
                style = base.copy(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, GColors.Champagne.copy(alpha = 0.95f), Color.White, GColors.Champagne.copy(alpha = 0.95f), Color.Transparent),
                        start = Offset(t * 900f - 160f, 0f),
                        end = Offset(t * 900f + 160f, 160f),
                    ),
                    shadow = null,
                ),
            )
        }
    }
}

/** Coroa com naipes vazados (ornamento vetorial do logotipo). */
@Composable
private fun CrownOrnament(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val crown = Path().apply {
            moveTo(w * 0.3f, h * 0.9f)
            lineTo(w * 0.27f, h * 0.3f); lineTo(w * 0.4f, h * 0.55f); lineTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.6f, h * 0.55f); lineTo(w * 0.73f, h * 0.3f); lineTo(w * 0.7f, h * 0.9f); close()
        }
        drawPath(crown, GoldBrush)
        drawPath(crown, GColors.GoldDeep, style = Stroke(1.5.dp.toPx()))
        listOf(0.27f, 0.5f, 0.73f).forEach { x -> drawCircle(GColors.Champagne, h * 0.07f, Offset(w * x, if (x == 0.5f) h * 0.1f else h * 0.28f)) }
        // Losangos laterais.
        listOf(0.1f, 0.9f).forEach { x ->
            val d = Path().apply {
                moveTo(w * x, h * 0.35f); lineTo(w * x + h * 0.15f, h * 0.6f); lineTo(w * x, h * 0.85f); lineTo(w * x - h * 0.15f, h * 0.6f); close()
            }
            drawPath(d, GColors.Gold, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Cartão grande de modo com ilustração ([seats] medalhões por lado: 1 × 1 ou 2 × 2). */
@Composable
private fun RowScope.ModeCard(text: String, seats: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    // Duplas: nome curto no cartão e a explicação embaixo, menor (não quebra em 3 linhas).
    val hint = if (seats == 2) stringResource(R.string.mode_duplas_hint) else null
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(
                if (selected) Brush.verticalGradient(listOf(GColors.Amethyst, GColors.RoyalEnd))
                else Brush.verticalGradient(listOf(GColors.IndigoLight, GColors.Indigo)),
                shape,
            )
            .border(if (selected) 2.dp else 1.dp, if (selected) GColors.Gold else GColors.Lavender.copy(alpha = 0.4f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.sm)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.width(88.dp).height(36.dp)) {
            val r = size.height * if (seats == 2) 0.2f else 0.26f
            val ring = if (selected) GColors.Gold else GColors.Lavender
            fun seat(x: Float, y: Float, own: Boolean) {
                drawCircle(if (own) GColors.Gold.copy(alpha = 0.85f) else GColors.Midnight, r, Offset(x, y))
                drawCircle(ring, r, Offset(x, y), style = Stroke(2.dp.toPx()))
            }
            val cy = size.height / 2
            if (seats == 1) {
                seat(size.width * 0.25f, cy, true); seat(size.width * 0.75f, cy, false)
            } else {
                seat(size.width * 0.1f, cy, true); seat(size.width * 0.3f, cy, true)
                seat(size.width * 0.7f, cy, false); seat(size.width * 0.9f, cy, false)
            }
            // "×" no meio.
            val c = Offset(size.width / 2, cy); val d = r * 0.5f
            drawLine(GColors.Champagne, c + Offset(-d, -d), c + Offset(d, d), 2.dp.toPx())
            drawLine(GColors.Champagne, c + Offset(-d, d), c + Offset(d, -d), 2.dp.toPx())
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) GColors.Ivory else GColors.Lavender,
            textAlign = TextAlign.Center,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (selected) GColors.Champagne else GColors.Lavender, textAlign = TextAlign.Center)
        }
    }
}

/** Ícone de perfil (linha fina): cabeça e ombros. */
@Composable
private fun ProfileGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = Stroke(1.75.dp.toPx())
        drawCircle(color, w * 0.2f, Offset(w / 2, w * 0.32f), style = stroke)
        drawArc(color, 200f, 140f, false, Offset(w * 0.15f, w * 0.58f), Size(w * 0.7f, w * 0.7f), style = stroke)
    }
}
