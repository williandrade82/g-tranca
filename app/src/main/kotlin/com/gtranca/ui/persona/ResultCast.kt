package com.gtranca.ui.persona

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gtranca.game.Persona

/** Vermelho escuro da base do fundo dos perdedores; o degradê sobe até ficar totalmente transparente. */
private val LoserRed = Color(0xFF6E0E20)

/** Intensidade do degradê no ponto mais fraco do ciclo (o mais forte é 1). */
private const val LOSER_FADE_MIN = 0.3f

/** Duração de um ciclo do fundo dos perdedores. */
private const val LOSER_PULSE_MILLIS = 2_800

/**
 * §14.5 quem ganhou e quem perdeu, nos anúncios e na tela final: os de [winners] na pose de vencedor, com confete (o
 * jogador, com a sua foto ou medalhão), e os de [losers] na de perdedor (o robô frustrado e a foto do jogador) sobre um
 * degradê vermelho (escuro embaixo, transparente em cima) que esmaece e reaparece devagar. Sem animação ([animate] falso), tudo fica parado.
 */
@Composable
fun ResultCast(winners: List<Persona>, losers: List<Persona>, animate: Boolean, modifier: Modifier = Modifier, loserScale: Float = 1f) {
    if (winners.isEmpty() && losers.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (winners.isNotEmpty()) CastRow(winners, Pose.VENCEDOR, animate, Modifier.testTag("cast-winners"))
        if (losers.isNotEmpty()) {
            LoserBackground(animate, Modifier.testTag("cast-losers")) { CastRow(losers, Pose.PERDEDOR, animate, scale = loserScale) }
        }
    }
}

/** Degradê vermelho escuro embaixo e transparente em cima, que esmaece e reaparece em ciclo (parado, forte, sem animação). */
@Composable
private fun LoserBackground(animate: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val intensity: () -> Float = if (animate) {
        val transition = rememberInfiniteTransition(label = "losers")
        val state = transition.animateFloat(
            1f,
            LOSER_FADE_MIN,
            infiniteRepeatable(tween(LOSER_PULSE_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "loser-fade",
        )
        ({ state.value })
    } else {
        ({ 1f })
    }
    val gradient = Brush.verticalGradient(listOf(Color.Transparent, LoserRed))
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .drawBehind { drawRoundRect(gradient, cornerRadius = CornerRadius(16.dp.toPx()), alpha = intensity()) }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CastRow(personas: List<Persona>, pose: Pose, animate: Boolean, modifier: Modifier = Modifier, scale: Float = 1f) {
    val maxWidth = (if (personas.size == 1) 220.dp else 150.dp) * scale
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        personas.forEach { persona ->
            val character = persona.character
            val item = Modifier.weight(1f, fill = false).widthIn(max = maxWidth).testTag("cast-${pose.code}")
            if (character != null) {
                CharacterPortrait(character, pose, item, animate = animate, description = persona.fullName)
            } else {
                HumanCast(persona, pose, animate, item)
            }
        }
    }
}

/** O jogador no quadro 3:2 dos retratos: a foto ou o medalhão e, por cima, o confete (ganhou) ou a nuvem com chuva (perdeu). */
@Composable
private fun HumanCast(persona: Persona, pose: Pose, animate: Boolean, modifier: Modifier) {
    Box(modifier.aspectRatio(Pose.APRESENTACAO.aspectRatio), contentAlignment = Alignment.BottomCenter) {
        PersonaAvatar(persona, 84.dp, Modifier.padding(bottom = 4.dp))
        // confete se ganhou; a nuvem com chuva dos robôs frustrados se perdeu
        if (animate && (pose == Pose.VENCEDOR || pose == Pose.PERDEDOR)) {
            PoseEffectOverlay(pose.animation.effect, pose.animation.cycleMillis, Modifier.fillMaxSize())
        }
    }
}
