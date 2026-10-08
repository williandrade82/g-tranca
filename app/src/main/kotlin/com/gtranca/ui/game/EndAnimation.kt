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

private val ConfettiColors = listOf(GColors.Gold, GColors.Champagne, GColors.GoldDeep, GColors.Ivory, GColors.Gold)

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
                val w = size.width * 0.013f * p.size
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
        val gold = Brush.linearGradient(listOf(GColors.Champagne, GColors.Gold, GColors.GoldDeep), Offset(0f, 0f), Offset(w, w))
        // Alças.
        val handle = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        drawArc(GColors.GoldDeep, 90f, 180f, false, Offset(w * 0.04f, w * 0.14f), Size(w * 0.3f, w * 0.34f), style = handle)
        drawArc(GColors.GoldDeep, -90f, 180f, false, Offset(w * 0.66f, w * 0.14f), Size(w * 0.3f, w * 0.34f), style = handle)
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
        drawPath(star, GColors.Ivory)
        drawArc(Color(0x66FFFFFF), 110f, 70f, false, Offset(w * 0.28f, w * 0.14f), Size(w * 0.26f, w * 0.4f), style = Stroke(width = w * 0.035f, cap = StrokeCap.Round))
    }
}

/**
 * Faixa de resultado: "VITÓRIA" em ouro com louros e coroa, descendo com um balanço; "DERROTA" em bordô,
 * sóbria e parada. Sem animação ([animate] falso), já aparece no lugar.
 */
@Composable
fun OutcomeRibbon(text: String, win: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val drop = remember { Animatable(if (animate && win) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate && win) drop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 120f)) }
    androidx.compose.foundation.layout.Box(
        modifier.graphicsLayer {
            translationY = (drop.value - 1f) * 120.dp.toPx()
            rotationZ = if (win) (1f - drop.value) * 8f else 0f
            alpha = drop.value.coerceIn(0f, 1f)
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 300.dp, height = 96.dp)) {
            val w = size.width
            val h = size.height
            val top = h * 0.3f
            val bh = h * 0.5f
            val fill = if (win) Brush.verticalGradient(listOf(GColors.Champagne, GColors.Gold, GColors.GoldDeep), top, top + bh)
                else Brush.verticalGradient(listOf(GColors.Bordeaux, Color(0xFF4A0D1D)), top, top + bh)
            val edge = if (win) GColors.GoldDeep else GColors.GoldDeep.copy(alpha = 0.6f)
            // Pontas da faixa (rabo de andorinha).
            listOf(-1f, 1f).forEach { dir ->
                val x0 = if (dir < 0) w * 0.04f else w * 0.96f
                val x1 = if (dir < 0) w * 0.2f else w * 0.8f
                val tail = Path().apply {
                    moveTo(x1, top + bh * 0.25f); lineTo(x0, top + bh * 0.25f); lineTo(x0 + dir * -w * 0.04f, top + bh * 0.75f)
                    lineTo(x0, top + bh * 1.25f); lineTo(x1, top + bh * 1.25f); close()
                }
                drawPath(tail, if (win) GColors.GoldDeep else Color(0xFF4A0D1D))
            }
            drawRoundRect(fill, Offset(w * 0.14f, top), Size(w * 0.72f, bh), CornerRadius(h * 0.06f))
            drawRoundRect(edge, Offset(w * 0.14f, top), Size(w * 0.72f, bh), CornerRadius(h * 0.06f), style = Stroke(1.5.dp.toPx()))
            if (win) {
                // Coroa no alto.
                val cx = w / 2
                val crown = Path().apply {
                    moveTo(cx - h * 0.2f, top - h * 0.02f); lineTo(cx - h * 0.24f, top - h * 0.26f); lineTo(cx - h * 0.1f, top - h * 0.14f)
                    lineTo(cx, top - h * 0.3f); lineTo(cx + h * 0.1f, top - h * 0.14f); lineTo(cx + h * 0.24f, top - h * 0.26f)
                    lineTo(cx + h * 0.2f, top - h * 0.02f); close()
                }
                drawPath(crown, Brush.verticalGradient(listOf(GColors.Champagne, GColors.Gold)))
                // Louros dos dois lados.
                listOf(-1f, 1f).forEach { dir ->
                    for (i in 0 until 5) {
                        val t = i / 4f
                        val px = w / 2 + dir * (w * 0.4f + h * 0.05f * sin(t * PI.toFloat()))
                        val py = top + bh * 1.2f - t * bh * 1.5f
                        rotate(dir * (30f + t * 40f), Offset(px, py)) {
                            drawOval(GColors.Gold, Offset(px - h * 0.035f, py - h * 0.08f), Size(h * 0.07f, h * 0.16f))
                        }
                    }
                }
            }
        }
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = com.gtranca.ui.theme.Cinzel,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                fontSize = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp),
                letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = if (win) GColors.OnGold else GColors.Ivory,
            ),
            modifier = Modifier.graphicsLayer { translationY = 7.dp.toPx() },
        )
    }
}
