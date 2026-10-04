@file:OptIn(ExperimentalLayoutApi::class)

package com.gtranca.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import android.annotation.SuppressLint
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
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
import com.gtranca.ui.theme.OnTable
import com.gtranca.ui.theme.TableAccent
import com.gtranca.ui.theme.TableGreen
import com.gtranca.ui.theme.TableGreenDark

/** Gestos da mesa, emitidos para o [GameViewModel]. */
interface TableEvents {
    fun onCardClick(card: Card)
    fun onClearSelection()
    fun onSortChange(sort: HandSort)
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
    fun onMessageShown()
    fun onRevealConfirmed()
    fun onResign()
    fun onConfirmResign()
    fun onDismissResign()
}

/** Texto claro para controles desabilitados sobre o verde (contraste ≥ 4,5:1 com o verde escuro e o da mesa). */
private val DisabledOnTable = Color(0xFFB4C3B4)

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
fun TableScreen(state: GameUiState, events: TableEvents, modifier: Modifier = Modifier) {
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
    // Posições dos elementos da mesa, para as animações (cartas voando entre eles).
    val anchors = remember { mutableStateMapOf<AnimAnchor, Rect>() }
    val tableScroll = rememberScrollState()
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
        modifier = modifier.testTag("table-screen"),
        containerColor = TableGreen,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalAnchors provides anchors) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    Header(state, events)
                    state.banner?.let { SwapBanner(it, view.mode, snapshot.viewerSeat) }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(tableScroll).padding(horizontal = 8.dp).testTag("table-scroll"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SeatsPanel(state)
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
                    StatusAndActions(state, events)
                    HandArea(state, events)
                }
                FlightsLayer(state.flights, anchors, state.animationMillis)
            }
        }
    }

    if (showDiscardPile) DiscardPileDialog(view.discardPile) { showDiscardPile = false }
    state.planChoice?.let { options -> PlanChoiceDialog(options, view, events) }
    state.reveal?.let { reveal -> RedThreeDialog(reveal, events::onRevealConfirmed, events::onResign) }
    if (state.confirmDecline) {
        AlertDialog(
            onDismissRequest = events::onDismissDecline,
            title = { Text(stringResource(R.string.decline_title)) },
            text = { Text(stringResource(R.string.decline_text)) },
            confirmButton = { TextButton(events::onConfirmDecline) { Text(stringResource(R.string.decline_confirm)) } },
            dismissButton = { TextButton(events::onDismissDecline) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Header(state: GameUiState, events: TableEvents) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val other = view.mode.sides.first { it != view.side }
    val resignText = stringResource(R.string.action_resign)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.table_round, snapshot.roundNumber, snapshot.config.targetScore),
            color = OnTable,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(
                R.string.score_line,
                shortSideName(view.mode, view.side, view.side),
                snapshot.totals[view.side.index],
                snapshot.totals[other.index],
                shortSideName(view.mode, other, view.side),
            ),
            color = TableAccent,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("score"),
        )
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
 * Os outros assentos, na ordem de jogada a partir do humano (§4.2, anti-horário): em duplas, adversário à
 * direita, parceiro e adversário à esquerda. De cada um, a mão como cartas viradas (nunca as cartas, nem as do
 * parceiro) com o número, se está pensando e a última jogada.
 */
@Composable
private fun SeatsPanel(state: GameUiState) {
    val snapshot = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.view.mode.seatsInPlayOrder(snapshot.viewerSeat).drop(1).forEach { seat -> SeatRow(state, seat) }
    }
}

@Composable
private fun SeatRow(state: GameUiState, seat: Seat) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val role = SeatRole.of(view.mode, seat, snapshot.viewerSeat)
    val thinking = snapshot.thinkingSeat == seat
    val handSize = view.handSizes[seat.index]
    val handText = countText(R.plurals.opponent_hand, R.string.opponent_hand_zero, handSize)
    val countText = countText(R.plurals.card_count, R.string.card_count_zero, handSize)
    val thinkingText = stringResource(R.string.seat_thinking)
    val events = snapshot.turnEvents[seat.index]
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (thinking) TableGreenDark else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .anchor(AnimAnchor.SeatHand(seat.index))
            .testTag("seat-${seat.index}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(role.nameRes()),
                color = if (role == SeatRole.PARTNER) TableAccent else OnTable,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Mão representada: uma carta virada por carta, compactadas para caber na largura.
            HiddenHand(
                handSize,
                Modifier.weight(1f).semantics { contentDescription = handText }.testTag("seat-hand-${seat.index}"),
            )
            Text(
                countText,
                color = OnTable,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("seat-hand-size-${seat.index}"),
            )
            if (thinking) {
                CircularProgressIndicator(
                    Modifier.size(16.dp).semantics { contentDescription = thinkingText }.testTag("seat-thinking-${seat.index}"),
                    color = TableAccent,
                    strokeWidth = 2.dp,
                )
            }
        }
        if (events.isNotEmpty()) {
            Text(
                stringResource(R.string.opponent_last_turn, turnText(events)),
                color = OnTable,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("seat-last-turn-${seat.index}"),
            )
        }
    }
}

/** [count] cartas viradas sobrepostas; o passo encolhe (até 2dp) para nunca passar da largura disponível. */
@Composable
private fun HiddenHand(count: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.height(CardSize.TINY.height)) {
        if (count == 0) return@BoxWithConstraints
        val size = CardSize.TINY
        val step = if (count == 1) 0.dp else ((maxWidth - size.width) / (count - 1)).coerceIn(2.dp, 9.dp)
        Box(Modifier.width(size.width + step * (count - 1))) {
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
            .border(1.dp, OnTable.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .anchor(AnimAnchor.SideArea(side))
            .padding(6.dp)
            .testTag("side-$sideTag"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OnTable, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
                            PlayingCard(card, Modifier.padding(start = 2.dp), size = CardSize.SMALL)
                        }
                    }
                }
            }
        }
        if (melds.isEmpty()) {
            Text(stringResource(R.string.no_melds), color = OnTable, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                melds.forEach { meld ->
                    MeldView(
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
    tableMeld: TableMeld,
    onClick: ((MeldId) -> Unit)?,
    modifier: Modifier,
    flash: CanastaFlash? = null,
    animationMillis: Long = 0,
) {
    // §7 canastra fechada agora: pulso curto (limpa em dourado, suja em azul-claro).
    val pulse = remember(tableMeld.id) { Animatable(0f) }
    val scale = animationScale()
    LaunchedEffect(flash?.id) {
        if (flash != null && animationMillis > 0 && scale > 0f) {
            val half = (animationMillis * scale).toInt().coerceAtLeast(1)
            pulse.animateTo(1f, tween(half))
            pulse.animateTo(0f, tween(half))
        }
    }
    val meld = tableMeld.meld
    val canasta = meld.isCanasta()
    val label = when {
        !canasta -> null
        meld.isClean -> stringResource(R.string.canasta_clean)
        else -> stringResource(R.string.canasta_dirty)
    }
    val borderColor = when {
        canasta && meld.isClean -> TableAccent
        canasta -> Color(0xFFB0BEC5)
        onClick != null -> OnTable.copy(alpha = 0.6f) // tocável para acrescentar
        else -> Color.Transparent
    }
    val descriptions = meld.cards.map { cardDescription(it) }
    val description = stringResource(R.string.meld_description, descriptions.joinToString(", ")) +
        (label?.let { ". $it" } ?: "")
    val flashColor = if (flash?.clean == true) TableAccent else CanastaDirtyFlash
    Column(
        modifier
            .graphicsLayer {
                val grow = 1f + 0.08f * pulse.value
                scaleX = grow
                scaleY = grow
            }
            .heightIn(min = 48.dp)
            .background(flashColor.copy(alpha = 0.45f * pulse.value), RoundedCornerShape(6.dp))
            .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable { onClick(tableMeld.id) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(3.dp),
    ) {
        OverlappedCards(meld.cards, step = 14.dp)
        if (label != null) {
            Text(label, color = borderColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Cartas pequenas sobrepostas, cada uma deslocada [step] para a direita (o índice de cada uma fica visível). */
@Composable
private fun OverlappedCards(cards: List<Card>, step: Dp, modifier: Modifier = Modifier, size: CardSize = CardSize.SMALL) {
    Box(modifier.width(size.width + step * (cards.size - 1).coerceAtLeast(0)).height(size.height)) {
        cards.forEachIndexed { i, card ->
            PlayingCard(card, Modifier.offset(x = step * i), size = size, describe = false)
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
                    .border(2.dp, if (state.canDraw) TableAccent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = state.canDraw, onClick = events::onDraw)
                    .semantics(mergeDescendants = true) { contentDescription = stockLabel }
                    .anchor(AnimAnchor.Stock)
                    .testTag("stock")
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (view.stockSize > 0) CardBack(size = CardSize.SMALL, describe = false) else Spacer(Modifier.width(CardSize.SMALL.width))
                Column {
                    Text(stringResource(R.string.stock), color = OnTable, style = MaterialTheme.typography.labelMedium)
                    Text(stockCount, color = OnTable, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("mortos")) {
                view.mortoStatus.forEachIndexed { i, status ->
                    val statusText = when (status) {
                        MortoStatus.Available -> countText(R.plurals.card_count, R.string.card_count_zero, view.mortoSizes[i])
                        MortoStatus.BecameStock -> stringResource(R.string.morto_became_stock)
                        is MortoStatus.Taken -> stringResource(mortoTakenRes(view.mode, own = status.side == view.side))
                    }
                    val title = stringResource(R.string.morto_title, i + 1)
                    Row(
                        Modifier.width(MortoWidth).semantics(mergeDescendants = true) { contentDescription = "$title, $statusText" }
                            .anchor(AnimAnchor.Morto(i))
                            .testTag("morto-$i"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (status == MortoStatus.Available) CardBack(size = CardSize.SMALL, describe = false)
                        Column {
                            Text(title, color = OnTable, style = MaterialTheme.typography.labelMedium)
                            Text(statusText, color = OnTable, style = MaterialTheme.typography.labelSmall, maxLines = 2)
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
                .border(2.dp, if (pileActive) TableAccent else OnTable.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .clickable(enabled = state.awaitingDraw || state.playing, onClick = events::onDiscardPileClick)
                .semantics(mergeDescendants = true) { contentDescription = pileLabel }
                .anchor(AnimAnchor.DiscardPile)
                .testTag("discard-pile")
                .padding(4.dp),
        ) {
            if (pile.isEmpty()) {
                Text(stringResource(R.string.discard_pile_empty), color = OnTable, modifier = Modifier.align(Alignment.CenterStart))
            } else {
                Box(Modifier.horizontalScroll(scroll)) { OverlappedCards(pile, step = 18.dp) }
            }
        }
    }
}

private val MortoWidth = 96.dp

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
        Modifier.fillMaxWidth().background(TableGreenDark).padding(horizontal = 8.dp, vertical = 4.dp).testTag("action-bar"),
    ) {
        Text(status, color = TableAccent, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("turn-status"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (snapshot.view.phase != Phase.PLAYING || !state.isHumanTurn) {
                ActionButton(stringResource(R.string.action_draw), state.canDraw, events::onDraw, "action-draw")
                ActionButton(stringResource(R.string.action_take_discard), state.canTakeDiscardPile, events::onTakeDiscardPile, "action-take-discard")
                if (state.canDecline) ActionButton(stringResource(R.string.action_decline), true, events::onDeclineDraw, "action-decline")
            } else {
                ActionButton(stringResource(R.string.action_meld), state.canMeld, events::onCreateMeld, "action-meld")
                ActionButton(stringResource(R.string.action_discard), state.canDiscard, events::onDiscard, "action-discard")
            }
            val clearEnabled = state.selected.isNotEmpty()
            OutlinedButton(
                onClick = events::onClearSelection,
                enabled = clearEnabled,
                border = BorderStroke(1.dp, if (clearEnabled) OnTable else DisabledOnTable),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnTable, disabledContentColor = DisabledOnTable),
                modifier = Modifier.heightIn(min = 48.dp).testTag("action-clear"),
            ) { Text(stringResource(R.string.action_clear)) }
        }
        // Fixo durante a vez (não depende da seleção) para os botões não pularem ao tocar nas cartas.
        if (state.playing && snapshot.view.tables[snapshot.view.side.index].melds.isNotEmpty()) {
            Text(
                stringResource(if (snapshot.view.mode == GameMode.DUPLAS) R.string.add_to_meld_hint_team else R.string.add_to_meld_hint),
                color = OnTable,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Botão de ação. Desabilitado: só o contorno, sem preenchimento, com texto claro legível sobre o verde. */
@Composable
private fun ActionButton(text: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        border = if (enabled) null else BorderStroke(1.dp, DisabledOnTable),
        colors = ButtonDefaults.buttonColors(
            containerColor = TableAccent,
            contentColor = Color.Black,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = DisabledOnTable,
        ),
    ) { Text(text) }
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
            Text(
                stringResource(R.string.your_hand, state.hand.size),
                color = OnTable,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            SortSelector(state.sort, events::onSortChange)
        }
        // 7 cartas de 48dp por linha em 360dp; com 20+ cartas, a área rola.
        val custom = state.customHand
        Box(
            Modifier.fillMaxWidth().heightIn(max = 74.dp * 3).anchor(AnimAnchor.OwnHand).verticalScroll(rememberScrollState())
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

/** "Ordem:" e três botões de ícone alternáveis (Valor, Naipe, Personalizado), o selecionado em destaque. */
@Composable
private fun SortSelector(current: HandSort, onChange: (HandSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
        Text(stringResource(R.string.hand_order), color = OnTable, style = MaterialTheme.typography.labelMedium)
        listOf(
            HandSort.BY_RANK to R.string.sort_rank,
            HandSort.BY_SUIT to R.string.sort_suit,
            HandSort.CUSTOM to R.string.sort_custom,
        ).forEach { (sort, label) ->
            val selected = sort == current
            val description = stringResource(label)
            Box(
                Modifier
                    .size(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(sort) })
                    .semantics { contentDescription = description }
                    .testTag("sort-${sort.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (selected) TableAccent else Color.Transparent, CircleShape)
                        .border(1.dp, if (selected) TableAccent else OnTable.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    val color = if (selected) Color.Black else OnTable
                    when (sort) {
                        HandSort.BY_RANK -> Text("A K", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        HandSort.BY_SUIT -> Text("♠︎♥︎", color = color, fontSize = 13.sp, maxLines = 1)
                        HandSort.CUSTOM -> TuneIcon(color, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Ícone de ajuste (três controles deslizantes), desenhado no código. */
@Composable
private fun TuneIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.1f
        listOf(0.2f to 0.65f, 0.5f to 0.3f, 0.8f to 0.55f).forEach { (y, knob) ->
            drawLine(color, Offset(0f, w * y), Offset(w, w * y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.12f, center = Offset(w * knob, w * y))
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
    AlertDialog(
        modifier = Modifier.testTag("red-three-dialog"),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(revealTitle(reveal.notice)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reveal.notice.cards.forEach { PlayingCard(it, size = CardSize.LARGE) }
                }
                Text(stringResource(R.string.red_three_value_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onConfirm, Modifier.heightIn(min = 48.dp).testTag("red-three-confirm")) {
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
    AlertDialog(
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

@Composable
private fun PlanChoiceDialog(options: List<Action.TakeDiscardPile>, view: PlayerView, events: TableEvents) {
    val top = view.discardTop
    AlertDialog(
        onDismissRequest = events::onDismissPlanChoice,
        title = { Text(stringResource(R.string.plan_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    val text = when (val plan = option.plan) {
                        is DiscardPlan.NewMeld ->
                            stringResource(R.string.plan_new_meld, (plan.handCards + listOfNotNull(top)).labels())
                        is DiscardPlan.AddToMeld -> {
                            // §6.4 pode haver grupos repetidos: o número do jogo os distingue.
                            val melds = view.tables[view.side.index].melds
                            val index = melds.indexOfFirst { it.id == plan.meldId }
                            stringResource(
                                if (view.mode == GameMode.DUPLAS) R.string.plan_add_to_team_meld else R.string.plan_add_to_meld,
                                index + 1,
                                melds.getOrNull(index)?.meld?.cards?.labels().orEmpty(),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { events.onPlanChosen(option) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
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
private fun SwapBanner(notice: RedThreeNotice, mode: GameMode, viewerSeat: Seat) {
    val text = bannerText(mode, notice, viewerSeat)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(TableGreenDark, RoundedCornerShape(8.dp))
            .border(1.dp, TableAccent, RoundedCornerShape(8.dp))
            .padding(6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("red-three-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notice.cards.forEach { card ->
            val visible = remember(notice.id, card) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.4f)) {
                PlayingCard(card, size = CardSize.SMALL)
            }
        }
        Text(text, color = OnTable, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Cor do destaque de canastra suja fechada (§7). */
private val CanastaDirtyFlash = Color(0xFF90CAF9)

/** Posições dos elementos da mesa (coordenadas da raiz), registradas por [anchor]; `null` fora da mesa. */
private val LocalAnchors = staticCompositionLocalOf<MutableMap<AnimAnchor, Rect>?> { null }

/** Registra a posição deste elemento como [key] para as animações da mesa. */
@SuppressLint("ModifierFactoryUnreferencedReceiver")
private fun Modifier.anchor(key: AnimAnchor): Modifier = composed {
    val anchors = LocalAnchors.current
    // Posição sem recorte: um elemento rolado para fora da área visível continua com a posição real (o voo entra
    // pela borda), em vez do retângulo vazio em (0,0) que `boundsInRoot` daria.
    if (anchors == null) this else this.onGloballyPositioned { anchors[key] = Rect(it.positionInRoot(), it.size.toSize()) }
}

/** Escala de duração das animações do sistema ("remover animações" = 0). */
@Composable
private fun animationScale(): Float {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
}

/**
 * Camada por cima da mesa com as cartas voando ([CardFlight]). Não recebe toques nem aparece para o leitor de tela;
 * com animações desligadas (duração 0 ou escala do sistema 0) não desenha nada.
 */
@Composable
private fun FlightsLayer(flights: List<CardFlight>, anchors: Map<AnimAnchor, Rect>, durationMillis: Long) {
    val scale = animationScale()
    if (durationMillis <= 0 || scale <= 0f || flights.isEmpty()) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { origin = it.positionInRoot() }
            .testTag("flights-layer"),
    ) {
        flights.forEach { flight ->
            key(flight.id) { FlyingCard(flight, anchors, { origin }, (durationMillis * scale).toLong(), (flight.delayMillis * scale).toLong()) }
        }
    }
}

private fun Map<AnimAnchor, Rect>.resolve(anchor: AnimAnchor): Rect? = when (anchor) {
    is AnimAnchor.Meld -> this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]
    is AnimAnchor.RedThrees -> this[anchor] ?: this[AnimAnchor.SideArea(anchor.side)]
    else -> this[anchor]
}

@Composable
private fun FlyingCard(flight: CardFlight, anchors: Map<AnimAnchor, Rect>, origin: () -> Offset, durationMillis: Long, delayMillis: Long) {
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        visible = true
        progress.animateTo(1f, tween(durationMillis.toInt().coerceAtLeast(1), easing = FastOutSlowInEasing))
        visible = false
    }
    if (!visible) return
    val size = CardSize.SMALL
    val density = LocalDensity.current
    val half = with(density) { Offset(size.width.toPx() / 2, size.height.toPx() / 2) }
    Box(
        Modifier
            .offset {
                // Lido a cada quadro: acompanha rolagem e o conjunto novo que aparece no destino.
                val from = anchors.resolve(flight.from)?.center ?: return@offset IntOffset(-10_000, -10_000)
                val to = anchors.resolve(flight.to)?.center ?: from
                val t = progress.value
                val point = from + (to - from) * t - origin() - half
                IntOffset(point.x.roundToInt(), point.y.roundToInt())
            }
            .graphicsLayer {
                val lift = 1f + 0.18f * sin(PI.toFloat() * progress.value)
                scaleX = lift
                scaleY = lift
                shadowElevation = 6f
            },
    ) {
        if (flight.card != null) PlayingCard(flight.card, size = size, describe = false) else CardBack(size = size, describe = false)
    }
}

