package com.gtranca.ui.online

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.game.BotCharacter
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.ui.persona.CharacterPortrait
import com.gtranca.ui.persona.Pose
import com.gtranca.ui.tables.BottomButton
import com.gtranca.ui.tables.TableFrame
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
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
    OnlineProblem.APP_NOT_RECOGNIZED -> R.string.online_problem_app_not_recognized
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

/** Porta do online (docs/telas.md 3.21): jogadores online e a lista de mesas do servidor (§18.2). */
@Composable
fun OnlineHomeScreen(
    viewModel: OnlineHomeViewModel,
    onJoined: (String) -> Unit,
    onProfile: () -> Unit,
    onBack: () -> Unit,
    /** Aviso de volta de uma sala (mesa encerrada, removido...). */
    notice: OnlineRoomEnd? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.joinedId) {
        state.joinedId?.let {
            viewModel.onJoinedHandled()
            onJoined(it)
        }
    }
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
            OnlineHomeStatus.READY -> {
                GPanel(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.online_playing_as, state.name),
                        Modifier.testTag("online-guest-name"),
                        style = MaterialTheme.typography.titleMedium,
                        color = GColors.Gold,
                    )
                    Text(
                        pluralStringResource(R.plurals.online_players_count, state.onlinePlayers, state.onlinePlayers),
                        Modifier.testTag("online-players-count"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = GColors.Champagne,
                    )
                }
                state.problem?.let { ProblemText(it, "online-error") }
                state.listProblem?.let {
                    Text(stringResource(R.string.online_list_problem), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne, textAlign = TextAlign.Center)
                }
                Text(stringResource(R.string.online_tables_title), style = MaterialTheme.typography.titleLarge, color = GColors.Champagne)
                val tables = state.tables
                when {
                    tables == null -> Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GColors.Gold)
                    }
                    tables.isEmpty() -> Text(stringResource(R.string.online_tables_opening), Modifier.testTag("online-tables-empty"), style = MaterialTheme.typography.bodyLarge)
                    else -> Column(Modifier.fillMaxWidth().testTag("online-tables"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        tables.forEach { table -> TableRow(table, state.myId) { viewModel.onSelect(table) } }
                    }
                }
                // O login Google entra na fase 2 (identity-service): por ora o botão aparece desabilitado.
                GButton({}, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-login"), enabled = false, kind = GButtonKind.Quiet) {
                    Text(stringResource(R.string.online_google_soon))
                }
            }
        }
    }
    state.selected?.let { table ->
        GMessage(
            modifier = Modifier.testTag("online-table-choose"),
            title = stringResource(R.string.online_table_choose_title, table.id),
            text = stringResource(R.string.online_invite_accept),
            illustration = MessageIllustration.Cards,
            tone = MessageTone.Info,
            confirmText = stringResource(R.string.online_join_seat),
            onConfirm = viewModel::onOccupy,
            confirmTag = "online-join-seat",
            dismissText = stringResource(R.string.cancel),
            onDismiss = viewModel::onDismissSelected,
            onDismissRequest = viewModel::onDismissSelected,
        ) {
            TableSummary(table)
        }
    }
}

/** Uma mesa da lista: id, estado e as quatro posições das duplas com foto e nome (§18.2). */
@Composable
private fun TableRow(table: TableView, myId: String, onClick: () -> Unit) {
    val mine = table.seats.any { it.guestId == myId }
    val enter = table.canEnter(myId)
    GPanel(
        Modifier
            .fillMaxWidth()
            .then(if (enter) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("online-table-row-${table.id}"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                table.id,
                Modifier.weight(1f).semantics { contentDescription = table.id.toList().joinToString(" ") },
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                color = GColors.Gold,
                fontWeight = FontWeight.ExtraBold,
            )
            val label = when {
                table.state == TableState.EM_JOGO -> R.string.online_table_playing
                mine -> R.string.online_table_mine
                else -> R.string.online_table_open
            }
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (table.state == TableState.EM_JOGO) GColors.Steel else GColors.Champagne)
        }
        DUPLA_ORDER.filter { it < table.seats.size }.forEach { i -> SeatLine(table.seats[i], isHost = table.seats[i].guestId == table.hostId && table.hostId.isNotEmpty(), isMine = table.seats[i].guestId == myId) }
        if (enter) {
            GButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-table-enter-${table.id}")) {
                Text(stringResource(if (mine) R.string.online_table_back_to_room else R.string.online_table_enter), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Uma posição: "Dupla x – y", avatar (retrato do robô ou inicial do humano) e o nome, ou "livre". */
@Composable
private fun SeatLine(seat: SeatView, isHost: Boolean, isMine: Boolean, trailing: @Composable () -> Unit = {}) {
    val (dupla, pos) = duplaPosition(seat.index)
    val shape = RoundedCornerShape(Corner.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(GColors.IndigoLight, shape)
            .border(1.dp, if (isMine) GColors.Gold else GColors.Steel.copy(alpha = 0.3f), shape)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .testTag("online-seat-${seat.index}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SeatAvatar(seat, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.online_dupla_seat, dupla, pos), style = MaterialTheme.typography.labelSmall, color = GColors.Steel)
            val name = when (seat.kind) {
                SeatKind.VAGO -> stringResource(R.string.online_seat_free)
                SeatKind.ROBO -> seat.profile?.let { robotCharacter(it, seat.level).profile.botName } ?: stringResource(R.string.online_seat_robot)
                SeatKind.HUMANO -> seat.name.orEmpty()
            }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = if (seat.kind == SeatKind.VAGO) GColors.Steel else GColors.Champagne,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tags = listOfNotNull(
                stringResource(R.string.online_seat_host).takeIf { isHost },
                stringResource(R.string.online_seat_you).takeIf { isMine },
            )
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = GColors.Gold)
        }
        trailing()
    }
}

/** Robô: retrato do personagem (§14.5). Humano: medalhão com a inicial (a foto chega com a conta Google, fase 2). */
@Composable
private fun SeatAvatar(seat: SeatView, size: Dp) {
    val profile = seat.profile
    when {
        seat.kind == SeatKind.ROBO && profile != null ->
            CharacterPortrait(robotCharacter(profile, seat.level), Pose.APRESENTACAO, Modifier.size(size).clip(CircleShape), animate = false, effects = false)
        seat.kind == SeatKind.HUMANO -> Box(Modifier.size(size).clip(CircleShape).background(GColors.Gold), contentAlignment = Alignment.Center) {
            Text(seat.name.orEmpty().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = GColors.Indigo, fontWeight = FontWeight.Bold)
        }
        else -> Box(Modifier.size(size).clip(CircleShape).border(1.dp, GColors.Steel.copy(alpha = 0.5f), CircleShape))
    }
}

/** Personagem do app para o robô do servidor (mesmos perfis; Fácil = Iniciante, Médio = Maduro, Difícil = Sênior). */
private fun robotCharacter(profile: BotProfile, level: BotLevel?): BotCharacter = BotCharacter(
    com.gtranca.game.BotProfile.valueOf(profile.name),
    when (level) {
        BotLevel.MEDIO -> com.gtranca.game.BotLevel.MADURO
        BotLevel.DIFICIL -> com.gtranca.game.BotLevel.SENIOR
        else -> com.gtranca.game.BotLevel.INICIANTE
    },
)

@StringRes
private fun OnlineRoomEnd.textRes(): Int = when (this) {
    OnlineRoomEnd.CLOSED -> R.string.online_ended_closed
    OnlineRoomEnd.NOT_FOUND -> R.string.online_ended_not_found
    OnlineRoomEnd.REMOVED -> R.string.online_ended_removed
    OnlineRoomEnd.LEFT -> R.string.online_ended_left
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
    TempoDeJogada.QUINZE_MINUTOS -> R.string.online_time_15
}

@StringRes
private fun Punicao.textRes(): Int = when (this) {
    Punicao.SEM_PUNICAO -> R.string.online_penalty_none
    Punicao.PERDE_APOSTA -> R.string.online_penalty_bet
}

/** Resumo da mesa (opções fixas do servidor, §18.2), igual na lista e na sala. */
@Composable
private fun TableSummary(table: TableView, modifier: Modifier = Modifier) {
    val rules = table.rules
    GSubPanel(modifier.testTag("online-table-summary")) {
        val mode = stringResource(if (rules.mode == TableMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual)
        Text(stringResource(R.string.online_summary_mode, mode, rules.targetScore), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (rules.bet > 0) stringResource(R.string.online_bet_value, rules.bet) else stringResource(R.string.online_bet_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.online_summary_time, stringResource(rules.tempoDeJogada.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_summary_penalty, stringResource(rules.punicao.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.online_summary_robots, stringResource(rules.robotLevel.textRes())), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (rules.skillBoostersAllowed) R.string.online_summary_skill_on else R.string.online_summary_skill_off),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------- 3.23 Mesa online (a sala) ----------

/** Sala da mesa online (docs/telas.md 3.23): id, posições ao vivo e os botões do anfitrião (§18.2). */
@Composable
fun OnlineRoomScreen(
    viewModel: OnlineRoomViewModel,
    onEnded: (OnlineRoomEnd) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.ended) { state.ended?.let(onEnded) }
    val table = state.table
    TableFrame(
        stringResource(R.string.online_room_title),
        "online-table",
        bottom = {
            when {
                state.started -> BottomButton(stringResource(R.string.back), "online-table-back", GButtonKind.Secondary, onClick = onBack)
                state.isHost -> {
                    BottomButton(stringResource(R.string.online_leave), "online-table-leave", GButtonKind.Secondary, enabled = !state.busy, weight = 0.8f, onClick = viewModel::onLeave)
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
        }
        if (state.started) {
            GPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.online_started), Modifier.testTag("online-started"), style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.online_started_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
        GPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.online_seats_title), style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
            DUPLA_ORDER.filter { it < table.seats.size }.forEach { i ->
                val seat = table.seats[i]
                val isHostSeat = seat.guestId != null && seat.guestId == table.hostId
                SeatLine(seat, isHost = isHostSeat, isMine = seat.guestId != null && seat.guestId == state.myId) {
                    if (state.isHost && !state.busy && !state.started && seat.kind == SeatKind.HUMANO && !isHostSeat) {
                        GButton({ viewModel.onRemove(seat.index) }, Modifier.heightIn(min = 48.dp).testTag("online-seat-remove-${seat.index}"), kind = GButtonKind.DangerOutline, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.online_seat_remove), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (state.isHost && state.passCandidates.isNotEmpty()) {
                GButton(viewModel::onAskPass, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-table-pass"), kind = GButtonKind.Secondary, enabled = !state.busy) {
                    Text(stringResource(R.string.online_pass_host), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        TableSummary(table)
        state.problem?.let { ProblemText(it, "online-table-error") }
    }
    if (state.choosingHost && table != null) {
        GMessage(
            modifier = Modifier.testTag("online-pass-dialog"),
            title = stringResource(R.string.online_pass_title),
            text = stringResource(R.string.online_pass_text),
            illustration = MessageIllustration.Question,
            tone = MessageTone.Info,
            confirmText = stringResource(R.string.cancel),
            onConfirm = viewModel::onDismissPass,
            confirmTag = "online-pass-cancel",
            onDismissRequest = viewModel::onDismissPass,
        ) {
            state.passCandidates.forEach { i ->
                val seat = table.seats[i]
                GButton({ viewModel.onPassTo(i) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-pass-to-$i"), kind = GButtonKind.Secondary) {
                    Text(seat.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
