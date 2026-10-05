package com.gtranca.ui.stats

import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing

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
import com.gtranca.ui.theme.GDialog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estatísticas por modo e dificuldade (jogos, vitórias, %); zerar com confirmação. */
class StatsViewModel(private val repository: StatsRepository) : ViewModel() {
    /** Estatísticas ilegíveis aparecem zeradas (nunca derrubam o app). */
    val stats: StateFlow<GameStats> = repository.stats
        .catch { emit(GameStats()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GameStats())

    fun onReset() {
        viewModelScope.launch {
            try {
                repository.reset()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Falha de disco: os números continuam os de antes.
            }
        }
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    GBackground(Modifier.fillMaxSize().testTag("stats-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.stats_title), Modifier.semantics { heading() })
            GameMode.entries.forEach { mode ->
                GPanel(Modifier.fillMaxWidth().testTag("stats-${mode.name.lowercase()}")) {
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
                    HorizontalDivider(color = GColors.Divider, thickness = 2.dp)
                    Difficulty.entries.forEach { difficulty ->
                        val line = stats.line(mode, difficulty.id)
                        StatsRow(
                            stringResource(difficulty.labelRes()),
                            listOf(line.games.toString(), line.wins.toString(), if (line.games == 0) "—" else "${line.winPercent}%"),
                            winsColor = GColors.GreenDark,
                        )
                    }
                }
            }
            Text(stringResource(R.string.stats_defeats_note), style = MaterialTheme.typography.bodySmall, color = GColors.OnTable)
            GButton(
                onClick = { confirmReset = true },
                kind = GButtonKind.DangerOutline,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stats-reset"),
            ) { Text(stringResource(R.string.stats_reset)) }
            GButton(onBack, Modifier.heightIn(min = 48.dp), kind = GButtonKind.Text) { Text(stringResource(R.string.back)) }
        }
    }
    if (confirmReset) {
        GDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_text)) },
            confirmButton = {
                TextButton(
                    {
                        confirmReset = false
                        viewModel.onReset()
                    },
                    Modifier.testTag("stats-reset-confirm"),
                    colors = ButtonDefaults.textButtonColors(contentColor = GColors.Red),
                ) { Text(stringResource(R.string.stats_reset_confirm), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatsRow(label: String, values: List<String>, bold: Boolean = false, winsColor: Color = Color.Unspecified) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f), fontWeight = weight)
        values.forEachIndexed { i, value ->
            val color = if (i == 1) winsColor else Color.Unspecified
            Text(value, Modifier.weight(1f), fontWeight = if (i == 1 && !bold) FontWeight.Bold else weight, color = color, textAlign = TextAlign.End)
        }
    }
}
