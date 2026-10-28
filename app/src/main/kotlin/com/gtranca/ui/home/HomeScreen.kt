package com.gtranca.ui.home

import com.gtranca.ui.theme.Corner
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import com.gtranca.engine.Coins
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.BuildConfig
import com.gtranca.R
import com.gtranca.ui.game.plainPoints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import com.gtranca.ui.theme.GDialog
import com.gtranca.ai.Difficulty
import com.gtranca.game.GameConfig
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode

/** Início: "Continuar" o jogo salvo ou "Novo jogo", que abre as mesas disponíveis (§14.4). */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewGame: () -> Unit,
    onContinue: () -> Unit = {},
    onContinueSession: () -> Unit = {},
    onStats: () -> Unit = {},
    onInventory: () -> Unit = {},
    onProfile: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Ao voltar para esta tela, o jogo salvo pode ter mudado (jogado, terminado).
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Novo jogo confirmado: o salvo antigo já foi apagado.
    LaunchedEffect(state.pendingNewGame) {
        if (state.pendingNewGame) {
            viewModel.onStartHandled()
            onNewGame()
        }
    }
    GBackground(Modifier.fillMaxSize(), royal = true, rays = true) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Conteúdo centralizado na vertical; se a tela for baixa, rola (a altura mínima é a da área disponível).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Acima de tudo: os quatro robôs (WebP reduzido; as bordas de baixo, cortadas na arte, esmaecem).
            Image(
                painterResource(R.drawable.home_all_bots),
                contentDescription = stringResource(R.string.home_bots_description),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .aspectRatio(900f / 508f)
                    .testTag("home-bots")
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                contentScale = ContentScale.Fit,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LogoTitle(stringResource(R.string.home_title))
                Text(
                    stringResource(R.string.home_title_tagline),
                    textAlign = TextAlign.Center,
                    style = goldTitleStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)),
                )
            }

            // §14.6 saldo da carteira e ganho diário (um resgate por dia).
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.coins_balance, state.balance),
                    Modifier.testTag("home-balance"),
                    style = MaterialTheme.typography.titleMedium,
                    color = GColors.Gold,
                )
                if (state.canClaimDaily) {
                    GButton(viewModel::onClaimDaily, Modifier.heightIn(min = 40.dp).testTag("claim-daily"), kind = GButtonKind.Secondary) {
                        Text(stringResource(R.string.home_daily_claim, Coins.DAILY_GAIN), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

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

            // §16.12 sessão multijogador guardada: "Continuar mesa", ao lado do jogo salvo contra robôs (cada um no seu botão).
            state.session?.let { session ->
                GButton(
                    onClick = onContinueSession,
                    enabled = state.ready,
                    kind = if (state.saved == null) GButtonKind.Primary else GButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("continue-session"),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.home_continue_table), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.home_continue_table_info, session.hostName, session.seat),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            val newGameModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("new-game")
            val startNew = { if (viewModel.onNewGame()) onNewGame() }
            GButton(
                onClick = { startNew() },
                enabled = state.ready,
                kind = if (state.saved == null) GButtonKind.Primary else GButtonKind.Secondary,
                modifier = newGameModifier,
            ) {
                Text(stringResource(R.string.home_new_game), style = MaterialTheme.typography.titleMedium)
            }
        }
        }
            // Barra inferior fixa (fora da rolagem): Perfil e Estatísticas; a versão, discreta, logo abaixo.
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = Spacing.sm)
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
                // §17 só o ícone do armário (com três botões com texto a barra não cabe em telas estreitas).
                val inventoryLabel = stringResource(R.string.home_inventory)
                GButton(
                    onInventory,
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = inventoryLabel }.testTag("inventory"),
                    kind = GButtonKind.Text,
                ) {
                    com.gtranca.ui.inventory.InventoryIcon(GColors.Gold)
                }
                GButton(onStats, Modifier.heightIn(min = 48.dp).testTag("stats"), kind = GButtonKind.Text) {
                    StatsIcon(GColors.Gold)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.home_stats))
                }
            }
            Text(
                stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = GColors.OnTable.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = Spacing.xs).testTag("home-version"),
            )
        }
        // Por cima da rolagem (senão a coluna rolável engole o toque): sempre à vista, mesmo com jogo salvo.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopEnd) { SoundButton(GColors.OnTable) }
    }

    // §17.5 Presente do dia: ícone, nome e quantidade do booster sorteado.
    state.gift?.let { gift ->
        GDialog(
            onDismissRequest = viewModel::onGiftDismissed,
            modifier = Modifier.testTag("gift-modal"),
            title = { Text(stringResource(R.string.gift_title)) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    com.gtranca.ui.inventory.boosterIconRes(gift.boosterId)?.let {
                        Image(painterResource(it), null, Modifier.size(112.dp).testTag("gift-icon"), contentScale = ContentScale.Fit)
                    }
                    Text(gift.name, style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("gift-name"))
                    Text(stringResource(R.string.gift_quantity, gift.quantity), style = MaterialTheme.typography.titleMedium, color = GColors.Ivory, modifier = Modifier.testTag("gift-quantity"))
                }
            },
            confirmButton = {
                GButton(viewModel::onGiftDismissed, Modifier.heightIn(min = 48.dp).testTag("gift-ok")) { Text(stringResource(R.string.gift_ok)) }
            },
        )
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
internal fun LogoTitle(text: String) {
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
    // Uma linha só, reduzindo a fonte até caber (nunca quebra "TRANCA-BOT" no meio).
    Box(Modifier.fillMaxWidth().testTag("logo-title")) {
        com.gtranca.ui.theme.FitText(text, base, Modifier.fillMaxWidth())
        if (t > -0.3f && t < 1.3f) {
            com.gtranca.ui.theme.FitText(
                text,
                style = base.copy(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, GColors.Champagne.copy(alpha = 0.95f), Color.White, GColors.Champagne.copy(alpha = 0.95f), Color.Transparent),
                        start = Offset(t * 900f - 160f, 0f),
                        end = Offset(t * 900f + 160f, 160f),
                    ),
                    shadow = null,
                ),
                modifier = Modifier.fillMaxWidth(),
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
internal fun RowScope.ModeCard(text: String, seats: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corner.md)
    // Duplas: nome curto no cartão e a explicação embaixo, menor (não quebra em 3 linhas).
    val hint = if (seats == 2) stringResource(R.string.mode_duplas_hint) else null
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(
                if (selected) Brush.verticalGradient(listOf(GColors.RoyalBlue, GColors.RoyalEnd))
                else Brush.verticalGradient(listOf(GColors.IndigoLight, GColors.Indigo)),
                shape,
            )
            .border(if (selected) 2.dp else 1.dp, if (selected) GColors.Gold else GColors.Steel.copy(alpha = 0.4f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.sm)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.width(88.dp).height(36.dp)) {
            val r = size.height * if (seats == 2) 0.2f else 0.26f
            val ring = if (selected) GColors.Gold else GColors.Steel
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
            color = if (selected) GColors.Ivory else GColors.Steel,
            textAlign = TextAlign.Center,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (selected) GColors.Champagne else GColors.Steel, textAlign = TextAlign.Center)
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
