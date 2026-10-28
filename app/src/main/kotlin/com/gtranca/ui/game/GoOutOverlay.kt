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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.height
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

/** Arte do banner de fim de partida (docs/telas.md 3.9). */
@androidx.annotation.DrawableRes
fun roundEndBannerArt(end: RoundEnd): Int = when (end) {
    RoundEnd.YOU, RoundEnd.PARTNER -> R.drawable.banner_bateu
    RoundEnd.OPPONENT -> R.drawable.banner_perdeu
    RoundEnd.NO_WINNER -> R.drawable.banner_ninguem_bateu
}

/** Legenda sob a arte: só em duplas (quem bateu ou "Sua dupla perdeu"); no individual a arte basta. */
fun roundEndCaptionRes(end: RoundEnd, mode: GameMode): Int? = when {
    mode != GameMode.DUPLAS -> null
    end == RoundEnd.YOU -> R.string.go_out_you
    end == RoundEnd.PARTNER -> R.string.go_out_partner
    end == RoundEnd.OPPONENT -> R.string.go_out_lost_team
    else -> null
}

/**
 * Banner de fim de partida (docs/telas.md 3.9): a arte pronta (Bateu! / Perdeu! / Ninguém Bateu) e, em duplas, uma legenda
 * pequena. [compact] = a forma minimizada, junto do Seguir na mesa final; [artHeight] ajusta a arte nos outros usos.
 */
@Composable
fun RoundEndBanner(end: RoundEnd, mode: GameMode, compact: Boolean, modifier: Modifier = Modifier, artHeight: androidx.compose.ui.unit.Dp? = null) {
    val message = stringResource(roundEndMessageRes(end, mode))
    val caption = roundEndCaptionRes(end, mode)?.let { stringResource(it) }
    val art = Modifier.height(artHeight ?: if (compact) 44.dp else 150.dp)
    val image = @Composable {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(roundEndBannerArt(end)),
            contentDescription = null,
            modifier = if (compact) art else art.testTag("go-out-message"),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
    val captionText = @Composable {
        if (caption != null) {
            androidx.compose.material3.Text(
                caption,
                style = goldTitleStyle((if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium).copy(fontWeight = FontWeight.ExtraBold)),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
    val semanticsMod = Modifier
        .semantics(mergeDescendants = true) { contentDescription = message; liveRegion = LiveRegionMode.Polite }
        .testTag(if (compact) "go-out-banner-mini" else "go-out-banner")
    if (compact) {
        androidx.compose.foundation.layout.Row(
            modifier.then(semanticsMod),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { image(); captionText() }
    } else {
        androidx.compose.foundation.layout.Column(
            modifier.then(semanticsMod),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { image(); captionText() }
    }
}

/** Base da barra superior da mesa em coordenadas da tela: os fogos passam por baixo dela (docs/telas.md 3.8, camadas). */
val LocalTableHeaderBottom = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<Float>?> { null }

private val FireworksGreen = listOf(Color(0xFF2ECC40), Color(0xFF8BF05A), Color(0xFFC8FFB0))
private val FireworksRed = listOf(Color(0xFFE53935), Color(0xFFFF6F60), Color(0xFFFFB4A8))

/**
 * Fogos de artifício da batida (docs/telas.md 3.9) por [Fireworks.TOTAL_MILLIS]: verdes se o jogador (ou a dupla) venceu,
 * vermelhos se perdeu. Só desenha (sem toques); a animação pertence à composição, então para sozinha se a tela sair.
 */
@Composable
fun GoOutFireworks(win: Boolean, modifier: Modifier = Modifier) {
    val bursts = remember { Fireworks.create(seed = if (win) 31L else 47L) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(Fireworks.TOTAL_MILLIS.toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
    val palette = if (win) FireworksGreen else FireworksRed
    val headerBottom = LocalTableHeaderBottom.current
    var top by remember { mutableStateOf(0f) }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { top = it.positionInRoot().y }.testTag("go-out-fireworks")) {
      clipRect(top = ((headerBottom?.value ?: 0f) - top).coerceAtLeast(0f)) {
        val t = progress.value
        val base = size.minDimension * 0.008f
        bursts.forEach { burst ->
            burst.sparks.forEach { spark ->
                val s = Fireworks.state(burst, spark, t) ?: return@forEach
                val center = Offset(s.x * size.width, s.y * size.height)
                val color = palette[spark.shade % palette.size]
                drawCircle(color.copy(alpha = s.alpha * 0.25f), base * 3.2f * s.size, center)
                drawCircle(color.copy(alpha = s.alpha), base * 1.4f * s.size, center)
            }
        }
      }
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
    // Dramatização (docs/telas.md 3.9): fogos de artifício por 1,5 s (um toque pula) e só então o banner.
    var phase by remember { mutableStateOf(GoOutDrama.startPhase(end, animate)) }
    LaunchedEffect(phase) {
        if (phase == GoOutDrama.OverlayPhase.FIREWORKS) {
            delay(Fireworks.TOTAL_MILLIS)
            phase = GoOutDrama.next(phase)
            return@LaunchedEffect
        }
        when (end) {
            RoundEnd.YOU, RoundEnd.PARTNER -> sound.play(SoundEffect.WIN)
            RoundEnd.OPPONENT -> sound.play(SoundEffect.LOSE)
            RoundEnd.NO_WINNER -> Unit
        }
        delay(GO_OUT_OVERLAY_MILLIS)
        finish()
    }
    val fireworks = phase == GoOutDrama.OverlayPhase.FIREWORKS
    val fade = 1f - collapse.value
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = if (fireworks) 0f else 0.4f * fade))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = { if (fireworks) phase = GoOutDrama.next(phase) else finish() })
            .testTag("go-out-overlay"),
        contentAlignment = Alignment.Center,
    ) {
        if (fireworks) {
            GoOutFireworks(win = GoOutDrama.fireworksWin(end))
            return@BoxWithConstraints
        }
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
