package com.gtranca.ui.selection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gtranca.R
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotCharacter
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import com.gtranca.game.Persona
import com.gtranca.game.SeatRole
import com.gtranca.net.Slot
import com.gtranca.net.TableLayout
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.Pose
import com.gtranca.ui.persona.characterRes
import com.gtranca.ui.theme.GColors

private val Felt = Color(0xFF1E6B3A)
private val FeltDark = Color(0xFF124A27)
private val Wood = Color(0xFF6B3E1F)

/** Selos de convite (docs/telas.md, glossário): [CALLED] = chamado (avatar esmaecido), [RECRUITED] = aceitou. */
enum class Seal { CALLED, RECRUITED }

/** Tamanho do selo sobre o avatar (~32 dp, docs/telas.md). */
private val SealSize = 32.dp

/** §14.5 a foto de apresentação do robô recortada em círculo (o rosto, sem o corpo). */
@Composable
fun RobotPhoto(profile: BotProfile, level: BotLevel, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(characterRes(BotCharacter(profile, level), Pose.APRESENTACAO)),
        null,
        modifier.size(size).clip(CircleShape).background(GColors.Indigo),
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
    )
}

/** Relógio local (ms) em que os prazos dos convites são medidos (o mesmo do recrutamento do `:net`). */
fun nowMillis(): Long = System.nanoTime() / 1_000_000

/**
 * A fração (1 → 0) que falta de [durationMillis] desde [startedAt] (relógio de [nowMillis]), atualizada a cada ~100 ms;
 * `null` sem [startedAt]. Pára em zero.
 */
@Composable
fun rememberCountdown(startedAt: Long?, durationMillis: Long): Float? {
    if (startedAt == null) return null
    val fraction by androidx.compose.runtime.produceState(1f, startedAt, durationMillis) {
        while (true) {
            val left = durationMillis - (nowMillis() - startedAt)
            value = (left.toFloat() / durationMillis).coerceIn(0f, 1f)
            if (left <= 0) break
            kotlinx.coroutines.delay(100)
        }
    }
    return fraction
}

/**
 * Avatar com o selo [seal] no canto (docs/telas.md): [dim] esmaece o avatar (Chamado e robôs já sentados); o selo fica
 * inteiro. [highlighted] marca a seleção pendente com um aro dourado. [calledAt] (Chamado, no recrutador) põe em volta do
 * avatar um anel fino de contagem regressiva do convite (§16.15). [content] desenha o avatar no tamanho [size].
 */
@Composable
fun SealedAvatar(
    size: Dp,
    seal: Seal?,
    dim: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    calledAt: Long? = null,
    content: @Composable () -> Unit,
) {
    val fraction = rememberCountdown(calledAt, com.gtranca.net.INVITE_TIMEOUT_MILLIS)
    Box(modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if (dim) 0.45f else 1f).clip(CircleShape)) { content() }
        if (highlighted) {
            Box(Modifier.matchParentSize().border(3.dp, GColors.Gold, CircleShape))
        }
        if (fraction != null) {
            val color = if (fraction * com.gtranca.net.INVITE_TIMEOUT_MILLIS <= 5_000) GColors.Ruby else GColors.Gold
            Canvas(Modifier.matchParentSize().testTag("seat-timer")) {
                val stroke = 2.5.dp.toPx()
                drawArc(
                    color, -90f, 360f * fraction, false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        if (seal != null) {
            Image(
                painterResource(if (seal == Seal.CALLED) R.drawable.seal_chamado else R.drawable.seal_recrutado),
                null,
                Modifier.align(Alignment.BottomEnd).size(SealSize),
            )
        }
    }
}

/** O lugar VAGO: círculo tracejado com o texto. */
@Composable
private fun VacantAvatar(size: Dp, highlighted: Boolean) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(GColors.Midnight.copy(alpha = 0.55f))
            .border(if (highlighted) 3.dp else 1.5.dp, if (highlighted) GColors.Gold else GColors.Steel.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.selection_seat_vacant), color = GColors.Steel, style = MaterialTheme.typography.labelMedium)
    }
}

/** O que um lugar mostra: o nome, o avatar e o selo. */
private class SlotLook(val name: String, val seal: Seal?, val dim: Boolean, val describe: String, val avatar: @Composable (Dp) -> Unit)

@Composable
private fun slotLook(
    index: Int,
    slot: Slot,
    viewerSeat: Int,
    self: Persona,
    hostName: String,
    level: BotLevel,
    photos: Map<String, ByteArray>,
    highlighted: Boolean,
): SlotLook {
    val vacantText = stringResource(R.string.selection_seat_vacant)
    return when {
        index == viewerSeat -> SlotLook(self.firstName, null, false, self.firstName) { PersonaAvatar(self, it) }
        slot is Slot.Vacant -> SlotLook(vacantText, null, false, stringResource(R.string.a11y_seat_vacant, index + 1)) { VacantAvatar(it, highlighted) }
        slot is Slot.Host -> {
            val persona = Persona(hostName, color = hostName.hashCode().mod(Persona.COLORS))
            SlotLook(hostName, null, false, hostName) { PersonaAvatar(persona, it) }
        }
        slot is Slot.Robot -> {
            val profile = BotProfile.entries.firstOrNull { it.code == slot.persona } ?: BotProfile.entries.first()
            SlotLook(profile.botName, null, false, stringResource(R.string.a11y_robot_seated, profile.botName)) { RobotPhoto(profile, level, it) }
        }
        slot is Slot.Called -> {
            val persona = Persona(slot.name, color = slot.key.hashCode().mod(Persona.COLORS), photoBytes = photos[slot.key])
            SlotLook(slot.name, Seal.CALLED, true, stringResource(R.string.a11y_called, slot.name)) { PersonaAvatar(persona, it) }
        }
        slot is Slot.Recruited -> {
            val persona = Persona(slot.name, color = slot.key.hashCode().mod(Persona.COLORS), photoBytes = photos[slot.key])
            SlotLook(slot.name, Seal.RECRUITED, false, stringResource(R.string.a11y_recruited, slot.name)) { PersonaAvatar(persona, it) }
        }
        else -> SlotLook("", null, false, "") {}
    }
}

/** O lugar cujo retângulo (em coordenadas da tela) contém [point], ou `null`. Puro, para testar. */
internal fun seatAt(bounds: Map<Int, Rect>, point: Offset): Int? = bounds.entries.firstOrNull { it.value.contains(point) }?.key

/**
 * A mesa de lugares (docs/telas.md 3.3 e 3.13): feltro verde com aro de madeira; o jogador de [viewerSeat] embaixo, o
 * parceiro em cima e os adversários nas laterais. Interativa só para o recrutador ([interactive]): tocar escolhe/abre o
 * menu e arrastar um avatar troca ou move. Na tela do recrutado ([interactive] falso) é só leitura.
 */
@Composable
fun SeatTable(
    layout: TableLayout,
    viewerSeat: Int,
    level: BotLevel,
    self: Persona,
    hostName: String,
    photos: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    selectedSeat: Int? = null,
    /** Quando cada Chamado recebeu o convite (relógio de [nowMillis]): o anel de contagem regressiva. */
    calledAt: Map<String, Long> = emptyMap(),
    tagPrefix: String = "seat",
    onSeatTap: (Int) -> Unit = {},
    onDrop: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    val mode = layout.mode
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.aspectRatio(if (mode.seatCount == 2) 1.15f else 1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(if (mode.seatCount == 2) 28.dp else 36.dp)) {
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Felt, FeltDark), center, radius), radius)
            drawCircle(Wood, radius, style = Stroke(radius * 0.09f))
            drawCircle(GColors.Gold.copy(alpha = 0.5f), radius * 0.955f, style = Stroke(2f))
            drawCircle(GColors.Gold.copy(alpha = 0.25f), radius * 0.35f, center, style = Stroke(2f))
        }
        layout.slots.forEachIndexed { index, slot ->
            val role = SeatRole.of(mode, Seat(index), Seat(viewerSeat))
            val align = when (role) {
                SeatRole.YOU -> Alignment.BottomCenter
                SeatRole.PARTNER, SeatRole.OPPONENT -> Alignment.TopCenter
                SeatRole.LEFT_OPPONENT -> Alignment.CenterStart
                SeatRole.RIGHT_OPPONENT -> Alignment.CenterEnd
            }
            val picked = selectedSeat == index
            val look = slotLook(index, slot, viewerSeat, self, hostName, level, photos, highlighted = picked)
            val stateTag = when {
                slot is Slot.Vacant -> "vacant"
                slot is Slot.Called -> "called"
                slot is Slot.Recruited -> "recruited"
                else -> "filled"
            }
            val draggable = interactive && index != TableLayout.HOST_SEAT && slot != Slot.Vacant
            val dragging = dragFrom == index
            Column(
                Modifier
                    .align(align)
                    .zIndex(if (dragging) 2f else 0f)
                    .widthIn(max = 96.dp)
                    .onGloballyPositioned { bounds[index] = it.boundsInRoot() }
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                        }
                    }
                    .semantics(mergeDescendants = true) { contentDescription = look.describe }
                    .testTag("$tagPrefix-$index")
                    .then(if (interactive) Modifier.clickable { onSeatTap(index) } else Modifier)
                    .then(
                        if (draggable) {
                            Modifier.pointerInput(index, layout) {
                                detectDragGestures(
                                    onDragStart = { dragFrom = index; dragOffset = Offset.Zero },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                    },
                                    onDragEnd = {
                                        val from = index
                                        val origin = bounds[from]
                                        val target = origin?.let { seatAt(bounds, it.center + dragOffset) }
                                        dragFrom = null
                                        dragOffset = Offset.Zero
                                        if (target != null && target != from) onDrop(from, target)
                                    },
                                    onDragCancel = { dragFrom = null; dragOffset = Offset.Zero },
                                )
                            }
                        } else Modifier,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SealedAvatar(
                    SeatAvatar, look.seal, look.dim, Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-$index-$stateTag"),
                    highlighted = picked && slot != Slot.Vacant,
                    calledAt = (slot as? Slot.Called)?.let { calledAt[it.key] },
                ) { look.avatar(SeatAvatar) }
                Text(
                    look.name,
                    color = GColors.Champagne,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Tamanho do avatar de um lugar. */
private val SeatAvatar = 56.dp
