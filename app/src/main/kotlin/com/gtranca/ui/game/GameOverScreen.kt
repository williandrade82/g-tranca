package com.gtranca.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextAlign
import com.gtranca.ui.theme.GoldBrush
import com.gtranca.ui.theme.TabularNums
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
    GBackground(Modifier.fillMaxSize().testTag("game-over"), royal = true, rays = won && animate) {
        // Vitória: confete dourado fino por alguns segundos; derrota: discreta.
        EndCelebration(outcome, brief = won, enabled = animate, playSound = snapshot.resigned)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.game_over_title))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutcomeRibbon(stringResource(if (won) R.string.outcome_victory else R.string.outcome_defeat), won, animate, Modifier.testTag("outcome-ribbon"))
            }
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
                if (won) GColors.RoyalEnd else GColors.Bordeaux,
                GColors.Ivory,
            )
            GPanel(Modifier.fillMaxWidth().testTag("game-over-table")) {
                ResultsTable(snapshot, sides, view.mode, view.side, personas)
            }
            GButton(onBackToHome, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("back-home")) {
                Text(stringResource(R.string.back_to_home))
            }
        }
    }
}

/**
 * Tabela do resultado: uma coluna por lado (avatares e nome curto), uma linha por partida e o total em cápsula.
 * Em cada linha, o maior valor fica em destaque (negrito e ouro); o vencedor do jogo leva a coroa ★ no cabeçalho
 * e a cápsula dourada no total (forma e texto, não só cor).
 */
@Composable
private fun ResultsTable(
    snapshot: GameSnapshot,
    sides: List<com.gtranca.engine.model.Side>,
    mode: GameMode,
    viewerSide: com.gtranca.engine.model.Side,
    personas: List<Persona>,
) {
    val numbers = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularNums)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Cabeçalho.
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.xs), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.game_over_col_round), Modifier.weight(0.9f), style = MaterialTheme.typography.labelLarge, color = GColors.Lavender)
            sides.forEach { side ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    AvatarRow(personasOfSide(mode, side, personas), 26.dp)
                    val winner = side == snapshot.winner
                    Text(
                        (if (winner) "★ " else "") + shortSideName(mode, side, viewerSide),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (winner) GColors.Gold else GColors.Ivory,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(color = GColors.Divider, thickness = 1.dp)
        // Uma linha por partida, em faixas alternadas.
        snapshot.history.forEachIndexed { index, record ->
            val values = sides.map { side -> record.scores.first { it.side == side }.total }
            val best = values.max()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 0) GColors.IndigoLight.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(record.number.toString(), Modifier.weight(0.9f), style = numbers, color = GColors.Lavender)
                values.forEach { v ->
                    val top = v == best && values.count { it == best } == 1
                    Text(
                        signed(v),
                        Modifier.weight(1f),
                        style = numbers,
                        fontWeight = if (top) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            top -> GColors.Gold
                            v < 0 -> GColors.Ruby.copy(alpha = 0.95f).let { androidx.compose.ui.graphics.lerp(it, GColors.Ivory, 0.35f) }
                            else -> GColors.Ivory
                        },
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        HorizontalDivider(color = GColors.Divider, thickness = 1.dp, modifier = Modifier.padding(top = Spacing.xs))
        // Total em cápsula: dourada para o vencedor.
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.game_over_col_total), Modifier.weight(0.9f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GColors.Gold)
            sides.forEach { side ->
                val winner = side == snapshot.winner
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        plainPoints(snapshot.totals[side.index]),
                        Modifier
                            .background(if (winner) GoldBrush else androidx.compose.ui.graphics.SolidColor(GColors.IndigoLight), CircleShape)
                            .border(1.dp, if (winner) GColors.Champagne else GColors.Lavender.copy(alpha = 0.5f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        style = numbers,
                        fontWeight = FontWeight.Bold,
                        color = if (winner) GColors.OnGold else GColors.Ivory,
                    )
                }
            }
        }
    }
}
