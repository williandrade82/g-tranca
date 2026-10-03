package com.gtranca.ui.game

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
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.SideScore
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage

fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()

/** Fim de partida: detalhamento §12 por lado, a partir de [SideScore] do histórico. */
@Composable
fun RoundSummaryScreen(snapshot: GameSnapshot, onNextRound: () -> Unit, onShowFinalResult: () -> Unit) {
    val record = snapshot.history.last()
    val view = snapshot.view
    val sides = listOf(view.side) + view.mode.sides.filter { it != view.side }
    val scores = sides.map { side -> record.scores.first { it.side == side } }
    val resultText = when (val result = record.result) {
        RoundResult.NoWinner -> stringResource(R.string.round_result_no_winner)
        is RoundResult.GoOut -> when {
            result.seat == snapshot.viewerSeat -> stringResource(R.string.round_result_you_went_out)
            result.side == view.side -> stringResource(R.string.round_result_partner_went_out)
            else -> stringResource(R.string.round_result_opponent_went_out)
        }
    }
    Surface(Modifier.fillMaxSize().testTag("round-summary")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.round_over_title, record.number), style = MaterialTheme.typography.headlineSmall)
            Text(resultText, style = MaterialTheme.typography.titleMedium)

            ScoreRow(stringResource(R.string.score_item), sides.map { sideName(view.mode, it, view.side) }, bold = true)
            HorizontalDivider()
            ScoreRow(stringResource(R.string.score_red_threes), scores.map { it.redThrees.text() })
            ScoreRow(stringResource(R.string.score_clean_canastas), scores.map { it.cleanCanastas.text() })
            ScoreRow(stringResource(R.string.score_dirty_canastas), scores.map { it.dirtyCanastas.text() })
            ScoreRow(stringResource(R.string.score_go_out), scores.map { signed(it.goOut) })
            ScoreRow(stringResource(R.string.score_morto_not_taken), scores.map { signed(it.mortoNotTaken) })
            Text(stringResource(R.string.score_hand_header), style = MaterialTheme.typography.labelLarge)
            ScoreRow(stringResource(R.string.score_hand_red_threes), scores.map { it.hand.redThrees.text() })
            ScoreRow(stringResource(R.string.score_hand_black_threes), scores.map { it.hand.blackThrees.text() })
            ScoreRow(stringResource(R.string.score_hand_four_to_ten), scores.map { it.hand.fourToTen.text() })
            ScoreRow(stringResource(R.string.score_hand_faces), scores.map { it.hand.faceCardsAndAces.text() })
            ScoreRow(stringResource(R.string.score_hand_wilds), scores.map { it.hand.wilds.text() })
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
                Button(onShowFinalResult, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("show-final")) {
                    Text(stringResource(R.string.see_final_result))
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
private fun ScoreLine.text(): String =
    if (count == 0) "0" else stringResource(R.string.score_points_with_count, signed(points), count)

@Composable
private fun ScoreRow(label: String, values: List<String>, bold: Boolean = false, tag: String? = null) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(label, Modifier.weight(1.4f), fontWeight = weight)
        values.forEach { Text(it, Modifier.weight(1f), fontWeight = weight, textAlign = TextAlign.End) }
    }
}
