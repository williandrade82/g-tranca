package com.gtranca.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.game.GameSnapshot
import com.gtranca.game.SeatRole
import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.goldTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Como a partida terminou, visto deste jogador (docs/telas.md 3.9). */
enum class RoundEnd { YOU, PARTNER, OPPONENT, NO_WINNER }

/** §11 como terminou a última partida, vista do jogador; `null` se ainda não há partida terminada. */
fun roundEnd(snapshot: GameSnapshot): RoundEnd? = when (val result = snapshot.history.lastOrNull()?.result) {
    null -> null
    is RoundResult.GoOut -> when (SeatRole.of(snapshot.view.mode, result.seat, snapshot.viewerSeat)) {
        SeatRole.YOU -> RoundEnd.YOU
        SeatRole.PARTNER -> RoundEnd.PARTNER
        else -> RoundEnd.OPPONENT
    }
    else -> RoundEnd.NO_WINNER
}

/** Texto do banner de fim de partida (docs/telas.md 3.9). */
fun roundEndMessageRes(end: RoundEnd, mode: GameMode): Int = when (end) {
    RoundEnd.YOU -> R.string.go_out_you
    RoundEnd.PARTNER -> R.string.go_out_partner
    RoundEnd.OPPONENT -> if (mode == GameMode.DUPLAS) R.string.go_out_lost_team else R.string.go_out_lost
    RoundEnd.NO_WINNER -> R.string.round_end_stock_out
}

/** §11 duração da animação do fim da partida. */
const val GO_OUT_OVERLAY_MILLIS: Long = 2_000

/** Duração da minimização do banner (encolhe e desliza até o Seguir). */
private const val COLLAPSE_MILLIS = 500

private val LostRed = Color(0xFFD32F2F)

/**
 * Banner de fim de partida (docs/telas.md 3.9): azul royal/marinho com borda e brilho dourados e o texto em Cinzel
 * dourado; na derrota, um toque discreto de vermelho na borda; sem batida, neutro. [compact] = a forma minimizada, junto
 * do Seguir na mesa final.
 */
@Composable
fun RoundEndBanner(end: RoundEnd, mode: GameMode, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (compact) 12.dp else 20.dp)
    val border = when (end) {
        RoundEnd.OPPONENT -> Brush.horizontalGradient(listOf(GColors.Gold, LostRed.copy(alpha = 0.85f), GColors.Gold))
        RoundEnd.NO_WINNER -> Brush.horizontalGradient(listOf(GColors.Steel, GColors.Gold.copy(alpha = 0.7f), GColors.Steel))
        else -> Brush.horizontalGradient(listOf(GColors.GoldDeep, GColors.Gold, GColors.Champagne, GColors.Gold, GColors.GoldDeep))
    }
    val base = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium
    Box(
        modifier
            .shadow(if (compact) 4.dp else 16.dp, shape, ambientColor = GColors.Gold, spotColor = GColors.Gold)
            .background(Brush.verticalGradient(listOf(GColors.RoyalStart, GColors.Midnight)), shape)
            .border(if (compact) 1.5.dp else 3.dp, border, shape)
            .padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 6.dp else 18.dp)
            .testTag(if (compact) "go-out-banner-mini" else "go-out-banner"),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            stringResource(roundEndMessageRes(end, mode)),
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .then(if (compact) Modifier else Modifier.testTag("go-out-message")),
            style = goldTitleStyle(base.copy(fontWeight = FontWeight.ExtraBold)),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * §11 animação do fim da partida sobre a mesa (docs/telas.md 3.9): a mesa escurecida ao fundo e o banner grande no centro
 * por 2 s (um toque encerra); confete se o seu lado bateu, borda da tela pulsando em vermelho se o adversário bateu, só o
 * escurecimento sem batida. Ao terminar, o banner encolhe e desliza para baixo (até o Seguir) e [onDone] é chamado. Sem
 * animações do sistema ([animate] falso): sem movimento.
 */
@Composable
fun RoundEndOverlay(end: RoundEnd, mode: GameMode, animate: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val sound = LocalSound.current
    val scope = rememberCoroutineScope()
    val collapse = remember { Animatable(0f) }
    var finishing by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finishing) {
            finishing = true
            scope.launch {
                if (animate) collapse.animateTo(1f, tween(COLLAPSE_MILLIS, easing = FastOutSlowInEasing))
                onDone()
            }
        }
    }
    LaunchedEffect(Unit) {
        when (end) {
            RoundEnd.YOU, RoundEnd.PARTNER -> sound.play(SoundEffect.WIN)
            RoundEnd.OPPONENT -> sound.play(SoundEffect.LOSE)
            RoundEnd.NO_WINNER -> Unit
        }
        delay(GO_OUT_OVERLAY_MILLIS)
        finish()
    }
    val fade = 1f - collapse.value
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.4f * fade))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = finish)
            .testTag("go-out-overlay"),
        contentAlignment = Alignment.Center,
    ) {
        val travel = constraints.maxHeight * 0.4f
        if (animate && !finishing) {
            when (end) {
                RoundEnd.YOU, RoundEnd.PARTNER -> EndCelebration(EndOutcome.WIN, brief = true, enabled = true, playSound = false)
                RoundEnd.OPPONENT -> {
                    // Borda da tela em vermelho, num pulso suave.
                    val pulse by rememberInfiniteTransition(label = "lost-border").animateFloat(
                        0.15f, 0.7f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "lost-alpha",
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val edge = size.minDimension * 0.12f
                        val red = LostRed.copy(alpha = pulse)
                        drawRect(Brush.verticalGradient(listOf(red, Color.Transparent), 0f, edge))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, red), size.height - edge, size.height), Offset(0f, size.height - edge))
                        drawRect(Brush.horizontalGradient(listOf(red, Color.Transparent), 0f, edge))
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, red), size.width - edge, size.width), Offset(size.width - edge, 0f))
                    }
                }
                RoundEnd.NO_WINNER -> Unit
            }
        }
        RoundEndBanner(
            end, mode, compact = false,
            Modifier
                .fillMaxWidth(0.86f)
                .graphicsLayer {
                    val p = collapse.value
                    translationY = travel * p
                    scaleX = 1f - 0.45f * p
                    scaleY = 1f - 0.45f * p
                    alpha = 1f - 0.6f * p
                },
        )
    }
}
