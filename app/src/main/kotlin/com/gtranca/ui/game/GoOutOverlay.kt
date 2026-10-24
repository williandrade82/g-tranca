package com.gtranca.ui.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.game.GameSnapshot
import com.gtranca.game.SeatRole
import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.theme.GColors
import kotlinx.coroutines.delay

/** Quem bateu, visto deste jogador (§11.1): o próprio, o parceiro (duplas) ou o outro lado. */
enum class GoOutBy { YOU, PARTNER, OPPONENT }

/** §11.1 quem bateu na última partida, visto do jogador; `null` = fim sem batida (§11.2). */
fun goOutBy(snapshot: GameSnapshot): GoOutBy? {
    val out = snapshot.history.lastOrNull()?.result as? RoundResult.GoOut ?: return null
    return when (SeatRole.of(snapshot.view.mode, out.seat, snapshot.viewerSeat)) {
        SeatRole.YOU -> GoOutBy.YOU
        SeatRole.PARTNER -> GoOutBy.PARTNER
        else -> GoOutBy.OPPONENT
    }
}

/** Mensagem da animação da batida (docs/telas.md 3.9). */
fun goOutMessageRes(by: GoOutBy, mode: GameMode): Int = when (by) {
    GoOutBy.YOU -> R.string.go_out_you
    GoOutBy.PARTNER -> R.string.go_out_partner
    GoOutBy.OPPONENT -> if (mode == GameMode.DUPLAS) R.string.go_out_lost_team else R.string.go_out_lost
}

/** §11.1 duração da animação da batida. */
const val GO_OUT_OVERLAY_MILLIS: Long = 2_000

/**
 * §11.1 animação da batida sobre a mesa (docs/telas.md 3.9): camada transparente com a mesa ao fundo um pouco
 * escurecida; confete na batida do lado do jogador, borda vermelha pulsando na do adversário. Some sozinha em 2 s ou
 * com um toque. Sem animações do sistema ([animate] falso): só a mensagem e o escurecimento.
 */
@Composable
fun GoOutOverlay(by: GoOutBy, mode: GameMode, animate: Boolean, onDone: () -> Unit) {
    val sound = LocalSound.current
    val win = by != GoOutBy.OPPONENT
    LaunchedEffect(Unit) {
        sound.play(if (win) SoundEffect.WIN else SoundEffect.LOSE)
        delay(GO_OUT_OVERLAY_MILLIS)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone)
            .testTag("go-out-overlay"),
        contentAlignment = Alignment.Center,
    ) {
        if (animate) {
            if (win) {
                EndCelebration(EndOutcome.WIN, brief = true, enabled = true, playSound = false)
            } else {
                // Borda da tela em vermelho, num pulso suave.
                val pulse by rememberInfiniteTransition(label = "lost-border").animateFloat(
                    0.15f, 0.7f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "lost-alpha",
                )
                Canvas(Modifier.fillMaxSize()) {
                    val edge = size.minDimension * 0.12f
                    val red = Color(0xFFD32F2F).copy(alpha = pulse)
                    drawRect(Brush.verticalGradient(listOf(red, Color.Transparent), 0f, edge))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, red), size.height - edge, size.height), Offset(0f, size.height - edge))
                    drawRect(Brush.horizontalGradient(listOf(red, Color.Transparent), 0f, edge))
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, red), size.width - edge, size.width), Offset(size.width - edge, 0f))
                }
            }
        }
        Text(
            stringResource(goOutMessageRes(by, mode)),
            Modifier
                .background(GColors.Midnight.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("go-out-message"),
            color = if (win) GColors.Gold else GColors.Ivory,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
