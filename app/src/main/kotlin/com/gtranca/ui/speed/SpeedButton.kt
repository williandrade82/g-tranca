package com.gtranca.ui.speed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gtranca.R

/** Velocidade das animações do jogador e como invertê-la (grava nas preferências); fornecida pelo app todo. */
data class SpeedSettings(val slow: Boolean = true, val toggle: () -> Unit = {})

val LocalSpeedSettings = androidx.compose.runtime.staticCompositionLocalOf { SpeedSettings() }

/**
 * Botão de velocidade das animações (a mesma chave do Perfil): tartaruga com a velocidade 30% mais lenta, setas duplas
 * "avançar" com a velocidade normal. Alvo de toque de 48 dp, ícone desenhado no código.
 */
@Composable
fun SpeedButton(color: Color, modifier: Modifier = Modifier) {
    val settings = LocalSpeedSettings.current
    val description = stringResource(if (settings.slow) R.string.speed_toggle_slow else R.string.speed_toggle_normal)
    Box(
        modifier
            .size(48.dp)
            .clickable(onClickLabel = description, role = Role.Switch, onClick = settings.toggle)
            .semantics { contentDescription = description }
            .testTag("speed-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        SpeedIcon(slow = settings.slow, color = color)
    }
}

/** Ícone de velocidade: tartaruga (lenta) ou setas duplas (normal). */
@Composable
fun SpeedIcon(slow: Boolean, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) { if (slow) drawTurtle(color) else drawFastForward(color) }
}

private fun DrawScope.drawFastForward(color: Color) {
    val w = size.width
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    listOf(0.14f, 0.5f).forEach { x ->
        val chevron = Path().apply {
            moveTo(w * x, w * 0.2f); lineTo(w * (x + 0.3f), w * 0.5f); lineTo(w * x, w * 0.8f)
        }
        drawPath(chevron, color, style = stroke)
    }
}

private fun DrawScope.drawTurtle(color: Color) {
    val w = size.width
    // Casco (meia-lua), cabeça à direita, cauda à esquerda e duas patas.
    drawArc(color, 180f, 180f, true, Offset(w * 0.12f, w * 0.26f), Size(w * 0.6f, w * 0.6f))
    drawCircle(color, w * 0.1f, Offset(w * 0.84f, w * 0.52f))
    val stroke = w * 0.09f
    drawLine(color, Offset(w * 0.32f, w * 0.58f), Offset(w * 0.3f, w * 0.84f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.56f, w * 0.58f), Offset(w * 0.58f, w * 0.84f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.12f, w * 0.58f), Offset(w * 0.04f, w * 0.66f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.72f, w * 0.52f), Offset(w * 0.76f, w * 0.52f), stroke, StrokeCap.Round)
}
