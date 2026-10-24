package com.gtranca.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Persona
import com.gtranca.game.SeatRole
import com.gtranca.ui.home.labelRes
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.sideLabel
import com.gtranca.ui.tables.RulesSummary
import com.gtranca.ui.tables.modeLabel
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.TabularNums

/** Uma partida já pontuada na tabela do jogo: o total de cada lado nela, quem bateu e o acumulado depois dela. */
data class MatchRoundRow(val number: Int, val totals: List<Int>, val wentOut: Seat?, val cumulative: List<Int>)

/** §12, §13 tabela partida a partida do jogo, a partir do histórico público (o mesmo que o convidado recebe). */
object MatchInfo {
    fun rows(history: List<RoundRecord>): List<MatchRoundRow> {
        var running: List<Int>? = null
        return history.map { record ->
            val totals = record.scores.sortedBy { it.side.index }.map { it.total }
            running = running?.zip(totals) { a, b -> a + b } ?: totals
            MatchRoundRow(record.number, totals, (record.result as? RoundResult.GoOut)?.seat, running!!)
        }
    }
}

/**
 * Informações do jogo (docs/telas.md 4): regras, modo, nível, alvo, aposta e prêmio, quem joga em cada lado (e o
 * anfitrião no multijogador), a partida atual e quem a começou, e a tabela das partidas. Só informação pública.
 */
@Composable
fun MatchInfoDialog(snapshot: GameSnapshot, personas: List<Persona>, hostSeat: Seat?, onClose: () -> Unit) {
    val view = snapshot.view
    val config = snapshot.config
    val mode = view.mode
    val sides = listOf(view.side) + mode.sides.filter { it != view.side }
    GDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("match-info"),
        title = { Text(stringResource(R.string.match_info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section(stringResource(R.string.match_info_game))
                Text(stringResource(R.string.match_info_mode, modeLabel(mode), stringResource(config.difficulty.labelRes()), config.targetScore))
                val stakes = ResignStakes.of(config.stake, mode.seatCount)
                Text(
                    if (stakes == null) stringResource(R.string.resign_no_stake)
                    else stringResource(R.string.match_info_stake, stakes.stake, stakes.prize),
                    color = GColors.Gold,
                )
                RulesSummary(view.rules, config.targetScore)

                Section(stringResource(R.string.match_info_sides))
                sides.forEach { side ->
                    Text(sideLabel(mode, side, view.side, personas), fontWeight = FontWeight.Bold, color = GColors.Champagne)
                    mode.seats.filter { mode.sideOf(it) == side }.forEach { seat ->
                        val persona = personas.getOrNull(seat.index)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            persona?.let { PersonaAvatar(it, 28.dp) }
                            val kind = stringResource(if (persona?.character != null) R.string.match_info_robot else R.string.match_info_human)
                            val host = if (seat == hostSeat) " · " + stringResource(R.string.match_info_host) else ""
                            val name = if (seat == snapshot.viewerSeat) stringResource(R.string.side_you) else persona?.fullName.orEmpty()
                            Text("$name ($kind$host)", Modifier.testTag("match-info-seat-${seat.index}"))
                        }
                    }
                }

                Section(stringResource(R.string.match_info_current))
                val starter = if (view.firstSeat == snapshot.viewerSeat) stringResource(R.string.side_you)
                else personas.getOrNull(view.firstSeat.index)?.fullName ?: stringResource(SeatRole.of(mode, view.firstSeat, snapshot.viewerSeat).nameRes())
                Text(stringResource(R.string.match_info_round, snapshot.roundNumber, starter))

                Section(stringResource(R.string.match_info_rounds))
                val rows = MatchInfo.rows(snapshot.history)
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.match_info_no_rounds), color = GColors.Steel)
                } else {
                    Column(Modifier.fillMaxWidth().testTag("match-info-rounds")) {
                        val numbers = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TabularNums, textAlign = TextAlign.End)
                        Row(Modifier.fillMaxWidth()) {
                            Text("#", Modifier.weight(0.4f), fontWeight = FontWeight.Bold)
                            sides.forEach { side -> Text(sideLabel(mode, side, view.side, personas), Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1) }
                            Text(stringResource(R.string.match_info_went_out), Modifier.weight(1.1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
                        }
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().testTag("match-info-round-${row.number}")) {
                                Text(row.number.toString(), Modifier.weight(0.4f))
                                sides.forEach { side -> Text(signed(row.totals[side.index]), Modifier.weight(1f), style = numbers) }
                                val who = row.wentOut?.let { seat ->
                                    if (seat == snapshot.viewerSeat) stringResource(R.string.side_you) else personas.getOrNull(seat.index)?.shortName.orEmpty()
                                } ?: stringResource(R.string.match_info_no_go_out)
                                Text(who, Modifier.weight(1.1f), textAlign = TextAlign.End, maxLines = 1)
                            }
                        }
                        HorizontalDivider(color = GColors.Divider)
                        Row(Modifier.fillMaxWidth().testTag("match-info-cumulative")) {
                            Text("", Modifier.weight(0.4f))
                            sides.forEach { side -> Text(plainPoints(snapshot.totals.getOrElse(side.index) { 0 }), Modifier.weight(1f), style = numbers, fontWeight = FontWeight.Bold) }
                            Text(stringResource(R.string.score_cumulative), Modifier.weight(1.1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClose, Modifier.heightIn(min = 48.dp).testTag("match-info-close")) { Text(stringResource(R.string.match_info_close)) }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = GColors.Gold)
}
