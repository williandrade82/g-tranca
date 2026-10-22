package com.gtranca.ui.multiplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.ai.Difficulty
import com.gtranca.engine.Coins
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotLevel
import com.gtranca.game.SeatRole
import com.gtranca.multiplayer.MpEnd
import com.gtranca.multiplayer.MpRole
import com.gtranca.multiplayer.MultiplayerTable
import com.gtranca.net.GuestStatus
import com.gtranca.net.PlayerInfo
import com.gtranca.ui.persona.LevelStars
import com.gtranca.ui.persona.labelRes
import com.gtranca.ui.tables.RulesSummary
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
import com.gtranca.ui.theme.RotateIcon
import com.gtranca.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val Felt = Color(0xFF1E6B3A)
private val FeltDark = Color(0xFF124A27)
private val Wood = Color(0xFF6B3E1F)

/**
 * §16.2 "Escolha sua posição" (docs/telas.md 3.13): a mesa redonda com os assentos, o jogador sempre embaixo, rotação
 * para assentos vagos, nome editável e confirmação; o anfitrião fica na posição 1 e inicia o jogo. Vai ao jogo quando
 * ele começa ([onStarted]); [onLeave] sai da mesa (sempre disponível).
 */
@Composable
fun SeatPickerScreen(table: MultiplayerTable, balance: Int, onStarted: () -> Unit, onLeave: () -> Unit) {
    val role by table.role.collectAsStateWithLifecycle()
    val roster by table.roster.collectAsStateWithLifecycle()
    val rules by table.rules.collectAsStateWithLifecycle()
    val end by table.end.collectAsStateWithLifecycle()
    val current = role
    val guestStatus by remember(current) {
        (current as? MpRole.Guesting)?.session?.status ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val isHost = current is MpRole.Hosting
    val started = (current as? MpRole.Hosting)?.host != null || guestStatus == GuestStatus.Playing
    LaunchedEffect(started) { if (started) onStarted() }
    BackHandler(onBack = onLeave)

    val myId = table.myId
    val me = roster.firstOrNull { it.id == myId }
    val mySeat = me?.seat ?: if (isHost) Seat(0) else null
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(table.name) }
    // §16.2 "Iniciar com robôs?": assentos vagos ou convidados sem confirmar pedem confirmação antes de começar.
    var startWarning by remember { mutableStateOf<StartWarning?>(null) }
    // O nome vai à mesa depois de uma pausa na digitação (todos veem a mudança).
    LaunchedEffect(name, current) {
        if (current == null || name == table.name) return@LaunchedEffect
        delay(NAME_DEBOUNCE_MILLIS)
        table.rename(name)
    }

    GBackground(Modifier.fillMaxSize().testTag("seat-picker")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GButton(onLeave, Modifier.heightIn(min = 48.dp).testTag("seat-picker-leave"), kind = GButtonKind.Secondary) {
                        Text(stringResource(R.string.mp_leave))
                    }
                }
                GTitle(stringResource(R.string.mp_picker_title), Modifier.semantics { heading() })
                Text(
                    stringResource(if (isHost) R.string.mp_host_subtitle else R.string.mp_picker_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GColors.Steel,
                    textAlign = TextAlign.Center,
                )
                val r = rules
                if (r == null || mySeat == null) {
                    // Entrando: a sala e as regras chegam com a resposta do anfitrião.
                    CircularProgressIndicator(color = GColors.Gold)
                    Text(
                        stringResource(if (guestStatus == GuestStatus.WaitingApproval) R.string.mp_waiting_approval else R.string.mp_connecting),
                        Modifier.testTag("seat-picker-connecting"),
                        color = GColors.Ivory,
                    )
                } else {
                    val vacant = r.mode.seats.filter { seat -> roster.none { it.seat == seat } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RotateButton(false, enabled = !isHost && vacant.isNotEmpty(), Modifier.testTag("seat-picker-rotate-left")) {
                            nextVacant(r.mode, mySeat, vacant, -1)?.let { scope.launch { table.chooseSeat(it) } }
                        }
                        RoundTable(r.mode, mySeat, roster, myId, Modifier.weight(1f).widthIn(max = 320.dp).testTag("seat-picker-table"))
                        RotateButton(true, enabled = !isHost && vacant.isNotEmpty(), Modifier.testTag("seat-picker-rotate-right")) {
                            nextVacant(r.mode, mySeat, vacant, +1)?.let { scope.launch { table.chooseSeat(it) } }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(NAME_MAX) },
                        label = { Text(stringResource(R.string.mp_your_name)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("seat-picker-name"),
                    )
                    // §16.2 as regras da mesa (enviadas uma vez): entrar é aceitá-las.
                    GPanel(Modifier.fillMaxWidth().testTag("seat-picker-rules")) {
                        Text(r.tableName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GColors.Champagne)
                        val level = BotLevel.of(Difficulty.fromId(r.difficulty))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.table_rules_level), color = GColors.Steel, style = MaterialTheme.typography.titleSmall)
                            LevelStars(level)
                            Text(stringResource(level.labelRes()), color = GColors.Gold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(R.string.stake_line, r.stake), style = MaterialTheme.typography.titleMedium, color = GColors.Gold)
                        Text(stringResource(R.string.coins_balance, balance), style = MaterialTheme.typography.bodyMedium, color = GColors.Steel)
                        if (!Coins.canJoin(balance, r.stake)) {
                            Text(stringResource(R.string.stake_insufficient), Modifier.testTag("seat-picker-insufficient"), color = GColors.Ruby)
                        }
                    }
                    RulesSummary(r.rules, r.targetScore)
                }
            }
            // Barra fixa: Confirmar posição / Aguardando início do jogo (convidado) ou Iniciar jogo (anfitrião).
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                val r = rules
                when {
                    isHost -> GButton(
                        {
                            val warning = r?.let { startWarning(it.mode, roster) }
                            if (warning == null) scope.launch { table.startGame() } else startWarning = warning
                        },
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seat-picker-start"),
                        enabled = r != null && Coins.canJoin(balance, r.stake),
                    ) { Text(stringResource(R.string.mp_start), style = MaterialTheme.typography.titleMedium) }
                    me?.confirmed == true -> GButton(
                        {},
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seat-picker-waiting"),
                        enabled = false,
                    ) { Text(stringResource(R.string.mp_waiting_start), style = MaterialTheme.typography.titleMedium) }
                    else -> GButton(
                        { scope.launch { table.confirmSeat() } },
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seat-picker-confirm"),
                        enabled = me != null && r != null && Coins.canJoin(balance, r.stake),
                    ) { Text(stringResource(R.string.mp_confirm_seat), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    startWarning?.let { warning ->
        val text = buildList {
            if (warning.vacantSeats > 0) add(pluralStringResource(R.plurals.mp_start_vacant, warning.vacantSeats, warning.vacantSeats))
            if (warning.unconfirmed.isNotEmpty()) add(stringResource(R.string.mp_start_unconfirmed, warning.unconfirmed.joinToString(", ")))
        }.joinToString("\n\n")
        GMessage(
            modifier = Modifier.testTag("seat-picker-start-confirm"),
            title = stringResource(R.string.mp_start_confirm_title),
            text = text,
            illustration = MessageIllustration.Question,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.mp_start),
            onConfirm = {
                startWarning = null
                scope.launch { table.startGame() }
            },
            confirmTag = "seat-picker-start-ok",
            dismissText = stringResource(R.string.cancel),
            dismissTag = "seat-picker-start-cancel",
            onDismissRequest = { startWarning = null },
        )
    }
    end?.let { reason -> MpEndMessage(reason, onLeave) }
}

/** §16.2 o que avisar antes de iniciar: quantos assentos ficam com robô e quem ainda não confirmou a posição. */
data class StartWarning(val vacantSeats: Int, val unconfirmed: List<String>)

/**
 * §16.2 aviso de "Iniciar jogo" ([StartWarning]): assentos sem jogador e convidados que não confirmaram (eles serão
 * desconectados e o assento também fica com robô); `null` = todos os assentos com jogador confirmado.
 */
internal fun startWarning(mode: GameMode, roster: List<com.gtranca.net.PlayerInfo>): StartWarning? {
    val seated = roster.filter { it.seat != null }
    val unconfirmed = seated.filter { !it.confirmed && !it.isHost }
    val vacant = mode.seats.count { seat -> seated.none { it.seat == seat } }
    if (vacant == 0 && unconfirmed.isEmpty()) return null
    return StartWarning(vacant, unconfirmed.map { it.name })
}

/** Mensagem de fim da mesa (recusa, mesa não achada, mesa encerrada) com volta. */
@Composable
fun MpEndMessage(reason: MpEnd, onClose: () -> Unit) {
    GMessage(
        modifier = Modifier.testTag("mp-end"),
        title = stringResource(R.string.mp_closed_title),
        text = when (reason) {
            is MpEnd.Refused -> reason.message
            MpEnd.Closed -> stringResource(R.string.mp_closed_text)
            MpEnd.NotFound -> stringResource(R.string.mp_not_found)
        },
        illustration = MessageIllustration.Info,
        tone = MessageTone.Warning,
        confirmText = stringResource(R.string.back_to_home),
        onConfirm = onClose,
        onDismissRequest = onClose,
    )
}

/** §16.2 próximo assento vago girando para a [direction] (+1 direita, -1 esquerda); nunca o de outro jogador. */
internal fun nextVacant(mode: GameMode, from: Seat, vacant: List<Seat>, direction: Int): Seat? =
    (1 until mode.seatCount).map { Seat(Math.floorMod(from.index + direction * it, mode.seatCount)) }.firstOrNull { it in vacant }

@Composable
private fun RotateButton(clockwise: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val text = stringResource(if (clockwise) R.string.mp_rotate_right else R.string.mp_rotate_left)
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = text, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) { RotateIcon(if (enabled) GColors.Gold else GColors.Steel.copy(alpha = 0.4f), clockwise, size = 30.dp) }
}

/**
 * §16.2 mesa redonda de posições: feltro verde com aro de madeira; o assento do jogador embaixo (marcador azul), o
 * parceiro em cima e os adversários nas laterais, cada um com o nome ou "VAGO".
 */
@Composable
private fun RoundTable(mode: GameMode, mySeat: Seat, roster: List<PlayerInfo>, myId: Int, modifier: Modifier) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(36.dp)) {
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Felt, FeltDark), center, radius), radius)
            drawCircle(Wood, radius, style = Stroke(radius * 0.09f))
            drawCircle(GColors.Gold.copy(alpha = 0.5f), radius * 0.955f, style = Stroke(2f))
            drawCircle(GColors.Gold.copy(alpha = 0.25f), radius * 0.35f, center + Offset(0f, 0f), style = Stroke(2f))
        }
        mode.seats.forEach { seat ->
            val role = SeatRole.of(mode, seat, mySeat)
            val align = when (role) {
                SeatRole.YOU -> Alignment.BottomCenter
                SeatRole.PARTNER, SeatRole.OPPONENT -> Alignment.TopCenter
                SeatRole.LEFT_OPPONENT -> Alignment.CenterStart
                SeatRole.RIGHT_OPPONENT -> Alignment.CenterEnd
            }
            val player = roster.firstOrNull { it.seat == seat }
            SeatSlot(seat, player, mine = role == SeatRole.YOU, mode, Modifier.align(align))
        }
    }
}

/** Um assento: pílula com o nome (ou "VAGO"), a dupla e a marca de posição confirmada; o do jogador com aro azul. */
@Composable
private fun SeatSlot(seat: Seat, player: PlayerInfo?, mine: Boolean, mode: GameMode, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    val vacant = stringResource(R.string.mp_seat_vacant)
    Column(
        modifier
            .widthIn(max = 116.dp)
            .heightIn(min = 48.dp)
            .background(if (player == null) GColors.Midnight.copy(alpha = 0.85f) else GColors.Indigo, shape)
            .border(if (mine) 3.dp else 1.dp, if (mine) GColors.SkyBlue else GColors.Gold.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("seat-slot-${seat.index}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            (player?.name ?: vacant) + if (player?.confirmed == true) " ✓" else "",
            color = if (player == null) GColors.Steel else GColors.Champagne,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        if (mode == GameMode.DUPLAS) {
            Text(stringResource(R.string.mp_team, mode.sideOf(seat).index + 1), color = GColors.Steel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Tempo sem digitar até o nome ir para a mesa. */
private const val NAME_DEBOUNCE_MILLIS = 600L

/** Tamanho máximo do nome na mesa (cabe na pílula do assento). */
private const val NAME_MAX = 16
