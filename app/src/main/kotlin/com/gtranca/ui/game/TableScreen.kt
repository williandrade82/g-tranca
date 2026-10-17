@file:OptIn(ExperimentalLayoutApi::class)

package com.gtranca.ui.game

import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.sound.SoundButton

import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.sound.SoundMap

import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
import com.gtranca.ui.theme.GSnack

import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.labelRes

import androidx.compose.ui.draw.clip

import androidx.annotation.StringRes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import com.gtranca.ui.theme.Elevation

import com.gtranca.ui.theme.Cinzel
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GCounterPill
import com.gtranca.ui.theme.GMedallion
import com.gtranca.ui.theme.GoldBrush
import com.gtranca.ui.theme.TabularNums
import com.gtranca.ui.theme.goldFrame
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBarsPadding
import com.gtranca.game.PlanRanking
import com.gtranca.game.HumanTurnResolver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import com.gtranca.R
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.TableMeld
import com.gtranca.game.HandOrder
import com.gtranca.game.HandSort
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.SeatRole
import com.gtranca.ui.cards.CardBack
import com.gtranca.ui.cards.CardEmphasis
import com.gtranca.ui.cards.CardSize
import com.gtranca.ui.cards.PlayingCard
import com.gtranca.ui.cards.cardDescription
import com.gtranca.ui.cards.shortLabel
import com.gtranca.ui.theme.OnTable
import com.gtranca.ui.theme.TableAccent
import com.gtranca.ui.theme.TableGreen
import com.gtranca.ui.theme.TableGreenDark

/** Gestos da mesa, emitidos para o [GameViewModel]. */
interface TableEvents {
    fun onCardClick(card: Card)
    fun onClearSelection()
    fun onSortChange(sort: HandSort)
    fun onSpecialColumnToggle()
    fun onDraw()
    fun onTakeDiscardPile()
    fun onDiscardPileClick()
    fun onCreateMeld()
    fun onAddToMeld(meldId: MeldId)
    fun onDiscard()
    fun onDeclineDraw()
    fun onConfirmDecline()
    fun onDismissDecline()
    fun onPlanChosen(action: Action.TakeDiscardPile)
    fun onDismissPlanChoice()
    fun onMeldChosen(action: Action)
    fun onDismissMeldChoice()
    fun onMessageShown()
    fun onRevealConfirmed()
    fun onResign()
    fun onConfirmResign()
    fun onDismissResign()
}

/** Texto claro para controles desabilitados sobre o verde (contraste ≥ 4,5:1 com o verde escuro e o da mesa). */

/** Largura de uma carta da mão mais o espaço entre cartas. */
private val HandCardSlot = CardSize.MEDIUM.width + 1.dp

/**
 * Mesa: desenhada só a partir da vista do humano ([PlayerView]); controles habilitados por `legalActions`.
 *
 * Layout em retrato (testado em 360dp): placar no topo; no meio, uma área com rolagem vertical (outros
 * assentos, jogos do outro lado, monte/mortos/lixo e os jogos do seu lado, nessa ordem); embaixo, fixas, a barra
 * de ações e a mão. A área do meio termina antes da barra (nada fica por baixo dela) e, no início da sua vez,
 * rola até o fim, deixando lixo e seus jogos logo acima dos botões.
 */
@Composable
fun TableScreen(
    state: GameUiState,
    events: TableEvents,
    modifier: Modifier = Modifier,
    /** Revisão da mesa final: no lugar da barra de ações, só "Continuar" (não há jogada possível). */
    onReviewContinue: (() -> Unit)? = null,
) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.messageRes()) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            events.onMessageShown()
        }
    }
    var showDiscardPile by rememberSaveable { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(false) }
    val sound = LocalSound.current
    // Canastra fechada: toca uma vez por destaque novo.
    var lastFlash by remember { mutableStateOf(0L) }
    LaunchedEffect(state.canastaFlashes) {
        val newest = state.canastaFlashes.maxOfOrNull { it.id } ?: return@LaunchedEffect
        if (newest > lastFlash) {
            lastFlash = newest
            sound.play(SoundEffect.CANASTA)
        }
    }
    var detailSeat by rememberSaveable { mutableStateOf<Int?>(null) }
    // Posições dos elementos da mesa, para as animações (cartas voando entre eles).
    val anchors = remember { mutableStateMapOf<AnimAnchor, Rect>() }
    val tableScroll = rememberScrollState()
    // Área visível da mesa (coordenadas da tela) e voos já liberados: um voo para os jogos de um lado só começa
    // depois que o destino está à vista (a mesa rola até ele, se preciso), para qualquer jogador.
    var tableViewport by remember { mutableStateOf<Rect?>(null) }
    val readyFlights = remember { mutableStateMapOf<Long, Boolean>() }
    val density = LocalDensity.current
    LaunchedEffect(state.flights.map { it.id }) {
        val fresh = state.flights.filter { readyFlights[it.id] != true }
        if (fresh.isEmpty()) return@LaunchedEffect
        val viewport = tableViewport
        val targets = fresh.mapNotNull { flight -> anchors.tableTarget(flight.to) }
        if (viewport != null && targets.isNotEmpty()) {
            val margin = with(density) { 12.dp.toPx() }
            val top = targets.minOf { it.top }
            val bottom = targets.maxOf { it.bottom }
            val delta = when {
                bottom > viewport.bottom - margin -> bottom - viewport.bottom + margin
                top < viewport.top + margin -> top - viewport.top - margin
                else -> 0f
            }
            // Outra rolagem (ex.: a mesa se mantendo no fim quando a mão cresce com o morto) cancela esta; os voos
            // são liberados mesmo assim, senão expiram sem animar.
            if (delta != 0f) {
                try {
                    tableScroll.animateScrollBy(delta)
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                }
            }
        }
        fresh.forEach { readyFlights[it.id] = true }
        // Esquece os voos que já terminaram.
        val live = state.flights.map { it.id }.toSet()
        readyFlights.keys.retainAll(live)
    }
    // Voos que já chegaram: a carta aparece no destino só então (sai da origem, voa, aparece no destino).
    val arrived = remember { mutableStateMapOf<Long, Boolean>() }
    val landing = if (state.animationMillis <= 0) emptySet() else
        state.flights.filter { it.card != null && arrived[it.id] != true }.mapNotNull { it.card }.toSet()
    // Só no início da vez do humano (etapa de comprar): rola até o fim, com lixo e os jogos do lado à vista.
    LaunchedEffect(state.awaitingDraw) {
        if (state.awaitingDraw) tableScroll.animateScrollTo(tableScroll.maxValue)
    }
    // Se a mesa estava no fim e a área mudou de tamanho (ex.: a dica de acrescentar aumenta a barra de ações),
    // continua no fim: os jogos do lado seguem à vista sem rolar a cada jogada.
    LaunchedEffect(tableScroll) {
        var lastMax = tableScroll.maxValue
        snapshotFlow { tableScroll.maxValue }.collect { max ->
            if (max != lastMax && tableScroll.value >= lastMax - 4) tableScroll.scrollTo(max)
            lastMax = max
        }
    }

    Scaffold(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(GColors.Midnight, GColors.Indigo, GColors.Midnight)))
            .testTag("table-screen"),
        containerColor = Color.Transparent,
        // Sem a margem de baixo: a barra de ações cobre a área da navegação e põe o espaço dentro dela.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) { data -> GSnack(data.visuals.message) } },
    ) { padding ->
        CompositionLocalProvider(LocalAnchors provides anchors, LocalLanding provides landing) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    Header(state, events) { showLegend = true }
                    // Jogadores fixos no alto; rola só a mesa (monte, mortos, lixo e os jogos dos dois lados).
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { SeatsPanel(state) { detailSeat = it } }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 2.dp).feltTable()
                            .onGloballyPositioned { tableViewport = Rect(it.positionInRoot(), it.size.toSize()) }
                            .verticalScroll(tableScroll).padding(horizontal = 12.dp, vertical = 18.dp).testTag("table-scroll"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SideArea(
                            side = view.mode.sides.first { it != view.side }.index,
                            flashes = state.canastaFlashes,
                            animationMillis = state.animationMillis,
                            title = stringResource(meldsTitleRes(view.mode, own = false)),
                            melds = view.tables[view.mode.sides.first { it != view.side }.index].melds,
                            redThrees = state.redThrees[view.mode.sides.first { it != view.side }.index],
                            onMeldClick = null,
                            sideTag = "opponent",
                        )
                        CenterArea(state, events, onShowDiscardPile = { showDiscardPile = true })
                        SideArea(
                            side = view.side.index,
                            flashes = state.canastaFlashes,
                            animationMillis = state.animationMillis,
                            title = stringResource(meldsTitleRes(view.mode, own = true)),
                            melds = view.tables[view.side.index].melds,
                            redThrees = state.redThrees[view.side.index],
                            onMeldClick = if (state.playing) events::onAddToMeld else null,
                            sideTag = "own",
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    HandArea(state, events)
                    // Comandos no ponto mais baixo da tela (o painel desce até a borda, sob a barra de navegação).
                    if (onReviewContinue != null) ReviewContinueBar(onReviewContinue) else StatusAndActions(state, events)
                }
                FlightsLayer(state.flights, anchors, state.animationMillis, { readyFlights[it] == true }) { id ->
                    arrived[id] = true
                    // O som acompanha a chegada da carta ao destino.
                    state.flights.firstOrNull { it.id == id }?.let(SoundMap::forFlight)?.let(sound::play)
                }
                // §3.5/§6.5 troca de 3 vermelho de outro assento: anúncio no meio da tela, que some sozinho.
                state.banner?.let { SwapAnnouncement(it, view.mode, snapshot.viewerSeat, state.personas.getOrNull(it.seat.index)) }
            }
        }
    }

    if (showLegend) LegendDialog(view.rules, snapshot.config.targetScore) { showLegend = false }
    detailSeat?.let { index ->
        val events = snapshot.turnEvents.getOrNull(index).orEmpty()
        if (events.isEmpty()) {
            detailSeat = null
        } else {
            val role = SeatRole.of(view.mode, Seat(index), snapshot.viewerSeat)
            val detailPersona = state.personas.getOrNull(index)
            SeatDetailDialog(detailPersona, stringResource(detailPersona?.let { role.labelRes(it.gender) } ?: role.nameRes()), events) { detailSeat = null }
        }
    }
    if (showDiscardPile) DiscardPileDialog(view.discardPile) { showDiscardPile = false }
    state.planChoice?.let { options -> PlanChoiceDialog(options, view, events) }
    state.meldChoice?.let { options -> MeldChoiceDialog(options, view, events) }
    state.reveal?.let { reveal -> RedThreeDialog(reveal, events::onRevealConfirmed, events::onResign) }
    if (state.confirmDecline) {
        GMessage(
            title = stringResource(R.string.decline_title),
            text = stringResource(R.string.decline_text),
            illustration = MessageIllustration.Question,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.decline_confirm),
            onConfirm = events::onConfirmDecline,
            dismissText = stringResource(R.string.cancel),
            onDismiss = events::onDismissDecline,
            onDismissRequest = events::onDismissDecline,
        )
    }
}

@Composable
private fun Header(state: GameUiState, events: TableEvents, onLegend: () -> Unit) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val other = view.mode.sides.first { it != view.side }
    val resignText = stringResource(R.string.action_resign)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Esquerda: "Partida 1 · alvo 3000" e, embaixo, o placar (cabe ao lado dos três botões, sem cortar o texto).
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.table_round, snapshot.roundNumber) + " · " + stringResource(R.string.table_target, snapshot.config.targetScore),
                color = OnTable.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.score_line,
                    shortSideName(view.mode, view.side, view.side),
                    plainPoints(snapshot.totals[view.side.index]),
                    plainPoints(snapshot.totals[other.index]),
                    shortSideName(view.mode, other, view.side),
                ),
                color = TableAccent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TabularNums),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(GColors.Midnight.copy(alpha = 0.7f), CircleShape)
                    .border(1.dp, GColors.Gold.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 1.dp)
                    .testTag("score"),
            )
        }
        SoundButton(OnTable)
        // Legenda dos símbolos das jogadas dos outros assentos.
        val legendText = stringResource(R.string.legend_button)
        Box(
            Modifier
                .size(48.dp)
                .clickable(onClickLabel = legendText, role = Role.Button, onClick = onLegend)
                .semantics { contentDescription = legendText }
                .testTag("action-legend"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).border(1.75.dp, TableAccent, CircleShape), contentAlignment = Alignment.Center) {
                Text("?", color = TableAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        // §13.1 desistir: ícone de bandeira, alvo de 48dp.
        Box(
            Modifier
                .size(48.dp)
                .clickable(onClickLabel = resignText, role = Role.Button, onClick = events::onResign)
                .semantics { contentDescription = resignText }
                .testTag("action-resign"),
            contentAlignment = Alignment.Center,
        ) { FlagIcon(Modifier.size(22.dp)) }
    }
}

/**
 * Os outros assentos, lado a lado como se vê da mesa: adversário à esquerda, parceiro e adversário à direita (em
 * duplas; sem espaço, o parceiro fica numa linha e os adversários na de baixo). De cada um, a mão como cartas
 * viradas (nunca as cartas, nem as do parceiro), a quantidade, se está pensando e a última jogada em símbolos.
 */
@Composable
private fun SeatsPanel(state: GameUiState, onSeatClick: (Int) -> Unit) {
    val snapshot = state.snapshot
    val mode = snapshot.view.mode
    val others = mode.seatsInPlayOrder(snapshot.viewerSeat).drop(1)
    if (mode != GameMode.DUPLAS) {
        others.forEach { SeatPill(state, it, Modifier.fillMaxWidth(), onSeatClick, minHeight = 72.dp) }
        return
    }
    val byRole = others.associateBy { SeatRole.of(mode, it, snapshot.viewerSeat) }
    val left = byRole.getValue(SeatRole.LEFT_OPPONENT)
    val partner = byRole.getValue(SeatRole.PARTNER)
    val right = byRole.getValue(SeatRole.RIGHT_OPPONENT)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 340.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(left, partner, right).forEach { SeatPill(state, it, Modifier.weight(1f), onSeatClick) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SeatPill(state, partner, Modifier.fillMaxWidth(), onSeatClick)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SeatPill(state, left, Modifier.weight(1f), onSeatClick)
                    SeatPill(state, right, Modifier.weight(1f), onSeatClick)
                }
            }
        }
    }
}

@Composable
private fun SeatPill(state: GameUiState, seat: Seat, modifier: Modifier, onSeatClick: (Int) -> Unit, minHeight: Dp = 96.dp) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val role = SeatRole.of(view.mode, seat, snapshot.viewerSeat)
    val thinking = snapshot.thinkingSeat == seat
    val handSize = view.handSizes[seat.index]
    val handText = countText(R.plurals.opponent_hand, R.string.opponent_hand_zero, handSize)
    val countText = countText(R.plurals.card_count, R.string.card_count_zero, handSize)
    val thinkingText = stringResource(R.string.seat_thinking)
    val events = snapshot.turnEvents[seat.index]
    val shape = RoundedCornerShape(Corner.sm)
    val detailHint = stringResource(R.string.seat_detail_hint)
    Column(
        modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .clickable(enabled = events.isNotEmpty(), onClickLabel = detailHint, role = Role.Button) { onSeatClick(seat.index) }
            .background(GColors.Indigo.copy(alpha = if (thinking) 0.95f else 0.75f), shape)
            .border(if (thinking) 2.dp else 1.dp, if (thinking) TableAccent else GColors.Gold.copy(alpha = 0.3f), shape)
            .padding(horizontal = 8.dp, vertical = Spacing.sm)
            .testTag("seat-${seat.index}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // §14.1 avatar discreto e nome curto; o papel (esquerda, parceiro, direita) vai embaixo, bem pequeno.
            val persona = state.personas.getOrNull(seat.index)
            if (persona != null) {
                GMedallion(Modifier.size(36.dp), active = thinking) { PersonaAvatar(persona, 30.dp) }
            }
            Column(Modifier.weight(1f)) {
                if (persona != null) {
                    Text(
                        persona.shortName,
                        color = OnTable,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(role.pillNameRes()),
                    color = if (role == SeatRole.PARTNER) TableAccent else OnTable.copy(alpha = if (persona != null) 0.8f else 1f),
                    fontWeight = if (persona != null) FontWeight.Normal else FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = if (persona != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (thinking) {
                CircularProgressIndicator(
                    Modifier.size(14.dp).semantics { contentDescription = thinkingText }.testTag("seat-thinking-${seat.index}"),
                    color = TableAccent,
                    strokeWidth = 2.dp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Mão representada: uma carta virada por carta, compactadas para caber na largura.
            HiddenHand(
                handSize,
                seat.index,
                Modifier.weight(1f).semantics { contentDescription = handText }.testTag("seat-hand-${seat.index}"),
            )
            GCounterPill(
                handSize.toString(),
                Modifier.semantics { contentDescription = countText }.testTag("seat-hand-size-${seat.index}"),
                icon = { CardsGlyph(TableAccent) },
            )
        }
        if (events.isNotEmpty()) {
            LastTurnSymbols(events, Modifier.testTag("seat-last-turn-${seat.index}"))
        }
    }
}

/** Nome curto do assento para a pílula (cabe em um terço da largura). */
@StringRes
private fun SeatRole.pillNameRes(): Int = when (this) {
    SeatRole.LEFT_OPPONENT -> R.string.seat_left_short
    SeatRole.RIGHT_OPPONENT -> R.string.seat_right_short
    else -> nameRes()
}

/** [count] cartas viradas sobrepostas; o passo encolhe (até 2dp) para nunca passar da largura disponível. */
@Composable
private fun HiddenHand(count: Int, seat: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.height(CardSize.TINY.height)) {
        if (count == 0) return@BoxWithConstraints
        val size = CardSize.TINY
        val step = if (count == 1) 0.dp else ((maxWidth - size.width) / (count - 1)).coerceIn(2.dp, 9.dp)
        Box(Modifier.width(size.width + step * (count - 1)).anchor(AnimAnchor.SeatHand(seat))) {
            repeat(count) { i -> CardBack(Modifier.offset(x = step * i), size = size, describe = false) }
        }
    }
}

@Composable
private fun SideArea(
    side: Int,
    flashes: List<CanastaFlash>,
    animationMillis: Long,
    title: String,
    melds: List<TableMeld>,
    redThrees: List<Card>,
    onMeldClick: ((MeldId) -> Unit)?,
    sideTag: String,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(GColors.Midnight.copy(alpha = 0.28f), RoundedCornerShape(Corner.sm))
            .border(1.dp, GColors.Gold.copy(alpha = 0.3f), RoundedCornerShape(Corner.sm))
            .anchor(AnimAnchor.SideArea(side))
            .padding(Spacing.sm)
            .testTag("side-$sideTag"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TableAccent, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            // Alvo dos 3 vermelhos que chegam (§6.5), mesmo antes do primeiro.
            Spacer(Modifier.size(1.dp).anchor(AnimAnchor.RedThrees(side)))
            if (redThrees.isNotEmpty()) {
                Text(
                    stringResource(R.string.red_threes, redThrees.size),
                    color = OnTable,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Row(Modifier.testTag("red-threes-$sideTag")) {
                    redThrees.forEach { card ->
                        // Animação simples ao chegar à mesa (depois do "Baixar 3 vermelho").
                        val visible = remember(card) { MutableTransitionState(false).apply { targetState = true } }
                        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 1.6f)) {
                            PlayingCard(card, Modifier.padding(start = 2.dp).anchor(AnimAnchor.RedThreeCard(side, card)).alpha(if (card in LocalLanding.current) 0f else 1f), size = CardSize.SMALL)
                        }
                    }
                }
            }
        }
        if (melds.isEmpty()) {
            Text(stringResource(R.string.no_melds), color = OnTable, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                melds.forEach { meld ->
                    MeldView(
                        side,
                        meld,
                        onMeldClick,
                        Modifier.anchor(AnimAnchor.Meld(side, meld.id.value)).testTag("meld-$sideTag-${meld.id.value}"),
                        flash = flashes.lastOrNull { it.side == side && it.meldId == meld.id.value },
                        animationMillis = animationMillis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeldView(
    side: Int,
    tableMeld: TableMeld,
    onClick: ((MeldId) -> Unit)?,
    modifier: Modifier,
    flash: CanastaFlash? = null,
    animationMillis: Long = 0,
) {
    // §7 canastra fechada agora: pulso curto (limpa em dourado, suja em bronze). [animationMillis] já vem
    // escalado; se o destaque for removido no meio, o pulso volta a 0 (nunca fica preso maior/colorido).
    val pulse = remember(tableMeld.id) { Animatable(0f) }
    LaunchedEffect(flash?.id) {
        if (flash == null || animationMillis <= 0) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        try {
            val half = animationMillis.toInt().coerceAtLeast(1)
            pulse.animateTo(1f, tween(half))
            pulse.animateTo(0f, tween(half))
        } finally {
            withContext(NonCancellable) { pulse.snapTo(0f) }
        }
    }
    val meld = tableMeld.meld
    val canasta = meld.isCanasta()
    val label = when {
        !canasta -> null
        LocalTableRules.current.noWilds -> stringResource(R.string.canasta_plain)
        meld.isClean -> stringResource(R.string.canasta_clean)
        else -> stringResource(R.string.canasta_dirty)
    }
    val borderColor = when {
        canasta && meld.isClean -> GColors.CanastraClean
        canasta -> GColors.CanastraDirty
        onClick != null -> OnTable.copy(alpha = 0.6f) // tocável para acrescentar
        else -> Color.Transparent
    }
    val descriptions = meld.cards.map { cardDescription(it) }
    val description = stringResource(R.string.meld_description, descriptions.joinToString(", ")) +
        (label?.let { ". $it" } ?: "")
    val flashColor = if (flash?.clean == true) GColors.Gold else GColors.CanastraDirtyFlash
    Column(
        modifier
            .graphicsLayer {
                val grow = 1f + 0.08f * pulse.value
                scaleX = grow
                scaleY = grow
            }
            .semantics { canastaPulse = pulse.value }
            .heightIn(min = 48.dp)
            .background(flashColor.copy(alpha = 0.45f * pulse.value), RoundedCornerShape(Corner.xs))
            .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(Corner.xs))
            .then(if (onClick != null) Modifier.clickable { onClick(tableMeld.id) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(3.dp),
    ) {
        OverlappedCards(meld.cards, step = 14.dp, anchorFor = { AnimAnchor.MeldCard(side, tableMeld.id.value, it) })
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CanastaSeal(meld.isClean, borderColor, pulse.value, Modifier.size(12.dp))
                Text(label, color = borderColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Cartas pequenas sobrepostas, cada uma deslocada [step] para a direita (o índice de cada uma fica visível). */
@Composable
private fun OverlappedCards(
    cards: List<Card>,
    step: Dp,
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.SMALL,
    anchorFor: ((Card) -> AnimAnchor)? = null,
) {
    val landing = LocalLanding.current
    Box(modifier.width(size.width + step * (cards.size - 1).coerceAtLeast(0)).height(size.height)) {
        cards.forEachIndexed { i, card ->
            val position = Modifier.offset(x = step * i)
            val placed = if (anchorFor != null) position.anchor(anchorFor(card)).alpha(if (card in landing) 0f else 1f) else position
            PlayingCard(card, placed, size = size, describe = false)
        }
    }
}

@Composable
private fun CenterArea(state: GameUiState, events: TableEvents, onShowDiscardPile: () -> Unit) {
    val view = state.snapshot.view
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Monte à esquerda; os dois mortos agrupados à direita, afastados do monte.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stockCount = countText(R.plurals.card_count, R.string.card_count_zero, view.stockSize)
            val stockLabel = stringResource(R.string.stock) + ", " + stockCount
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .border(2.dp, if (state.canDraw) TableAccent else Color.Transparent, RoundedCornerShape(Corner.xs))
                    .clickable(enabled = state.canDraw, onClick = events::onDraw)
                    .semantics(mergeDescendants = true) { contentDescription = stockLabel }
                    .testTag("stock")
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (view.stockSize > 0) DeckStack(view.stockSize, AnimAnchor.Stock) else Spacer(Modifier.width(CardSize.SMALL.width).anchor(AnimAnchor.Stock))
                Column {
                    Text(stringResource(R.string.stock), color = OnTable, style = MaterialTheme.typography.labelMedium)
                    GCounterPill(view.stockSize.toString(), icon = { CardsGlyph(TableAccent) })
                }
            }
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("mortos")) {
                view.mortoStatus.forEachIndexed { i, status ->
                    val statusText = when (status) {
                        MortoStatus.Available -> countText(R.plurals.card_count, R.string.card_count_zero, view.mortoSizes[i])
                        MortoStatus.BecameStock -> stringResource(R.string.morto_became_stock)
                        is MortoStatus.Taken -> mortoTakerName(state, status)?.let { stringResource(R.string.morto_taken_by, it) }
                            ?: stringResource(mortoTakenRes(view.mode, own = status.side == view.side))
                    }
                    val title = stringResource(R.string.morto_title, i + 1)
                    Row(
                        Modifier.width(MortoWidth).semantics(mergeDescendants = true) { contentDescription = "$title, $statusText" }
                            .testTag("morto-$i"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val available = status == MortoStatus.Available
                        if (available) DeckStack(view.mortoSizes[i], AnimAnchor.Morto(i))
                        Column(if (available) Modifier else Modifier.anchor(AnimAnchor.Morto(i))) {
                            Text(title, color = OnTable, style = MaterialTheme.typography.labelMedium)
                            if (available) GCounterPill(view.mortoSizes[i].toString(), icon = { CardsGlyph(TableAccent) })
                            else if (status is MortoStatus.Taken && mortoTakerName(state, status) != null) {
                                // Quem pegou: ícone verde (você ou parceiro) ou vermelho (rival) e o nome.
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PersonGlyph(if (status.side == view.side) GColors.Emerald else GColors.Ruby)
                                    Text(mortoTakerName(state, status)!!, color = OnTable, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else Text(statusText, color = GColors.Lavender, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                        }
                    }
                }
            }
        }
        // Lixo: aberto, todas as cartas visíveis (§5.6); topo à direita. Antes de comprar, tocar pega o lixo;
        // depois, tocar descarta a carta selecionada (§8).
        val pile = view.discardPile
        val pileLabel = stringResource(R.string.discard_pile) + ": " +
            if (pile.isEmpty()) stringResource(R.string.discard_pile_empty) else pile.map { cardDescription(it) }.joinToString(", ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.discard_pile) + " (" + pile.size + ")",
                color = OnTable,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (pile.isNotEmpty()) {
                TextButton(onShowDiscardPile, modifier = Modifier.heightIn(min = 48.dp).testTag("show-discard-pile")) {
                    Text(stringResource(R.string.discard_pile_show_all), color = TableAccent)
                }
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(pile.size) { scroll.scrollTo(scroll.maxValue) }
        // Aceso só se tocar faz algo agora: pegar o lixo, ou descartar a carta selecionada (Discard em legal).
        val pileActive = state.canTakeDiscardPile || state.canDiscardSelected
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(GColors.Midnight.copy(alpha = 0.25f), RoundedCornerShape(Corner.sm))
                .border(if (pileActive) 2.dp else 1.dp, if (pileActive) TableAccent else OnTable.copy(alpha = 0.35f), RoundedCornerShape(Corner.sm))
                .clickable(enabled = state.awaitingDraw || state.playing, onClick = events::onDiscardPileClick)
                .semantics(mergeDescendants = true) { contentDescription = pileLabel }
                .anchor(AnimAnchor.DiscardPile)
                .testTag("discard-pile")
                .padding(4.dp),
        ) {
            if (pile.isEmpty()) {
                Text(stringResource(R.string.discard_pile_empty), color = OnTable, modifier = Modifier.align(Alignment.CenterStart))
            } else {
                Box(Modifier.horizontalScroll(scroll)) { OverlappedCards(pile, step = 18.dp, anchorFor = { AnimAnchor.DiscardCard(it) }) }
            }
        }
    }
}

private val MortoWidth = 96.dp

/** Espaço acima e abaixo da mão para a carta marcada crescer (10%) sem ser cortada. */
private val HandGrowMargin = 6.dp

/** Barra da revisão da mesa final: no mesmo lugar e estilo da barra de ações, só com "Continuar". */
@Composable
private fun ReviewContinueBar(onContinue: () -> Unit) {
    val shape = RoundedCornerShape(topStart = Corner.md, topEnd = Corner.md)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(Elevation.dialog, shape)
            .background(Brush.verticalGradient(listOf(GColors.IndigoLight, GColors.Indigo)), shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.45f), shape)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = Spacing.sm),
    ) {
        GButton(onContinue, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-continue")) {
            Text(stringResource(R.string.table_review_continue))
        }
    }
}

@Composable
private fun StatusAndActions(state: GameUiState, events: TableEvents) {
    val snapshot = state.snapshot
    val thinking = snapshot.thinkingSeat
    val status = when {
        state.awaitingDraw -> stringResource(R.string.turn_draw)
        state.playing -> stringResource(R.string.turn_play)
        thinking != null -> stringResource(SeatRole.of(snapshot.view.mode, thinking, snapshot.viewerSeat).turnRes())
        else -> stringResource(R.string.turn_waiting)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(Elevation.dialog, RoundedCornerShape(topStart = Corner.md, topEnd = Corner.md))
            .background(Brush.verticalGradient(listOf(GColors.IndigoLight, GColors.Indigo)), RoundedCornerShape(topStart = Corner.md, topEnd = Corner.md))
            .border(1.dp, GColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(topStart = Corner.md, topEnd = Corner.md))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = Spacing.sm)
            .testTag("action-bar"),
    ) {
        Text(status, color = TableAccent, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("turn-status"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (snapshot.view.phase != Phase.PLAYING || !state.isHumanTurn) {
                ActionButton(stringResource(R.string.action_draw), state.canDraw, events::onDraw, "action-draw", ActionKind.DRAW)
                ActionButton(stringResource(R.string.action_take_discard), state.canTakeDiscardPile, events::onTakeDiscardPile, "action-take-discard", ActionKind.TAKE)
                if (state.canDecline) ActionButton(stringResource(R.string.action_decline), true, events::onDeclineDraw, "action-decline", ActionKind.DECLINE)
            } else {
                ActionButton(stringResource(R.string.action_meld), state.canMeld, events::onCreateMeld, "action-meld", ActionKind.MELD)
                ActionButton(stringResource(R.string.action_discard), state.canDiscard, events::onDiscard, "action-discard", ActionKind.DISCARD)
            }
            GButton(
                onClick = events::onClearSelection,
                enabled = state.selected.isNotEmpty(),
                kind = GButtonKind.Secondary,
                modifier = Modifier.heightIn(min = 48.dp).testTag("action-clear"),
            ) { Text(stringResource(R.string.action_clear)) }
        }
        // Fixo durante a vez (não depende da seleção) para os botões não pularem ao tocar nas cartas.
        if (state.playing && snapshot.view.tables[snapshot.view.side.index].melds.isNotEmpty()) {
            Text(
                stringResource(if (snapshot.view.mode == GameMode.DUPLAS) R.string.add_to_meld_hint_team else R.string.add_to_meld_hint),
                color = GColors.Lavender,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Botão de ação: o mesmo [GButton] dourado das outras telas (afundar ao tocar, brilho), com ícone. */
@Composable
private fun ActionButton(text: String, enabled: Boolean, onClick: () -> Unit, tag: String, icon: ActionKind? = null) {
    GButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        if (icon != null) {
            ActionIcon(icon, if (enabled) GColors.OnGold else GColors.OnTableDisabled, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text)
    }
}

/**
 * Realce de uma carta da mão. Sem seleção, só um realce discreto para as que participam de alguma jogada
 * (no começo da vez quase toda a mão participa). Com seleção, destaque forte para as que completam jogada
 * junto com a seleção e esmaecimento das demais. [highlighted] vem de `legalActions` (sem o descarte).
 */
internal fun handEmphasis(selected: Boolean, highlighted: Boolean, hasSelection: Boolean, humanTurn: Boolean): CardEmphasis =
    when {
        selected -> CardEmphasis.SELECTED
        !humanTurn -> CardEmphasis.NONE
        !hasSelection -> if (highlighted) CardEmphasis.SUBTLE else CardEmphasis.NONE
        highlighted -> CardEmphasis.STRONG
        else -> CardEmphasis.DIMMED
    }

@Composable
private fun HandArea(state: GameUiState, events: TableEvents) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // §14.1 seu avatar, bem discreto, junto do título da mão.
            state.personas.getOrNull(state.snapshot.viewerSeat.index)?.let {
                GMedallion(Modifier.padding(end = Spacing.sm).size(30.dp), active = state.isHumanTurn) { PersonaAvatar(it, 24.dp) }
            }
            Text(
                stringResource(R.string.your_hand, state.hand.size),
                color = OnTable,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            SortSelector(state.sort, state.customHand != null, events::onSortChange, events::onSpecialColumnToggle)
        }
        // 7 cartas de 48dp por linha em 360dp; 2 linhas à vista (sobra mesa para os jogos), o resto rola. A margem
        // dentro da rolagem deixa a carta marcada crescer sem ser cortada pela borda.
        val custom = state.customHand
        Box(
            Modifier.fillMaxWidth().heightIn(max = 74.dp * 2 + HandGrowMargin * 2).anchor(AnimAnchor.OwnHand)
                .verticalScroll(rememberScrollState()).padding(vertical = HandGrowMargin, horizontal = 3.dp)
                .testTag("hand"),
        ) {
            if (custom == null || custom.special.isEmpty()) {
                CardFlow(state.hand, state, events, Modifier.fillMaxWidth())
            } else {
                // Personalizado: 3 pretos e coringas numa coluna fixa à esquerda (uma por linha; se houver mais do
                // que linhas, a coluna ganha mais uma carta de largura); as demais fluem à direita.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val total = maxWidth
                    val columns = HandOrder.specialColumns(custom.special.size, custom.rest.size) { c ->
                        ((total - HandCardSlot * c - 6.dp) / HandCardSlot).toInt()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardFlow(
                            custom.special, state, events,
                            Modifier.width(HandCardSlot * columns).testTag("hand-special"),
                            maxPerRow = columns,
                        )
                        CardFlow(custom.rest, state, events, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardFlow(cards: List<Card>, state: GameUiState, events: TableEvents, modifier: Modifier, maxPerRow: Int = Int.MAX_VALUE) {
    val selectedText = stringResource(R.string.card_selected)
    val playableText = stringResource(R.string.card_playable)
    val completesText = stringResource(R.string.card_completes_selection)
    val notWithSelectionText = stringResource(R.string.card_not_with_selection)
    val newText = stringResource(R.string.card_new)
    val newDescription = stringResource(R.string.card_new_description)
    val hasSelection = state.selected.isNotEmpty()
    val landing = LocalLanding.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = maxPerRow,
    ) {
        cards.forEach { card ->
            val selected = card in state.selected
            val isNew = card in state.newCards
            val emphasis = handEmphasis(selected, card in state.highlighted, hasSelection, state.isHumanTurn)
            val emphasisText = when (emphasis) {
                CardEmphasis.SELECTED -> selectedText
                CardEmphasis.SUBTLE -> playableText
                CardEmphasis.STRONG -> completesText
                CardEmphasis.DIMMED -> notWithSelectionText
                CardEmphasis.NONE -> ""
            }
            PlayingCard(
                card,
                Modifier
                    .anchor(AnimAnchor.HandCard(card))
                    .alpha(if (card in landing) 0f else 1f)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { events.onCardClick(card) })
                    .semantics {
                        stateDescription = listOf(emphasisText, if (isNew) newDescription else "").filter { it.isNotEmpty() }.joinToString(", ")
                    }
                    .testTag("hand-card-$card"),
                size = CardSize.MEDIUM,
                emphasis = emphasis,
                badge = if (isNew) newText else null,
            )
        }
    }
}

/**
 * "Ordem:" e três botões de ícone: Valor e Naipe (um dos dois sempre escolhido) e, à parte, a chave de separar os
 * 3 pretos e coringas numa coluna à esquerda (ligada: o resto segue o critério escolhido; desligada: não interfere).
 */
@Composable
private fun SortSelector(current: HandSort, separate: Boolean, onChange: (HandSort) -> Unit, onToggleSpecial: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.hand_order), color = OnTable, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.selectableGroup()) {
            listOf(HandSort.BY_RANK to R.string.sort_rank, HandSort.BY_SUIT to R.string.sort_suit).forEach { (sort, label) ->
                val description = stringResource(label)
                SortButton(
                    selected = sort == current,
                    modifier = Modifier
                        .selectable(selected = sort == current, role = Role.RadioButton, onClick = { onChange(sort) })
                        .semantics { contentDescription = description }
                        .testTag("sort-${sort.name.lowercase()}"),
                ) { color ->
                    when (sort) {
                        HandSort.BY_RANK -> Text("A K", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        HandSort.BY_SUIT -> Text("♠︎♥︎", color = color, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        val description = stringResource(R.string.sort_special)
        SortButton(
            selected = separate,
            modifier = Modifier
                .toggleable(value = separate, role = Role.Switch, onValueChange = { onToggleSpecial() })
                .semantics { contentDescription = description }
                .testTag("sort-custom"),
        ) { color -> SplitColumnIcon(color, Modifier.size(20.dp)) }
    }
}

/** Botão redondo de 48dp de área de toque; [selected] = preenchido em amarelo. */
@Composable
private fun SortButton(selected: Boolean, modifier: Modifier, content: @Composable (Color) -> Unit) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(36.dp)
                .then(if (selected) Modifier.background(GoldBrush, CircleShape) else Modifier)
                .border(1.dp, if (selected) GColors.Champagne else GColors.Lavender.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content(if (selected) GColors.OnGold else OnTable) }
    }
}

/** Ícone de "exceção à esquerda": uma coluna de cartas separada por um traço das demais, desenhado no código. */
@Composable
private fun SplitColumnIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.06f)
        val card = Size(w * 0.22f, h * 0.36f)
        val stroke = Stroke(width = w * 0.07f)
        // Coluna separada (preenchida): duas cartas.
        drawRoundRect(color, Offset(w * 0.02f, h * 0.08f), card, corner)
        drawRoundRect(color, Offset(w * 0.02f, h * 0.56f), card, corner)
        // Traço divisor.
        drawLine(color, Offset(w * 0.36f, h * 0.02f), Offset(w * 0.36f, h * 0.98f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        // Demais cartas (contornos): grade 2 × 2.
        listOf(0.46f, 0.74f).forEach { x ->
            listOf(0.08f, 0.56f).forEach { y ->
                drawRoundRect(color, Offset(w * x, h * y), card, corner, style = stroke)
            }
        }
    }
}

/** Ícone de bandeira (desistir), desenhado no código. */
@Composable
private fun FlagIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawLine(OnTable, Offset(w * 0.2f, w * 0.08f), Offset(w * 0.2f, w * 0.95f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(w * 0.25f, w * 0.1f); lineTo(w * 0.85f, w * 0.28f); lineTo(w * 0.25f, w * 0.5f); close()
        }
        drawPath(flag, OnTable)
    }
}

/**
 * §6.5 encenação do 3 vermelho do lado do humano: a(s) carta(s) e o botão "Baixar 3 vermelho". O motor já os
 * baixou e repôs; ao tocar, eles aparecem na mesa e a reposição aparece na mão, destacada como nova.
 */
@Composable
private fun RedThreeDialog(reveal: RedThreeReveal, onConfirm: () -> Unit, onResign: () -> Unit) {
    GDialog(
        modifier = Modifier.testTag("red-three-dialog"),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(revealTitle(reveal.notice)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    reveal.notice.cards.forEach { PlayingCard(it, size = CardSize.LARGE) }
                }
                Text(stringResource(R.string.red_three_value_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            GButton(onConfirm, Modifier.heightIn(min = 48.dp).testTag("red-three-confirm")) {
                Text(stringResource(R.string.red_three_button))
            }
        },
        // §13.1 desistir "a qualquer momento", inclusive durante a encenação.
        dismissButton = {
            TextButton(onResign, Modifier.heightIn(min = 48.dp).testTag("red-three-resign")) {
                Text(stringResource(R.string.action_resign))
            }
        },
    )
}

@Composable
private fun DiscardPileDialog(pile: List<Card>, onDismiss: () -> Unit) {
    GDialog(
        onDismissRequest = onDismiss,
        title = { Text(countText(R.plurals.discard_pile_dialog_title, R.string.discard_pile_empty, pile.size)) },
        text = {
            FlowRow(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pile.forEach { PlayingCard(it, size = CardSize.MEDIUM) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** "Baixar" com mais de uma possibilidade: cada opção com as cartas, o destino e os pontos que acrescenta. */
@Composable
private fun MeldChoiceDialog(options: List<Action>, view: PlayerView, events: TableEvents) {
    GDialog(
        onDismissRequest = events::onDismissMeldChoice,
        title = { Text(stringResource(R.string.meld_choice_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("meld-choice"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { option ->
                    val destination = when (option) {
                        is Action.AddToMeld -> {
                            val melds = view.tables[view.side.index].melds
                            val index = melds.indexOfFirst { it.id == option.meldId }
                            stringResource(
                                if (view.mode == GameMode.DUPLAS) R.string.plan_dest_add_team else R.string.plan_dest_add,
                                index + 1,
                            )
                        }
                        else -> stringResource(R.string.plan_dest_new)
                    }
                    val gain = PlanRanking.meldGain(option, view)
                    val points = pluralStringResource(R.plurals.meld_choice_points, gain, gain)
                    val cards = HumanTurnResolver.handCardsOf(option)
                    val description = cards.labels() + ": " + destination + ", " + points
                    GButton(
                        onClick = { events.onMeldChosen(option) },
                        kind = GButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                cards.forEach { PlayingCard(it, size = CardSize.SMALL, describe = false) }
                            }
                            Text(destination, textAlign = TextAlign.Center)
                            DestinationMeld(option, view)
                            Text(points, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(events::onDismissMeldChoice) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Cartas do jogo da mesa que recebe o acréscimo (desenhadas, menores), ou nada para jogo novo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationMeld(option: Action, view: PlayerView) {
    val meldId = when (option) {
        is Action.AddToMeld -> option.meldId
        is Action.TakeDiscardPile -> (option.plan as? DiscardPlan.AddToMeld)?.meldId
        else -> null
    } ?: return
    val cards = view.tables[view.side.index].meld(meldId)?.cards ?: return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy((-12).dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.alpha(0.85f),
    ) {
        cards.forEach { PlayingCard(it, size = CardSize.SMALL, describe = false) }
    }
}

@Composable
private fun PlanChoiceDialog(options: List<Action.TakeDiscardPile>, view: PlayerView, events: TableEvents) {
    val top = view.discardTop
    GDialog(
        onDismissRequest = events::onDismissPlanChoice,
        title = { Text(stringResource(R.string.plan_choice_title)) },
        text = {
            // Todos os planos, com rolagem; cada um diz o destino e as cartas da mão que usa (§5.1).
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("plan-choice"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Planos que só diferem pelo coringa usado aparecem uma vez (com o primeiro coringa).
                options.distinctBy { planKey(it) }.sortedBy { planUsesWild(it) }.forEach { option ->
                    val plan = option.plan
                    val handCards = when (plan) {
                        is DiscardPlan.NewMeld -> plan.handCards
                        is DiscardPlan.AddToMeld -> plan.handCards
                    }
                    val destination = when (plan) {
                        is DiscardPlan.NewMeld -> stringResource(R.string.plan_dest_new)
                        is DiscardPlan.AddToMeld -> {
                            // §6.4 pode haver grupos repetidos: o número do jogo os distingue.
                            val melds = view.tables[view.side.index].melds
                            val index = melds.indexOfFirst { it.id == plan.meldId }
                            stringResource(
                                if (view.mode == GameMode.DUPLAS) R.string.plan_dest_add_team else R.string.plan_dest_add,
                                index + 1,
                            )
                        }
                    }
                    val used = listOfNotNull(top) + handCards
                    val description = used.labels() + ": " + destination
                    GButton(
                        onClick = { events.onPlanChosen(option) },
                        kind = GButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                used.forEach { PlayingCard(it, size = CardSize.SMALL, describe = false) }
                            }
                            Text(destination, textAlign = TextAlign.Center)
                            DestinationMeld(option, view)
                        }
                    }
                }
                Text(stringResource(R.string.plan_choice_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(events::onDismissPlanChoice) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * §3.5/§6.5 troca de 3 vermelho de outro assento: faixa com quem trocou e a carta, por alguns instantes (a
 * reposição dele nunca é revelada). Ao terminar, o 3 vermelho entra na mesa do lado.
 */
@Composable
private fun SwapAnnouncement(notice: RedThreeNotice, mode: GameMode, viewerSeat: Seat, persona: com.gtranca.game.Persona?) {
    val text = bannerText(mode, notice, viewerSeat)
    val shown = remember(notice.id) { MutableTransitionState(false).apply { targetState = true } }
    // Véu leve sobre a mesa (não bloqueia toques) e o anúncio centralizado; some quando a troca termina.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.7f)) {
            Column(
                Modifier
                    .padding(Spacing.xl)
                    .shadow(Elevation.dialog, RoundedCornerShape(Corner.lg))
                    .background(GColors.Indigo, RoundedCornerShape(Corner.lg))
                    .goldFrame(Corner.lg, 3.dp, lights = false)
                    .padding(Spacing.lg)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("red-three-banner"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                persona?.let { com.gtranca.ui.persona.PersonaAvatar(it, 56.dp) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    notice.cards.forEach { PlayingCard(it, size = CardSize.LARGE) }
                }
                Text(
                    text,
                    color = GColors.Champagne,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Intensidade atual do pulso de canastra (0 = em repouso); exposta na semântica para os testes. */
val CanastaPulseKey = SemanticsPropertyKey<Float>("CanastaPulse")
var SemanticsPropertyReceiver.canastaPulse by CanastaPulseKey

/** Cor do destaque de canastra suja fechada (§7). */

/** Posições dos elementos da mesa (coordenadas da raiz), registradas por [anchor]; `null` fora da mesa. */
private val LocalAnchors = staticCompositionLocalOf<MutableMap<AnimAnchor, Rect>?> { null }

/** Cartas com voo em andamento: ficam invisíveis no destino (mantendo o lugar) até o voo chegar. */
private val LocalLanding = staticCompositionLocalOf<Set<Card>> { emptySet() }

/** Registra a posição deste elemento como [key] para as animações da mesa. */
@SuppressLint("ModifierFactoryUnreferencedReceiver")
private fun Modifier.anchor(key: AnimAnchor): Modifier = composed {
    val anchors = LocalAnchors.current
    // Posição sem recorte: um elemento rolado para fora da área visível continua com a posição real (o voo entra
    // pela borda), em vez do retângulo vazio em (0,0) que `boundsInRoot` daria.
    if (anchors == null) this else this.onGloballyPositioned { anchors[key] = Rect(it.positionInRoot(), it.size.toSize()) }
}

/**
 * Camada por cima da mesa com as cartas voando ([CardFlight]). Não recebe toques nem aparece para o leitor de tela;
 * com animações desligadas (duração 0 ou escala do sistema 0) não desenha nada.
 */
@Composable
private fun FlightsLayer(
    flights: List<CardFlight>,
    anchors: Map<AnimAnchor, Rect>,
    durationMillis: Long,
    isReady: (Long) -> Boolean,
    onArrived: (Long) -> Unit,
) {
    // [durationMillis] e os atrasos dos voos já vêm escalados pelo ViewModel (escala 0 = nenhum voo).
    if (durationMillis <= 0 || flights.isEmpty()) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { origin = it.positionInRoot() }
            .testTag("flights-layer"),
    ) {
        flights.forEach { flight ->
            key(flight.id) { FlyingCard(flight, anchors, { origin }, durationMillis, flight.delayMillis, { isReady(flight.id) }, onArrived) }
        }
    }
}

/**
 * Posição de [anchor] na tela. Com a [card] do voo, o ponto é o da própria carta (na mão, no conjunto, no lixo ou
 * nos 3 vermelhos): o voo sai de onde ela estava e chega onde ela aparece. Sem a posição exata, cai na região.
 */
/**
 * Região da mesa (dentro da rolagem) que precisa estar à vista para o voo até [anchor]: o conjunto, se já existe;
 * senão (conjunto novo) o fim da área de jogos do lado. `null` para destinos fora da rolagem (mão, monte, lixo...).
 */
private fun Map<AnimAnchor, Rect>.tableTarget(anchor: AnimAnchor): Rect? = when (anchor) {
    is AnimAnchor.Meld -> this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]?.let { area ->
        Rect(area.left, area.bottom - 1f, area.right, area.bottom)
    }
    is AnimAnchor.SideArea -> this[anchor]
    is AnimAnchor.RedThrees -> this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]
    else -> null
}

private fun Map<AnimAnchor, Rect>.resolve(anchor: AnimAnchor, card: Card?): Rect? = when (anchor) {
    is AnimAnchor.OwnHand -> card?.let { this[AnimAnchor.HandCard(it)] } ?: this[anchor]
    is AnimAnchor.DiscardPile -> card?.let { this[AnimAnchor.DiscardCard(it)] } ?: this[anchor]
    is AnimAnchor.Meld ->
        card?.let { this[AnimAnchor.MeldCard(anchor.side, anchor.meldId, it)] } ?: this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]
    is AnimAnchor.RedThrees ->
        card?.let { this[AnimAnchor.RedThreeCard(anchor.side, it)] } ?: this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]
    else -> this[anchor]
}

@Composable
private fun FlyingCard(
    flight: CardFlight,
    anchors: Map<AnimAnchor, Rect>,
    origin: () -> Offset,
    durationMillis: Long,
    delayMillis: Long,
    isReady: () -> Boolean,
    onArrived: (Long) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Espera a mesa mostrar o destino (rolagem até os jogos do lado).
        snapshotFlow { isReady() }.first { it }
        delay(delayMillis)
        visible = true
        progress.animateTo(1f, tween(durationMillis.toInt().coerceAtLeast(1), easing = FastOutSlowInEasing))
        onArrived(flight.id)
        visible = false
    }
    if (!visible) return
    val size = CardSize.SMALL
    val density = LocalDensity.current
    val half = with(density) { Offset(size.width.toPx() / 2, size.height.toPx() / 2) }
    val arcPx = with(density) { 36.dp.toPx() }
    val flips = flight.card != null && flight.from == AnimAnchor.Stock
    Box(
        Modifier
            .offset {
                // Lido a cada quadro: acompanha rolagem e o conjunto novo que aparece no destino.
                val from = anchors.resolve(flight.from, flight.card)?.center ?: return@offset IntOffset(-10_000, -10_000)
                val to = anchors.resolve(flight.to, flight.card)?.center ?: from
                val t = progress.value
                // Trajeto em curva: sobe um pouco no meio do caminho.
                val arc = Offset(0f, -arcPx * sin(PI.toFloat() * t))
                val point = from + (to - from) * t + arc - origin() - half
                IntOffset(point.x.roundToInt(), point.y.roundToInt())
            }
            .graphicsLayer {
                val lift = 1f + 0.18f * sin(PI.toFloat() * progress.value)
                scaleX = lift
                scaleY = lift
                shadowElevation = 6f
                // Compra do monte: a carta vira no meio do voo (verso → face).
                if (flips) rotationY = if (progress.value < 0.5f) 180f * progress.value else 180f * progress.value - 180f
                cameraDistance = 12f * density.density
            },
    ) {
        val showFace = flight.card != null && (!flips || progress.value >= 0.5f)
        if (showFace) PlayingCard(flight.card!!, size = size, describe = false) else CardBack(size = size, describe = false)
    }
}


/** Chave de um plano para o diálogo: destino e cartas da mão, com todos os coringas contados como um só tipo. */
private fun planKey(action: Action.TakeDiscardPile): Any {
    val (destination, cards) = when (val plan = action.plan) {
        is DiscardPlan.NewMeld -> "new" to plan.handCards
        is DiscardPlan.AddToMeld -> plan.meldId to plan.handCards
    }
    return Triple(destination, cards.count { it.isWild }, cards.filterNot { it.isWild }.map { it.toString() }.sorted())
}

/** Planos sem coringa vêm primeiro na lista (a ordem se mantém dentro de cada grupo). */
private fun planUsesWild(action: Action.TakeDiscardPile): Boolean = when (val plan = action.plan) {
    is DiscardPlan.NewMeld -> plan.handCards.any { it.isWild }
    is DiscardPlan.AddToMeld -> plan.handCards.any { it.isWild }
}

/** Cartas por camada de profundidade do monte e dos mortos (a pilha afina a cada tantas cartas). */
private const val CARDS_PER_LAYER = 2

/**
 * Pilha de cartas viradas vista de lado: uma lâmina por [CARDS_PER_LAYER] cartas (sem limite prático), e a cada
 * 10 cartas a lâmina é dourada, como marcas de régua. A altura da pilha permite estimar quantas cartas restam.
 */
@Composable
private fun DeckStack(count: Int, anchor: AnimAnchor) {
    val size = CardSize.SMALL
    val layers = ((count + CARDS_PER_LAYER - 1) / CARDS_PER_LAYER).coerceIn(1, 60)
    val step = 1.dp
    val depth = step * (layers - 1)
    Box(Modifier.size(size.width + 2.dp, size.height + depth)) {
        // Lâminas de baixo para cima; a carta do topo fica no alto e é a âncora.
        Canvas(Modifier.matchParentSize()) {
            val cw = size.width.toPx()
            val ch = size.height.toPx()
            val s = step.toPx()
            val r = CornerRadius(cw * 0.12f)
            for (i in layers - 1 downTo 1) {
                val y = i * s
                val tick = (i * CARDS_PER_LAYER) % 10 == 0
                drawRoundRect(GColors.CardFace, Offset(0f, y), Size(cw, ch), r)
                drawRoundRect(
                    if (tick) GColors.Gold else GColors.CardBorder, Offset(0f, y), Size(cw, ch), r,
                    style = Stroke(if (tick) 1.2.dp.toPx() else 0.6.dp.toPx()),
                )
            }
        }
        CardBack(Modifier.anchor(anchor).shadow(2.dp, RoundedCornerShape(4.dp)), size = size, describe = false)
    }
}

/** Feltro esmeralda da mesa (quase oval: cantos bem grandes) com a moldura dourada biselada. */
private fun Modifier.feltTable(): Modifier {
    val shape = RoundedCornerShape(Corner.md)
    return this
        .shadow(Elevation.dialog, shape)
        .background(Brush.radialGradient(listOf(GColors.Table, GColors.TableDark), radius = 1400f), shape)
        .goldFrame(Corner.md, 3.dp)
        .clip(shape)
}

/** Nome de quem pegou o morto ("Você" para o próprio jogador); `null` sem o assento (partida salva antiga). */
@Composable
private fun mortoTakerName(state: GameUiState, status: MortoStatus.Taken): String? {
    val seat = status.seat ?: return null
    if (seat == state.snapshot.viewerSeat) return stringResource(R.string.side_you)
    return state.personas.getOrNull(seat.index)?.firstName
}

/** Ícone de pessoa num círculo: verde = seu lado (você ou parceiro), vermelho = rival. */
@Composable
private fun PersonGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        drawCircle(color, w / 2)
        drawCircle(Color.White, w * 0.17f, Offset(w / 2, w * 0.36f))
        drawArc(Color.White, 180f, 180f, true, Offset(w * 0.22f, w * 0.58f), Size(w * 0.56f, w * 0.5f))
    }
}

/** Ícone de "cartas" (duas cartas sobrepostas) para as cápsulas contadoras. */
@Composable
private fun CardsGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val card = Size(w * 0.55f, w * 0.78f)
        val r = CornerRadius(w * 0.08f)
        drawRoundRect(color.copy(alpha = 0.55f), Offset(w * 0.05f, w * 0.05f), card, r)
        drawRoundRect(color, Offset(w * 0.4f, w * 0.18f), card, r)
    }
}

/**
 * Selo da canastra (a forma diz o tipo, não só a cor): limpa = estrela cheia; suja = estrela vazada.
 * [flash] (0..1) acende um halo curto quando ela acaba de fechar.
 */
@Composable
private fun CanastaSeal(clean: Boolean, color: Color, flash: Float, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2
        if (flash > 0f) drawCircle(GColors.Champagne.copy(alpha = 0.6f * flash), outer * (1f + flash), c)
        val star = Path().apply {
            for (i in 0 until 10) {
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val rr = if (i % 2 == 0) outer else outer * 0.45f
                val p = Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        if (clean) drawPath(star, color) else drawPath(star, color, style = Stroke(width = outer * 0.25f))
    }
}
