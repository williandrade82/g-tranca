package com.gtranca.ui.game

import androidx.activity.compose.BackHandler
import com.gtranca.ui.theme.GDialog
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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.gtranca.R
import com.gtranca.game.Stage

/** Tela do jogo: mesa, fim de partida (§12) ou fim de jogo (§13), conforme o estado. */
@Composable
fun GameScreen(viewModel: GameViewModel, onExit: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAnimationScale(viewModel::onAnimationScaleChanged)
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
            RoundSummaryScreen(snapshot, viewModel::onNextRound, viewModel::onEndNext)
        EndScreen.ANNOUNCE_GAME -> GameAnnouncement(snapshot, viewModel::onEndNext)
        EndScreen.FINAL -> GameOverScreen(snapshot, onExit)
    }

    // §13.1 desistência: disponível na mesa, na encenação e na tela de anúncio (não na de pontos, decisão do usuário).
    if (state.confirmResign) ResignDialog(viewModel::onConfirmResign, viewModel::onDismissResign)

    if (confirmExit) {
        GDialog(
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
    GDialog(
        modifier = Modifier.testTag("resign-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resign_title)) },
        text = { Text(stringResource(R.string.resign_text)) },
        confirmButton = { TextButton(onConfirm, Modifier.testTag("resign-confirm")) { Text(stringResource(R.string.resign_confirm)) } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("resign-cancel")) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Observa a escala de duração das animações do sistema (`Settings.Global.ANIMATOR_DURATION_SCALE`, "remover
 * animações" = 0) e avisa [onChange] na entrada e a cada mudança, enquanto a tela estiver na composição.
 */
@Composable
private fun ObserveAnimationScale(onChange: (Float) -> Unit) {
    val context = LocalContext.current
    val current by rememberUpdatedState(onChange)
    DisposableEffect(context) {
        val resolver = context.contentResolver
        fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        current(read())
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = current(read())
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
}
