package com.gtranca.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Ícone de reciclagem (duas setas em círculo), desenhado no código: "sortear outros jogadores". */
@Composable
fun RecycleIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.11f
        val r = w * 0.34f
        val center = Offset(w / 2, w / 2)
        listOf(25f, 205f).forEach { start ->
            drawArc(color, start, 125f, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
            // Ponta da seta no fim do arco.
            val end = (start + 125f) * PI.toFloat() / 180f
            val tip = Offset(center.x + r * cos(end), center.y + r * sin(end))
            val tangent = end + PI.toFloat() / 2
            val head = w * 0.17f
            val path = Path().apply {
                moveTo(tip.x + head * cos(tangent), tip.y + head * sin(tangent))
                lineTo(tip.x + head * 0.8f * cos(tangent + 2.2f), tip.y + head * 0.8f * sin(tangent + 2.2f))
                lineTo(tip.x + head * 0.8f * cos(tangent - 2.2f), tip.y + head * 0.8f * sin(tangent - 2.2f))
                close()
            }
            drawPath(path, color, style = Fill)
        }
    }
}

/** Ícone de "ver regras": uma lista (pontos e linhas) com uma lupa por cima, desenhado no código. */
@Composable
fun RulesIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawRulesIcon(color) }
}

private fun DrawScope.drawRulesIcon(color: Color) {
    val w = size.width
    val line = w * 0.075f
    // Lista: três linhas com marcador.
    listOf(0.18f, 0.40f, 0.62f).forEach { fy ->
        val y = w * fy
        drawCircle(color, w * 0.05f, Offset(w * 0.12f, y))
        drawLine(color, Offset(w * 0.24f, y), Offset(w * 0.62f, y), line, StrokeCap.Round)
    }
    // Lupa no canto inferior direito.
    val c = Offset(w * 0.64f, w * 0.60f)
    val r = w * 0.2f
    drawCircle(color, r, c, style = Stroke(line * 1.2f))
    val d = r * 0.72f
    drawLine(color, Offset(c.x + d, c.y + d), Offset(w * 0.93f, w * 0.89f), line * 1.4f, StrokeCap.Round)
}
