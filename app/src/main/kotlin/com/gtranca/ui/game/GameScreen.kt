package com.gtranca.ui.game

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Regras da mesa do jogo em andamento (§14.4), para os textos que dependem delas. */
val LocalTableRules = androidx.compose.runtime.staticCompositionLocalOf { com.gtranca.engine.RuleSet.DEFAULT }

/** Sem coringas (§1, §6.3) não há canastra suja: os textos dizem só "Canastra". */
val com.gtranca.engine.RuleSet.noWilds: Boolean get() = maxWildsPerMeld == 0

/**
 * §16 o que a tela do jogo ganha na mesa multijogador (3.14, 3.15): o menu do anfitrião no cabeçalho, os modais e faixas
 * da mesa por cima, o resultado em moedas vindo do anfitrião e "Voltar à mesa" no fim de jogo.
 */
class MultiplayerScreenHooks(
    /** Itens do menu do anfitrião no menu de Opções (só quando este aparelho é o anfitrião). */
    val headerExtra: (@Composable (close: () -> Unit) -> Unit)?,
    val overlay: @Composable () -> Unit,
    /** Moedas ganhas (ou perdidas, negativo) no fim; `null` enquanto o anfitrião não envia. */
    val coinsNet: () -> Int?,
    val gameOverExtra: @Composable () -> Unit,
    /** Assento do anfitrião (Informações do jogo). */
    val hostSeat: () -> com.gtranca.engine.model.Seat? = { null },
    /** §16.15 qualidade da conexão com o anfitrião (1 a 3), só no convidado. */
    val hostLinkQuality: () -> Int? = { null },
    /** §16.9 a desistência foi confirmada ou cancelada no "Desistir da mesa?" (o Wi-Fi do convidado depende disso, 3.14). */
    val onResignAnswered: (confirmed: Boolean) -> Unit = {},
    /** §16, 3.14 botão de Wi-Fi da mesa multijogador. */
    val wifi: TableWifi? = null,
)

/** Tela do jogo: mesa, fim de partida (§12) ou fim de jogo (§13), conforme o estado. */
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    multiplayer: MultiplayerScreenHooks? = null,
    /** §16, docs/telas.md 3.14 botão de Wi-Fi da mesa; `null` = sem o botão. */
    wifi: TableWifi? = null,
    onExit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAnimationScale(viewModel::onAnimationScaleChanged)
    // Velocidade das animações (Perfil ou botão da mesa): vale já na jogada seguinte.
    val slowAnimations = com.gtranca.ui.speed.LocalSpeedSettings.current.slow
    androidx.compose.runtime.LaunchedEffect(slowAnimations) { viewModel.onSpeedChanged(slowAnimations) }
    val snapshot = state.snapshot
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            snapshot.stage == Stage.GAME_OVER -> onExit()
            // §16.9 no multijogador, Voltar é a desistência (mesma confirmação da bandeira).
            multiplayer != null -> viewModel.onResign()
            else -> confirmExit = true
        }
    }

    // §11.1 animação da batida sobre a mesa final: uma vez por partida, 2 s ou até um toque (docs/telas.md 3.9).
    var goOutSeen by rememberSaveable { mutableStateOf(0) }
    val finishedRounds = snapshot.history.size
    // §14.4 regras da mesa para os textos (sem coringa, §1: só "Canastra", sem limpa/suja).
    androidx.compose.runtime.CompositionLocalProvider(LocalTableRules provides snapshot.view.rules, LocalTableHeaderBottom provides androidx.compose.runtime.remember { mutableStateOf(0f) }) {
    if (state.boosterSelectorPending) {
        // §17.3 antes de cada partida: o seletor de boosters, sem a distribuição à vista (docs/telas.md 3.19).
        viewModel.boosterInventory?.let { inventory ->
            val round = state.boosterSelectorRound.takeIf { it != 0 } ?: snapshot.roundNumber
            com.gtranca.ui.boosters.BoosterSelectorScreen(
                androidx.lifecycle.viewmodel.compose.viewModel(key = "boosters-selector-$round") { com.gtranca.ui.boosters.BoosterSelectorViewModel(inventory) },
                viewModel::onBoostersConfirmed,
            )
        }
    }
    else if (state.endScreen == EndScreen.FINAL_TABLE) {
        // docs/telas.md 3.9, 3.12 a mesa final, só para olhar: o banner de fim de partida grande por 2 s e, depois,
        // minimizado junto do "Seguir", que vai direto aos pontos da partida. Numa caixa só, com a animação por cima.
        val end = roundEnd(snapshot)
        val showing = end != null && goOutSeen < finishedRounds
        // Tremor da mesa quando a última carta chega (junto com os fogos); só a mesa treme, não o banner. Sem animação, sem tremor.
        val shake = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        val shakes = showing && state.animationMillis > 0 && end != RoundEnd.NO_WINNER
        androidx.compose.runtime.LaunchedEffect(shakes) {
            if (shakes) shake.animateTo(1f, androidx.compose.animation.core.tween(GoOutDrama.SHAKE_MILLIS, easing = androidx.compose.animation.core.LinearEasing))
        }
        val shakeReach = androidx.compose.ui.platform.LocalDensity.current.density * 5f
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val (x, y, r) = GoOutDrama.shake(shake.value)
                    translationX = x * shakeReach
                    translationY = y * shakeReach
                    rotationZ = r * 0.4f
                },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalTableWifi provides (wifi ?: multiplayer?.wifi)) {
                    FinalTableReview(
                        state, viewModel, viewModel::onEndNext,
                        banner = if (end != null && !showing) {
                            { RoundEndBanner(end, snapshot.view.mode, compact = true, Modifier.fillMaxWidth()) }
                        } else null,
                    )
                }
            }
            if (showing) RoundEndOverlay(end!!, snapshot.view.mode, state.animationMillis > 0, onDone = { goOutSeen = finishedRounds })
        }
    }
    else if (state.endScreen != null) {
        // Telas de resultado: a faixa do saldo no topo (docs/telas.md 0), como fora do jogo.
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            com.gtranca.ui.theme.CoinBalanceBar(Modifier.statusBarsPadding())
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)) {
                EndScreens(state, viewModel, snapshot, multiplayer, onExit)
            }
        }
    }
    else androidx.compose.runtime.CompositionLocalProvider(LocalTableWifi provides (wifi ?: multiplayer?.wifi)) {
        TableScreen(state, viewModel, headerExtra = multiplayer?.headerExtra, hostSeat = multiplayer?.hostSeat?.invoke(), hostLinkQuality = multiplayer?.hostLinkQuality?.invoke())
        // §17.8 a reação enviada por qualquer assento aparece no meio da tela, animada.
        ReactionCenterOverlay(state.lastReaction, snapshot.viewerSeat, snapshot.view.mode.seatCount)
    }
    }

    // §13.1 desistência: disponível na mesa, na encenação e na tela de anúncio (não na de pontos, decisão do usuário).
    if (state.confirmResign) {
        if (multiplayer != null) {
            // §16.9 "Desistir da mesa?": um robô não assume na hora; a aposta se perde e o jogo segue para os demais.
            GMessage(
                modifier = Modifier.testTag("resign-dialog"),
                title = stringResource(R.string.mp_resign_title),
                text = stringResource(if (snapshot.view.mode.seatCount == 2) R.string.mp_resign_text_duel else R.string.mp_resign_text),
                illustration = MessageIllustration.Flag,
                tone = MessageTone.Danger,
                confirmText = stringResource(R.string.resign_confirm),
                onConfirm = { viewModel.onConfirmResign(); multiplayer.onResignAnswered(true) },
                confirmTag = "resign-confirm",
                dismissText = stringResource(R.string.cancel),
                onDismiss = { viewModel.onDismissResign(); multiplayer.onResignAnswered(false) },
                dismissTag = "resign-cancel",
                onDismissRequest = { viewModel.onDismissResign(); multiplayer.onResignAnswered(false) },
                content = { ResignSummary(snapshot, state.personas) },
            )
        } else {
            ResignDialog(viewModel::onConfirmResign, viewModel::onDismissResign) { ResignSummary(snapshot, state.personas) }
        }
    }
    multiplayer?.overlay?.invoke()

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

/** Mesa final só para olhar; "Seguir" fica no lugar da barra de ações e vai aos pontos da partida. */
@Composable
private fun FinalTableReview(state: GameUiState, events: TableEvents, onContinue: () -> Unit, banner: (@Composable () -> Unit)? = null) {
    BackHandler(onBack = onContinue)
    TableScreen(state, events, onReviewContinue = onContinue, reviewBanner = banner)
}

/** §13.1 confirmação da desistência; cancelar volta exatamente ao estado anterior. */
@Composable
fun ResignDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, content: (@Composable () -> Unit)? = null) {
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
        content = content,
    )
}

/**
 * §13.1, §14.6 o que está em jogo ao desistir (docs/telas.md 4): a aposta de cada jogador, o total apostado na mesa, quanto o
 * jogador perde (e que deixa de concorrer ao prêmio) e o placar de cada lado com os nomes de quem está nele. Só dados
 * públicos já na tela (no multijogador, os recebidos).
 */
@Composable
fun ResignSummary(snapshot: com.gtranca.game.GameSnapshot, personas: List<com.gtranca.game.Persona>) {
    val stakes = ResignStakes.of(snapshot.config.stake, snapshot.view.mode.seatCount)
    val view = snapshot.view
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().testTag("resign-summary"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        if (stakes == null) {
            Text(stringResource(R.string.resign_no_stake), style = style, color = com.gtranca.ui.theme.GColors.Champagne, modifier = Modifier.testTag("resign-stakes"))
        } else {
            Text(
                stringResource(R.string.resign_stakes, stakes.stake, stakes.total, stakes.prize),
                style = style,
                color = com.gtranca.ui.theme.GColors.Gold,
                modifier = Modifier.testTag("resign-stakes"),
            )
            Text(stringResource(R.string.resign_you_lose, stakes.stake), style = style, color = com.gtranca.ui.theme.GColors.Ruby)
        }
        Text(stringResource(R.string.resign_scores), style = MaterialTheme.typography.titleSmall, color = com.gtranca.ui.theme.GColors.Champagne)
        val you = stringResource(R.string.side_you)
        view.mode.sides.sortedBy { it != view.side }.forEach { side ->
            val names = view.mode.seats.filter { view.mode.sideOf(it) == side }.joinToString(", ") { seat ->
                if (seat == snapshot.viewerSeat) you else personas.getOrNull(seat.index)?.shortName.orEmpty()
            }
            Text(
                "${shortSideName(view.mode, side, view.side)} ($names): ${plainPoints(snapshot.totals.getOrElse(side.index) { 0 })}",
                style = style,
                color = com.gtranca.ui.theme.GColors.White,
                modifier = Modifier.testTag("resign-score-${side.index}"),
            )
        }
    }
}

/**
 * §14.6 apostas em jogo numa mesa em que todos apostam [stake]: o [total] apostado e o [prize] que cada vencedor recebe
 * (a própria aposta mais a parte das perdidas: 2 × aposta, no individual e em duplas). `null` sem aposta.
 */
data class ResignStakes(val stake: Int, val total: Int, val prize: Int) {
    companion object {
        fun of(stake: Int, seatCount: Int): ResignStakes? =
            if (stake <= 0) null else ResignStakes(stake, stake * seatCount, stake * 2)
    }
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

/** Telas de resultado (pontos, fim de jogo), conforme a etapa. */
@Composable
private fun EndScreens(
    state: GameUiState,
    viewModel: GameViewModel,
    snapshot: com.gtranca.game.GameSnapshot,
    multiplayer: MultiplayerScreenHooks?,
    onExit: () -> Unit,
) {
    when (state.endScreen) {
        null, EndScreen.FINAL_TABLE -> Unit
        EndScreen.ROUND_POINTS ->
            RoundSummaryScreen(snapshot, viewModel::onNextRound, viewModel::onEndNext, state.personas)
        EndScreen.FINAL -> GameOverScreen(
            snapshot, onExit, state.personas, state.animationMillis > 0,
            multiplayer = multiplayer != null,
            multiplayerNet = multiplayer?.coinsNet?.invoke(),
            extra = multiplayer?.gameOverExtra,
        )
    }
}
