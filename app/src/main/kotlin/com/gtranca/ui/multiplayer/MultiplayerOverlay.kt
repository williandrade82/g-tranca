package com.gtranca.ui.multiplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.engine.model.Seat
import com.gtranca.game.Persona
import com.gtranca.multiplayer.MpNotice
import com.gtranca.multiplayer.MpRole
import com.gtranca.multiplayer.MultiplayerHost
import com.gtranca.multiplayer.MultiplayerTable
import com.gtranca.net.GuestStatus
import com.gtranca.net.JoinRequest
import com.gtranca.net.NoticeKind
import com.gtranca.net.Paused
import com.gtranca.ui.game.MultiplayerScreenHooks
import com.gtranca.ui.theme.Corner
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.TableIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * §16 o que a mesa de jogo ganha no multijogador (docs/telas.md 3.14 e 3.15): menu do anfitrião, pausa e pedidos de
 * entrada, faixas e avisos da mesa, reconexão, sucessão, moedas do fim de jogo e "Voltar à mesa".
 */
@Composable
fun rememberMultiplayerHooks(table: MultiplayerTable, personas: List<Persona>, onExit: () -> Unit): MultiplayerScreenHooks {
    val received by table.received.collectAsState()
    val resigned by table.resigned.collectAsState()
    val role by table.role.collectAsState()
    val rules by table.rules.collectAsState()
    val stake = rules?.stake ?: 0
    // §16.10 segue o papel atual: quem recebe a mesa ganha o menu do anfitrião e quem a deixa o perde.
    val host by table.hostControls.collectAsState()
    var passing by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val current = host
    return MultiplayerScreenHooks(
        headerExtra = if (current == null) null else {
            { close -> HostMenuItems(onPass = { close(); passing = true }, onOpen = { close(); opening = true }) }
        },
        overlay = {
            MultiplayerOverlay(table, personas, onExit)
            if (current != null) HostMenuDialogs(table, current, personas, passing, opening, { passing = false }, { opening = false })
        },
        coinsNet = { received?.let { it - stake } },
        gameOverExtra = { if (resigned && role is MpRole.Guesting) ReturnToTableButton(table) },
    )
}

/** §16.9 "Voltar à mesa": quem desistiu reingressa pelo token enquanto o assento não foi substituído nem ocupado. */
@Composable
private fun ReturnToTableButton(table: MultiplayerTable) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    GButton(
        {
            busy = true
            failed = false
            scope.launch {
                failed = !table.returnToTable()
                busy = false
            }
        },
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mp-return-table"),
        enabled = !busy,
        kind = GButtonKind.Secondary,
    ) { Text(stringResource(if (busy) R.string.mp_returning else R.string.mp_return_table)) }
    if (failed) Text(stringResource(R.string.mp_return_failed), Modifier.fillMaxWidth(), color = GColors.Ruby, textAlign = TextAlign.Center)
}

@Composable
private fun MultiplayerOverlay(table: MultiplayerTable, personas: List<Persona>, onExit: () -> Unit) {
    val role by table.role.collectAsState()
    val resigned by table.resigned.collectAsState()
    val received by table.received.collectAsState()
    val end by table.end.collectAsState()
    val tookOver by table.tookOver.collectAsState()
    val current = role
    val guest = (current as? MpRole.Guesting)?.session
    val host = (current as? MpRole.Hosting)?.host
    val status by remember(guest) { guest?.status ?: flowOf(null) }.collectAsState(null)
    val paused by remember(guest) { guest?.paused ?: flowOf(null) }.collectAsState(null)

    // Avisos da mesa (§16.8): um de cada vez, no alto, somem sozinhos.
    val queue = remember { mutableStateListOf<MpNotice>() }
    LaunchedEffect(table) { table.notices.collect { queue += it } }
    val head = queue.firstOrNull()
    if (head != null) {
        LaunchedEffect(head.id) {
            delay(NOTICE_MILLIS)
            queue.remove(head)
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
            if (head != null) Strip(noticeText(head), "mp-notice", GColors.Indigo)
            if (!resigned && status == GuestStatus.Reconnecting) Strip(stringResource(R.string.mp_reconnecting), "mp-reconnecting", GColors.Bordeaux)
            val p = paused
            if (!resigned && p != null && guest != null) Strip(pausedText(p, personas), "mp-paused-banner", GColors.Bordeaux)
        }
    }

    if (host != null && !resigned) HostPanels(table, host, personas)
    // §16.10 no provisório, "Você agora é o anfitrião" só depois de confirmado (antes vale "Aguardando os outros jogadores").
    val committed by remember(host) { host?.committed ?: flowOf(true) }.collectAsState(true)
    if (tookOver && committed) {
        GMessage(
            modifier = Modifier.testTag("mp-host-now"),
            title = stringResource(R.string.mp_host_now_title),
            text = stringResource(R.string.mp_host_now_text),
            illustration = MessageIllustration.Info,
            confirmText = stringResource(R.string.mp_ok),
            onConfirm = table::takeOverSeen,
            onDismissRequest = table::takeOverSeen,
        )
    }
    val reason = end
    if (reason != null && !resigned && received == null) MpEndMessage(reason, onExit)
}

/** Faixa curta no alto da mesa. */
@Composable
private fun Strip(text: String, tag: String, color: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(Corner.md)
    Text(
        text,
        Modifier
            .padding(horizontal = Spacing.lg)
            .background(color.copy(alpha = 0.95f), shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.6f), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .testTag(tag),
        color = GColors.Ivory,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun noticeText(notice: MpNotice): String {
    val name = notice.name ?: stringResource(R.string.mp_someone)
    val persona = notice.persona?.let { code -> com.gtranca.game.BotProfile.entries.firstOrNull { it.code == code }?.botName }
    return when (notice.kind) {
        NoticeKind.DISCONNECTED -> stringResource(R.string.mp_notice_disconnected, name)
        NoticeKind.RECONNECTED -> stringResource(R.string.mp_notice_reconnected, name)
        NoticeKind.RESIGNED -> stringResource(R.string.mp_notice_resigned, name)
        NoticeKind.REPLACED_BY_ROBOT ->
            if (persona != null) stringResource(R.string.mp_notice_robot, name, persona) else stringResource(R.string.mp_notice_robot_plain, name)
        NoticeKind.WAITING -> stringResource(R.string.mp_notice_waiting, name)
        NoticeKind.SEAT_OPENED -> stringResource(R.string.mp_notice_opened, name)
        NoticeKind.JOINED -> stringResource(R.string.mp_notice_joined, name)
        NoticeKind.HOST_CHANGED -> stringResource(R.string.mp_notice_host, name)
    }
}

/** §16.8 faixa dos convidados: "aguardando <nome>" / "aguardando recrutamento" / "aguardando o anfitrião". */
@Composable
private fun pausedText(paused: Paused, personas: List<Persona>): String = when (paused.hostChoice) {
    NoticeKind.WAITING -> stringResource(R.string.mp_paused_waiting, personas.getOrNull(paused.seat.index)?.firstName ?: stringResource(R.string.mp_someone))
    NoticeKind.SEAT_OPENED -> stringResource(R.string.mp_paused_recruit)
    else -> stringResource(R.string.mp_paused_host)
}

/** Painéis do anfitrião: pausa com as três ações, pedidos de entrada, provisório, aviso de início e sem sucessor. */
@Composable
private fun HostPanels(table: MultiplayerTable, host: MultiplayerHost, personas: List<Persona>) {
    val scope = rememberCoroutineScope()
    val decision by host.decision.collectAsState()
    val requests by host.sessionJoinRequests().collectAsState()
    val committed by host.committed.collectAsState()
    val canForce by host.canForceCommit.collectAsState()
    val dropped by host.hostSession.droppedAtStart.collectAsState()
    val noSuccessor by host.noSuccessor.collectAsState()
    var droppedSeen by remember { mutableStateOf(false) }
    var noSuccessorSeen by remember { mutableStateOf(false) }
    fun seatName(seat: Seat): String = personas.getOrNull(seat.index)?.firstName.orEmpty()

    val d = decision
    if (!committed) {
        // §16.10 anfitrião provisório: espera alguém reingressar; depois de 30 s, "Assumir mesmo assim".
        GDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag("mp-waiting-others"),
            title = { Text(stringResource(R.string.mp_waiting_others_title)) },
            text = { Text(stringResource(R.string.mp_waiting_others_text)) },
            confirmButton = {
                if (canForce) {
                    TextButton({ scope.launch { host.forceCommit() } }, Modifier.testTag("mp-force-commit")) {
                        Text(stringResource(R.string.mp_force_commit))
                    }
                }
            },
        )
    } else if (d != null) {
        // §16.8, §16.9 a vez é de quem não está: o anfitrião decide (e vê os pedidos para esse assento).
        val name = personas.getOrNull(d.seat.index)?.firstName.orEmpty()
        val forSeat = requests.filter { it.seat == d.seat }
        GDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag("mp-pause"),
            title = {
                Text(
                    when (d.choice) {
                        NoticeKind.WAITING -> stringResource(R.string.mp_pause_waiting, name)
                        NoticeKind.SEAT_OPENED -> stringResource(R.string.mp_pause_recruiting)
                        else -> stringResource(R.string.mp_pause_title, name)
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.mp_pause_text))
                    if (d.choice != NoticeKind.WAITING) {
                        DecisionButton(R.string.mp_pause_wait, "mp-pause-wait") { scope.launch { host.waitForPlayer() } }
                    }
                    DecisionButton(R.string.mp_pause_robot, "mp-pause-robot") { scope.launch { host.replaceWithRobot() } }
                    if (d.choice != NoticeKind.SEAT_OPENED) {
                        DecisionButton(R.string.mp_pause_recruit, "mp-pause-recruit") { scope.launch { host.openSeatForRecruit() } }
                    }
                    forSeat.forEach { request -> JoinRequestRow(request, seatName(request.seat), host) }
                }
            },
            confirmButton = {},
        )
    } else {
        // §16.7 pedido de entrada num assento de robô liberado pelo anfitrião.
        requests.firstOrNull()?.let { request ->
            GMessage(
                modifier = Modifier.testTag("mp-join-request"),
                title = stringResource(R.string.mp_join_request, request.name, seatName(request.seat)),
                illustration = MessageIllustration.Question,
                confirmText = stringResource(R.string.mp_join_accept),
                onConfirm = { scope.launch { host.approveJoin(request.requestId) } },
                confirmTag = "mp-join-accept",
                dismissText = stringResource(R.string.mp_join_refuse),
                onDismiss = { scope.launch { host.hostSession.refuseJoin(request.requestId) } },
                dismissTag = "mp-join-refuse",
                onDismissRequest = {},
            )
        }
    }
    if (dropped.isNotEmpty() && !droppedSeen) {
        // §16.2 quem não confirmou a posição no início saiu da mesa.
        GMessage(
            modifier = Modifier.testTag("mp-dropped"),
            title = stringResource(R.string.mp_dropped_title),
            text = stringResource(R.string.mp_dropped_text, dropped.joinToString(", ") { it.name }),
            illustration = MessageIllustration.Info,
            confirmText = stringResource(R.string.mp_ok),
            onConfirm = { droppedSeen = true },
            onDismissRequest = { droppedSeen = true },
        )
    }
    val othersHere = table.roster.collectAsState().value.any { it.id != host.hostSession.hostId && !it.resigned }
    if (noSuccessor && othersHere && !noSuccessorSeen && committed) {
        LaunchedEffect(Unit) {
            delay(NOTICE_MILLIS)
            noSuccessorSeen = true
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 200.dp), contentAlignment = Alignment.BottomCenter) {
            Strip(stringResource(R.string.mp_no_successor), "mp-no-successor", GColors.Bordeaux)
        }
    }
    if (!noSuccessor) noSuccessorSeen = false
}

@Composable
private fun DecisionButton(text: Int, tag: String, onClick: () -> Unit) {
    GButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), kind = GButtonKind.Secondary) { Text(stringResource(text)) }
}

/** §16.7 "<nome> quer entrar no lugar de <assento>": Aceitar (abre a vaga e aprova) ou Recusar. */
@Composable
private fun JoinRequestRow(request: JoinRequest, seatName: String, host: MultiplayerHost) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, GColors.Gold.copy(alpha = 0.6f), RoundedCornerShape(Corner.sm))
            .padding(Spacing.sm)
            .testTag("mp-join-request"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(stringResource(R.string.mp_join_request, request.name, seatName), fontWeight = FontWeight.Bold)
        GButton({ scope.launch { host.approveJoin(request.requestId) } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mp-join-accept")) {
            Text(stringResource(R.string.mp_join_accept))
        }
        GButton(
            { scope.launch { host.hostSession.refuseJoin(request.requestId) } },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mp-join-refuse"),
            kind = GButtonKind.Quiet,
        ) { Text(stringResource(R.string.mp_join_refuse)) }
    }
}

/** §16.7, §16.10 itens do menu do anfitrião (no menu de Opções da mesa): passar o cargo e liberar um assento de robô. */
@Composable
private fun HostMenuItems(onPass: () -> Unit, onOpen: () -> Unit) {
    Text(
        stringResource(R.string.mp_host_menu),
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("host-menu"),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = GColors.Gold,
    )
    DropdownMenuItem(
        { Text(stringResource(R.string.mp_pass_role)) },
        onPass,
        Modifier.heightIn(min = 48.dp).testTag("host-pass-role"),
        leadingIcon = { TableIcon(GColors.Gold, size = 20.dp) },
    )
    DropdownMenuItem(
        { Text(stringResource(R.string.mp_open_seat)) },
        onOpen,
        Modifier.heightIn(min = 48.dp).testTag("host-open-seat"),
    )
}

/** Diálogos do menu do anfitrião (fora do menu, que fecha ao escolher). */
@Composable
private fun HostMenuDialogs(
    table: MultiplayerTable,
    host: MultiplayerHost,
    personas: List<Persona>,
    passing: Boolean,
    opening: Boolean,
    closePassing: () -> Unit,
    closeOpening: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val roster by table.roster.collectAsState()
    val openSeats by host.hostSession.openSeats.collectAsState()
    if (passing) {
        // §16.10 só humanos conectados, pela ordem de entrada; nunca robôs.
        val candidates = roster.filter { it.id != host.hostSession.hostId && it.connected && !it.resigned }.sortedBy { it.joinOrder }
        GDialog(
            onDismissRequest = closePassing,
            title = { Text(stringResource(R.string.mp_pass_role)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(if (candidates.isEmpty()) R.string.mp_pass_none else R.string.mp_pass_text))
                    candidates.forEach { player ->
                        GButton(
                            { closePassing(); scope.launch { table.passRole(player.id) } },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("host-pass-${player.id}"),
                            kind = GButtonKind.Secondary,
                        ) { Text(stringResource(R.string.mp_pass_confirm) + " · " + player.name) }
                    }
                }
            },
            confirmButton = { TextButton(closePassing) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (opening) {
        // §16.7 assentos de robô (sem humano), ainda não abertos.
        val mode = table.rules.collectAsState().value?.mode
        val robots = mode?.seats.orEmpty().filter { seat -> roster.none { it.seat == seat } && seat !in openSeats && seat != host.hostSeat }
        GDialog(
            onDismissRequest = closeOpening,
            title = { Text(stringResource(R.string.mp_open_seat)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(if (robots.isEmpty()) R.string.mp_open_none else R.string.mp_open_text))
                    robots.forEach { seat ->
                        GButton(
                            { closeOpening(); scope.launch { table.openRobotSeat(seat) } },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("host-open-${seat.index}"),
                            kind = GButtonKind.Secondary,
                        ) { Text(personas.getOrNull(seat.index)?.firstName.orEmpty()) }
                    }
                }
            },
            confirmButton = { TextButton(closeOpening) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Quanto tempo cada aviso fica na tela. */
private const val NOTICE_MILLIS = 3_500L
