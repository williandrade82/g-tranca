package com.gtranca.ui.multiplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.ai.Difficulty
import com.gtranca.game.BotLevel
import com.gtranca.game.Persona
import com.gtranca.net.RecruitStatus
import com.gtranca.ui.persona.LevelStars
import com.gtranca.ui.persona.labelRes
import com.gtranca.ui.selection.SeatTable
import com.gtranca.ui.tables.BottomButton
import com.gtranca.ui.tables.RulesDetailsDialog
import com.gtranca.ui.tables.TableFrame
import com.gtranca.ui.tables.modeLabel
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.Spacing

/**
 * §16.2 *Mesa do recrutado* (docs/telas.md 3.13): a mesa do recrutador, **só para leitura** e ao vivo, para quem aceitou o
 * convite. Lê só do [joined] (nunca do estado local do jogador): lugares (o recrutado se vê embaixo), aposta, tipo, nível,
 * alvo e regras. Sem lista de jogadores próximos, sem rotação nem confirmação. "Sair da mesa" libera o lugar.
 */
@Composable
fun RecruitedTableScreen(joined: RecruitStatus.Joined, self: Persona, photos: Map<String, ByteArray>, onLeave: () -> Unit) {
    BackHandler(onBack = onLeave)
    val table = joined.table
    val rules = table.rules
    val level = BotLevel.of(Difficulty.fromId(rules.difficulty))
    var showDetails by rememberSaveable { mutableStateOf(false) }
    TableFrame(
        stringResource(R.string.tables_host, table.hostName),
        "recruited-table",
        bottom = {
            BottomButton(stringResource(R.string.recruit_leave), "recruited-leave", GButtonKind.Secondary, onClick = onLeave)
        },
    ) {
        SeatTable(
            table.layout, viewerSeat = joined.you, level = level, self = self, hostName = table.hostName, photos = photos,
            modifier = Modifier.fillMaxWidth(),
            tagPrefix = "recruited-seat",
        )
        // O resumo da montagem do recrutador (só leitura).
        GPanel(Modifier.fillMaxWidth().testTag("recruited-summary")) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(modeLabel(table.layout.mode), style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
                Text("·", color = GColors.Steel)
                LevelStars(level)
                Text(stringResource(level.labelRes()), style = MaterialTheme.typography.titleSmall, color = GColors.Gold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.coin_bag_small), null, Modifier.size(20.dp))
                Text(stringResource(R.string.stake_line, rules.stake), style = MaterialTheme.typography.titleMedium, color = GColors.Gold)
            }
            Text(stringResource(R.string.rule_target_value, com.gtranca.ui.game.plainPoints(rules.targetScore)), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
            Text(rules.rulesTitle.ifBlank { rules.rules.name }, style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
            TextButton(
                { showDetails = true },
                Modifier.heightIn(min = 48.dp).testTag("recruited-rules-details"),
            ) { Text(stringResource(R.string.rules_view_details), color = GColors.Gold) }
        }
        Text(
            stringResource(R.string.recruited_waiting),
            Modifier.fillMaxWidth().testTag("recruited-waiting"),
            style = MaterialTheme.typography.bodyMedium,
            color = GColors.Steel,
            textAlign = TextAlign.Center,
        )
    }
    if (showDetails) RulesDetailsDialog(rules.rulesTitle.ifBlank { rules.rules.name }, rules.rules, rules.targetScore) { showDetails = false }
}
