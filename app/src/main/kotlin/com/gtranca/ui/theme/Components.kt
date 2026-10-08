package com.gtranca.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Variantes de [GButton]. */
enum class GButtonKind { Primary, Secondary, Danger, DangerOutline, Text, Quiet }

/**
 * `false` quando o usuário ativou "remover animações" no Android (escala 0): efeitos decorativos
 * (brilhos, raios, pulsos) ficam parados.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** Degradê metálico do ouro (bisel: claro em cima, profundo embaixo). */
val GoldBrush: Brush = Brush.verticalGradient(listOf(GColors.Champagne, GColors.Gold, GColors.GoldDeep))

/**
 * Botão do app em cápsula. Primary = ouro biselado com brilho periódico; Secondary = índigo com borda
 * ametista; Danger = rubi. Ao tocar, afunda 3%.
 */
@Composable
fun GButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: GButtonKind = GButtonKind.Primary,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = CircleShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    val quiet = kind == GButtonKind.Text || kind == GButtonKind.Quiet
    val contentColor = when (kind) {
        GButtonKind.Primary -> GColors.OnGold
        GButtonKind.Secondary, GButtonKind.Danger -> GColors.Ivory
        GButtonKind.DangerOutline -> GColors.Ruby
        GButtonKind.Quiet -> GColors.Champagne
        GButtonKind.Text -> GColors.OnTable
    }
    val surface: Modifier = when (kind) {
        GButtonKind.Primary -> Modifier
            .shadow(Elevation.button, shape, ambientColor = GColors.GoldDeep, spotColor = GColors.GoldDeep)
            .background(GoldBrush, shape)
            .border(1.dp, GColors.Champagne.copy(alpha = 0.8f), shape)
            .sheen(enabled)
        GButtonKind.Secondary -> Modifier
            .shadow(Elevation.button, shape)
            .background(GColors.Indigo, shape)
            .border(1.5.dp, GColors.Amethyst, shape)
        GButtonKind.Danger -> Modifier
            .shadow(Elevation.button, shape)
            .background(Brush.verticalGradient(listOf(GColors.Ruby, GColors.Bordeaux)), shape)
        GButtonKind.DangerOutline -> Modifier
            .background(GColors.Indigo, shape)
            .border(1.5.dp, GColors.Ruby, shape)
        else -> Modifier
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier
                .heightIn(min = 48.dp)
                .scale(pressScale)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(shape)
                .then(surface)
                .clickable(interaction, indication = androidx.compose.material3.ripple(color = GColors.Champagne), enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(if (quiet) PaddingValues(horizontal = 12.dp, vertical = 8.dp) else PaddingValues(horizontal = 24.dp, vertical = 10.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Faixa de luz que varre o componente a cada poucos segundos (parada sem animações do sistema). */
@Composable
private fun Modifier.sheen(active: Boolean): Modifier {
    if (!active || !rememberMotionEnabled()) return this
    val t by rememberInfiniteTransition(label = "sheen").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            keyframes { durationMillis = 4200; -0.4f at 0; -0.4f at 3000; 1.4f at 4200 },
        ),
        label = "sheen-x",
    )
    return drawWithContent {
        drawContent()
        val x = size.width * t
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, GColors.Champagne.copy(alpha = 0.55f), Color.Transparent),
                start = Offset(x - size.height, 0f),
                end = Offset(x + size.height, size.height),
            ),
        )
    }
}

/**
 * Fundo das telas: degradê meia-noite → royal, raios suaves (girando muito devagar quando [rays]) e
 * partículas douradas estáticas.
 */
@Composable
fun GBackground(modifier: Modifier = Modifier, royal: Boolean = false, rays: Boolean = false, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val angle = if (rays && motion) {
        val a by rememberInfiniteTransition(label = "rays").animateFloat(
            0f, 360f, infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart), label = "rays-angle",
        )
        a
    } else 0f
    val particles = remember { List(40) { Triple(Random(it * 31 + 7).nextFloat(), Random(it * 17 + 3).nextFloat(), Random(it * 13 + 1).nextFloat()) } }
    val colors = if (royal) listOf(GColors.RoyalStart, GColors.RoyalEnd, GColors.Midnight) else listOf(GColors.Midnight, GColors.Indigo, GColors.RoyalEnd.copy(alpha = 0.9f))
    Box(
        modifier
            .background(Brush.verticalGradient(colors))
            .drawWithContent {
                if (rays) drawRays(angle, if (royal) 0.07f else 0.05f)
                particles.forEach { (px, py, pr) ->
                    drawCircle(GColors.Champagne.copy(alpha = 0.10f + pr * 0.15f), radius = 0.8.dp.toPx() + pr * 1.6.dp.toPx(), center = Offset(px * size.width, py * size.height))
                }
                drawContent()
            },
    ) { content() }
}

/** Raios de luz a partir do centro superior; [alpha] ≤ 0,1. */
fun DrawScope.drawRays(angle: Float, alpha: Float, center: Offset = Offset(size.width / 2, size.height * 0.28f), count: Int = 12) {
    val r = size.maxDimension * 1.2f
    rotate(angle, center) {
        repeat(count) { i ->
            val a0 = (i * 2 * PI / count).toFloat()
            val a1 = a0 + (PI / count * 0.6).toFloat()
            val p = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + r * cos(a0), center.y + r * sin(a0))
                lineTo(center.x + r * cos(a1), center.y + r * sin(a1))
                close()
            }
            drawPath(p, Brush.radialGradient(listOf(GColors.Champagne.copy(alpha = alpha), Color.Transparent), center, r))
        }
    }
}

/** Painel índigo translúcido de cantos 20dp, com borda dourada fina e sombra arroxeada. */
@Composable
fun GPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    CompositionLocalProvider(LocalContentColor provides GColors.Ivory) {
        Column(
            modifier
                .shadow(Elevation.dialog, shape, ambientColor = GColors.RoyalEnd, spotColor = GColors.RoyalEnd)
                .background(GColors.Indigo.copy(alpha = 0.92f), shape)
                .border(1.dp, GColors.Gold.copy(alpha = 0.4f), shape)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

/**
 * Moldura de destaque: borda dourada dupla com bisel (ouro profundo → champanhe) e pontos de luz nas
 * quinas. Só para a mesa e faixas/painéis de destaque.
 */
fun Modifier.goldFrame(corner: Dp = 20.dp, width: Dp = 3.dp, lights: Boolean = true): Modifier = drawWithContent {
    drawContent()
    val w = width.toPx()
    val r = corner.toPx()
    val bevel = Brush.linearGradient(listOf(GColors.Champagne, GColors.GoldDeep, GColors.Gold, GColors.GoldDeep), Offset.Zero, Offset(size.width, size.height))
    drawRoundRect(bevel, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r), style = Stroke(w))
    val inset = w * 2.2f
    drawRoundRect(
        GColors.Champagne.copy(alpha = 0.55f), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
        CornerRadius((r - inset).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()),
    )
    if (lights) {
        val d = r * 0.42f + w / 2
        listOf(Offset(d, d), Offset(size.width - d, d), Offset(d, size.height - d), Offset(size.width - d, size.height - d)).forEach {
            drawCircle(Brush.radialGradient(listOf(Color.White, GColors.Champagne.copy(alpha = 0f)), it, w * 2.4f), w * 2.4f, it)
        }
    }
}

/** Ficha selecionável (substitui o botão de opção): cápsula índigo; selecionada = ametista com borda ouro. */
@Composable
fun GChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = CircleShape
    val fill = if (selected) GColors.Amethyst else GColors.Indigo
    val border = if (selected) GColors.Gold else GColors.Lavender.copy(alpha = 0.5f)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .shadow(if (selected) Elevation.button else Elevation.card, shape)
            .clip(shape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) GColors.Ivory else GColors.Lavender,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Diálogo padrão do app (painel índigo arredondado). */
@Composable
fun GDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(1.dp, GColors.Gold.copy(alpha = 0.5f), MaterialTheme.shapes.extraLarge),
        dismissButton = dismissButton,
        title = title,
        text = text,
        properties = properties,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = Elevation.dialog,
        containerColor = GColors.Indigo,
        titleContentColor = GColors.Gold,
        textContentColor = GColors.Ivory,
    )
}

/** Ícone de estatísticas (três barras), desenhado no código. */
@Composable
fun StatsIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bar = w * 0.22f
        val radius = CornerRadius(bar * 0.3f)
        listOf(0.45f, 0.75f, 1f).forEachIndexed { i, fraction ->
            val x = w * (0.06f + i * 0.32f)
            drawRoundRect(color, Offset(x, h * (1f - fraction)), Size(bar, h * fraction), radius)
        }
    }
}

/** Estilo de título dourado em Cinzel (degradê metálico + contorno escuro por sombra). */
fun goldTitleStyle(base: TextStyle): TextStyle = base.copy(
    fontFamily = Cinzel,
    brush = GoldBrush,
    shadow = Shadow(Color(0xCC000000), Offset(0f, 3f), 6f),
)

/** Título de tela: Cinzel em ouro com degradê. */
@Composable
fun GTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = goldTitleStyle(MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold)),
    )
}

/** Faixa de destaque (resultado): [color] de fundo com moldura dourada, texto em [onColor]. */
@Composable
fun GBanner(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(Elevation.button, shape)
            .background(Brush.verticalGradient(listOf(color, color.darken(0.75f))), shape)
            .goldFrame(16.dp, 2.dp, lights = false),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            style = MaterialTheme.typography.titleLarge,
            color = onColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** Bloco interno de um painel (índigo mais claro). */
@Composable
fun GSubPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(GColors.IndigoLight, RoundedCornerShape(16.dp))
            .border(1.dp, GColors.Gold.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

/**
 * Cápsula contadora: [icon] dourado + número, pílula escura com borda dourada (monte, mortos, cartas
 * dos oponentes, placar). Algarismos tabulares.
 */
@Composable
fun GCounterPill(
    value: String,
    modifier: Modifier = Modifier,
    icon: (@Composable BoxScope.() -> Unit)? = null,
    gold: Boolean = false,
) {
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(if (gold) GoldBrush else Brush.verticalGradient(listOf(GColors.Midnight.copy(alpha = 0.85f), GColors.Indigo.copy(alpha = 0.85f))), shape)
            .border(1.dp, if (gold) GColors.Champagne else GColors.Gold.copy(alpha = 0.7f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Box(Modifier.size(14.dp), contentAlignment = Alignment.Center, content = icon)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TabularNums),
            color = if (gold) GColors.OnGold else GColors.Ivory,
        )
    }
}

/**
 * Medalhão: aro dourado biselado de 3dp com filete champanhe interno. [active] = vez do jogador: o
 * aro respira em ouro (e ganha um anel externo, para não depender só da cor).
 */
@Composable
fun GMedallion(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = rememberMotionEnabled()
    val glow = if (active && motion) {
        val g by rememberInfiniteTransition(label = "medal").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "medal-glow",
        )
        g
    } else if (active) 1f else 0f
    Box(
        modifier
            .drawWithContent {
                drawContent()
                val ring = 3.dp.toPx()
                val r = size.minDimension / 2
                drawCircle(Brush.linearGradient(listOf(GColors.Champagne, GColors.GoldDeep, GColors.Gold), Offset.Zero, Offset(size.width, size.height)), r - ring / 2, style = Stroke(ring))
                drawCircle(GColors.Champagne.copy(alpha = 0.8f), r - ring - 0.6.dp.toPx(), style = Stroke(0.8.dp.toPx()))
                if (glow > 0f) {
                    drawCircle(GColors.Gold.copy(alpha = glow), r + 2.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                }
            }
            .padding(3.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)
