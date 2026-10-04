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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

    // Depois do fim do jogo (§13) não há mais o que desistir.
    val canResign = snapshot.stage != Stage.GAME_OVER
    when (state.endScreen) {
        null -> TableScreen(state, viewModel)
        EndScreen.ANNOUNCE_ROUND -> RoundAnnouncement(snapshot, viewModel::onEndNext, viewModel::onResign.takeIf { canResign })
        EndScreen.ROUND_POINTS ->
            RoundSummaryScreen(snapshot, viewModel::onNextRound, viewModel::onEndNext, viewModel::onResign.takeIf { canResign })
        EndScreen.ANNOUNCE_GAME -> GameAnnouncement(snapshot, viewModel::onEndNext)
        EndScreen.FINAL -> GameOverScreen(snapshot, onExit)
    }

    // §13.1 desistência: disponível na mesa, na encenação e nas telas de anúncio e de pontos.
    if (state.confirmResign) ResignDialog(viewModel::onConfirmResign, viewModel::onDismissResign)

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

/** §13.1 confirmação da desistência; cancelar volta exatamente ao estado anterior. */
@Composable
fun ResignDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("resign-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resign_title)) },
        text = { Text(stringResource(R.string.resign_text)) },
        confirmButton = { TextButton(onConfirm, Modifier.testTag("resign-confirm")) { Text(stringResource(R.string.resign_confirm)) } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("resign-cancel")) { Text(stringResource(R.string.cancel)) } },
    )
}
