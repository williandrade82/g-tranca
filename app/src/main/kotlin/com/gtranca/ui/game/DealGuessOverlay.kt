package com.gtranca.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gtranca.R
import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.goldTitleStyle
import com.gtranca.ui.theme.loungeDialog
import com.gtranca.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Um ciclo (subida OU descida) do indicador, por padrão dentro de 2 a 4 s (§14.5), como as demais animações cíclicas. */
private const val DEAL_GUESS_CYCLE_MILLIS = 2_600

/**
 * §3.6 (ajuste de 15/11/2026, toque único) número que o indicador mostra em [elapsedMs] desde o início do ciclo,
 * sempre dentro de [range] (nunca fora dela, nem na mão nem no morto — a faixa é fixa por papel/modo,
 * `dealGuessHandRange`/`dealGuessMortoRange`). Pura (sem Compose) para ser testável direto na JVM: sobe do
 * início ao fim de [range] no 1º meio-ciclo e desce de volta no 2º, em ciclo contínuo de `2 * cycleMillis`.
 */
internal fun dealGuessStageAt(elapsedMs: Long, range: IntRange, cycleMillis: Int = DEAL_GUESS_CYCLE_MILLIS): Int {
    val span = range.last - range.first
    if (span <= 0) return range.first
    val period = cycleMillis * 2
    val phase = elapsedMs % period
    val progress = if (phase < cycleMillis) phase.toFloat() / cycleMillis else 2f - phase.toFloat() / cycleMillis
    val offset = (progress * span).roundToInt().coerceIn(0, span)
    return range.first + offset
}

/**
 * §3.6 estágio ao vivo do indicador (sempre dentro de [range]), um relógio só (não um `Animatable` por carta): sobe
 * e desce em ciclo com o tempo real do quadro ([withFrameNanos]), **independente** da escala de animação do
 * sistema — é a mecânica do minijogo, não um efeito cosmético, então continua rodando mesmo com "remover
 * animações" ligado. Para de vez quando [frozen] não é nulo (o toque único que resolve o palpite).
 */
@Composable
private fun dealGuessLiveStage(range: IntRange, frozen: Int?): Int {
    var stage by remember(range) { mutableStateOf(range.first) }
    LaunchedEffect(range, frozen) {
        if (frozen != null) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - start) / 1_000_000
                stage = dealGuessStageAt(elapsedMs, range)
            }
        }
    }
    return frozen ?: stage
}

/**
 * §3.6 fração (0 a 1) da barra preenchida do topo ([range.first]) até o estágio atual [stage] — mantém, no toque
 * único, a mesma leitura visual de "faixa preenchida" que a barra já tinha (14/11/2026), sem precisar de um 1º
 * toque: o preenchimento sempre nasce no topo e cresce/encolhe com o indicador. Extraída à parte (testável sem
 * Compose).
 */
internal fun dealGuessFillFraction(stage: Int, range: IntRange): Float {
    val span = range.last - range.first
    if (span <= 0) return 0f
    return ((stage - range.first).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * Frações medidas na arte `deal_guess_background` (640×853 px) do **vazado** do bastão dourado onde a barra
 * funcional entra (trilho de ponta a ponta, ocupando o vazado inteiro — não mais só a metade dele) e da borda
 * externa do trilho esquerdo (onde a ponta do indicador encosta), com ~2 px de folga da antialiasing da arte.
 */
private const val HOLLOW_LEFT = 519f / 640f
private const val HOLLOW_TOP = 194f / 853f
private const val HOLLOW_WIDTH = 48f / 640f
private const val HOLLOW_HEIGHT = 565f / 853f
private const val RAIL_OUTER_LEFT = 491f / 640f

/** Proporção (largura/altura) e altura relativa do "cabo" (círculo) de [R.drawable.deal_guess_indicator]. */
private const val INDICATOR_ASPECT = 240f / 164f
private const val INDICATOR_HUB_Y = 88f / 164f
private const val INDICATOR_WIDTH_FRACTION = 0.24f

/**
 * §3.6, telas-app.md 3.8.2 (reescrita em `Canvas`): a barra de palpite — **trilho azul sempre visível, de ponta a
 * ponta** ([GColors.RoyalBlue]) e o **preenchimento verde** ([GColors.Emerald]) do topo até o estágio atual
 * ([fill], 0 a 1, [dealGuessFillFraction]). Eixo do `Canvas`: y cresce para baixo, então o estágio mínimo da faixa
 * fica no **topo** e o máximo na **base**. O indicador (imagem de verdade, não um triângulo desenhado aqui) é
 * posicionado por [DealGuessModal], fora deste `Canvas`, porque precisa das dimensões em dp da arte de fundo inteira.
 */
@Composable
private fun DealGuessGauge(fill: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val corner = CornerRadius(size.width / 2f)
        // Trilho: sempre visível, do topo à base (nunca escondido pelo preenchimento).
        drawRoundRect(color = GColors.RoyalBlue, size = size, cornerRadius = corner)
        val bottom = fill.coerceIn(0f, 1f) * size.height
        if (bottom > 0f) {
            drawRoundRect(color = GColors.Emerald, size = Size(size.width, bottom), cornerRadius = corner)
        }
    }
}

/**
 * §3.6, telas-app.md 3.8.2 (ajuste de 15/11/2026, toque único): modal "Cartas para a distribuição" / "Cartas para o
 * Morto". Só aparece na tela de quem está palpitando. O indicador percorre, ida e volta, só os números de
 * [DealGuessPrompt.range]; um único toque marca o número do estágio naquele instante como o palpite e já o envia
 * ao motor ([onGuess]). A tela nunca decide sozinha se acertou: só mostra o número tocado; o resultado chega
 * depois, em [GuessSideReactionOverlay] (reação pequena, dentro da mesa — 15/11/2026, antes era uma tela cheia).
 *
 * [onGuess] devolve se o motor aceitou de verdade o toque (`TableEvents.onGuessDealNumber`): um toque pode ser
 * ignorado por uma corrida de estado passageira (multijogador/online, §16.8/§16.9) sem que o jogo tenha avançado —
 * só congela a barra ([frozen]) quando a devolução for `true`; senão, o próximo toque tenta de novo normalmente,
 * como qualquer outro controle da mesa (nenhum outro usa um "já toquei, ignoro pra sempre" do lado da tela).
 */
@Composable
fun DealGuessModal(prompt: DealGuessPrompt, onGuess: (Int) -> Boolean) {
    var frozen by remember(prompt) { mutableStateOf<Int?>(null) }
    val stage = dealGuessLiveStage(prompt.range, frozen)
    val title = stringResource(if (prompt.stage == DealGuessStage.HAND) R.string.deal_guess_title_hand else R.string.deal_guess_title_morto)
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (frozen != null) return@clickable // já resolvido: espera o motor (sem toque duplo)
                if (onGuess(stage)) frozen = stage
            }
            .semantics(mergeDescendants = true) { contentDescription = title; liveRegion = LiveRegionMode.Polite }
            .testTag("deal-guess-modal"),
        contentAlignment = Alignment.Center,
    ) {
        // §3.6: o conteúdo mora num cartão de fundo sólido (`loungeDialog`, mesmo estilo dos demais modais do
        // jogo), não solto sobre o scrim — mas o `clickable` continua no `Box` de fora, em qualquer ponto da tela
        // (inclusive fora do cartão): é assim que o toque único funciona.
        val panelShape = RoundedCornerShape(Corner.lg)
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .loungeDialog(panelShape)
                .clip(panelShape)
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = goldTitleStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)), textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.sm))
            // §3.6 item 7 do relato 14/11/2026: o alvo (44/22 ou 22) é número público das regras
            // (`definition.md` §3.6); só a posição certa na barra é segredo do motor.
            Text(
                stringResource(R.string.deal_guess_target, prompt.target),
                color = GColors.Gold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(Spacing.md))
            // Tamanho reduzido (14/11/2026): a arte inteira mede 150dp de largura (antes era ilimitada por engano —
            // o `widthIn` depois do `aspectRatio` não tinha efeito nenhum, pois `aspectRatio` mede o filho com
            // restrições fixas antes do `widthIn` seguinte rodar). A barra funcional fica encaixada com precisão
            // dentro do vazado dourado da própria arte (`HOLLOW_*`, medido em pixels da arte).
            BoxWithConstraints(Modifier.width(150.dp).aspectRatio(640f / 853f)) {
                Image(
                    painterResource(R.drawable.deal_guess_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                val hollowLeft = maxWidth * HOLLOW_LEFT
                val hollowTop = maxHeight * HOLLOW_TOP
                val hollowWidth = maxWidth * HOLLOW_WIDTH
                val hollowHeight = maxHeight * HOLLOW_HEIGHT
                val fraction = dealGuessFillFraction(stage, prompt.range)
                DealGuessGauge(
                    fraction,
                    Modifier
                        .offset(x = hollowLeft, y = hollowTop)
                        .size(hollowWidth, hollowHeight)
                        .testTag("deal-guess-gauge"),
                )
                // Indicador: a imagem de verdade (`deal_guess_indicator`, não um triângulo desenhado), com a ponta
                // encostada na borda externa do trilho esquerdo, na altura do estágio atual.
                val indicatorWidth = maxWidth * INDICATOR_WIDTH_FRACTION
                val indicatorHeight = indicatorWidth / INDICATOR_ASPECT
                val railOuterLeft = maxWidth * RAIL_OUTER_LEFT
                Image(
                    painterResource(R.drawable.deal_guess_indicator),
                    contentDescription = null,
                    modifier = Modifier
                        .size(indicatorWidth, indicatorHeight)
                        .offset(
                            x = railOuterLeft - indicatorWidth,
                            y = hollowTop + hollowHeight * fraction - indicatorHeight * INDICATOR_HUB_Y,
                        ),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                stringResource(R.string.deal_guess_instructions),
                color = GColors.Ivory,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(Spacing.lg))
            // §3.6 (toque único, 15/11/2026): um único campo, com o número que o indicador mostra agora (atualizado
            // a cada quadro) — nunca mais Início/Fim/Total, que só faziam sentido com dois toques.
            DealGuessField(stringResource(R.string.deal_guess_number_label), stage, Modifier.testTag("deal-guess-number"))
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
 * Texto exato do §3.8.2 do telas-app.md para a reação dentro da mesa, própria ou do parceiro ([reaction.own]).
 */
private fun dealGuessSideReactionTextRes(reaction: DealGuessSideReaction): Int = when {
    reaction.own && reaction.correct -> R.string.deal_guess_win_you
    reaction.own && reaction.stage == DealGuessStage.HAND -> R.string.deal_guess_miss_you_hand
    reaction.own -> R.string.deal_guess_miss_you_morto
    reaction.correct && reaction.stage == DealGuessStage.HAND -> R.string.deal_guess_win_partner_hand
    reaction.correct -> R.string.deal_guess_win_partner_morto
    reaction.stage == DealGuessStage.HAND -> R.string.deal_guess_miss_partner_hand
    else -> R.string.deal_guess_miss_partner_morto
}

/**
 * Imagem de vitória/erro (§3.8.2) no topo do cartão, sem fundo: 90 % da largura do CARTÃO (não um tamanho fixo —
 * 14/11/2026, pedido do usuário: o tamanho fixo anterior ficava pequeno demais e ilegível com o cartão agora largo).
 * A altura segue a proporção original da imagem ([aspectRatio], abaixo), nunca distorcida.
 */
private const val PARTNER_GUESS_IMAGE_WIDTH_FRACTION = 0.9f

/** Largura do cartão de texto (telas-app.md 3.8.2, pedido do usuário 14/11/2026): 90 % da largura da MESA. */
private const val PARTNER_GUESS_WIDTH_FRACTION = 0.9f

/** Sem [Rect] da mesa pra calcular a largura (não devia acontecer): cai numa largura fixa, como antes do redesenho. */
private val PARTNER_GUESS_FALLBACK_WIDTH = 248.dp

/** Quanto dura a entrada (de fora da mesa, à esquerda, até a posição de repouso encostada na borda); igual para as duas reações. */
private const val PARTNER_GUESS_ENTER_MILLIS = 450

/**
 * Quanto o cartão fica parado e visível na posição de repouso, antes de começar a sair — pedido do usuário: 4 s
 * para o parceiro (duplas, 14/11/2026), **1,5 s para o próprio jogador** (15/11/2026, [DealGuessSideReaction.own],
 * substitui a antiga tela cheia que só fechava no toque). Mesma relação que já valia pro parceiro: este valor
 * conduz só a ANIMAÇÃO (entrada → repouso → saída, ~450 ms + este valor + ~450 ms); [GameViewModel.OWN_GUESS_REACTION_MILLIS]
 * (também 1,5 s, mas contado a partir do nascimento da reação, não do início do repouso) é quem de fato zera
 * [GameUiState.ownGuessReaction] e libera [GameUiState.isDealGuessing]/o monte — um pouco ANTES de a animação
 * terminar de verdade (a carta continua visível, conduzida sozinha por este `Composable`, até sair da mesa).
 */
private const val PARTNER_GUESS_REST_MILLIS = 4_000L
private const val OWN_GUESS_REST_MILLIS = 1_500L

/** Quanto dura a saída (da posição de repouso até sair da mesa pela borda direita); igual para as duas reações. */
private const val PARTNER_GUESS_EXIT_MILLIS = 450

/**
 * §3.6, telas-app.md 3.8.2 (15/11/2026, unificada): reação pequena e **não bloqueante**, dentro da mesa, a um
 * palpite resolvido — do PRÓPRIO jogador deste aparelho ([reaction.own] `true`, `role == YOU`, substitui a antiga
 * tela cheia que exigia toque) ou do PARCEIRO (duplas, `role == PARTNER`). Em nenhum dos dois casos esta função
 * espera nada: o motor já foi liberado na hora ([GameViewModel.reactToGuess]/[GameController.guessRevealDone]),
 * antes mesmo desta reação nascer.
 *
 * Redesenhada 14/11/2026 (pedido do usuário) para o parceiro, reaproveitada 15/11/2026 para o próprio jogador:
 * nasce encostada na borda **esquerda** da área de jogos do próprio lado ([sideAreaRect], `AnimAnchor.SideArea` do
 * lado do jogador — "a área de organização das cartas baixadas pela dupla", a mesma onde ele baixa as próprias
 * cartas) e desliza **horizontalmente**, da esquerda para a direita, em três fases que [reaction] por si só não
 * controla (a UI conduz sozinha, a partir da última reação não-nula recebida — por isso [reaction] aqui é
 * anulável: chamar sempre, incondicionalmente, é o que permite continuar desenhando a saída mesmo depois do
 * `GameViewModel` já ter zerado o estado, [GameViewModel.PARTNER_GUESS_REACTION_MILLIS]/[GameViewModel.OWN_GUESS_REACTION_MILLIS]
 * depois de armado):
 * 1. **Entrada** ([PARTNER_GUESS_ENTER_MILLIS]): de fora da mesa (à esquerda de [sideAreaRect]) até a posição de
 *    repouso, encostada na borda esquerda dele.
 * 2. **Repouso** ([PARTNER_GUESS_REST_MILLIS] ou [OWN_GUESS_REST_MILLIS], conforme [reaction.own]): parada, visível.
 * 3. **Saída** ([PARTNER_GUESS_EXIT_MILLIS]): continua para a direita até sair da mesa pela borda direita de
 *    [tableRect], e só então o bloco é descartado de vez.
 *
 * **Selo de acerto/erro:** só quando [reaction.own] é `true` (o parceiro já lê o resultado pelo parágrafo de
 * números do próprio cartão) — um ✓ verde ([GColors.Emerald]) no acerto, um ✗ vermelho ([GColors.Ruby]) no erro,
 * desenhado em `Canvas` ([GuessOutcomeMark]) no canto superior do bloco (sem ícone de biblioteca: o catálogo
 * `material-icons` não está nas dependências do projeto, `gradle/libs.versions.toml`).
 *
 * Mesma técnica de posicionamento por coordenadas de raiz do resto da mesa (`FlyingCard`, `ReactionCenterOverlay`):
 * os retângulos e o próprio cartão são medidos na raiz da janela, então a conta vale mesmo com a mesa rolada.
 *
 * **Atrás das cartas da distribuição:** esta função não define `zIndex` nenhum — quem chama deve compô-la **antes**
 * de `FlightsLayer` (o voo das cartas), no mesmo `Box`, para o voo (declarado depois) ficar visualmente por cima
 * quando passar pela mesma área (telas-app.md 3.8.2).
 */
@Composable
fun GuessSideReactionOverlay(reaction: DealGuessSideReaction?, tableRect: Rect?, sideAreaRect: Rect?) {
    val sound = LocalSound.current
    // Som de canastra no acerto do PRÓPRIO jogador (herdado da antiga tela cheia); o parceiro nunca teve som aqui.
    LaunchedEffect(reaction?.id) { if (reaction?.own == true && reaction.correct) sound.play(SoundEffect.CANASTA) }
    // Guarda a última reação não-nula por `id`: a UI decide sozinha quando descartar de vez (ao fim da saída),
    // independente de o ViewModel já ter zerado `reaction` antes disso (ele nunca espera por esta animação).
    var shown by remember { mutableStateOf<DealGuessSideReaction?>(null) }
    LaunchedEffect(reaction?.id) { if (reaction != null) shown = reaction }
    val current = shown ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .testTag("deal-guess-side-reaction"),
    ) {
        key(current.id) {
            val motion = rememberMotionEnabled()
            // 0 = nascimento (fora da mesa, à esquerda); 1 = repouso (encostada na borda esquerda da área de jogos).
            val enterProgress = remember { Animatable(if (motion) 0f else 1f) }
            // 0 = ainda em repouso; 1 = saiu da mesa pela direita.
            val exitProgress = remember { Animatable(0f) }
            LaunchedEffect(current.id) {
                if (motion) enterProgress.animateTo(1f, tween(PARTNER_GUESS_ENTER_MILLIS, easing = FastOutSlowInEasing))
                delay(if (current.own) OWN_GUESS_REST_MILLIS else PARTNER_GUESS_REST_MILLIS)
                if (motion) exitProgress.animateTo(1f, tween(PARTNER_GUESS_EXIT_MILLIS, easing = FastOutSlowInEasing)) else exitProgress.snapTo(1f)
                // Só descarta se ninguém chegou com uma reação mais nova enquanto este bloco saía.
                if (shown?.id == current.id) shown = null
            }
            val density = LocalDensity.current
            val fallbackWidthPx = with(density) { PARTNER_GUESS_FALLBACK_WIDTH.toPx() }
            val widthPx = tableRect?.width?.times(PARTNER_GUESS_WIDTH_FRACTION) ?: fallbackWidthPx
            val insetPx = with(density) { Spacing.md.toPx() }
            var selfHeightPx by remember { mutableStateOf(0f) }
            val accent = if (current.correct) GColors.Emerald else GColors.Ruby
            val numbersRes = if (current.own) R.string.deal_guess_own_numbers else R.string.deal_guess_partner_numbers
            val text = stringResource(dealGuessSideReactionTextRes(current)) + "\n\n" +
                stringResource(numbersRes, current.target, current.guessedWidth)
            Column(
                Modifier
                    .offset {
                        // Repouso: encostada na borda esquerda da área de jogos do próprio lado; nascimento: a
                        // mesma posição, deslocada para fora da mesa (à esquerda); saída: desliza para a direita
                        // até sair da mesa pela borda direita — tudo em coordenadas de raiz, como [origin].
                        val restX = sideAreaRect?.left ?: tableRect?.left ?: 0f
                        val enterStartX = restX - widthPx - insetPx
                        val exitEndX = (tableRect?.right ?: (restX + widthPx)) + widthPx + insetPx
                        val baseX = enterStartX + (restX - enterStartX) * enterProgress.value
                        val x = baseX + (exitEndX - restX) * exitProgress.value
                        val areaTop = sideAreaRect?.top ?: tableRect?.top ?: 0f
                        val areaHeight = sideAreaRect?.height ?: 0f
                        var y = areaTop + areaHeight / 2f - selfHeightPx / 2f
                        // §3.8.2 nunca deixa o bloco (imagem + cartão) sair da área visível da mesa (14/11/2026,
                        // pedido do usuário: com a imagem maior o bloco ficava mais alto que a área de jogos e
                        // acabava renderizando fora do quadro) — fica dentro de [tableRect], a janela visível.
                        if (tableRect != null) y = y.coerceIn(tableRect.top, (tableRect.bottom - selfHeightPx).coerceAtLeast(tableRect.top))
                        val point = Offset(x, y) - origin
                        IntOffset(point.x.roundToInt(), point.y.roundToInt())
                    }
                    .onGloballyPositioned { selfHeightPx = it.size.height.toFloat() }
                    .width(with(density) { widthPx.toDp() })
                    .testTag("deal-guess-side-reaction-card"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // §3.8.2 a imagem não leva fundo (só o cartão do texto, abaixo); centralizada por cima, 90 % da
                // largura do cartão (14/11/2026), com a altura seguindo a proporção original da arte (nunca
                // distorcida) — [aspectRatio] calculado a partir do tamanho intrínseco do próprio `painter`.
                val painter = painterResource(if (current.correct) R.drawable.deal_guess_win else R.drawable.deal_guess_miss)
                val imageRatio = painter.intrinsicSize.let { if (it.isSpecified && it.height > 0f) it.width / it.height else 1f }
                Box(contentAlignment = Alignment.TopEnd) {
                    Image(
                        painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(PARTNER_GUESS_IMAGE_WIDTH_FRACTION).aspectRatio(imageRatio),
                        contentScale = ContentScale.FillWidth,
                    )
                    // §3.6 selo ✓/✗ (15/11/2026, pedido do usuário): só na reação do PRÓPRIO jogador — o parceiro
                    // já lê o resultado pelo parágrafo de números abaixo.
                    if (current.own) {
                        GuessOutcomeMark(
                            current.correct,
                            Modifier.padding(Spacing.xs).size(28.dp).testTag("deal-guess-outcome-mark"),
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(GColors.Midnight.copy(alpha = 0.92f), RoundedCornerShape(Corner.sm))
                        .border(2.dp, accent, RoundedCornerShape(Corner.sm))
                        .padding(Spacing.sm)
                        .semantics(mergeDescendants = true) { contentDescription = text; liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        color = GColors.Ivory,
                        textAlign = TextAlign.Center,
                        maxLines = 6,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * §3.6 (15/11/2026, pedido do usuário) selo de acerto/erro da reação pequena do PRÓPRIO jogador: um ✓ verde
 * ([GColors.Emerald]) no acerto, um ✗ vermelho ([GColors.Ruby]) no erro — desenhado em `Canvas`, sem ícone de
 * biblioteca (o catálogo `androidx.compose.material:material-icons-*` não está em `gradle/libs.versions.toml`;
 * não foi adicionado só para isto, por decisão do CLAUDE.md de registrar dependências novas no catálogo). Um
 * disco sólido atrás do traço garante contraste sobre a arte de fundo (clara, no acerto; cinza, no erro).
 */
@Composable
private fun GuessOutcomeMark(correct: Boolean, modifier: Modifier = Modifier) {
    val mark = if (correct) GColors.Emerald else GColors.Ruby
    // Decorativo: o texto do cartão já anuncia o resultado (contentDescription do Box de texto, acima).
    Canvas(modifier.clearAndSetSemantics {}) {
        drawCircle(color = Color.White, radius = size.minDimension / 2f)
        drawCircle(color = mark, radius = size.minDimension / 2f, alpha = 0.92f)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
        if (correct) {
            val path = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.68f)
                lineTo(size.width * 0.75f, size.height * 0.32f)
            }
            drawPath(path, color = Color.White, style = stroke)
        } else {
            drawLine(Color.White, Offset(size.width * 0.3f, size.height * 0.3f), Offset(size.width * 0.7f, size.height * 0.7f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(size.width * 0.7f, size.height * 0.3f), Offset(size.width * 0.3f, size.height * 0.7f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

/**
 * §3.6 (15/11/2026, pedido do usuário) selos PERMANENTES do resultado do palpite da distribuição certeira, na
 * pílula do assento [seat] (`SeatPill`, `TableScreen.kt`) — em **todas** as telas, inclusive a do próprio jogador
 * (junto do avatar da própria mão, não só na pílula dos outros assentos) e a do parceiro, não só "nos demais
 * aparelhos" como a reação transitória (`dealGuessPills`). Ficam até o fim da PARTIDA ([dealGuessBadges] lê
 * direto `PlayerView.dealGuess`, que só muda na próxima partida — nenhum estado novo na UI) e aparecem **junto**
 * com a reação já existente da pílula (confete/borda dourada no acerto, tremor no erro), não a substituem.
 *
 * Até dois selos (mão e morto) — mas [dealGuessBadges] documenta que [seat] nunca acumula os dois na prática
 * ([dealGuessSeats] garante assentos diferentes para os dois papéis, em qualquer modo).
 */
@Composable
fun DealGuessBadges(dealGuess: com.gtranca.engine.model.DealGuess?, seat: com.gtranca.engine.model.Seat, modifier: Modifier = Modifier) {
    val badges = dealGuessBadges(dealGuess, seat)
    if (badges.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        badges.forEach { (stage, correct) -> key(stage) { DealGuessBadge(stage, correct) } }
    }
}

/** Texto do selo de acerto ("+100pts") exibido junto da arte do baralho cortada no erro. */
private fun dealGuessBadgeTextRes(stage: DealGuessStage, correct: Boolean): Int = when {
    correct && stage == DealGuessStage.HAND -> R.string.deal_guess_badge_hit_hand
    correct -> R.string.deal_guess_badge_hit_morto
    stage == DealGuessStage.HAND -> R.string.deal_guess_badge_miss_hand
    else -> R.string.deal_guess_badge_miss_morto
}

/** Tamanho do selo (quadrado, pequeno — uma pílula já é compacta em telas de 360dp). */
private val DEAL_GUESS_BADGE_SIZE = 18.dp

@Composable
private fun DealGuessBadge(stage: DealGuessStage, correct: Boolean) {
    val text = stringResource(dealGuessBadgeTextRes(stage, correct))
    val tag = "deal-guess-badge-${if (correct) "hit" else "miss"}-${if (stage == DealGuessStage.HAND) "hand" else "morto"}"
    if (correct) {
        // Selo "+100pts": cápsula dourada/esmeralda — não é a imagem do baralho (essa é só do erro, abaixo).
        Box(
            Modifier
                .background(GColors.Emerald, RoundedCornerShape(50))
                .border(1.dp, GColors.Gold, RoundedCornerShape(50))
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .semantics { contentDescription = text }
                .testTag(tag),
        ) {
            Text(
                stringResource(R.string.deal_guess_badge_points),
                color = GColors.Midnight,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
            )
        }
    } else {
        // Selo de erro: recorte quadrado da arte do baralho (telas-app.md 3.8.2: "a imagem do baralho") com um
        // círculo vermelho cortado por cima, desenhado em Canvas (mesmo padrão de GuessOutcomeMark/DealGuessGauge).
        Box(
            Modifier
                .size(DEAL_GUESS_BADGE_SIZE)
                .clip(RoundedCornerShape(3.dp))
                .semantics { contentDescription = text }
                .testTag(tag),
        ) {
            Image(
                painterResource(R.drawable.deal_guess_background),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Canvas(Modifier.matchParentSize()) {
                val stroke = size.minDimension * 0.16f
                drawCircle(
                    color = GColors.Ruby,
                    radius = size.minDimension / 2f - stroke / 2f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawLine(
                    GColors.Ruby,
                    Offset(size.width * 0.22f, size.height * 0.22f),
                    Offset(size.width * 0.78f, size.height * 0.78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
