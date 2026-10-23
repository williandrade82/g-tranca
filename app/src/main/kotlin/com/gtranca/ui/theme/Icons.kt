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

/** §16 ícone de Wi-Fi (três arcos e um ponto), desenhado no código; [crossed] = desligado (um risco por cima). */
@Composable
fun WifiIcon(color: Color, crossed: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.09f
        val base = Offset(w / 2, w * 0.82f)
        drawCircle(color, w * 0.07f, base)
        listOf(0.22f, 0.40f, 0.58f).forEach { fr ->
            val r = w * fr
            drawArc(color, 225f, 90f, false, Offset(base.x - r, base.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (crossed) drawLine(color, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), stroke, StrokeCap.Round)
    }
}

/** §16 ícone de mesa (tampo oval com pés), para o menu do anfitrião. */
@Composable
fun TableIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.09f
        drawOval(color, Offset(w * 0.08f, w * 0.22f), Size(w * 0.84f, w * 0.3f), style = Stroke(stroke))
        drawLine(color, Offset(w * 0.24f, w * 0.48f), Offset(w * 0.2f, w * 0.88f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, w * 0.48f), Offset(w * 0.8f, w * 0.88f), stroke, StrokeCap.Round)
    }
}

/** §16.2 seta curva de girar a mesa; [clockwise] = para a direita. */
@Composable
fun RotateIcon(color: Color, clockwise: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.1f
        val r = w * 0.32f
        val center = Offset(w / 2, w / 2)
        val start = if (clockwise) 200f else 340f
        val sweep = if (clockwise) 250f else -250f
        drawArc(color, start, sweep, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
        val end = (start + sweep) * PI.toFloat() / 180f
        val tip = Offset(center.x + r * cos(end), center.y + r * sin(end))
        val tangent = end + if (clockwise) PI.toFloat() / 2 else -PI.toFloat() / 2
        val head = w * 0.2f
        val path = Path().apply {
            moveTo(tip.x + head * cos(tangent), tip.y + head * sin(tangent))
            lineTo(tip.x + head * 0.8f * cos(tangent + 2.2f), tip.y + head * 0.8f * sin(tangent + 2.2f))
            lineTo(tip.x + head * 0.8f * cos(tangent - 2.2f), tip.y + head * 0.8f * sin(tangent - 2.2f))
            close()
        }
        drawPath(path, color, style = Fill)
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

/** Ícone de engrenagem (Opções), desenhado no código: aro com oito dentes e furo no centro. */
@Composable
fun GearIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val center = Offset(w / 2, w / 2)
        val tooth = w * 0.13f
        repeat(8) { i ->
            val a = i * PI.toFloat() / 4
            val r = w * 0.36f
            drawLine(color, Offset(center.x + r * 0.9f * cos(a), center.y + r * 0.9f * sin(a)), Offset(center.x + r * 1.25f * cos(a), center.y + r * 1.25f * sin(a)), strokeWidth = tooth * 1.4f, cap = StrokeCap.Butt)
        }
        // Aro (anel) com o furo do centro transparente.
        drawCircle(color, w * 0.24f, center, style = Stroke(w * 0.18f))
    }
}

/** Ícone de olho (mostrar) ou olho riscado (escondido), desenhado no código: "Esconder cartas". */
@Composable
fun EyeIcon(color: Color, crossed: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        val eye = Path().apply {
            moveTo(w * 0.06f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.05f, w * 0.94f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.95f, w * 0.06f, h * 0.5f)
            close()
        }
        drawPath(eye, color, style = Stroke(stroke))
        drawCircle(color, w * 0.14f, Offset(w / 2, h / 2))
        if (crossed) drawLine(color, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.88f, h * 0.12f), stroke, cap = StrokeCap.Round)
    }
}

/** §16.15 sinal da conexão: 3 barras crescentes, acesas até [bars] (1 a 3); as outras apagadas. */
@Composable
fun SignalIcon(bars: Int, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.24f
        val gap = (w - barW * 3) / 2
        repeat(3) { i ->
            val barH = h * (0.4f + 0.3f * i)
            drawRect(
                if (i < bars) color else color.copy(alpha = 0.25f),
                topLeft = Offset(i * (barW + gap), h - barH),
                size = Size(barW, barH),
            )
        }
    }
}
