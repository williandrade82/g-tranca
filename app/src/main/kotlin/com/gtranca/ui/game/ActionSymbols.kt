package com.gtranca.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.ui.cards.CardSize
import com.gtranca.ui.cards.PlayingCard
import com.gtranca.ui.cards.shortLabel
import androidx.compose.ui.platform.testTag
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.Spacing

/** Símbolos das jogadas públicas dos outros assentos (a legenda está no botão "?" da mesa). */
enum class ActionKind { DRAW, TAKE, MELD, ADD, DISCARD, DECLINE }

/** Um símbolo com o detalhe curto ao lado (quantidade de cartas ou a carta descartada). */
data class ActionSymbol(val kind: ActionKind, val detail: String? = null, val redSuit: Boolean = false)

fun PublicEvent.symbol(): ActionSymbol = when (val a = action) {
    Action.DrawFromStock -> ActionSymbol(ActionKind.DRAW)
    Action.DeclineDraw -> ActionSymbol(ActionKind.DECLINE)
    is Action.TakeDiscardPile -> ActionSymbol(ActionKind.TAKE, takenFromDiscard.size.toString())
    is Action.CreateMeld -> ActionSymbol(ActionKind.MELD, a.cards.size.toString())
    is Action.AddToMeld -> ActionSymbol(ActionKind.ADD, a.cards.size.toString())
    is Action.Discard -> ActionSymbol(ActionKind.DISCARD, a.card.shortLabel, a.card.suit.isRed)
}

private val RedOnDark = Color(0xFFFF8A80)

/** Última jogada resumida em símbolos; o leitor de tela recebe a frase completa. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LastTurnSymbols(events: List<PublicEvent>, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.opponent_last_turn, turnText(events))
    FlowRow(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        events.forEach { SymbolWithDetail(it.symbol()) }
    }
}

@Composable
private fun SymbolWithDetail(symbol: ActionSymbol) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(symbol.kind, GColors.OnTable, Modifier.size(14.dp))
        symbol.detail?.let {
            Text(
                it,
                Modifier.padding(start = 2.dp),
                color = if (symbol.redSuit) RedOnDark else GColors.OnTable,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Ícone da ação, desenhado no código. */
@Composable
fun ActionIcon(kind: ActionKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.13f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), strokeWidth = stroke, cap = StrokeCap.Round)
        when (kind) {
            ActionKind.DRAW -> arrow(::line, down = true, from = 0.08f, to = 0.9f)
            ActionKind.TAKE -> {
                arrow(::line, down = true, from = 0.05f, to = 0.68f)
                line(0.12f, 0.93f, 0.88f, 0.93f)
            }
            ActionKind.DISCARD -> {
                arrow(::line, down = false, from = 0.72f, to = 0.08f)
                line(0.12f, 0.93f, 0.88f, 0.93f)
            }
            ActionKind.MELD -> listOf(0.04f, 0.3f, 0.56f).forEach { x ->
                drawRoundRect(
                    color, Offset(w * x, w * 0.14f), Size(w * 0.4f, w * 0.72f), CornerRadius(w * 0.08f),
                    style = Stroke(width = w * 0.1f),
                )
            }
            ActionKind.ADD -> {
                line(0.5f, 0.12f, 0.5f, 0.88f)
                line(0.12f, 0.5f, 0.88f, 0.5f)
            }
            ActionKind.DECLINE -> {
                line(0.2f, 0.2f, 0.8f, 0.8f)
                line(0.8f, 0.2f, 0.2f, 0.8f)
            }
        }
    }
}

/** Seta vertical de [from] a [to] (frações da altura) com a ponta em [to]. */
private fun arrow(line: (Float, Float, Float, Float) -> Unit, down: Boolean, from: Float, to: Float) {
    line(0.5f, from, 0.5f, to)
    val back = if (down) to - 0.3f else to + 0.3f
    line(0.5f, to, 0.22f, back)
    line(0.5f, to, 0.78f, back)
}

/** Legenda dos símbolos (botão "?" da mesa). */
@Composable
fun LegendDialog(onDismiss: () -> Unit) {
    GDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legend_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                listOf(
                    ActionKind.DRAW to R.string.legend_draw,
                    ActionKind.TAKE to R.string.legend_take,
                    ActionKind.MELD to R.string.legend_meld,
                    ActionKind.ADD to R.string.legend_add,
                    ActionKind.DISCARD to R.string.legend_discard,
                    ActionKind.DECLINE to R.string.legend_decline,
                ).forEach { (kind, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ActionIcon(kind, MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
                        Text(stringResource(text))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Detalhe da última jogada de um assento: cada ação com o símbolo, a frase completa e as cartas desenhadas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatDetailDialog(title: String, events: List<PublicEvent>, onDismiss: () -> Unit) {
    GDialog(
        modifier = Modifier.testTag("seat-detail-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                events.forEach { event ->
                    val cards = when (val action = event.action) {
                        is Action.Discard -> listOf(action.card)
                        is Action.CreateMeld -> action.cards
                        is Action.AddToMeld -> action.cards
                        is Action.TakeDiscardPile -> event.takenFromDiscard
                        else -> emptyList()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ActionIcon(event.symbol().kind, MaterialTheme.colorScheme.onSurface, Modifier.padding(top = 2.dp).size(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(eventText(event).replaceFirstChar { it.uppercase() })
                            if (cards.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    cards.forEach { PlayingCard(it, size = CardSize.SMALL, describe = false) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("seat-detail-close")) { Text(stringResource(R.string.close)) } },
    )
}
