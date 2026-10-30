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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.Persona
import com.gtranca.game.SeatRole
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.selection.Felt
import com.gtranca.ui.selection.FeltDark
import com.gtranca.ui.selection.RobotPhoto
import com.gtranca.ui.selection.Wood
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
import com.gtranca.ui.tables.BottomButton
import com.gtranca.ui.tables.TableFrame
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
    OnlineProblem.SEAT_TAKEN -> R.string.online_problem_seat_taken
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
    /** O próprio jogador (nome e foto do Perfil), para o lugar dele na mesa. */
    self: Persona,
    onJoined: (String) -> Unit,
    onProfile: () -> Unit,
    onBack: () -> Unit,
    /** Aviso de volta de uma sala (mesa encerrada, removido...). */
    notice: OnlineRoomEnd? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
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
                Text(stringResource(R.string.online_seat_tap_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel, textAlign = TextAlign.Center)
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
                        tables.forEach { table ->
                            TableCard(table, state.myId, self, photos, joiningSeat = state.joining?.takeIf { it.first == table.id }?.second,
                                onSeatTap = { seat -> viewModel.onSeatTap(table, seat) }, onBackToRoom = { viewModel.onBackToRoom(table) })
                        }
                    }
                }
                // O login Google entra na fase 2 (identity-service): por ora o botão aparece desabilitado.
                GButton({}, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-login"), enabled = false, kind = GButtonKind.Quiet) {
                    Text(stringResource(R.string.online_google_soon))
                }
            }
        }
    }
}

/** Uma mesa da lista: id, estado, a mesa de lugares (tocar num VAGO senta ali, §18.2) e as opções fixas. */
@Composable
private fun TableCard(table: TableView, myId: String, self: Persona, photos: Map<String, ByteArray>, joiningSeat: Int?, onSeatTap: (Int) -> Unit, onBackToRoom: () -> Unit) {
    val mine = table.seats.any { it.guestId == myId }
    GPanel(Modifier.fillMaxWidth().testTag("online-table-row-${table.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(table.displayName(), style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(table.id, Modifier.semantics { contentDescription = table.id.toList().joinToString(" ") }, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = GColors.Steel)
            }
            val label = when {
                table.state == TableState.EM_JOGO -> R.string.online_table_playing
                mine -> R.string.online_table_mine
                else -> R.string.online_table_open
            }
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (table.state == TableState.EM_JOGO) GColors.Steel else GColors.Champagne)
        }
        OnlineSeatTable(
            table, myId, self, photos,
            modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
            tagPrefix = "online-seat-${table.id}",
            busySeat = joiningSeat,
            // Fora da mesa: tocar senta; já nela: tocar troca de lugar (§18.2).
            onSeatTap = if (table.state == TableState.ABERTA) onSeatTap else null,
        )
        if (mine && table.state == TableState.ABERTA) {
            GButton(onBackToRoom, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-table-enter-${table.id}")) {
                Text(stringResource(R.string.online_table_back_to_room), style = MaterialTheme.typography.titleMedium)
            }
        }
        TableSummary(table)
    }
}

/**
 * A mesa de lugares online (o desenho de 3.3, §18.2): feltro com aro de madeira e as quatro posições em cruz; quem olha
 * fica embaixo se está sentado (senão, o lugar 0). Cada lugar mostra foto e nome; VAGO é tracejado. [onSeatTap] (nulo =
 * só leitura) recebe o toque num lugar; [busySeat] marca o lugar pedido ao servidor.
 */
@Composable
fun OnlineSeatTable(
    table: TableView,
    myId: String,
    self: Persona,
    /** Fotos dos jogadores (§18.2), por id do convidado; sem foto, o medalhão com a inicial. */
    photos: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
    tagPrefix: String = "online-seat",
    busySeat: Int? = null,
    onSeatTap: ((Int) -> Unit)? = null,
    /** Também aceita toque nos humanos (menos o próprio): sala do anfitrião, para remover. */
    tapsFilled: Boolean = false,
) {
    val gameMode = if (table.rules.mode == TableMode.DUPLAS) GameMode.DUPLAS else GameMode.INDIVIDUAL
    val viewer = table.seats.indexOfFirst { it.guestId == myId }.takeIf { it >= 0 } ?: 0
    Box(modifier.aspectRatio(if (gameMode.seatCount == 2) 1.15f else 1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(if (gameMode.seatCount == 2) 28.dp else 36.dp)) {
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Felt, FeltDark), center, radius), radius)
            drawCircle(Wood, radius, style = Stroke(radius * 0.09f))
            drawCircle(GColors.Gold.copy(alpha = 0.5f), radius * 0.955f, style = Stroke(2f))
            drawCircle(GColors.Gold.copy(alpha = 0.25f), radius * 0.35f, center, style = Stroke(2f))
        }
        table.seats.forEach { seat ->
            val align = when (SeatRole.of(gameMode, Seat(seat.index), Seat(viewer))) {
                SeatRole.YOU -> Alignment.BottomCenter
                SeatRole.PARTNER, SeatRole.OPPONENT -> Alignment.TopCenter
                SeatRole.LEFT_OPPONENT -> Alignment.CenterStart
                SeatRole.RIGHT_OPPONENT -> Alignment.CenterEnd
            }
            val isMine = seat.guestId != null && seat.guestId == myId
            val isHost = seat.guestId != null && seat.guestId == table.hostId
            val name = when (seat.kind) {
                SeatKind.VAGO -> stringResource(R.string.selection_seat_vacant)
                SeatKind.ROBO -> seat.profile?.let { robotCharacter(it, seat.level).profile.botName } ?: stringResource(R.string.online_seat_robot)
                SeatKind.HUMANO -> if (isMine) self.firstName else seat.name.orEmpty()
            }
            val (dupla, pos) = duplaPosition(seat.index)
            val where = stringResource(R.string.online_dupla_seat, dupla, pos)
            val tappable = onSeatTap != null && busySeat == null &&
                (seat.kind == SeatKind.VAGO || tapsFilled && seat.kind == SeatKind.HUMANO && !isMine)
            Column(
                Modifier
                    .align(align)
                    .widthIn(max = 96.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$where: $name" }
                    .testTag("$tagPrefix-${seat.index}")
                    .then(if (tappable) Modifier.clickable { onSeatTap?.invoke(seat.index) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(SeatAvatarSize + 6.dp), contentAlignment = Alignment.Center) {
                    when {
                        busySeat == seat.index -> CircularProgressIndicator(color = GColors.Gold, modifier = Modifier.size(SeatAvatarSize))
                        seat.kind == SeatKind.VAGO -> Box(
                            Modifier
                                .size(SeatAvatarSize)
                                .clip(CircleShape)
                                .background(GColors.Midnight.copy(alpha = 0.55f))
                                .border(if (tappable) 2.dp else 1.5.dp, if (tappable) GColors.Gold.copy(alpha = 0.8f) else GColors.Steel.copy(alpha = 0.7f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.selection_seat_vacant), color = GColors.Steel, style = MaterialTheme.typography.labelMedium)
                        }
                        seat.kind == SeatKind.ROBO && seat.profile != null -> {
                            val c = robotCharacter(seat.profile!!, seat.level)
                            RobotPhoto(c.profile, c.level, SeatAvatarSize)
                        }
                        isMine -> PersonaAvatar(self, SeatAvatarSize)
                        else -> PersonaAvatar(
                            Persona(name.ifBlank { "?" }, color = (seat.guestId ?: name).hashCode().mod(Persona.COLORS), photoBytes = seat.guestId?.let(photos::get)),
                            SeatAvatarSize,
                        )
                    }
                    if (isMine || isHost) Box(Modifier.matchParentSize().border(if (isMine) 3.dp else 1.5.dp, GColors.Gold, CircleShape))
                }
                Text(name, color = GColors.Champagne, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                val tag = when {
                    isHost && isMine -> stringResource(R.string.online_seat_host) + " · " + stringResource(R.string.online_seat_you)
                    isHost -> stringResource(R.string.online_seat_host)
                    isMine -> stringResource(R.string.online_seat_you)
                    else -> where
                }
                Text(tag, color = if (isHost || isMine) GColors.Gold else GColors.Steel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val SeatAvatarSize = 52.dp

/** Personagem do app para o robô do servidor (mesmos perfis; Fácil = Iniciante, Médio = Maduro, Difícil = Sênior). */
private fun robotCharacter(profile: BotProfile, level: BotLevel?): BotCharacter = BotCharacter(
    com.gtranca.game.BotProfile.valueOf(profile.name),
    when (level) {
        BotLevel.MEDIO -> com.gtranca.game.BotLevel.MADURO
        BotLevel.DIFICIL -> com.gtranca.game.BotLevel.SENIOR
        else -> com.gtranca.game.BotLevel.INICIANTE
    },
)

/** Nome amigável da mesa (§18.2); mesas antigas sem nome mostram o id. */
private fun TableView.displayName(): String = name.ifBlank { id }

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
        SummaryLines(table)
    }
}

@Composable
private fun SummaryLines(table: TableView) {
    val rules = table.rules
    run {
        val mode = stringResource(if (rules.mode == TableMode.DUPLAS) R.string.mode_duplas_short else R.string.mode_individual)
        Text(stringResource(R.string.online_summary_mode, mode, rules.targetScore), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne)
        Text(
            if (rules.bet > 0) stringResource(R.string.online_bet_value, rules.bet) else stringResource(R.string.online_bet_none),
            style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne,
        )
        Text(stringResource(R.string.online_summary_time, stringResource(rules.tempoDeJogada.textRes())), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne)
        Text(stringResource(R.string.online_summary_penalty, stringResource(rules.punicao.textRes())), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne)
        Text(stringResource(R.string.online_summary_robots, stringResource(rules.robotLevel.textRes())), style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne)
        Text(
            stringResource(if (rules.skillBoostersAllowed) R.string.online_summary_skill_on else R.string.online_summary_skill_off),
            style = MaterialTheme.typography.bodyMedium, color = GColors.Champagne,
        )
    }
}

// ---------- 3.23 Mesa online (a sala) ----------

/** Sala da mesa online (docs/telas.md 3.23): id, posições ao vivo e os botões do anfitrião (§18.2). */
@Composable
fun OnlineRoomScreen(
    viewModel: OnlineRoomViewModel,
    self: Persona,
    onEnded: (OnlineRoomEnd) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
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
                else -> {
                    BottomButton(stringResource(R.string.online_leave), "online-table-leave", GButtonKind.Secondary, enabled = !state.busy && table != null, weight = 0.8f, onClick = viewModel::onLeave)
                    // Quem não é o anfitrião espera o início (o botão não faz nada).
                    GButton({}, Modifier.weight(1.2f).heightIn(min = 56.dp).testTag("online-table-waiting"), enabled = false) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = GColors.OnGold, strokeWidth = 2.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        FitText(stringResource(R.string.online_waiting_host), MaterialTheme.typography.titleSmall, minFontSp = 10)
                    }
                }
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FitText(
                    table.displayName(),
                    MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, color = GColors.Gold),
                    Modifier.testTag("online-table-name"),
                )
            }
            Text(
                stringResource(R.string.online_table_id_small, table.id),
                Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = table.id.toList().joinToString(" ") }.testTag("online-table-id"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = GColors.Steel,
            )
        }
        if (state.started) {
            GPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.online_started), Modifier.testTag("online-started"), style = MaterialTheme.typography.titleLarge, color = GColors.Gold, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.online_started_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
        GPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.online_seats_title), style = MaterialTheme.typography.titleMedium, color = GColors.Champagne)
            OnlineSeatTable(
                table, state.myId, self, photos,
                modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally),
                // Antes do jogo: tocar num VAGO troca de lugar; o anfitrião, tocando num jogador, pergunta se o remove.
                onSeatTap = if (!state.busy && !state.started) { seat ->
                    if (table.seats[seat].kind == SeatKind.VAGO) viewModel.onMove(seat) else viewModel.onAskRemove(seat)
                } else null,
                tapsFilled = state.isHost,
            )
            if (state.isHost && state.passCandidates.isNotEmpty()) {
                GButton(viewModel::onAskPass, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-table-pass"), kind = GButtonKind.Secondary, enabled = !state.busy) {
                    Text(stringResource(R.string.online_pass_host), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        TableSummary(table)
        state.problem?.let { ProblemText(it, "online-table-error") }
    }
    val removing = state.askRemove?.let { table?.seats?.getOrNull(it) }
    if (removing != null) {
        GMessage(
            modifier = Modifier.testTag("online-remove-dialog"),
            title = stringResource(R.string.online_remove_title, removing.name.orEmpty()),
            illustration = MessageIllustration.Exit,
            tone = MessageTone.Danger,
            confirmText = stringResource(R.string.online_seat_remove),
            onConfirm = { viewModel.onRemove(removing.index) },
            confirmTag = "online-seat-remove-${removing.index}",
            dismissText = stringResource(R.string.cancel),
            onDismiss = viewModel::onDismissRemove,
            onDismissRequest = viewModel::onDismissRemove,
        )
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
