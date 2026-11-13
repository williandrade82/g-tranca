package com.gtranca.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.rememberMotionEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2 * PI).toFloat()
private const val ENTER_MILLIS = 1_800
private const val LOOP_MILLIS = 8_000

/**
 * Animações da tela inicial (docs/telas-app.md 3.1). [enter] vai de 0 a 1 uma vez, na primeira abertura (a entrada);
 * [loop] gira de 0 a 1 sem parar (holofotes, brilhos, brasão). Com as animações do sistema desligadas, [enter] já
 * nasce em 1 e [loop] fica em 0: a tela aparece parada.
 */
@Stable
class HomeMotion(val enabled: Boolean, val enter: () -> Float, val loop: () -> Float)

@Composable
fun rememberHomeMotion(): HomeMotion {
    val enabled = rememberMotionEnabled()
    // A entrada toca uma vez; ao voltar de outra tela, a cena já está montada.
    var played by rememberSaveable { mutableStateOf(false) }
    val enter = remember { Animatable(if (enabled && !played) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) enter.animateTo(1f, tween(ENTER_MILLIS, easing = LinearEasing))
        played = true
    }
    val loop = if (enabled) {
        rememberInfiniteTransition(label = "home").animateFloat(
            0f, 1f, infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing)), label = "home-loop",
        )
    } else null
    return remember(enabled, loop) { HomeMotion(enabled, { enter.value }, { loop?.value ?: 0f }) }
}

/** Trecho [from]..[to] do progresso [p], suavizado (0 antes, 1 depois). */
private fun stage(p: Float, from: Float, to: Float): Float =
    FastOutSlowInEasing.transform(((p - from) / (to - from)).coerceIn(0f, 1f))

/** Entrada em sequência: aparece e sobe um pouco a partir de [start] (0 a 1 da entrada). */
fun Modifier.homeEnter(motion: HomeMotion, start: Float): Modifier = graphicsLayer {
    val s = stage(motion.enter(), start, start + 0.3f)
    alpha = s
    translationY = (1f - s) * 24.dp.toPx()
}

/** Reflexo claro que varre a arte (só onde ela é opaca) enquanto [loop] passa por [from]..[to]. */
private fun Modifier.sheen(motion: HomeMotion, from: Float, to: Float): Modifier = drawWithContent {
    drawContent()
    val t = (motion.loop() - from) / (to - from)
    if (!motion.enabled || t <= 0f || t >= 1f) return@drawWithContent
    val x = (-0.3f + 1.6f * t) * size.width
    val band = size.width * 0.14f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
            start = Offset(x - band, 0f),
            end = Offset(x + band, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/** Brasão: desce ao entrar, flutua de leve e recebe um reflexo de tempos em tempos. */
fun Modifier.homeCrest(motion: HomeMotion): Modifier = graphicsLayer {
    val s = stage(motion.enter(), 0.3f, 0.6f)
    alpha = s
    translationY = -(1f - s) * 40.dp.toPx() + sin(motion.loop() * TAU * 2f) * 3.dp.toPx()
    compositingStrategy = CompositingStrategy.Offscreen
}.sheen(motion, 0.08f, 0.22f)

/** Robôs: sobem por trás da mesa ao entrar e "respiram" de leve. */
fun Modifier.homeBots(motion: HomeMotion): Modifier = graphicsLayer {
    val s = stage(motion.enter(), 0.35f, 0.75f)
    alpha = (s * 3f).coerceAtMost(1f)
    translationY = (1f - s) * size.height * 0.3f + sin(motion.loop() * TAU * 2f + 1f) * 2.dp.toPx()
}

/** Mesa: aparece logo que as cortinas abrem; um brilho corre por ela de tempos em tempos. */
fun Modifier.homeTable(motion: HomeMotion): Modifier = graphicsLayer {
    alpha = stage(motion.enter(), 0.2f, 0.45f)
    compositingStrategy = CompositingStrategy.Offscreen
}.sheen(motion, 0.55f, 0.72f)

/** Holofotes balançando e brilhos dourados flutuando sobre o salão (metade de cima da tela). */
@Composable
fun HomeAmbience(motion: HomeMotion, modifier: Modifier = Modifier) {
    if (!motion.enabled) return
    val sparks = remember {
        val rnd = Random(7)
        List(26) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), (1 + rnd.nextInt(3)).toFloat(), 1f + rnd.nextFloat() * 1.6f) }
    }
    Canvas(modifier) {
        val show = stage(motion.enter(), 0.3f, 0.8f)
        if (show <= 0f) return@Canvas
        val loop = motion.loop()
        val reach = size.height * 0.6f
        // Holofotes: fachos que saem do alto e balançam devagar.
        listOf(0.22f to 0f, 0.5f to 2.1f, 0.78f to 4.2f).forEach { (x, phase) ->
            val angle = sin(loop * TAU + phase) * 0.13f
            val top = Offset(x * size.width, -size.height * 0.02f)
            val end = Offset(top.x + sin(angle) * reach, top.y + cos(angle) * reach)
            val half = size.width * 0.1f
            val beam = Path().apply {
                moveTo(top.x, top.y)
                lineTo(end.x - half, end.y)
                lineTo(end.x + half, end.y)
                close()
            }
            drawPath(
                beam,
                Brush.verticalGradient(listOf(GColors.Champagne.copy(alpha = 0.2f * show), Color.Transparent), startY = 0f, endY = reach),
            )
        }
        // Brilhos: sobem e piscam; multiplicadores inteiros mantêm o ciclo sem salto.
        sparks.forEach { (x0, y0, phase, speed, radius) ->
            val y = ((y0 - loop * speed) % 1f + 1f) % 1f
            val edge = (sin(y * PI.toFloat())).coerceIn(0f, 1f)
            val twinkle = (sin((loop * speed * 3f + phase) * TAU) + 1f) / 2f
            drawCircle(
                GColors.Champagne.copy(alpha = 0.85f * twinkle * edge * show),
                radius = radius.dp.toPx(),
                center = Offset((x0 + sin((loop + phase) * TAU) * 0.01f) * size.width, y * size.height * 0.62f),
            )
        }
    }
}

/** Cortinas de veludo azul que se afastam na entrada (somem quando abertas; não pegam toques). */
@Composable
fun HomeCurtains(motion: HomeMotion, modifier: Modifier = Modifier) {
    if (!motion.enabled) return
    Canvas(modifier) {
        val open = stage(motion.enter(), 0.05f, 0.5f)
        if (open >= 1f) return@Canvas
        val half = size.width / 2f
        val fold = 28.dp.toPx()
        val velvet = Brush.horizontalGradient(
            listOf(Color(0xFF0A1B5E), Color(0xFF1B3DB8), Color(0xFF0A1B5E)),
            startX = 0f, endX = fold, tileMode = TileMode.Repeated,
        )
        val shift = open * (half + 4.dp.toPx())
        val edge = 3.dp.toPx()
        // Esquerda
        drawRect(velvet, Offset(-shift, 0f), Size(half, size.height))
        drawRect(GColors.Gold, Offset(half - shift - edge, 0f), Size(edge, size.height))
        // Direita
        drawRect(velvet, Offset(half + shift, 0f), Size(half, size.height))
        drawRect(GColors.Gold, Offset(half + shift, 0f), Size(edge, size.height))
    }
}
