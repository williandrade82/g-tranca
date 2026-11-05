package com.gtranca.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Globo que gira (botão Jogar online, docs/telas.md 3.21): esfera azul com paralelos fixos e meridianos que andam,
 * desenhados no código. Parado sem animações do sistema. *Provisório*: pode virar um ícone animado próprio.
 */
@Composable
fun AnimatedGlobe(size: Dp = 26.dp, modifier: Modifier = Modifier) {
    val turn = if (rememberMotionEnabled()) {
        val t by rememberInfiniteTransition(label = "globe").animateFloat(
            0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "globe-turn",
        )
        t
    } else 0.15f
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        val line = Stroke(r * 0.09f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7FC4FF), Color(0xFF2763C9)), c, r), r)
        val ink = GColors.Ivory.copy(alpha = 0.85f)
        // Paralelos (equador e dois trópicos), fixos.
        listOf(0f, 0.5f, -0.5f).forEach { k ->
            val y = c.y + k * r
            val half = r * kotlin.math.sqrt(1f - k * k)
            drawLine(ink, Offset(c.x - half, y), Offset(c.x + half, y), line.width)
        }
        // Meridianos: elipses cuja largura acompanha a rotação.
        repeat(3) { i ->
            val a = ((turn + i / 3f) % 1f) * 2 * PI
            val w = abs(cos(a)).toFloat() * r
            drawOval(ink, Offset(c.x - w, c.y - r), Size(w * 2, r * 2), style = line)
        }
        drawCircle(GColors.Gold, r, style = Stroke(r * 0.12f))
    }
}
