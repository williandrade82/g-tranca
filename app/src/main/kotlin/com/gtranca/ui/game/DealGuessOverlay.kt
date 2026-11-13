package com.gtranca.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gtranca.R
import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.goldTitleStyle
import kotlin.math.roundToInt

/** Um ciclo (subida OU descida) do indicador, por padrão dentro de 2 a 4 s (§14.5), como as demais animações cíclicas. */
private const val DEAL_GUESS_CYCLE_MILLIS = 2_600

/** Âmbar da borda piscando no erro do palpite (telas-app.md 3.8.2); sem equivalente em [GColors]. */
private val DealGuessAmber = Color(0xFFFFB300)

/**
 * §3.6 estágio ao vivo do indicador (0 a [totalStages]), um relógio só (não um `Animatable` por carta): sobe e desce
 * em ciclo com o tempo real do quadro ([withFrameNanos]), **independente** da escala de animação do sistema — é a
 * mecânica do minijogo, não um efeito cosmético, então continua rodando mesmo com "remover animações" ligado. Para
 * de vez quando [frozen] não é nulo (2º toque).
 */
@Composable
private fun dealGuessLiveStage(totalStages: Int, frozen: Int?): Int {
    var stage by remember(totalStages) { mutableStateOf(0) }
    LaunchedEffect(totalStages, frozen) {
        if (frozen != null || totalStages <= 0) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - start) / 1_000_000
                val period = DEAL_GUESS_CYCLE_MILLIS * 2
                val phase = elapsedMs % period
                val progress = if (phase < DEAL_GUESS_CYCLE_MILLIS) {
                    phase.toFloat() / DEAL_GUESS_CYCLE_MILLIS
                } else {
                    2f - phase.toFloat() / DEAL_GUESS_CYCLE_MILLIS
                }
                stage = (progress * totalStages).roundToInt().coerceIn(0, totalStages)
            }
        }
    }
    return frozen ?: stage
}

/**
 * §3.6 a barra de palpite: o fundo azul ([R.drawable.deal_guess_bar_track]), o segmento verde da faixa já marcada
 * ([rangeStart] até [stage], quando o 1º toque já aconteceu) e o indicador, à esquerda da barra, na altura de
 * [stage]/[totalStages].
 */
@Composable
private fun DealGuessGauge(stage: Int, totalStages: Int, rangeStart: Int?, modifier: Modifier = Modifier) {
    val fraction = if (totalStages <= 0) 0f else stage.toFloat() / totalStages
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        // O indicador sobe por uma coluna própria (a ponta fica colada na barra, à direita dela).
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().fillMaxHeightFraction(fraction), contentAlignment = Alignment.TopEnd) {
                Image(
                    painterResource(R.drawable.deal_guess_indicator),
                    contentDescription = null,
                    modifier = Modifier.widthIn(max = 40.dp).aspectRatio(415f / 283f).offset(y = (-10).dp),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.fillMaxHeight().width(22.dp)) {
            Image(
                painterResource(R.drawable.deal_guess_bar_track),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (rangeStart != null && totalStages > 0) {
                val lo = minOf(rangeStart, stage)
                val hi = maxOf(rangeStart, stage)
                val loFrac = lo.toFloat() / totalStages
                val hiFrac = hi.toFloat() / totalStages
                Box(Modifier.fillMaxWidth().fillMaxHeightFraction(hiFrac), contentAlignment = Alignment.BottomCenter) {
                    val segFrac = if (hiFrac > 0f) (hiFrac - loFrac) / hiFrac else 0f
                    Box(Modifier.fillMaxWidth().fillMaxHeightFraction(segFrac)) {
                        Image(
                            painterResource(R.drawable.deal_guess_bar_fill),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                }
            }
        }
    }
}

/** Como `Modifier.fillMaxHeight(fraction)`, com [fraction] fora de `0f..1f` (chega assim quando `totalStages` é 0). */
private fun Modifier.fillMaxHeightFraction(fraction: Float): Modifier = this.fillMaxHeight(fraction.coerceIn(0f, 1f))

/**
 * §3.6, telas-app.md 3.8.2: modal "Cartas para a distribuição" / "Cartas para o Morto". Só aparece na tela de quem
 * está palpitando. Dois toques: o 1º marca Início (o indicador continua); o 2º para o indicador e resolve Início/Fim
 * (o maior vira Fim, nunca invertido) — então [onGuess] envia o palpite ao motor. A tela nunca decide sozinha se
 * acertou: só mostra os números tocados; o resultado chega depois, em [DealGuessRevealOverlay].
 */
@Composable
fun DealGuessModal(prompt: DealGuessPrompt, onGuess: (Int, Int) -> Unit) {
    var start by remember(prompt) { mutableStateOf<Int?>(null) }
    var frozen by remember(prompt) { mutableStateOf<Int?>(null) }
    val stage = dealGuessLiveStage(prompt.totalStages, frozen)
    val lo = start?.let { minOf(it, stage) }
    val hi = start?.let { maxOf(it, stage) }
    val title = stringResource(if (prompt.stage == DealGuessStage.HAND) R.string.deal_guess_title_hand else R.string.deal_guess_title_morto)
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (frozen != null) return@clickable // já resolvido: espera o motor (sem toque duplo)
                if (start == null) {
                    start = stage
                } else {
                    frozen = stage
                    onGuess(minOf(start!!, stage), maxOf(start!!, stage))
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = title; liveRegion = LiveRegionMode.Polite }
            .testTag("deal-guess-modal"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = goldTitleStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // O baralho e a barra vazia já vêm prontos na arte de fundo; a barra funcional (azul/verde/indicador)
                // fica sobre a área onde a arte desenha o bastão dourado vazio, à direita da pilha de cartas.
                BoxWithConstraints(Modifier.aspectRatio(640f / 853f).widthIn(max = 220.dp)) {
                    Image(
                        painterResource(R.drawable.deal_guess_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    DealGuessGauge(
                        stage, prompt.totalStages, lo,
                        Modifier
                            .offset(x = maxWidth * 0.70f, y = maxHeight * 0.07f)
                            .width(maxWidth * 0.28f)
                            .height(maxHeight * 0.86f)
                            .testTag("deal-guess-gauge"),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.deal_guess_instructions),
                color = GColors.Ivory,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DealGuessField(stringResource(R.string.deal_guess_start_label), lo, Modifier.testTag("deal-guess-start"))
                DealGuessField(stringResource(R.string.deal_guess_end_label), hi, Modifier.testTag("deal-guess-end"))
                DealGuessField(
                    stringResource(R.string.deal_guess_total_label),
                    if (lo != null && hi != null) hi - lo else null,
                    Modifier.testTag("deal-guess-total"),
                )
            }
        }
    }
}

@Composable
private fun DealGuessField(label: String, value: Int?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = GColors.Gold, style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier
                .size(width = 56.dp, height = 36.dp)
                .background(GColors.Midnight.copy(alpha = 0.7f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(value?.toString() ?: "—", color = GColors.Ivory, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/**
 * §3.6, telas-app.md 3.8.2: reação em tela cheia do palpite resolvido (vitória: +100 pontos, confete e som de
 * canastra; erro: "Não foi dessa vez", borda âmbar piscando) — do próprio jogador ou do parceiro dele (duplas). Fica
 * até o toque ([onDismiss], que libera o motor para o próximo palpite ou a 1ª jogada de verdade).
 */
@Composable
fun DealGuessRevealOverlay(reveal: DealGuessReveal, onDismiss: () -> Unit) {
    val sound = LocalSound.current
    LaunchedEffect(reveal.id) { if (reveal.correct) sound.play(SoundEffect.CANASTA) }
    val text = stringResource(dealGuessRevealTextRes(reveal))
    val closeText = stringResource(R.string.deal_guess_tap_to_close)
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(11f)
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics(mergeDescendants = true) { contentDescription = "$text $closeText"; liveRegion = LiveRegionMode.Polite }
            .testTag("deal-guess-close"),
        contentAlignment = Alignment.Center,
    ) {
        if (reveal.correct) {
            EndCelebration(EndOutcome.WIN, brief = false, enabled = true, playSound = false, modifier = Modifier.testTag("deal-guess-confetti"))
        } else {
            DealGuessAmberBorder(Modifier)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp).testTag(if (reveal.correct) "deal-guess-win" else "deal-guess-miss"),
        ) {
            Image(
                painterResource(if (reveal.correct) R.drawable.deal_guess_win else R.drawable.deal_guess_miss),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
            Spacer(Modifier.height(16.dp))
            Text(text, color = GColors.Ivory, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            Text(closeText, color = GColors.Ivory.copy(alpha = 0.8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Texto exato do §3.8.2 do telas-app.md, conforme quem palpitou (próprio/parceiro) e a fase (mão/morto). */
private fun dealGuessRevealTextRes(reveal: DealGuessReveal): Int = when {
    reveal.correct && !reveal.partner -> R.string.deal_guess_win_you
    reveal.correct && reveal.partner && reveal.stage == DealGuessStage.HAND -> R.string.deal_guess_win_partner_hand
    reveal.correct && reveal.partner -> R.string.deal_guess_win_partner_morto
    !reveal.partner && reveal.stage == DealGuessStage.HAND -> R.string.deal_guess_miss_you_hand
    !reveal.partner -> R.string.deal_guess_miss_you_morto
    reveal.stage == DealGuessStage.HAND -> R.string.deal_guess_miss_partner_hand
    else -> R.string.deal_guess_miss_partner_morto
}

/** Borda da tela piscando em âmbar (erro do palpite, telas-app.md 3.8.2): mesmo padrão do pulso vermelho de [RoundEndOverlay]. */
@Composable
private fun DealGuessAmberBorder(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "deal-guess-amber").animateFloat(
        0.15f, 0.7f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "deal-guess-amber-alpha",
    )
    Canvas(modifier.fillMaxSize()) {
        val edge = size.minDimension * 0.12f
        val amber = DealGuessAmber.copy(alpha = pulse)
        drawRect(Brush.verticalGradient(listOf(amber, Color.Transparent), 0f, edge))
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, amber), size.height - edge, size.height),
            androidx.compose.ui.geometry.Offset(0f, size.height - edge),
        )
        drawRect(Brush.horizontalGradient(listOf(amber, Color.Transparent), 0f, edge))
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, amber), size.width - edge, size.width),
            androidx.compose.ui.geometry.Offset(size.width - edge, 0f),
        )
    }
}
