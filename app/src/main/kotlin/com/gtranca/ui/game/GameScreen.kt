package com.gtranca.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.game.Stage

/** Tela do jogo: mesa, fim de partida (§12) ou fim de jogo (§13), conforme o estado. */
@Composable
fun GameScreen(viewModel: GameViewModel, onExit: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        if (snapshot.stage == Stage.GAME_OVER) onExit() else confirmExit = true
    }

    when {
        snapshot.stage == Stage.GAME_OVER && state.showFinalResult -> GameOverScreen(snapshot, onExit)
        snapshot.stage != Stage.PLAYING -> RoundSummaryScreen(snapshot, viewModel::onNextRound, viewModel::onShowFinalResult)
        else -> TableScreen(state, viewModel)
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_text)) },
            confirmButton = {
                TextButton({
                    confirmExit = false
                    onExit()
                }) { Text(stringResource(R.string.exit_confirm)) }
            },
            dismissButton = { TextButton({ confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
