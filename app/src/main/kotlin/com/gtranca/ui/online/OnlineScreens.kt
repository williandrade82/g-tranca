package com.gtranca.ui.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.ui.tables.BottomButton
import com.gtranca.ui.tables.TableFrame
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GChoiceChip
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GSubPanel
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
import com.gtranca.ui.theme.Spacing

@StringRes
fun OnlineProblem.textRes(): Int = when (this) {
    OnlineProblem.NETWORK -> R.string.online_problem_network
    OnlineProblem.UPDATE_APP -> R.string.online_problem_update_app
    OnlineProblem.MAINTENANCE -> R.string.online_problem_maintenance
    OnlineProblem.UNAUTHENTICATED -> R.string.online_problem_unauthenticated
    OnlineProblem.TABLE_NOT_FOUND -> R.string.online_problem_table_not_found
    OnlineProblem.TABLE_FULL -> R.string.online_problem_table_full
    OnlineProblem.ALREADY_STARTED -> R.string.online_problem_already_started
    OnlineProblem.NOT_HOST -> R.string.online_problem_not_host
    OnlineProblem.EMPTY_SEATS -> R.string.online_problem_empty_seats
    OnlineProblem.INVALID_SEAT -> R.string.online_problem_invalid_seat
    OnlineProblem.INVALID_REQUEST -> R.string.online_problem_invalid_request
    OnlineProblem.RATE_LIMIT -> R.string.online_problem_rate_limit
    OnlineProblem.GAME_UNAVAILABLE -> R.string.online_problem_game_unavailable
    OnlineProblem.INSUFFICIENT_BALANCE -> R.string.online_problem_insufficient_balance
    OnlineProblem.INVALID_ID -> R.string.online_problem_invalid_id
    OnlineProblem.INVALID_TARGET -> R.string.online_problem_invalid_target
    OnlineProblem.UNEXPECTED -> R.string.online_problem_unexpected
}

@Composable
private fun ProblemText(problem: OnlineProblem, tag: String, modifier: Modifier = Modifier) {
    Text(
        stringResource(problem.textRes()),
        modifier.fillMaxWidth().testTag(tag),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = GColors.Ruby,
        textAlign = TextAlign.Center,
    )
}

// ---------- 3.21 Jogar online ----------

/** Porta do online (docs/telas.md 3.21). Etapa A: convidado provisório; o Google fica "em breve". */
@Composable
fun OnlineHomeScreen(
    viewModel: OnlineHomeViewModel,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onProfile: () -> Unit,
    onBack: () -> Unit,
    /** Aviso de volta de uma sala (mesa encerrada, removido...). */
    notice: OnlineRoomEnd? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TableFrame(
        stringResource(R.string.online_title),
        "online-home",
        bottom = { BottomButton(stringResource(R.string.back), "online-back", GButtonKind.Secondary, onClick = onBack) },
    ) {
        notice?.let { GSubPanel { Text(stringResource(it.textRes()), Modifier.testTag("online-notice"), style = MaterialTheme.typography.bodyLarge, color = GColors.Champagne) } }
        when (state.status) {
            OnlineHomeStatus.LOADING -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GColors.Gold, modifier = Modifier.testTag("online-loading"))
            }
            OnlineHomeStatus.NO_SERVER -> GPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.online_no_server), Modifier.testTag("online-no-server"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                GButton(onProfile, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("online-go-profile")) {
                    Text(stringResource(R.string.online_go_profile), style = MaterialTheme.typography.titleMedium)
                }
            }
            OnlineHomeStatus.ERROR -> GPanel(Modifier.fillMaxWidth()) {
                ProblemText(state.problem ?: OnlineProblem.UNEXPECTED, "online-error")
                GButton(viewModel::retry, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("online-retry")) {
                    Text(stringResource(R.string.online_retry), style = MaterialTheme.typography.titleMedium)
                }
                GButton(onProfile, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-go-profile"), kind = GButtonKind.Secondary) {
                    Text(stringResource(R.string.online_go_profile))
                }
            }
            OnlineHomeStatus.READY -> GPanel(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.online_playing_as, state.name),
                    Modifier.testTag("online-guest-name"),
                    style = MaterialTheme.typography.titleMedium,
                    color = GColors.Gold,
                )
                GButton(onCreate, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("online-create")) {
                    Text(stringResource(R.string.online_create), style = MaterialTheme.typography.titleMedium)
                }
                GButton(onJoin, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("online-join"), kind = GButtonKind.Secondary) {
                    Text(stringResource(R.string.online_join), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        // O login Google entra na fase 2 (identity-service): por ora o botão aparece desabilitado.
        if (state.status == OnlineHomeStatus.READY) {
            GButton({}, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-login"), enabled = false, kind = GButtonKind.Quiet) {
                Text(stringResource(R.string.online_google_soon))
            }
        }
    }
}

@StringRes
private fun OnlineRoomEnd.textRes(): Int = when (this) {
    OnlineRoomEnd.CLOSED -> R.string.online_ended_closed
    OnlineRoomEnd.NOT_FOUND -> R.string.online_ended_not_found
    OnlineRoomEnd.REMOVED -> R.string.online_ended_removed
    OnlineRoomEnd.LEFT -> R.string.online_ended_left
}

// ---------- 3.23 Criar mesa online ----------

@Composable
private fun OnlineSection(title: String, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { chips() }
    }
}

@Composable
private fun OnlineChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    GChoiceChip(text, selected, onClick, Modifier.testTag(tag))
}

/** Criar mesa online (docs/telas.md 3.23): as opções da mesa e os critérios online (§18.4). */
@Composable
fun OnlineCreateScreen(viewModel: OnlineCreateViewModel, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) {
        state.createdId?.let {
            viewModel.onCreatedHandled()
            onCreated(it)
        }
    }
    TableFrame(
        stringResource(R.string.online_create_title),
        "online-create-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "online-create-back", GButtonKind.Secondary, weight = 0.7f, onClick = onBack)
            BottomButton(stringResource(R.string.online_create), "online-table-create", GButtonKind.Primary, enabled = state.canCreate, weight = 1.3f, onClick = viewModel::onCreate)
        },
    ) {
        GPanel(Modifier.fillMaxWidth()) {
            OnlineSection(stringResource(R.string.home_mode)) {
                OnlineChip(stringResource(R.string.mode_individual), state.mode == GameMode.INDIVIDUAL, "online-mode-individual") { viewModel.onMode(GameMode.INDIVIDUAL) }
                OnlineChip(stringResource(R.string.mode_duplas_short), state.mode == GameMode.DUPLAS, "online-mode-duplas") { viewModel.onMode(GameMode.DUPLAS) }
            }
            OnlineSection(stringResource(R.string.create_rules)) {
                OnlineChip(com.gtranca.engine.RuleSet.CLASSICA.name, state.rules == OnlineRulesChoice.CLASSICA, "online-rules-classic") { viewModel.onRules(OnlineRulesChoice.CLASSICA) }
                OnlineChip(com.gtranca.engine.RuleSet.PAULISTA.name, state.rules == OnlineRulesChoice.PAULISTA, "online-rules-paulista") { viewModel.onRules(OnlineRulesChoice.PAULISTA) }
            }
            OnlineSection(stringResource(R.string.create_skill_boosters)) {
                OnlineChip(stringResource(R.string.skill_boosters_on), state.skillBoosters, "online-skill-on") { viewModel.onSkillBoosters(true) }
                OnlineChip(stringResource(R.string.skill_boosters_off), !state.skillBoosters, "online-skill-off") { viewModel.onSkillBoosters(false) }
            }
            OutlinedTextField(
                value = state.targetText,
                onValueChange = viewModel::onTarget,
                label = { Text(stringResource(R.string.home_target)) },
                isError = state.target == null,
                supportingText = if (state.target == null) {
                    { Text(stringResource(R.string.home_target_error)) }
                } else {
                    null
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("online-target"),
            )
            Text(stringResource(R.string.online_bet_none), Modifier.testTag("online-bet-none"), style = MaterialTheme.typography.bodySmall, color = GColors.Steel)
        }
        GPanel(Modifier.fillMaxWidth()) {
            OnlineSection(stringResource(R.string.online_time_limit)) {
                OnlineChip(stringResource(R.string.online_time_indefinite), state.tempo == TempoDeJogada.INDETERMINADO, "online-time-indefinite") { viewModel.onTempo(TempoDeJogada.INDETERMINADO) }
                OnlineChip(stringResource(R.string.online_time_1), state.tempo == TempoDeJogada.UM_MINUTO, "online-time-1") { viewModel.onTempo(TempoDeJogada.UM_MINUTO) }
                OnlineChip(stringResource(R.string.online_time_5), state.tempo == TempoDeJogada.CINCO_MINUTOS, "online-time-5") { viewModel.onTempo(TempoDeJogada.CINCO_MINUTOS) }
            }
            OnlineSection(stringResource(R.string.online_penalty)) {
                OnlineChip(stringResource(R.string.online_penalty_none), state.punicao == Punicao.SEM_PUNICAO, "online-penalty-none") { viewModel.onPunicao(Punicao.SEM_PUNICAO) }
                OnlineChip(stringResource(R.string.online_penalty_bet), state.punicao == Punicao.PERDE_APOSTA, "online-penalty-bet") { viewModel.onPunicao(Punicao.PERDE_APOSTA) }
            }
            OnlineSection(stringResource(R.string.online_robot_level)) {
                BotLevel.entries.forEach { level ->
                    OnlineChip(stringResource(level.textRes()), state.robotLevel == level, "online-robot-${level.name.lowercase()}") { viewModel.onRobotLevel(level) }
                }
            }
        }
        state.problem?.let { ProblemText(it, "online-create-error") }
    }
}

@StringRes
private fun BotLevel.textRes(): Int = when (this) {
    BotLevel.FACIL -> R.string.difficulty_facil
    BotLevel.MEDIO -> R.string.difficulty_medio
    BotLevel.DIFICIL -> R.string.difficulty_dificil
}

@StringRes
private fun TempoDeJogada.textRes(): Int = when (this) {
    TempoDeJogada.INDETERMINADO -> R.string.online_time_indefinite
    TempoDeJogada.UM_MINUTO -> R.string.online_time_1
    TempoDeJogada.CINCO_MINUTOS -> R.string.online_time_5
}

@StringRes
private fun Punicao.textRes(): Int = when (this) {
    Punicao.SEM_PUNICAO -> R.string.online_penalty_none
    Punicao.PERDE_APOSTA -> R.string.online_penalty_bet
}

/** Resumo da mesa (aposta, critérios e boosters), igual no convite e na sala (§18.4). */
@Composable
private fun TableSummary(table: TableView, modifier: Modifier = Modifier) {
    val rules = table.rules
    GSubPanel(modifier.testTag("online-table-summary")) {
        val mode = stringResource(if (rules.mode == TableMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual)
        Text(stringResource(R.string.online_summary_mode, mode, rules.targetScore), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_bet_none), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_summary_time, stringResource(rules.tempoDeJogada.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_summary_penalty, stringResource(rules.punicao.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_summary_robots, stringResource(rules.robotLevel.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (rules.skillBoostersAllowed) R.string.online_summary_skill_on else R.string.online_summary_skill_off),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------- 3.24 Entrar numa mesa ----------

/** Entrar numa mesa (docs/telas.md 3.24): digita o id, vê o convite e ocupa o lugar. */
@Composable
fun OnlineJoinScreen(viewModel: OnlineJoinViewModel, onJoined: (String) -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.joinedId) {
        state.joinedId?.let {
            viewModel.onJoinedHandled()
            onJoined(it)
        }
    }
    TableFrame(
        stringResource(R.string.online_join_title),
        "online-join-screen",
        bottom = {
            BottomButton(stringResource(R.string.back), "online-join-back", GButtonKind.Secondary, weight = 0.7f, onClick = onBack)
            if (state.invite != null) {
                BottomButton(stringResource(R.string.online_join_seat), "online-join-seat", GButtonKind.Primary, enabled = !state.loading, weight = 1.3f, onClick = viewModel::onOccupy)
            } else {
                BottomButton(stringResource(R.string.online_join_go), "online-join-go", GButtonKind.Primary, enabled = state.canGo, weight = 1.3f, onClick = viewModel::onGo)
            }
        },
    ) {
        GPanel(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.idText,
                onValueChange = viewModel::onId,
                label = { Text(stringResource(R.string.online_join_id_label)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().testTag("online-join-id"),
            )
            state.problem?.let { ProblemText(it, "online-join-error") }
            if (state.loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GColors.Gold) }
            }
        }
        state.invite?.let { invite ->
            GPanel(Modifier.fillMaxWidth().testTag("online-invite")) {
                Text(stringResource(R.string.online_invite_title), style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.online_invite_host, invite.hostName), style = MaterialTheme.typography.titleMedium)
                TableSummary(invite)
                Text(stringResource(R.string.online_invite_accept), style = MaterialTheme.typography.bodySmall, color = GColors.Steel)
            }
        }
    }
}

// ---------- 3.23 Mesa online (a sala) ----------

/** Sala da mesa online (docs/telas.md 3.23): id, QR, link, lugares ao vivo e os botões do anfitrião ou do convidado. */
@Composable
fun OnlineRoomScreen(
    viewModel: OnlineRoomViewModel,
    inviteLink: String,
    onEnded: (OnlineRoomEnd) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.ended) { state.ended?.let(onEnded) }
    val context = LocalContext.current
    val table = state.table
    TableFrame(
        stringResource(R.string.online_room_title),
        "online-table",
        bottom = {
            when {
                state.started -> BottomButton(stringResource(R.string.back), "online-table-back", GButtonKind.Secondary, onClick = onBack)
                state.isHost -> {
                    BottomButton(stringResource(R.string.online_close), "online-table-close", GButtonKind.DangerOutline, enabled = !state.busy, weight = 0.8f, onClick = viewModel::onAskClose)
                    BottomButton(stringResource(R.string.online_start), "online-table-start", GButtonKind.Primary, enabled = state.canStart, weight = 1.2f, onClick = viewModel::onStart)
                }
                else -> BottomButton(stringResource(R.string.online_leave), "online-table-leave", GButtonKind.Secondary, enabled = !state.busy && table != null, onClick = viewModel::onLeave)
            }
        },
    ) {
        if (state.connectionLost) {
            Text(stringResource(R.string.online_connection_lost), Modifier.fillMaxWidth().testTag("online-connection-lost"), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne, textAlign = TextAlign.Center)
        }
        if (table == null) {
            Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GColors.Gold) }
            return@TableFrame
        }
        GPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.online_table_id_label), style = MaterialTheme.typography.labelLarge, color = GColors.Steel, modifier = Modifier.align(Alignment.CenterHorizontally))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FitText(
                    table.id,
                    MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp, color = GColors.Gold),
                    Modifier.semantics { contentDescription = table.id.toList().joinToString(" ") }.testTag("online-table-id"),
                )
            }
            if (!state.started) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCode(inviteLink, stringResource(R.string.online_qr_desc, table.id), Modifier.widthIn(max = 200.dp).fillMaxWidth().testTag("online-table-qr"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GButton({ copyId(context, table.id) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("online-table-copy"), kind = GButtonKind.Secondary) {
                        FitText(stringResource(R.string.online_copy), MaterialTheme.typography.titleSmall, minFontSp = 10)
                    }
                    GButton({ shareInvite(context, table.id, inviteLink) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("online-table-share"), kind = GButtonKind.Secondary) {
                        FitText(stringResource(R.string.online_share), MaterialTheme.typography.titleSmall, minFontSp = 10)
                    }
                }
            }
        }
        if (state.started) {
            GPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.online_started), Modifier.testTag("online-started"), style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.online_started_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
        GPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.online_seats_title), style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
            table.seats.forEach { seat ->
                SeatRow(
                    seat = seat,
                    isHostSeat = seat.guestId != null && seat.guestId == table.hostId,
                    isMine = seat.guestId != null && seat.guestId == state.myId,
                    canEdit = state.isHost && !state.busy && !state.started,
                    onRobot = { viewModel.onSeatRobot(seat.index) },
                    onRemove = { viewModel.onRemove(seat.index) },
                )
            }
        }
        TableSummary(table)
        state.problem?.let { ProblemText(it, "online-table-error") }
    }
    if (state.confirmClose) {
        GMessage(
            modifier = Modifier.testTag("online-close-dialog"),
            title = stringResource(R.string.online_close_title),
            text = stringResource(R.string.online_close_text),
            illustration = MessageIllustration.Exit,
            tone = MessageTone.Danger,
            confirmText = stringResource(R.string.online_close),
            onConfirm = viewModel::onConfirmClose,
            confirmTag = "online-close-confirm",
            dismissText = stringResource(R.string.cancel),
            onDismiss = viewModel::onDismissClose,
            onDismissRequest = viewModel::onDismissClose,
        )
    }
}

@Composable
private fun SeatRow(seat: SeatView, isHostSeat: Boolean, isMine: Boolean, canEdit: Boolean, onRobot: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Corner.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(GColors.IndigoLight, shape)
            .border(1.dp, if (isMine) GColors.Gold else GColors.Steel.copy(alpha = 0.3f), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .testTag("online-seat-${seat.index}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            when (seat.kind) {
                SeatKind.VAGO -> Text(stringResource(R.string.online_seat_empty), style = MaterialTheme.typography.titleMedium, color = GColors.Steel)
                SeatKind.ROBO -> Text(stringResource(R.string.online_seat_robot), style = MaterialTheme.typography.titleMedium)
                SeatKind.HUMANO -> {
                    Text(seat.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val tags = listOfNotNull(
                        stringResource(R.string.online_seat_host).takeIf { isHostSeat },
                        stringResource(R.string.online_seat_you).takeIf { isMine },
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = GColors.Gold)
                }
            }
        }
        if (canEdit && seat.kind == SeatKind.VAGO) {
            GButton(onRobot, Modifier.heightIn(min = 48.dp).testTag("online-seat-robot-${seat.index}"), kind = GButtonKind.Secondary, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.online_seat_add_robot), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (canEdit && seat.kind == SeatKind.HUMANO && !isHostSeat) {
            GButton(onRemove, Modifier.heightIn(min = 48.dp).testTag("online-seat-remove-${seat.index}"), kind = GButtonKind.DangerOutline, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.online_seat_remove), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun copyId(context: Context, id: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.online_table_id_label), id))
    Toast.makeText(context, context.getString(R.string.online_copied), Toast.LENGTH_SHORT).show()
}

private fun shareInvite(context: Context, id: String, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.online_share_text, id, link))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.online_share)))
}
