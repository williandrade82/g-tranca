package com.gtranca.ui.game

import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gtranca.ui.theme.GColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Resultado que a animação de fim celebra (vitória) ou ameniza (derrota). */
enum class EndOutcome { WIN, LOSE }

/** Uma partícula da animação (confete na vitória, carta caindo na derrota). Posições em frações da tela. */
data class Particle(
    val x: Float,
    val offset: Float,
    val speed: Float,
    val size: Float,
    val colorIndex: Int,
    val spin: Float,
    val sway: Float,
    val phase: Float,
)

/** Matemática das partículas (pura, testável na JVM): quedas contínuas ou de um disparo só. */
object EndParticles {
    private const val MARGIN = 0.1f

    fun create(count: Int, seed: Long): List<Particle> {
        val random = Random(seed)
        return List(count) {
            Particle(
                x = random.nextFloat(),
                offset = random.nextFloat(),
                speed = 0.6f + random.nextFloat() * 0.8f,
                size = 0.6f + random.nextFloat() * 0.7f,
                colorIndex = random.nextInt(5),
                spin = (random.nextFloat() - 0.5f) * 3f,
                sway = 0.5f + random.nextFloat() * 1.5f,
                phase = random.nextFloat() * (2 * PI).toFloat(),
            )
        }
    }

    /** Altura (0 = topo, 1 = base; fora de [0, 1] = fora da tela) no instante [t]: contínua (volta ao topo) ou de um disparo. */
    fun y(p: Particle, t: Float, looping: Boolean): Float =
        if (looping) {
            val cycle = (p.offset + t * p.speed) % 1f
            -MARGIN + (1f + 2 * MARGIN) * cycle
        } else {
            -MARGIN - p.offset * 0.5f + t * (1.4f + p.speed * 0.6f)
        }

    /** Posição horizontal com um balanço suave. */
    fun x(p: Particle, t: Float): Float = p.x + sin(t * 2 * PI.toFloat() * p.sway + p.phase) * 0.03f
}

private val ConfettiColors = listOf(GColors.Yellow, GColors.Green, GColors.Red, GColors.White, GColors.YellowSoft)

/**
 * Camada de fundo da tela final: confete caindo na vitória, cartas descendo devagar na derrota. [brief] = um disparo
 * curto (fim de partida); senão, em laço (fim de jogo). Sem animação ([enabled] falso, escala do sistema em zero),
 * não desenha nada.
 */
@Composable
fun EndCelebration(outcome: EndOutcome, brief: Boolean, enabled: Boolean, modifier: Modifier = Modifier, playSound: Boolean = true) {
    if (!enabled) return
    val sound = LocalSound.current
    // O som de vitória ou derrota toca uma vez, junto com a animação.
    LaunchedEffect(outcome) { if (playSound) sound.play(if (outcome == EndOutcome.WIN) SoundEffect.WIN else SoundEffect.LOSE) }
    val win = outcome == EndOutcome.WIN
    val particles = remember(outcome) { EndParticles.create(if (win) 70 else 14, seed = if (win) 11L else 23L) }
    val progress = remember { Animatable(0f) }
    val loop = rememberInfiniteTransition(label = "end-loop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (win) 6000 else 14000, easing = LinearEasing), RepeatMode.Restart),
        label = "end-time",
    )
    if (brief) LaunchedEffect(outcome) { progress.animateTo(1f, tween(if (win) 2600 else 4200, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize().testTag("end-celebration")) {
        val t = if (brief) progress.value else loop.value
        if (brief && t >= 1f) return@Canvas
        particles.forEach { p ->
            val y = EndParticles.y(p, t, looping = !brief)
            if (y < -0.12f || y > 1.12f) return@forEach
            val cx = EndParticles.x(p, t) * size.width
            val cy = y * size.height
            if (win) {
                val w = size.width * 0.022f * p.size
                rotate(p.spin * t * 360f + p.phase * 20f, Offset(cx, cy)) {
                    drawRoundRect(ConfettiColors[p.colorIndex], Offset(cx - w / 2, cy - w), Size(w, w * 2), CornerRadius(w * 0.2f))
                }
            } else {
                // Cartas viradas, caindo devagar, discretas.
                val w = size.width * 0.07f * p.size
                rotate(p.spin * 40f * sin(t * 2 * PI.toFloat() + p.phase), Offset(cx, cy)) {
                    drawRoundRect(GColors.CardBackFrame.copy(alpha = 0.4f), Offset(cx - w / 2, cy - w * 0.7f), Size(w, w * 1.4f), CornerRadius(w * 0.12f))
                    drawRoundRect(
                        Color.White.copy(alpha = 0.35f), Offset(cx - w / 2, cy - w * 0.7f), Size(w, w * 1.4f), CornerRadius(w * 0.12f),
                        style = Stroke(width = w * 0.06f),
                    )
                }
            }
        }
    }
}

/** Movimento do avatar: pulinhos na vitória, balanço lento na derrota. Sem animação, parado. */
fun Modifier.outcomeMotion(outcome: EndOutcome?, enabled: Boolean): Modifier = composed {
    if (outcome == null || !enabled) return@composed this
    val transition = rememberInfiniteTransition(label = "avatar-motion")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (outcome == EndOutcome.WIN) 900 else 3200, easing = LinearEasing), RepeatMode.Restart),
        label = "avatar-t",
    )
    graphicsLayer {
        val phase = t.value * 2 * PI.toFloat()
        if (outcome == EndOutcome.WIN) {
            translationY = -abs(sin(phase / 2)) * 14.dp.toPx()
            val grow = 1f + 0.05f * abs(sin(phase / 2))
            scaleX = grow
            scaleY = grow
        } else {
            rotationZ = sin(phase) * 3f
        }
    }
}

/** Troféu desenhado no código; brilha e pulsa quando [animate]. */
@Composable
fun Trophy(size: Dp, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "trophy")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "trophy-pulse",
    )
    Canvas(modifier.size(size).graphicsLayer {
        val s = if (animate) 1f + 0.06f * pulse.value else 1f
        scaleX = s
        scaleY = s
    }) {
        val w = this.size.width
        val gold = Brush.linearGradient(listOf(Color(0xFFFFE082), GColors.Yellow, Color(0xFFFF8F00)), Offset(0f, 0f), Offset(w, w))
        // Alças.
        val handle = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        drawArc(Color(0xFFFFB300), 90f, 180f, false, Offset(w * 0.04f, w * 0.14f), Size(w * 0.3f, w * 0.34f), style = handle)
        drawArc(Color(0xFFFFB300), -90f, 180f, false, Offset(w * 0.66f, w * 0.14f), Size(w * 0.3f, w * 0.34f), style = handle)
        // Taça.
        val cup = Path().apply {
            moveTo(w * 0.22f, w * 0.1f); lineTo(w * 0.78f, w * 0.1f)
            cubicTo(w * 0.78f, w * 0.5f, w * 0.64f, w * 0.64f, w * 0.5f, w * 0.64f)
            cubicTo(w * 0.36f, w * 0.64f, w * 0.22f, w * 0.5f, w * 0.22f, w * 0.1f)
            close()
        }
        drawPath(cup, gold)
        // Haste e base.
        drawRoundRect(gold, Offset(w * 0.45f, w * 0.62f), Size(w * 0.1f, w * 0.16f), CornerRadius(w * 0.02f))
        drawRoundRect(gold, Offset(w * 0.3f, w * 0.76f), Size(w * 0.4f, w * 0.12f), CornerRadius(w * 0.03f))
        // Estrela na taça e brilho.
        val star = Path().apply {
            val cx = w * 0.5f
            val cy = w * 0.34f
            val outer = w * 0.1f
            val inner = w * 0.04f
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val a = (-PI / 2 + i * PI / 5).toFloat()
                val px = cx + r * kotlin.math.cos(a)
                val py = cy + r * sin(a)
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        drawPath(star, Color(0xFFFFF8E1))
        drawArc(Color(0x66FFFFFF), 110f, 70f, false, Offset(w * 0.28f, w * 0.14f), Size(w * 0.26f, w * 0.4f), style = Stroke(width = w * 0.035f, cap = StrokeCap.Round))
    }
}
