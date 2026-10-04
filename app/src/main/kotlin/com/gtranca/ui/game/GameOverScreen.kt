package com.gtranca.ui.game

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
    Surface(Modifier.fillMaxSize().testTag("game-over")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.game_over_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(
                    if (snapshot.resigned) resignedRes(view.mode) else gameResultRes(view.mode, won = snapshot.winner == view.side),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            sides.forEach { side ->
                Text(
                    sideName(view.mode, side, view.side) + ": " + plainPoints(snapshot.totals[side.index]),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.game_over_rounds, snapshot.history.size))
            snapshot.history.forEach { record ->
                val points = sides.map { side -> signed(record.scores.first { it.side == side }.total) }
                Text(stringResource(R.string.game_over_round_line, record.number, points[0], points[1]))
            }
            Button(onBackToHome, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("back-home")) {
                Text(stringResource(R.string.back_to_home))
            }
        }
    }
}
