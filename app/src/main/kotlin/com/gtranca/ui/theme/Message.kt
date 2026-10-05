package com.gtranca.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Tom de uma mensagem: informação (verde), atenção (amarelo) ou ação sem volta (vermelho). */
enum class MessageTone { Info, Warning, Danger }

/** Ilustração da mensagem, desenhada no código. */
enum class MessageIllustration { Question, Info, Warning, Flag, Exit, Trash, Cards }

private fun MessageTone.color(): Color = when (this) {
    MessageTone.Info -> GColors.Green
    MessageTone.Warning -> GColors.Yellow
    MessageTone.Danger -> GColors.Red
}

private fun MessageTone.onColor(): Color = if (this == MessageTone.Warning) GColors.CardBlack else GColors.White

/** Medalha redonda com a ilustração da mensagem, colorida pelo [tone]. */
@Composable
fun MessageBadge(illustration: MessageIllustration, tone: MessageTone, size: Dp = 76.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .shadow(Elevation.button, CircleShape)
            .background(tone.color(), CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val ink = tone.onColor()
        when (illustration) {
            MessageIllustration.Question -> Glyph("?", ink, size)
            MessageIllustration.Info -> Glyph("i", ink, size)
            MessageIllustration.Warning -> Glyph("!", ink, size)
            else -> MessageIcon(illustration, ink, Modifier.size(size * 0.52f))
        }
    }
}

@Composable
private fun Glyph(text: String, color: Color, badge: Dp) {
    Text(text, color = color, fontSize = (badge.value * 0.56f).sp, fontWeight = FontWeight.ExtraBold)
}

/** Desenho de linhas das ilustrações que não são letras. */
@Composable
private fun MessageIcon(illustration: MessageIllustration, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val line = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (illustration) {
            MessageIllustration.Flag -> {
                drawLine(color, Offset(w * 0.2f, w * 0.06f), Offset(w * 0.2f, w * 0.96f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(w * 0.26f, w * 0.1f); lineTo(w * 0.92f, w * 0.3f); lineTo(w * 0.26f, w * 0.55f); close()
                }
                drawPath(flag, color)
            }
            MessageIllustration.Exit -> {
                // Porta (aberta à direita) e seta saindo.
                val door = Path().apply {
                    moveTo(w * 0.55f, w * 0.1f); lineTo(w * 0.12f, w * 0.1f); lineTo(w * 0.12f, w * 0.9f); lineTo(w * 0.55f, w * 0.9f)
                }
                drawPath(door, color, style = line)
                drawLine(color, Offset(w * 0.4f, w * 0.5f), Offset(w * 0.92f, w * 0.5f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.74f, w * 0.32f), Offset(w * 0.92f, w * 0.5f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.74f, w * 0.68f), Offset(w * 0.92f, w * 0.5f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
            }
            MessageIllustration.Trash -> {
                drawLine(color, Offset(w * 0.1f, w * 0.24f), Offset(w * 0.9f, w * 0.24f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.38f, w * 0.1f), Offset(w * 0.62f, w * 0.1f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
                val bin = Path().apply {
                    moveTo(w * 0.2f, w * 0.34f); lineTo(w * 0.26f, w * 0.92f); lineTo(w * 0.74f, w * 0.92f); lineTo(w * 0.8f, w * 0.34f)
                }
                drawPath(bin, color, style = line)
            }
            MessageIllustration.Cards -> {
                listOf(0.04f to 0.2f, 0.28f to 0.12f, 0.52f to 0.04f).forEach { (x, y) ->
                    drawRoundRect(
                        color, Offset(w * x, w * y), Size(w * 0.44f, w * 0.7f), CornerRadius(w * 0.08f),
                        style = Stroke(width = w * 0.09f),
                    )
                }
            }
            else -> Unit
        }
    }
}

/**
 * Mensagem de confirmação ou aviso do app: medalha ilustrada, título curto, texto e botões grandes do tema. O botão de
 * confirmar é vermelho no tom [MessageTone.Danger] e amarelo nos demais; "cancelar" é discreto.
 */
@Composable
fun GMessage(
    title: String,
    illustration: MessageIllustration,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    tone: MessageTone = MessageTone.Info,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    confirmTag: String? = null,
    dismissTag: String? = null,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier.widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = GColors.Cream,
            shadowElevation = Elevation.dialog,
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                MessageBadge(illustration, tone)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = GColors.CardBlack,
                    textAlign = TextAlign.Center,
                )
                text?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = GColors.CardBlack, textAlign = TextAlign.Center) }
                GButton(
                    onConfirm,
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (confirmTag != null) it.testTag(confirmTag) else it },
                    kind = if (tone == MessageTone.Danger) GButtonKind.Danger else GButtonKind.Primary,
                ) { Text(confirmText, style = MaterialTheme.typography.titleMedium) }
                dismissText?.let {
                    GButton(
                        onDismiss,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).let { m -> if (dismissTag != null) m.testTag(dismissTag) else m },
                        kind = GButtonKind.Quiet,
                    ) { Text(it) }
                }
            }
        }
    }
}

/** Aviso rápido (barra inferior): cartão escuro com borda amarela e o "!" de atenção. */
@Composable
fun GSnack(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(Spacing.md)
            .shadow(Elevation.dialog, MaterialTheme.shapes.large)
            .background(GColors.TableDark, MaterialTheme.shapes.large)
            .border(2.dp, GColors.Yellow, MaterialTheme.shapes.large)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        MessageBadge(MessageIllustration.Warning, MessageTone.Warning, size = 32.dp)
        Text(message, Modifier.weight(1f), color = GColors.OnTable, style = MaterialTheme.typography.bodyMedium)
    }
}
