package com.gtranca.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import com.gtranca.game.SeatRole
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.SideScore
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage

fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()

/** Fim de partida: detalhamento §12 por lado, a partir de [SideScore] do histórico. */
@Composable
fun RoundSummaryScreen(
    snapshot: GameSnapshot,
    onNextRound: () -> Unit,
    onContinue: () -> Unit,
    onResign: (() -> Unit)? = null,
) {
    val record = snapshot.history.last()
    val view = snapshot.view
    val sides = listOf(view.side) + view.mode.sides.filter { it != view.side }
    val scores = sides.map { side -> record.scores.first { it.side == side } }
    val resultText = when (val result = record.result) {
        RoundResult.NoWinner -> stringResource(R.string.round_result_no_winner)
        // §11.1 quem bateu, pelo assento (em duplas: você, parceiro ou um dos adversários).
        is RoundResult.GoOut -> stringResource(SeatRole.of(view.mode, result.seat, snapshot.viewerSeat).wentOutRes())
    }
    Surface(Modifier.fillMaxSize().testTag("round-summary")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.round_over_title, record.number),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                // §13.1 desistir também daqui.
                if (onResign != null) {
                    TextButton(onResign, Modifier.heightIn(min = 48.dp).testTag("action-resign")) {
                        Text(stringResource(R.string.action_resign))
                    }
                }
            }
            Text(resultText, style = MaterialTheme.typography.titleMedium)

            ScoreRow(stringResource(R.string.score_item), sides.map { sideName(view.mode, it, view.side) }, bold = true)
            HorizontalDivider()
            // Tudo vem de ScoreBreakdown: a soma das linhas exibidas é o "Total da partida" de cada lado.
            val lines = scores.map { ScoreBreakdown.lines(it) }
            // §12.1 pontos especiais e §12.2 morto não pego.
            ScoreItem.entries.filter { it.section == ScoreSection.SPECIAL }.forEach { item ->
                val noCanasta = stringResource(R.string.score_red_threes_no_canasta)
                ScoreRow(
                    stringResource(item.labelRes()),
                    lines.map { side ->
                        val line = side.getValue(item)
                        // §12.1 sem canastra na mesa, 3 vermelho vale −100: a célula diz o motivo.
                        line.text() + if (item == ScoreItem.RED_THREES && line.points < 0) "\n$noCanasta" else ""
                    },
                    tag = if (item == ScoreItem.RED_THREES) "red-threes-row" else null,
                )
                if (item == ScoreItem.RED_THREES && lines.any { it.getValue(item).points < 0 }) {
                    Text(stringResource(R.string.score_red_threes_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            // §12.1 cartas na mesa e §12.2 cartas na mão: seções destacadas, com subtotal.
            ScoreSectionBox(ScoreSection.TABLE, scores, lines)
            ScoreSectionBox(ScoreSection.HAND, scores, lines)
            HorizontalDivider()
            ScoreRow(stringResource(R.string.score_round_total), scores.map { signed(it.total) }, bold = true)
            ScoreRow(
                stringResource(R.string.score_cumulative),
                sides.map { snapshot.totals[it.index].toString() },
                bold = true,
                tag = "cumulative",
            )

            val tie = snapshot.stage == Stage.ROUND_OVER && snapshot.totals.any { it >= snapshot.config.targetScore }
            if (tie) Text(stringResource(R.string.round_tie_notice), style = MaterialTheme.typography.bodyMedium)

            if (snapshot.stage == Stage.GAME_OVER) {
                Button(onContinue, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("show-final")) {
                    Text(stringResource(R.string.announce_continue))
                }
            } else {
                Button(onNextRound, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("next-round")) {
                    Text(stringResource(R.string.next_round))
                }
            }
        }
    }
}

@Composable
private fun ScoreBreakdown.Line.text(): String = when {
    count == null -> signed(points)
    count == 0 -> "0"
    else -> stringResource(R.string.score_points_with_count, signed(points), count)
}

@Composable
private fun ScoreSectionBox(section: ScoreSection, scores: List<SideScore>, lines: List<Map<ScoreItem, ScoreBreakdown.Line>>) {
    val tag = if (section == ScoreSection.TABLE) "table-section" else "hand-section"
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(if (section == ScoreSection.TABLE) R.string.score_table_header else R.string.score_hand_header),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        ScoreItem.entries.filter { it.section == section }.forEach { item ->
            ScoreRow(stringResource(item.labelRes()), lines.map { it.getValue(item).text() })
        }
        HorizontalDivider()
        ScoreRow(
            stringResource(if (section == ScoreSection.TABLE) R.string.score_table_subtotal else R.string.score_hand_subtotal),
            scores.map { signed(ScoreBreakdown.subtotal(it, section)) },
            bold = true,
            tag = if (section == ScoreSection.TABLE) "table-subtotal" else "hand-subtotal",
        )
    }
}

@StringRes
private fun ScoreItem.labelRes(): Int = when (this) {
    ScoreItem.RED_THREES -> R.string.score_red_threes
    ScoreItem.CLEAN_CANASTAS -> R.string.score_clean_canastas
    ScoreItem.DIRTY_CANASTAS -> R.string.score_dirty_canastas
    ScoreItem.GO_OUT -> R.string.score_go_out
    ScoreItem.MORTO_NOT_TAKEN -> R.string.score_morto_not_taken
    ScoreItem.TABLE_RED_THREES -> R.string.score_table_red_threes
    ScoreItem.TABLE_FOUR_TO_TEN -> R.string.score_table_four_to_ten
    ScoreItem.TABLE_FACES -> R.string.score_table_faces
    ScoreItem.TABLE_WILDS -> R.string.score_table_wilds
    ScoreItem.HAND_RED_THREES -> R.string.score_hand_red_threes
    ScoreItem.HAND_BLACK_THREES -> R.string.score_hand_black_threes
    ScoreItem.HAND_FOUR_TO_TEN -> R.string.score_hand_four_to_ten
    ScoreItem.HAND_FACES -> R.string.score_hand_faces
    ScoreItem.HAND_WILDS -> R.string.score_hand_wilds
}

@Composable
private fun ScoreRow(label: String, values: List<String>, bold: Boolean = false, tag: String? = null) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(label, Modifier.weight(1.4f), fontWeight = weight)
        values.forEach { Text(it, Modifier.weight(1f), fontWeight = weight, textAlign = TextAlign.End) }
    }
}
