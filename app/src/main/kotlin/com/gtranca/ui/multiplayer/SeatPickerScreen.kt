package com.gtranca.ui.multiplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.ai.Difficulty
import com.gtranca.engine.Coins
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotCharacter
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import com.gtranca.game.Persona
import com.gtranca.game.SeatRole
import com.gtranca.multiplayer.MpEnd
import com.gtranca.net.JoinRequest
import com.gtranca.net.RotateDirection
import com.gtranca.net.SeatOccupant
import com.gtranca.net.SeatingState
import com.gtranca.net.TableRules
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

private val Felt = Color(0xFF1E6B3A)
private val FeltDark = Color(0xFF124A27)
private val Wood = Color(0xFF6B3E1F)

/**
 * O que "Escolha sua posição" desenha (§16.2, docs/telas.md 3.13), igual para a mesa só com robôs (local, sem rede) e para
 * a mesa com jogadores por perto.
 *
 * @property seating ocupantes, confirmações e trava; `null` enquanto entra na mesa.
 * @property myId id deste jogador na escolha de posições.
 * @property names nome de cada humano (id → nome).
 * @property photos fotos recebidas dos outros humanos (já vazio se o jogador não quer vê-las).
 * @property rules regras da mesa (o painel "Mesa de <anfitrião>"), `null` enquanto entra.
 * @property waitingApproval o pedido de entrada espera o anfitrião (§16.2).
 * @property joinRequests pedidos de entrada esperando este anfitrião (§16.2).
 * @property notice aviso breve ("<nome> saiu; <robô> assume o lugar").
 */
data class SeatPickerUi(
    val seating: SeatingState?,
    val myId: Int,
    val names: Map<Int, String>,
    val photos: Map<Int, ByteArray> = emptyMap(),
    val rules: TableRules?,
    val waitingApproval: Boolean = false,
    val joinRequests: List<JoinRequest> = emptyList(),
    val notice: String? = null,
)

/** Gestos de "Escolha sua posição". */
class SeatPickerActions(
    val rotate: (RotateDirection) -> Unit,
    val confirm: () -> Unit,
    val rename: (String) -> Unit,
    val leave: () -> Unit,
    val approve: (Int) -> Unit = {},
    val refuse: (Int) -> Unit = {},
)

/**
 * §16.2 "Escolha sua posição" (docs/telas.md 3.13): a mesa redonda com todos os assentos ocupados (humanos ou robôs), o
 * jogador sempre embaixo, a rotação que troca de lugar com o próximo não confirmado, o nome editável e a confirmação.
 * Quando todos os humanos confirmam, os robôs confirmam sozinhos e o jogo começa 2 s depois (quem conduz é a mesa).
 */
@Composable
fun SeatPickerScreen(
    ui: SeatPickerUi,
    balance: Int,
    actions: SeatPickerActions,
    /** §14.1 avatar do próprio jogador (foto do perfil local ou medalhão). */
    self: Persona? = null,
    initialName: String = "",
    end: MpEnd? = null,
) {
    BackHandler(onBack = actions.leave)
    var name by rememberSaveable { mutableStateOf(initialName) }
    // O nome vai à mesa depois de uma pausa na digitação (todos veem a mudança).
    LaunchedEffect(name) {
        if (name == initialName) return@LaunchedEffect
        delay(NAME_DEBOUNCE_MILLIS)
        actions.rename(name)
    }
    val seating = ui.seating
    val rules = ui.rules
    val mySeat = seating?.seatOf(ui.myId)
    val confirmed = mySeat != null && seating.isConfirmed(mySeat)
    val canAfford = rules == null || Coins.canJoin(balance, rules.stake)

    GBackground(Modifier.fillMaxSize().testTag("seat-picker")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GButton(actions.leave, Modifier.heightIn(min = 48.dp).testTag("seat-picker-leave"), kind = GButtonKind.Secondary) {
                        Text(stringResource(R.string.mp_leave))
                    }
                }
                GTitle(stringResource(R.string.mp_picker_title), Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.mp_picker_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GColors.Steel,
                    textAlign = TextAlign.Center,
                )
                ui.notice?.let { NoticeStrip(it) }
                if (seating == null || rules == null || mySeat == null) {
                    // Entrando: a sala e as regras chegam com a resposta do anfitrião.
                    CircularProgressIndicator(color = GColors.Gold)
                    Text(
                        stringResource(if (ui.waitingApproval) R.string.mp_waiting_approval else R.string.mp_connecting),
                        Modifier.testTag("seat-picker-connecting"),
                        color = GColors.Ivory,
                    )
                } else {
                    val level = BotLevel.of(Difficulty.fromId(rules.difficulty))
                    val left = seating.rotationTarget(ui.myId, RotateDirection.LEFT)
                    val right = seating.rotationTarget(ui.myId, RotateDirection.RIGHT)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // §16.2 depois de confirmar, as rotações somem.
                        if (!confirmed) {
                            RotateButton(false, enabled = left != null, Modifier.testTag("seat-picker-rotate-left")) { actions.rotate(RotateDirection.LEFT) }
                        }
                        RoundTable(seating, mySeat, ui, level, self, Modifier.weight(1f).widthIn(max = 320.dp).testTag("seat-picker-table"))
                        if (!confirmed) {
                            RotateButton(true, enabled = right != null, Modifier.testTag("seat-picker-rotate-right")) { actions.rotate(RotateDirection.RIGHT) }
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
                        Text(rules.tableName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GColors.Champagne)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tables_difficulty), color = GColors.Steel, style = MaterialTheme.typography.titleSmall)
                            LevelStars(level)
                            Text(stringResource(level.labelRes()), color = GColors.Gold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(R.string.stake_line, rules.stake), style = MaterialTheme.typography.titleMedium, color = GColors.Gold)
                        if (!canAfford) {
                            Text(stringResource(R.string.stake_insufficient), Modifier.testTag("seat-picker-insufficient"), color = GColors.Ruby)
                        }
                    }
                    RulesSummary(rules.rules, rules.targetScore)
                }
            }
            // Barra fixa: Confirmar posição / Aguardando os outros jogadores.
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                if (confirmed) {
                    GButton(
                        {},
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seat-picker-waiting"),
                        enabled = false,
                    ) { Text(stringResource(R.string.mp_waiting_others), style = MaterialTheme.typography.titleMedium) }
                } else {
                    GButton(
                        actions.confirm,
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seat-picker-confirm"),
                        enabled = mySeat != null && rules != null && canAfford && seating?.locked != true,
                    ) { Text(stringResource(R.string.mp_confirm_seat), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    // §16.2 pedido de entrada (no lugar do primeiro robô): o anfitrião aceita ou recusa.
    ui.joinRequests.firstOrNull()?.let { request ->
        val robot = (seating?.occupants?.getOrNull(request.seat.index) as? SeatOccupant.Robot)?.persona
            ?.let { code -> BotProfile.entries.firstOrNull { it.code == code }?.botName }.orEmpty()
        GMessage(
            modifier = Modifier.testTag("mp-join-request"),
            title = stringResource(R.string.mp_join_request, request.name.ifBlank { stringResource(R.string.mp_someone) }, robot),
            illustration = MessageIllustration.Question,
            confirmText = stringResource(R.string.mp_join_accept),
            onConfirm = { actions.approve(request.requestId) },
            confirmTag = "mp-join-accept",
            dismissText = stringResource(R.string.mp_join_refuse),
            onDismiss = { actions.refuse(request.requestId) },
            dismissTag = "mp-join-refuse",
            onDismissRequest = {},
        )
    }
    end?.let { reason -> MpEndMessage(reason, actions.leave) }
}

@Composable
private fun NoticeStrip(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(GColors.Indigo, shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.6f), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .testTag("seat-picker-notice"),
        color = GColors.Ivory,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Mensagem de fim da mesa (recusa, mesa não achada, mesa encerrada) com volta. */
@Composable
fun MpEndMessage(reason: MpEnd, onClose: () -> Unit) {
    // §16.2 recusa do convite ou do pedido de entrada: o modal Recusa, com o motivo.
    if (reason is MpEnd.Refused && reason.reason != null) {
        GMessage(
            modifier = Modifier.testTag("mp-refusal"),
            title = stringResource(R.string.mp_refusal_title),
            text = stringResource(refusalText(reason.reason)),
            illustration = MessageIllustration.Info,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.mp_ok_short),
            confirmTag = "mp-refusal-ok",
            onConfirm = onClose,
            onDismissRequest = onClose,
        )
        return
    }
    GMessage(
        modifier = Modifier.testTag("mp-end"),
        title = stringResource(R.string.mp_closed_title),
        text = when (reason) {
            is MpEnd.Refused -> reason.message
            MpEnd.Closed -> stringResource(R.string.mp_closed_text)
            MpEnd.NotFound -> stringResource(R.string.mp_not_found)
            is MpEnd.Ended -> stringResource(
                if (reason.reason == com.gtranca.net.CloseReason.HOST_LEFT) R.string.mp_closed_host_left else R.string.mp_closed_host_offline,
            )
        },
        illustration = MessageIllustration.Info,
        tone = MessageTone.Warning,
        confirmText = stringResource(R.string.back_to_home),
        onConfirm = onClose,
        onDismissRequest = onClose,
    )
}

/** §16.2 texto do motivo de uma recusa (o mesmo nos dois lados). */
fun refusalText(reason: com.gtranca.net.RefusalReason): Int = when (reason) {
    com.gtranca.net.RefusalReason.INVITE_REFUSED -> R.string.mp_refusal_invite_refused
    com.gtranca.net.RefusalReason.INSUFFICIENT_BALANCE -> R.string.mp_refusal_balance
    com.gtranca.net.RefusalReason.OUT_OF_RANGE -> R.string.mp_refusal_out_of_range
    com.gtranca.net.RefusalReason.HOST_REFUSED -> R.string.mp_refusal_host
    com.gtranca.net.RefusalReason.TABLE_FULL -> R.string.mp_refusal_full
}

/**
 * §16.2 avatar de quem ocupa um assento: o próprio jogador com o perfil local ([self]); outro humano com a foto recebida
 * ([photos]) ou o medalhão com a inicial; o robô com o retrato do personagem no nível da mesa.
 */
internal fun occupantAvatar(occupant: SeatOccupant, ui: SeatPickerUi, level: BotLevel, self: Persona?): Persona = when (occupant) {
    is SeatOccupant.Human -> {
        val name = ui.names[occupant.playerId].orEmpty()
        if (occupant.playerId == ui.myId && self != null) self.copy(firstName = name.ifBlank { self.firstName }, lastName = "")
        else Persona(name, color = occupant.playerId.mod(Persona.COLORS), photoBytes = ui.photos[occupant.playerId])
    }
    is SeatOccupant.Robot -> {
        val profile = BotProfile.entries.firstOrNull { it.code == occupant.persona } ?: BotProfile.entries.first()
        Persona(profile.botName, "", BotCharacter(profile, level))
    }
}

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
 * parceiro em cima e os adversários nas laterais, cada um com o avatar, o nome e a marca de confirmado.
 */
@Composable
private fun RoundTable(seating: SeatingState, mySeat: Seat, ui: SeatPickerUi, level: BotLevel, self: Persona?, modifier: Modifier) {
    val mode = seating.mode
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
            val occupant = seating.occupants[seat.index]
            SeatSlot(
                seat,
                occupantAvatar(occupant, ui, level, self),
                confirmed = seating.isConfirmed(seat),
                mine = role == SeatRole.YOU,
                mode,
                Modifier.align(align),
            )
        }
    }
}

/** Um assento: avatar, nome, a dupla e a marca de posição confirmada; o do jogador com aro azul. */
@Composable
private fun SeatSlot(seat: Seat, avatar: Persona, confirmed: Boolean, mine: Boolean, mode: GameMode, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Column(
        modifier
            .widthIn(max = 116.dp)
            .heightIn(min = 48.dp)
            .background(GColors.Indigo, shape)
            .border(if (mine) 3.dp else 1.dp, if (mine) GColors.SkyBlue else GColors.Gold.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("seat-slot-${seat.index}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.gtranca.ui.persona.PersonaAvatar(avatar, 40.dp, Modifier.testTag("seat-avatar-${seat.index}"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                avatar.firstName,
                Modifier.weight(1f, fill = false),
                color = GColors.Champagne,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            if (confirmed) {
                Spacer(Modifier.size(4.dp))
                val description = stringResource(R.string.mp_seat_confirmed)
                Text(
                    "✓",
                    Modifier.semantics { contentDescription = description }.testTag("seat-slot-${seat.index}-confirmed"),
                    color = GColors.Gold,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (mode == GameMode.DUPLAS) {
            Text(stringResource(R.string.mp_team, mode.sideOf(seat).index + 1), color = GColors.Steel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Tempo sem digitar até o nome ir para a mesa. */
private const val NAME_DEBOUNCE_MILLIS = 600L

/** Tamanho máximo do nome na mesa (cabe na pílula do assento). */
private const val NAME_MAX = 16
