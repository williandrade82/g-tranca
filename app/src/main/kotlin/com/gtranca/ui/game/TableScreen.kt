@file:OptIn(ExperimentalLayoutApi::class)

package com.gtranca.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.TableMeld
import com.gtranca.game.SeatRole
import com.gtranca.game.cardClass
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
    fun onToggleSort()
    fun onDraw()
    fun onTakeDiscardPile()
    fun onCreateMeld()
    fun onAddToMeld(meldId: MeldId)
    fun onDiscard()
    fun onDeclineDraw()
    fun onConfirmDecline()
    fun onDismissDecline()
    fun onPlanChosen(action: Action.TakeDiscardPile)
    fun onDismissPlanChoice()
    fun onMessageShown()
}

/** Texto claro para controles desabilitados sobre o verde (contraste ≥ 4,5:1 com o verde escuro e o da mesa). */
private val DisabledOnTable = Color(0xFFB4C3B4)

/**
 * Mesa: desenhada só a partir da vista do humano ([PlayerView]); controles habilitados por `legalActions`.
 *
 * Layout em retrato (testado em 360dp): placar no topo; no meio, uma área com rolagem vertical (outros
 * assentos, jogos do outro lado, monte/mortos/lixo e os jogos do seu lado, nessa ordem); embaixo, fixas, a barra
 * de ações e a mão. A área do meio termina antes da barra (nada fica por baixo dela) e, no começo de cada etapa
 * da sua vez, rola até o fim, deixando lixo e seus jogos logo acima dos botões.
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
    val tableScroll = rememberScrollState()
    LaunchedEffect(state.isHumanTurn, view.phase) {
        if (state.isHumanTurn) tableScroll.animateScrollTo(tableScroll.maxValue)
    }

    Scaffold(
        modifier = modifier.testTag("table-screen"),
        containerColor = TableGreen,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(state)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(tableScroll).padding(horizontal = 8.dp).testTag("table-scroll"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeatsPanel(state)
                SideArea(
                    title = stringResource(meldsTitleRes(view.mode, own = false)),
                    view = view,
                    side = view.mode.sides.first { it != view.side },
                    onMeldClick = null,
                    sideTag = "opponent",
                )
                CenterArea(state, events, onShowDiscardPile = { showDiscardPile = true })
                SideArea(
                    title = stringResource(meldsTitleRes(view.mode, own = true)),
                    view = view,
                    side = view.side,
                    onMeldClick = if (state.playing) events::onAddToMeld else null,
                    sideTag = "own",
                )
                Spacer(Modifier.height(4.dp))
            }
            StatusAndActions(state, events)
            HandArea(state, events)
        }
    }

    if (showDiscardPile) DiscardPileDialog(view.discardPile) { showDiscardPile = false }
    state.planChoice?.let { options -> PlanChoiceDialog(options, view, events) }
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
private fun Header(state: GameUiState) {
    val snapshot = state.snapshot
    val view = snapshot.view
    val other = view.mode.sides.first { it != view.side }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.table_round, snapshot.roundNumber, snapshot.config.targetScore),
            color = OnTable,
            style = MaterialTheme.typography.labelLarge,
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
    }
}

/**
 * Os outros assentos, na ordem de jogada a partir do humano (§4.2, anti-horário): em duplas, adversário à
 * direita, parceiro e adversário à esquerda. De cada um, só o tamanho da mão (nunca as cartas, nem as do parceiro),
 * se está pensando e a última jogada.
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
    val handText = pluralStringResource(R.plurals.opponent_hand, handSize, handSize)
    val thinkingText = stringResource(R.string.seat_thinking)
    val events = snapshot.turnEvents[seat.index]
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (thinking) TableGreenDark else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("seat-${seat.index}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(role.nameRes()),
                color = if (role == SeatRole.PARTNER) TableAccent else OnTable,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "· $handText",
                color = OnTable,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).testTag("seat-hand-size-${seat.index}"),
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

@Composable
private fun SideArea(
    title: String,
    view: PlayerView,
    side: Side,
    onMeldClick: ((MeldId) -> Unit)?,
    sideTag: String,
) {
    val melds = view.tables[side.index].melds
    val redThrees = view.redThrees[side.index]
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, OnTable.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(6.dp)
            .testTag("side-$sideTag"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OnTable, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (redThrees.isNotEmpty()) {
                Text(
                    stringResource(R.string.red_threes, redThrees.size),
                    color = OnTable,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 4.dp),
                )
                OverlappedCards(redThrees, step = 12.dp)
            }
        }
        if (melds.isEmpty()) {
            Text(stringResource(R.string.no_melds), color = OnTable, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                melds.forEach { meld -> MeldView(meld, onMeldClick, Modifier.testTag("meld-$sideTag-${meld.id.value}")) }
            }
        }
    }
}

@Composable
private fun MeldView(tableMeld: TableMeld, onClick: ((MeldId) -> Unit)?, modifier: Modifier) {
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
    Column(
        modifier
            .heightIn(min = 48.dp)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Monte
            val stockCount = pluralStringResource(R.plurals.card_count, view.stockSize, view.stockSize)
            val stockLabel = stringResource(R.string.stock) + ", " + stockCount
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .border(2.dp, if (state.canDraw) TableAccent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = state.canDraw, onClick = events::onDraw)
                    .semantics(mergeDescendants = true) { contentDescription = stockLabel }
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
            // Mortos
            view.mortoStatus.forEachIndexed { i, status ->
                val statusText = when (status) {
                    MortoStatus.Available -> pluralStringResource(R.plurals.card_count, view.mortoSizes[i], view.mortoSizes[i])
                    MortoStatus.BecameStock -> stringResource(R.string.morto_became_stock)
                    is MortoStatus.Taken -> stringResource(mortoTakenRes(view.mode, own = status.side == view.side))
                }
                val title = stringResource(R.string.morto_title, i + 1)
                Row(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$title, $statusText" }
                        .testTag("morto-$i"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (status == MortoStatus.Available) CardBack(size = CardSize.SMALL, describe = false)
                    Column {
                        Text(title, color = OnTable, style = MaterialTheme.typography.labelMedium)
                        Text(statusText, color = OnTable, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Start)
                    }
                }
            }
        }
        // Lixo: aberto, todas as cartas visíveis (§5.6); topo à direita.
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
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .border(2.dp, if (state.canTakeDiscardPile) TableAccent else OnTable.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .clickable(enabled = state.awaitingDraw, onClick = events::onTakeDiscardPile)
                .semantics(mergeDescendants = true) { contentDescription = pileLabel }
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
                ActionButton(stringResource(R.string.action_take_discard), state.awaitingDraw, events::onTakeDiscardPile, "action-take-discard")
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
    val selectedText = stringResource(R.string.card_selected)
    val playableText = stringResource(R.string.card_playable)
    val completesText = stringResource(R.string.card_completes_selection)
    val notWithSelectionText = stringResource(R.string.card_not_with_selection)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.your_hand, state.hand.size),
                color = OnTable,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(events::onToggleSort, modifier = Modifier.testTag("sort")) {
                Text(
                    stringResource(if (state.sort == HandSort.BY_SUIT) R.string.action_sort_rank else R.string.action_sort_suit),
                    color = TableAccent,
                )
            }
        }
        // 7 cartas de 48dp por linha em 360dp; com 20+ cartas, a área rola.
        FlowRow(
            Modifier.fillMaxWidth().heightIn(max = 74.dp * 3).verticalScroll(rememberScrollState()).testTag("hand"),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hasSelection = state.selected.isNotEmpty()
            state.hand.forEach { card ->
                val selected = card in state.selected
                val emphasis = handEmphasis(selected, card.cardClass in state.highlighted, hasSelection, state.isHumanTurn)
                PlayingCard(
                    card,
                    Modifier
                        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { events.onCardClick(card) })
                        .semantics {
                            stateDescription = when (emphasis) {
                                CardEmphasis.SELECTED -> selectedText
                                CardEmphasis.SUBTLE -> playableText
                                CardEmphasis.STRONG -> completesText
                                CardEmphasis.DIMMED -> notWithSelectionText
                                CardEmphasis.NONE -> ""
                            }
                        }
                        .testTag("hand-card-$card"),
                    size = CardSize.MEDIUM,
                    emphasis = emphasis,
                )
            }
        }
    }
}

@Composable
private fun DiscardPileDialog(pile: List<Card>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.discard_pile_dialog_title, pile.size, pile.size)) },
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
