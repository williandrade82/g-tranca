package com.gtranca.ui.tour

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtranca.R
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import kotlin.math.roundToInt

/** Um passo do tour guiado (docs/telas.md 3.16): o elemento destacado ([target], a mesma tag de teste dele) e o texto. */
data class TourStep(val target: String, @StringRes val text: Int)

/** Passo atual de um tour com [size] passos; `null` = tour fechado. Puro: testável na JVM. */
data class TourState(val size: Int, val index: Int = 0) {
    val isLast: Boolean get() = index == size - 1

    /** Próximo passo; no último, o tour termina (`null`). */
    fun next(): TourState? = if (isLast) null else copy(index = index + 1)

    /** Passo anterior (no primeiro, fica nele). */
    fun back(): TourState = copy(index = (index - 1).coerceAtLeast(0))
}

/** Os tours do app e quando a mesa de jogo pode mostrar o seu. */
object Tours {
    const val TABLES = "tables"
    const val SELECTION = "player-selection"
    const val TABLE = "table"
    const val SUMMARY = "round-summary"
    const val BUTTON = "tour-button"

    /**
     * Tour de Pontos da partida (docs/telas.md 3.10, 3.16): cada coluna e cada linha da tabela, de cima para baixo.
     * Sem coringas na mesa (§1), as linhas de canastra suja e de coringas não existem e ficam de fora.
     */
    fun summarySteps(noWilds: Boolean): List<TourStep> = listOfNotNull(
        TourStep(BUTTON, R.string.tour_again),
        TourStep("score-col-item", R.string.tour_summary_item),
        TourStep("score-side-own", R.string.tour_summary_sides),
        TourStep("score-col-value", R.string.tour_summary_value),
        TourStep("score-col-count", R.string.tour_summary_count),
        TourStep("score-col-points", R.string.tour_summary_points),
        TourStep("score-row-RED_THREES", R.string.tour_summary_red_threes),
        TourStep("score-row-CLEAN_CANASTAS", if (noWilds) R.string.tour_summary_canastas else R.string.tour_summary_clean),
        TourStep("score-row-DIRTY_CANASTAS", R.string.tour_summary_dirty).takeUnless { noWilds },
        TourStep("score-row-GO_OUT", R.string.tour_summary_go_out),
        TourStep("score-row-MORTO_NOT_TAKEN", R.string.tour_summary_morto),
        TourStep("table-section", R.string.tour_summary_table_section),
        TourStep("score-row-TABLE_RED_THREES", R.string.tour_summary_table_red_threes),
        TourStep("score-row-TABLE_FOUR_TO_TEN", R.string.tour_summary_table_low),
        TourStep("score-row-TABLE_FACES", R.string.tour_summary_table_faces),
        TourStep("score-row-TABLE_WILDS", R.string.tour_summary_table_wilds).takeUnless { noWilds },
        TourStep("table-subtotal", R.string.tour_summary_table_subtotal),
        TourStep("hand-section", R.string.tour_summary_hand_section),
        TourStep("score-row-HAND_RED_THREES", R.string.tour_summary_hand_red_threes),
        TourStep("score-row-HAND_BLACK_THREES", R.string.tour_summary_hand_black_threes),
        TourStep("score-row-HAND_FOUR_TO_TEN", R.string.tour_summary_hand_low),
        TourStep("score-row-HAND_FACES", R.string.tour_summary_hand_faces),
        TourStep("score-row-HAND_WILDS", R.string.tour_summary_hand_wilds).takeUnless { noWilds },
        TourStep("hand-subtotal", R.string.tour_summary_hand_subtotal),
        TourStep("score-round-total", R.string.tour_summary_round_total),
        TourStep("cumulative", R.string.tour_summary_cumulative),
        TourStep("summary-continue", R.string.tour_summary_continue),
    )

    // Os passos seguem a tela de cima para baixo (e, na mesma linha, da esquerda para a direita).
    val tablesSteps = listOf(
        TourStep(BUTTON, R.string.tour_again),
        TourStep("tables-wifi", R.string.tour_tables_wifi),
        TourStep("recruiters", R.string.tour_tables_recruiters),
        TourStep("refresh-players", R.string.tour_refresh),
        TourStep("table-options-panel", R.string.tour_tables_options),
        TourStep("select-players", R.string.tour_tables_select_players),
    )

    /** Tour da Seleção de jogadores (docs/telas.md 3.3, 3.16). */
    val selectionSteps = listOf(
        TourStep(BUTTON, R.string.tour_again),
        TourStep("seat-table", R.string.tour_sel_table),
        TourStep("robot-row", R.string.tour_sel_robots),
        TourStep("multiplayer-block", R.string.tour_sel_multiplayer),
        TourStep("selection-refresh", R.string.tour_refresh),
        TourStep("fill-with-robots", R.string.tour_sel_fill),
        TourStep("start-match", R.string.tour_sel_start),
    )

    val tableSteps = listOf(
        TourStep("score", R.string.tour_table_score),
        TourStep("table-pot", R.string.tour_table_pot),
        TourStep("coin-balance", R.string.tour_table_balance),
        TourStep(BUTTON, R.string.tour_again),
        TourStep("table-options", R.string.tour_table_options),
        TourStep("seats", R.string.tour_table_seats),
        TourStep("stock", R.string.tour_table_stock),
        TourStep("mortos", R.string.tour_table_mortos),
        TourStep("discard-pile", R.string.tour_table_discard),
        TourStep("hand", R.string.tour_table_hand),
        TourStep("action-bar", R.string.tour_table_actions),
    )

    /**
     * A mesa mostra o tour sozinha só na primeira vez em que é a vez do jogador, já liberada, com as animações iniciais
     * (distribuição, voos e trocas de 3 vermelho) terminadas e sem tela de fim por cima.
     */
    fun tableReady(isHumanTurn: Boolean, dealPending: Boolean, animating: Boolean, swapShowing: Boolean, endScreen: Boolean): Boolean =
        isHumanTurn && !dealPending && !animating && !swapShowing && !endScreen
}

/** Tours já vistos (`null` enquanto lê) e como marcar um como visto; fornecido pelo app todo. */
data class TourSettings(val seen: Set<String>? = null, val auto: Boolean = true, val markSeen: (String) -> Unit = {})

/**
 * Cores do balão do tour: marfim com texto azul-marinho (contraste AA ≥ 4,5:1; o "Cream" do tema é o índigo escuro, que
 * deixava o texto escuro sobre escuro).
 */
object TourColors {
    val background = GColors.Ivory
    val text = GColors.Midnight

    /** Razão de contraste WCAG entre duas cores (1 a 21). */
    fun contrast(a: Color, b: Color): Double {
        fun lum(c: Color): Double {
            fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}

val LocalTours = staticCompositionLocalOf { TourSettings(seen = emptySet()) }

private val LocalTourTargets = compositionLocalOf<MutableMap<String, Rect>?> { null }

private val LocalTourRequesters = compositionLocalOf<MutableMap<String, androidx.compose.foundation.relocation.BringIntoViewRequester>?> { null }

/**
 * Marca o elemento como alvo [key] do tour (posição na tela); sem tour por perto, não faz nada. Dentro de uma área que
 * rola, o tour rola até ele no seu passo.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.tourTarget(key: String): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed this
    val requesters = LocalTourRequesters.current
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.DisposableEffect(key, requesters) {
        requesters?.set(key, requester)
        onDispose { if (requesters?.get(key) === requester) requesters.remove(key) }
    }
    this.bringIntoViewRequester(requester)
        .onGloballyPositioned { targets[key] = it.boundsInRoot() }
}

/**
 * Tela com tour guiado: [content] recebe a ação de abrir o tour (para o ícone). O tour [id] abre sozinho uma vez quando
 * [autoStart] e ainda não foi visto; ao terminar ou pular, fica marcado como visto.
 */
@Composable
fun TourHost(id: String, steps: List<TourStep>, autoStart: Boolean, modifier: Modifier = Modifier, content: @Composable (start: () -> Unit) -> Unit) {
    val tours = LocalTours.current
    val targets = remember { mutableStateMapOf<String, Rect>() }
    var state by remember { mutableStateOf<TourState?>(null) }
    val start = { state = TourState(steps.size) }
    LaunchedEffect(autoStart, tours.seen) {
        val seen = tours.seen ?: return@LaunchedEffect
        if (tours.auto && autoStart && id !in seen && state == null) {
            start()
            tours.markSeen(id)
        }
    }
    val requesters = remember { mutableMapOf<String, androidx.compose.foundation.relocation.BringIntoViewRequester>() }
    // Passo novo: rola até o alvo, se ele estiver numa área que rola.
    LaunchedEffect(state?.index) {
        val index = state?.index ?: return@LaunchedEffect
        runCatching { requesters[steps[index].target]?.bringIntoView() }
    }
    Box(modifier) {
        CompositionLocalProvider(LocalTourTargets provides targets, LocalTourRequesters provides requesters) { content(start) }
        state?.let { current ->
            TourOverlay(steps, current, targets, onChange = { state = it }, onClose = {
                state = null
                tours.markSeen(id)
            })
        }
    }
}

@Composable
private fun TourOverlay(steps: List<TourStep>, state: TourState, targets: Map<String, Rect>, onChange: (TourState) -> Unit, onClose: () -> Unit) {
    val step = steps[state.index]
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = targets[step.target]?.translate(-origin)
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Tocar fora do balão não faz nada (nunca joga).
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .testTag("tour-overlay"),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            target?.let { r ->
                drawRoundRect(
                    Color.Transparent,
                    topLeft = Offset(r.left - pad, r.top - pad),
                    size = androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height + 2 * pad),
                    cornerRadius = CornerRadius(pad * 2),
                    blendMode = BlendMode.Clear,
                )
            }
        }
        val heightPx = with(density) { maxHeight.toPx() }
        // Balão abaixo do alvo se ele está na metade de cima; senão, acima.
        val below = target == null || target.center.y < heightPx / 2
        val balloonModifier = if (target == null) {
            Modifier.align(Alignment.Center)
        } else if (below) {
            Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (target.bottom + pad * 3).roundToInt()) }
        } else {
            Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -(heightPx - target.top + pad * 3).roundToInt()) }
        }
        Column(
            balloonModifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(TourColors.background, RoundedCornerShape(16.dp))
                .border(2.dp, GColors.Gold, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(step.text),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("tour-text"),
                style = MaterialTheme.typography.bodyLarge,
                color = TourColors.text,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.tour_step, state.index + 1, state.size),
                    Modifier.testTag("tour-step"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TourColors.text,
                )
                Box(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClose, Modifier.heightIn(min = 48.dp).testTag("tour-skip")) {
                    Text(stringResource(R.string.tour_skip), color = TourColors.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                if (state.index > 0) {
                    androidx.compose.material3.TextButton({ onChange(state.back()) }, Modifier.heightIn(min = 48.dp).testTag("tour-back")) {
                        Text(stringResource(R.string.tour_back), color = TourColors.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
                GButton({ state.next()?.let(onChange) ?: onClose() }, Modifier.heightIn(min = 48.dp).testTag("tour-next"), kind = GButtonKind.Primary) {
                    Text(stringResource(if (state.isLast) R.string.tour_done else R.string.tour_next))
                }
            }
        }
    }
}

/** Ícone do tour guiado (balão com "?"), alvo de 48 dp: mostra o tour da tela de novo. */
@Composable
fun TourButton(onClick: () -> Unit, color: Color = GColors.Champagne) {
    val label = stringResource(R.string.tour_button)
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(Tours.BUTTON)
            .tourTarget(Tours.BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            Text("i", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
