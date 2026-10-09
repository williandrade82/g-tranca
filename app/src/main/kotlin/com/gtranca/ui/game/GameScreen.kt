package com.gtranca.ui.game

import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.gtranca.ui.theme.GButton

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
    // Revisão da mesa final, aberta pelo anúncio; "Continuar" volta ao anúncio e segue o fluxo de fim.
    var reviewingTable by rememberSaveable(state.endScreen) { mutableStateOf(false) }
    val review = { reviewingTable = true }
    if (reviewingTable) FinalTableReview(state, viewModel) { reviewingTable = false }
    else when (state.endScreen) {
        null -> TableScreen(state, viewModel)
        EndScreen.ANNOUNCE_ROUND -> RoundAnnouncement(snapshot, viewModel::onEndNext, viewModel::onResign.takeIf { canResign }, state.personas, state.animationMillis > 0, review)
        EndScreen.ROUND_POINTS ->
            RoundSummaryScreen(snapshot, viewModel::onNextRound, viewModel::onEndNext, state.personas)
        EndScreen.ANNOUNCE_GAME -> GameAnnouncement(snapshot, viewModel::onEndNext, state.personas, state.animationMillis > 0, review)
        EndScreen.FINAL -> GameOverScreen(snapshot, onExit, state.personas, state.animationMillis > 0)
    }

    // §13.1 desistência: disponível na mesa, na encenação e na tela de anúncio (não na de pontos, decisão do usuário).
    if (state.confirmResign) ResignDialog(viewModel::onConfirmResign, viewModel::onDismissResign)

    if (confirmExit) {
        GMessage(
            title = stringResource(R.string.exit_title),
            text = stringResource(R.string.exit_text),
            illustration = MessageIllustration.Exit,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.exit_confirm),
            onConfirm = {
                confirmExit = false
                onExit()
            },
            dismissText = stringResource(R.string.cancel),
            onDismissRequest = { confirmExit = false },
        )
    }
}

/** Mesa final só para olhar, com um botão fixo para seguir o fluxo de fim da partida. */
@Composable
private fun FinalTableReview(state: GameUiState, events: TableEvents, onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    Box(Modifier.fillMaxSize()) {
        TableScreen(state, events)
        GButton(
            onContinue,
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp).heightIn(min = 48.dp).testTag("review-continue"),
        ) { Text(stringResource(R.string.table_review_continue)) }
    }
}

/** §13.1 confirmação da desistência; cancelar volta exatamente ao estado anterior. */
@Composable
fun ResignDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GMessage(
        modifier = Modifier.testTag("resign-dialog"),
        title = stringResource(R.string.resign_title),
        text = stringResource(R.string.resign_text),
        illustration = MessageIllustration.Flag,
        tone = MessageTone.Danger,
        confirmText = stringResource(R.string.resign_confirm),
        onConfirm = onConfirm,
        confirmTag = "resign-confirm",
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        dismissTag = "resign-cancel",
        onDismissRequest = onDismiss,
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
