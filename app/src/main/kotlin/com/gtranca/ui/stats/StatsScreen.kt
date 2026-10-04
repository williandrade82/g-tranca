package com.gtranca.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gtranca.R
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameStats
import com.gtranca.data.StatsRepository
import com.gtranca.engine.model.GameMode
import com.gtranca.ui.home.labelRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estatísticas por modo e dificuldade (jogos, vitórias, %); zerar com confirmação. */
class StatsViewModel(private val repository: StatsRepository) : ViewModel() {
    val stats: StateFlow<GameStats> = repository.stats.stateIn(viewModelScope, SharingStarted.Eagerly, GameStats())

    fun onReset() {
        viewModelScope.launch { repository.reset() }
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag("stats-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            GameMode.entries.forEach { mode ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .testTag("stats-${mode.name.lowercase()}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(if (mode == GameMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    StatsRow(
                        stringResource(R.string.stats_difficulty),
                        listOf(stringResource(R.string.stats_games), stringResource(R.string.stats_wins), stringResource(R.string.stats_percent)),
                        bold = true,
                    )
                    HorizontalDivider()
                    Difficulty.entries.forEach { difficulty ->
                        val line = stats.line(mode, difficulty.id)
                        StatsRow(
                            stringResource(difficulty.labelRes()),
                            listOf(line.games.toString(), line.wins.toString(), if (line.games == 0) "—" else "${line.winPercent}%"),
                        )
                    }
                }
            }
            Text(stringResource(R.string.stats_defeats_note), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stats-reset"),
            ) { Text(stringResource(R.string.stats_reset)) }
            TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_text)) },
            confirmButton = {
                TextButton({
                    confirmReset = false
                    viewModel.onReset()
                }, Modifier.testTag("stats-reset-confirm")) { Text(stringResource(R.string.stats_reset_confirm)) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatsRow(label: String, values: List<String>, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f), fontWeight = weight)
        values.forEach { Text(it, Modifier.weight(1f), fontWeight = weight, textAlign = TextAlign.End) }
    }
}
