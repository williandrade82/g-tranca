package com.gtranca.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.gtranca.engine.model.GameMode
import com.gtranca.game.Persona
import com.gtranca.ui.persona.AvatarRow
import com.gtranca.ui.persona.personasOfSide
import com.gtranca.ui.persona.sideLabel

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
fun GameOverScreen(snapshot: GameSnapshot, onBackToHome: () -> Unit, personas: List<Persona> = emptyList(), animate: Boolean = true) {
    val view = snapshot.view
    val sides = listOf(view.side) + view.mode.sides.filter { it != view.side }
    val won = !snapshot.resigned && snapshot.winner == view.side
    val outcome = if (won) EndOutcome.WIN else EndOutcome.LOSE
    GBackground(Modifier.fillMaxSize().testTag("game-over")) {
        EndCelebration(outcome, brief = false, enabled = animate, playSound = snapshot.resigned)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.game_over_title))
            if (won) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Trophy(96.dp, animate, Modifier.testTag("game-over-trophy")) }
            // §14.1 quem venceu, com o avatar em destaque.
            snapshot.winner?.let { winner ->
                val winners = personasOfSide(view.mode, winner, personas)
                if (winners.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AvatarRow(winners, 88.dp, Modifier.testTag("winner-avatars").outcomeMotion(outcome, animate)) }
                }
            }
            val opponentName = personasOfSide(view.mode, view.mode.sides.first { it != view.side }, personas).firstOrNull()
            GBanner(
                if (view.mode == GameMode.INDIVIDUAL && !won && !snapshot.resigned && opponentName != null) {
                    stringResource(R.string.game_over_named_won, opponentName.shortName)
                } else {
                    stringResource(
                        if (snapshot.resigned) resignedRes(view.mode) else gameResultRes(view.mode, won = snapshot.winner == view.side),
                    )
                },
                if (won) GColors.Green else GColors.Neutral,
                GColors.White,
            )
            GPanel(Modifier.fillMaxWidth()) {
                sides.forEach { side ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AvatarRow(personasOfSide(view.mode, side, personas), 30.dp)
                        Text(
                            sideLabel(view.mode, side, view.side, personas) + ": " + plainPoints(snapshot.totals[side.index]),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
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
