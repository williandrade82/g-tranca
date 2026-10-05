package com.gtranca.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** Variantes de [GButton]. */
enum class GButtonKind { Primary, Secondary, Danger, DangerOutline, Text }

/** Botão do app: arredondado, com sombra. Primary = amarelo; Secondary = branco com contorno; Danger = vermelho. */
@Composable
fun GButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: GButtonKind = GButtonKind.Primary,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val shadow = ButtonDefaults.buttonElevation(defaultElevation = Elevation.button)
    when (kind) {
        GButtonKind.Primary -> Button(
            onClick, modifier, enabled, shape = shape, elevation = shadow,
            colors = ButtonDefaults.buttonColors(containerColor = GColors.Yellow, contentColor = GColors.CardBlack),
            content = content,
        )
        GButtonKind.Secondary -> Button(
            onClick, modifier, enabled, shape = shape, elevation = shadow,
            colors = ButtonDefaults.buttonColors(containerColor = GColors.White, contentColor = GColors.GreenDark),
            border = BorderStroke(2.dp, GColors.Green),
            content = content,
        )
        GButtonKind.Danger -> Button(
            onClick, modifier, enabled, shape = shape, elevation = shadow,
            colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = GColors.White),
            content = content,
        )
        GButtonKind.DangerOutline -> Button(
            onClick, modifier, enabled, shape = shape, elevation = shadow,
            colors = ButtonDefaults.buttonColors(containerColor = GColors.White, contentColor = GColors.Red),
            border = BorderStroke(2.dp, GColors.Red),
            content = content,
        )
        GButtonKind.Text -> TextButton(
            onClick, modifier, enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = GColors.OnTable),
            content = content,
        )
    }
}

/** Fundo em degradê verde-mesa das telas de menu. */
@Composable
fun GBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(GColors.Table, GColors.TableDark))),
    ) { content() }
}

/** Painel claro de cantos grandes e sombra. */
@Composable
fun GPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = GColors.Cream,
        shadowElevation = Elevation.dialog,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

/** Ficha selecionável (substitui o botão de opção). */
@Composable
fun GChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = MaterialTheme.shapes.medium
    val fill = if (selected) GColors.Green else GColors.White
    val border = if (selected) GColors.Yellow else GColors.CardBorder
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .shadow(if (selected) Elevation.button else Elevation.card, shape)
            .clip(shape)
            .background(fill)
            .border(2.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) GColors.White else GColors.CardBlack,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Diálogo padrão do app (forma arredondada do tema). */
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
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        properties = properties,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = Elevation.dialog,
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

/** Título de tela sobre o fundo verde: amarelo, em negrito, com sombra. */
@Composable
fun GTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(GColors.Shadow, Offset(0f, 4f), 8f),
        ),
        color = GColors.Yellow,
    )
}

/** Faixa de destaque (resultado): [color] de fundo, texto em [onColor]. */
@Composable
fun GBanner(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color,
        contentColor = onColor,
        shadowElevation = Elevation.button,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Bloco interno de um painel (fundo creme mais forte). */
@Composable
fun GSubPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(GColors.CreamDeep, MaterialTheme.shapes.medium)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}
