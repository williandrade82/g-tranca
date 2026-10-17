package com.gtranca.ui.persona

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gtranca.game.BotCharacter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Retrato animado de um personagem numa pose (§14.5): a imagem com um movimento suave e cíclico (respirar, balançar,
 * pular) e, nas poses grandes, um efeito (brilho, confete, chuva, pensamentos). O ciclo dura de 2 a 4 segundos e se
 * repete; com [animate] falso (testes, animações desligadas) fica parado.
 *
 * @param effects desenha o efeito da pose; desligue nos tamanhos pequenos (medalhão da mesa).
 * @param description texto para o leitor de tela (nome do personagem); sem ele a imagem é decorativa.
 */
@Composable
fun CharacterPortrait(
    character: BotCharacter,
    pose: Pose,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    effects: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
    description: String? = null,
) {
    val spec = pose.animation
    val phase: () -> Float = if (animate) {
        val transition = rememberInfiniteTransition(label = "pose")
        val state = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(spec.cycleMillis, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        ({ state.value })
    } else {
        ({ 0f })
    }
    val semanticsModifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics {}
    Box(modifier.then(semanticsModifier).aspectRatio(pose.aspectRatio)) {
        Image(
            painterResource(characterRes(character, pose)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = phase()
                val wave = sin(2 * PI * p).toFloat()
                val lift = if (spec.jump) abs(wave) else (wave + 1f) / 2f
                translationY = -lift * spec.floatFraction * size.height
                val scale = 1f + spec.breathe * (wave + 1f) / 2f
                scaleX = scale
                scaleY = scale
                rotationZ = spec.swayDegrees * sin(2 * PI * p + 1.2).toFloat()
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            },
            contentScale = contentScale,
            alignment = Alignment.BottomCenter,
        )
        if (animate && effects) {
            Canvas(Modifier.fillMaxSize()) { drawEffect(spec.effect, phase()) }
        }
    }
}

private val ConfettiColors = listOf(Color(0xFFF5C542), Color(0xFFD23A4F), Color(0xFF2FBF84), Color(0xFF4FA8E8), Color(0xFFFFE9A8))

/** Efeito da pose no instante [p] (0 a 1) do ciclo; tudo determinístico (sem sorteio por quadro). */
private fun DrawScope.drawEffect(effect: PoseEffect, p: Float) {
    val w = size.width
    val h = size.height
    when (effect) {
        PoseEffect.SPARKLE -> {
            // brilho dourado que cruza o terço de cima da esquerda para a direita
            val x = w * (0.1f + 0.8f * p)
            val y = h * 0.18f
            val alpha = sin(PI * p).toFloat().coerceIn(0f, 1f)
            val r = w * 0.035f
            val color = Color(0xFFFFE9A8).copy(alpha = alpha)
            drawLine(color, Offset(x - r, y), Offset(x + r, y), strokeWidth = r * 0.28f)
            drawLine(color, Offset(x, y - r), Offset(x, y + r), strokeWidth = r * 0.28f)
            drawCircle(color.copy(alpha = alpha * 0.5f), r * 0.35f, Offset(x, y))
        }
        PoseEffect.CONFETTI -> repeat(18) { i ->
            val x = ((i * 53) % 100) / 100f * w
            val speed = 0.8f + (i % 4) * 0.15f
            val y = ((p * speed + i * 0.13f) % 1f) * h
            val side = w * 0.022f
            rotate((p * 360f * (i % 3 + 1)) + i * 20f, pivot = Offset(x, y)) {
                drawRect(ConfettiColors[i % ConfettiColors.size], Offset(x - side / 2, y - side / 2), Size(side, side * 1.6f))
            }
        }
        PoseEffect.DROPS -> {
            // pequena nuvem escura no alto e gotas que caem sob ela
            val cx = w * 0.5f
            val cy = h * 0.08f
            val cloud = Color(0xFF59627F).copy(alpha = 0.85f)
            drawCircle(cloud, w * 0.05f, Offset(cx - w * 0.05f, cy))
            drawCircle(cloud, w * 0.065f, Offset(cx, cy - h * 0.02f))
            drawCircle(cloud, w * 0.05f, Offset(cx + w * 0.055f, cy))
            repeat(5) { i ->
                val t = (p + i * 0.2f) % 1f
                val x = cx + (i - 2) * w * 0.032f
                val y = cy + h * 0.05f + t * h * 0.28f
                drawLine(Color(0xFF8FC8FF).copy(alpha = (1f - t) * 0.9f), Offset(x, y), Offset(x, y + h * 0.03f), strokeWidth = w * 0.007f)
            }
        }
        PoseEffect.THOUGHTS -> repeat(3) { i ->
            // três pontos que acendem em sequência, subindo para o canto
            val visible = ((p * 3f) - i).coerceIn(0f, 1f)
            val fade = if (p > 0.85f) (1f - p) / 0.15f else 1f
            val x = w * (0.78f + i * 0.07f)
            val y = h * (0.22f - i * 0.05f)
            drawCircle(Color(0xFFFFE9A8).copy(alpha = visible * fade * 0.9f), w * (0.012f + i * 0.007f), Offset(x, y))
        }
    }
}
