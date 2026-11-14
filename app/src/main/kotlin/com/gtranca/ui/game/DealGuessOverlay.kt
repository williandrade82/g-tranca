package com.gtranca.ui.game

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * §3.6, telas-app.md 3.8.2 (14/11/2026, reescrita em `Canvas`: a composição por `Box`es sobre a arte de fundo tinha
 * um bug de orientação — o `Box` que cresce com `fillMaxHeight(fraction)` dentro de outro sem `contentAlignment`
 * ancora no topo por padrão, então o indicador não acompanhava o estágio corretamente): a barra de palpite —
 * **trilho azul sempre visível, de ponta a ponta** ([GColors.RoyalBlue]); o **segmento verde** ([GColors.Emerald])
 * da faixa já marcada ([rangeStart] até [stage], quando o 1º toque já aconteceu); e o **indicador** (triângulo
 * dourado), à esquerda da barra. Eixo do `Canvas`: y cresce para baixo, então o estágio 0 fica no **topo** e
 * [totalStages] na **base** — o indicador percorre a barra de cima a baixo e de baixo para cima em ciclo contínuo
 * (quem anima o vai-e-vem é [dealGuessLiveStage]; aqui só se desenha a posição atual).
 */
@Composable
private fun DealGuessGauge(stage: Int, totalStages: Int, rangeStart: Int?, modifier: Modifier = Modifier) {
    val fraction = if (totalStages <= 0) 0f else (stage.toFloat() / totalStages).coerceIn(0f, 1f)
    val rangeFraction = rangeStart?.takeIf { totalStages > 0 }?.let { (it.toFloat() / totalStages).coerceIn(0f, 1f) }
    Canvas(modifier) {
        val barWidth = size.width * 0.5f
        val barLeft = size.width - barWidth
        val corner = CornerRadius(barWidth / 2f)
        // Trilho: sempre visível, do topo à base (nunca escondido pelo preenchimento nem pelo indicador).
        drawRoundRect(color = GColors.RoyalBlue, topLeft = Offset(barLeft, 0f), size = Size(barWidth, size.height), cornerRadius = corner)
        // Faixa já marcada: do Início (1º toque) até a posição atual do indicador.
        if (rangeFraction != null) {
            val top = minOf(rangeFraction, fraction) * size.height
            val bottom = maxOf(rangeFraction, fraction) * size.height
            if (bottom > top) {
                drawRoundRect(
                    color = GColors.Emerald,
                    topLeft = Offset(barLeft, top),
                    size = Size(barWidth, bottom - top),
                    cornerRadius = corner,
                )
            }
        }
        // Indicador: triângulo dourado apontando para a barra, na altura do estágio atual (0 = topo).
        val y = fraction * size.height
        val half = (barWidth * 0.9f).coerceAtMost(size.height / 2f)
        val tailX = (barLeft - barWidth).coerceAtLeast(0f)
        drawPath(
            Path().apply {
                moveTo(barLeft, y)
                lineTo(tailX, y - half)
                lineTo(tailX, y + half)
                close()
            },
            color = GColors.Gold,
        )
    }
}

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
 * canastra; erro, 14/11/2026 simplificada: só o banner a 75% da largura da tela e, embaixo dele, os três campos
 * Início/Fim/Total que o jogador tocou, sem borda piscando nem outro efeito) — do próprio jogador ou do parceiro
 * dele (duplas). Fica até o toque ([onDismiss], que libera o motor para o próximo palpite ou a 1ª jogada de verdade).
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 420.dp).padding(24.dp).testTag("deal-guess-win"),
            ) {
                Image(
                    painterResource(R.drawable.deal_guess_win),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(Modifier.height(16.dp))
                Text(text, color = GColors.Ivory, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text(closeText, color = GColors.Ivory.copy(alpha = 0.8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // telas-app.md 3.8.2 (14/11/2026): sem borda âmbar nem outro drama — só o banner a 75% da tela e,
            // embaixo, os campos que o jogador tocou (mesmo visual do modal de palpite, `DealGuessField`).
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("deal-guess-miss")) {
                Image(
                    painterResource(R.drawable.deal_guess_miss),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(0.75f),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // §3.6 Início/Fim só existem quando foi este aparelho que tocou (`reveal.start`/`.end`, `null` na
                    // reação do parceiro: a tela mostra "—", `DealGuessField` já trata isso); o Total (largura) vem
                    // do motor e aparece sempre.
                    DealGuessField(stringResource(R.string.deal_guess_start_label), reveal.start, Modifier.testTag("deal-guess-reveal-start"))
                    DealGuessField(stringResource(R.string.deal_guess_end_label), reveal.end, Modifier.testTag("deal-guess-reveal-end"))
                    DealGuessField(stringResource(R.string.deal_guess_total_label), reveal.width, Modifier.testTag("deal-guess-reveal-total"))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text, color = GColors.Ivory, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(closeText, color = GColors.Ivory.copy(alpha = 0.8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
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

