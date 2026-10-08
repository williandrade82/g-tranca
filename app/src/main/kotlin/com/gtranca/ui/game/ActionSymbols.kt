package com.gtranca.ui.game

import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import com.gtranca.game.Persona
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.labelRes

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
        ActionIcon(symbol.kind, GColors.Gold, Modifier.size(15.dp))
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

/**
 * Ícone da ação em linha fina (1,75dp num ícone de 22dp, proporcional), desenhado no código: cada jogada
 * tem forma própria, sem depender da cor.
 */
@Composable
fun ActionIcon(kind: ActionKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = (1.75.dp.toPx() * w / 22.dp.toPx()).coerceAtLeast(1.dp.toPx())
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), strokeWidth = stroke, cap = StrokeCap.Round)
        fun card(x: Float, y: Float, cw: Float = 0.42f, ch: Float = 0.6f, angle: Float = 0f) =
            rotate(angle, Offset(w * (x + cw / 2), w * (y + ch))) {
                drawRoundRect(color, Offset(w * x, w * y), Size(w * cw, w * ch), CornerRadius(w * 0.07f), style = line)
            }
        fun head(x: Float, y: Float, dx: Float, dy: Float) {
            // Ponta de seta em (x, y) apontando na direção (dx, dy).
            val px = -dy
            val py = dx
            seg(x, y, x - dx * 0.18f + px * 0.14f, y - dy * 0.18f + py * 0.14f)
            seg(x, y, x - dx * 0.18f - px * 0.14f, y - dy * 0.18f - py * 0.14f)
        }
        when (kind) {
            // Comprar: monte (duas cartas empilhadas) e seta saindo para baixo.
            ActionKind.DRAW -> {
                card(0.12f, 0.06f, 0.4f, 0.5f)
                card(0.2f, 0.14f, 0.4f, 0.5f)
                seg(0.78f, 0.3f, 0.78f, 0.92f); head(0.78f, 0.92f, 0f, 1f)
            }
            // Pegar o descarte: leque de cartas recolhido por uma seta para baixo.
            ActionKind.TAKE -> {
                card(0.08f, 0.08f, 0.34f, 0.48f, -18f)
                card(0.3f, 0.04f, 0.34f, 0.48f, 0f)
                card(0.52f, 0.08f, 0.34f, 0.48f, 18f)
                seg(0.5f, 0.62f, 0.5f, 0.95f); head(0.5f, 0.95f, 0f, 1f)
            }
            // Baixar jogo: três cartas abertas sobre a linha da mesa.
            ActionKind.MELD -> {
                listOf(0.04f, 0.31f, 0.58f).forEach { x -> card(x, 0.1f, 0.36f, 0.58f) }
                seg(0.02f, 0.86f, 0.98f, 0.86f)
            }
            // Acrescentar: carta com "+".
            ActionKind.ADD -> {
                card(0.06f, 0.14f, 0.42f, 0.66f)
                seg(0.72f, 0.3f, 0.72f, 0.7f)
                seg(0.52f, 0.5f, 0.92f, 0.5f)
            }
            // Descartar: carta saindo para a direita.
            ActionKind.DISCARD -> {
                card(0.06f, 0.16f, 0.4f, 0.62f, -10f)
                seg(0.52f, 0.47f, 0.94f, 0.47f); head(0.94f, 0.47f, 1f, 0f)
            }
            // Recusar a compra: carta riscada.
            ActionKind.DECLINE -> {
                card(0.25f, 0.1f, 0.5f, 0.76f)
                seg(0.1f, 0.9f, 0.9f, 0.1f)
            }
        }
    }
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
                        ActionIcon(kind, GColors.Gold, Modifier.size(22.dp))
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
fun SeatDetailDialog(persona: Persona?, roleLabel: String, events: List<PublicEvent>, onDismiss: () -> Unit) {
    GDialog(
        modifier = Modifier.testTag("seat-detail-dialog"),
        onDismissRequest = onDismiss,
        title = {
            if (persona == null) {
                Text(roleLabel)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PersonaAvatar(persona, 56.dp)
                    Column {
                        Text(persona.fullName)
                        Text(
                            stringResource(R.string.persona_caption, stringResource(persona.profession.labelRes(persona.gender)), roleLabel),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
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
                        ActionIcon(event.symbol().kind, GColors.Gold, Modifier.padding(top = 2.dp).size(22.dp))
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
