package com.gtranca.ui.game

import androidx.compose.ui.text.font.FontWeight
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GBanner
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.game.GameSnapshot

/** Fim de jogo (§13): vencedor, totais e o resultado de cada partida. */
@Composable
fun GameOverScreen(snapshot: GameSnapshot, onBackToHome: () -> Unit) {
    val view = snapshot.view
    val sides = listOf(view.side) + view.mode.sides.filter { it != view.side }
    GBackground(Modifier.fillMaxSize().testTag("game-over")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.game_over_title))
            val won = !snapshot.resigned && snapshot.winner == view.side
            GBanner(
                stringResource(
                    if (snapshot.resigned) resignedRes(view.mode) else gameResultRes(view.mode, won = snapshot.winner == view.side),
                ),
                if (won) GColors.Green else GColors.Neutral,
                GColors.White,
            )
            GPanel(Modifier.fillMaxWidth()) {
                sides.forEach { side ->
                    Text(
                        sideName(view.mode, side, view.side) + ": " + plainPoints(snapshot.totals[side.index]),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                HorizontalDivider(color = GColors.Divider, thickness = 2.dp)
                Text(stringResource(R.string.game_over_rounds, snapshot.history.size), fontWeight = FontWeight.Bold)
                snapshot.history.forEach { record ->
                    val points = sides.map { side -> signed(record.scores.first { it.side == side }.total) }
                    Text(stringResource(R.string.game_over_round_line, record.number, points[0], points[1]))
                }
            }
            GButton(onBackToHome, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("back-home")) {
                Text(stringResource(R.string.back_to_home))
            }
        }
    }
}
