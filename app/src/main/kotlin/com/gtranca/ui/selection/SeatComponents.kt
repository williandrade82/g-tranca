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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

internal val Felt = Color(0xFF1E6B3A)
internal val FeltDark = Color(0xFF124A27)
internal val Wood = Color(0xFF6B3E1F)

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

/**
 * A imagem de apresentação do robô **inteira** (sem recortar em círculo), do jeito que ela aparece na mesa de lugares
 * (docs/telas.md 3.3): grande, do tamanho de [height] (a largura segue a proporção da imagem). [highlighted] marca a
 * seleção pendente com uma moldura dourada.
 */
@Composable
fun RobotStandee(profile: BotProfile, level: BotLevel, height: Dp, highlighted: Boolean, modifier: Modifier = Modifier) {
    Image(
        painterResource(characterRes(BotCharacter(profile, level), Pose.APRESENTACAO)),
        null,
        modifier
            .height(height)
            .then(if (highlighted) Modifier.border(3.dp, GColors.Gold, RoundedCornerShape(20.dp)) else Modifier),
        contentScale = ContentScale.FillHeight,
    )
}

/**
 * A foto do próprio jogador (ou do anfitrião) **inteira** na mesa de lugares (docs/telas.md 3.3), do tamanho de
 * [height] em altura; sem foto, o medalhão com a inicial continua redondo. [highlighted] marca a seleção pendente com
 * uma moldura dourada. Só para quem nunca leva selo nem anel de convite (o próprio jogador e o anfitrião); Chamado e
 * Recrutado continuam em [SealedAvatar], que precisa do círculo para o selo e o anel.
 */
@Composable
fun PersonaStandee(persona: Persona, height: Dp, highlighted: Boolean, modifier: Modifier = Modifier) {
    PersonaAvatar(
        persona,
        height,
        modifier.then(if (highlighted) Modifier.border(3.dp, GColors.Gold, RoundedCornerShape(20.dp)) else Modifier),
        crop = false,
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

/** O lugar VAGO: quadrado (cantos levemente arredondados) com o texto. */
@Composable
private fun VacantAvatar(size: Dp, highlighted: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(GColors.Midnight.copy(alpha = 0.55f))
            .border(if (highlighted) 3.dp else 1.5.dp, if (highlighted) GColors.Gold else GColors.Steel.copy(alpha = 0.7f), shape),
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
            SlotLook(profile.botName, null, false, stringResource(R.string.a11y_robot_seated, profile.botName)) { RobotStandee(profile, level, it, highlighted) }
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
 * A mesa de lugares (docs/telas.md 3.3 e 3.13): a imagem da mesa (a mesma do Início) com os lugares **acima** dela —
 * em duplas, adversário esquerda / parceiro / adversário direita; no individual, só o adversário — e o jogador de
 * [viewerSeat] sempre **embaixo**. Interativa só para o recrutador ([interactive]): tocar escolhe/abre o menu e
 * arrastar um avatar troca ou move. Na tela do recrutado ([interactive] falso) é só leitura.
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

    @Composable
    fun SeatItem(index: Int, avatarSize: Dp = SeatAvatar, modifier: Modifier = Modifier) {
        val slot = layout.slots[index]
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
            modifier
                .zIndex(if (dragging) 2f else 0f)
                .widthIn(max = avatarSize + 24.dp)
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
            // O nome fica acima da imagem, alinhado com ela (docs/telas.md 3.3): a posição de cada lugar (esquerda,
            // centro, direita) já vem do Box de fora; aqui só muda a ordem (nome em cima, imagem embaixo).
            Text(
                look.name,
                color = GColors.Champagne,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
            when {
                slot is Slot.Robot -> {
                    // A imagem de apresentação inteira (sem recorte em círculo): não leva selo nem anel de convite.
                    val profile = BotProfile.entries.firstOrNull { it.code == slot.persona } ?: BotProfile.entries.first()
                    RobotStandee(profile, level, avatarSize, highlighted = picked, modifier = Modifier.testTag("$tagPrefix-$index-$stateTag"))
                }
                index == viewerSeat || slot is Slot.Host -> {
                    // A foto do próprio jogador ou do anfitrião, inteira: nunca leva selo nem anel de convite.
                    val persona = if (index == viewerSeat) self else Persona(hostName, color = hostName.hashCode().mod(Persona.COLORS))
                    PersonaStandee(persona, avatarSize, highlighted = picked, modifier = Modifier.testTag("$tagPrefix-$index-$stateTag"))
                }
                slot is Slot.Vacant -> {
                    // Quadrado, não círculo: também não leva selo nem anel.
                    VacantAvatar(avatarSize, picked, Modifier.testTag("$tagPrefix-$index-$stateTag"))
                }
                else -> {
                    // Só sobra Chamado e Recrutado: continuam no círculo, que carrega o selo e o anel do convite.
                    SealedAvatar(
                        avatarSize, look.seal, look.dim, Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-$index-$stateTag"),
                        highlighted = picked,
                        calledAt = (slot as? Slot.Called)?.let { calledAt[it.key] },
                    ) { look.avatar(avatarSize) }
                }
            }
        }
    }

    // Acima da mesa (docs/telas.md 3.3): em duplas o parceiro fica em cima e mais à frente (maior, por cima no
    // desenho) e os dois adversários um pouco abaixo dele, quase encostando na mesa; no individual, só o adversário,
    // no mesmo tamanho do parceiro. O lugar de viewerSeat nunca entra aqui, fica embaixo da imagem.
    val roleOf = layout.slots.indices.associateWith { SeatRole.of(mode, Seat(it), Seat(viewerSeat)) }
    val partnerIndex = roleOf.entries.firstOrNull { it.value == SeatRole.PARTNER }?.key
    val leftIndex = roleOf.entries.firstOrNull { it.value == SeatRole.LEFT_OPPONENT }?.key
    val rightIndex = roleOf.entries.firstOrNull { it.value == SeatRole.RIGHT_OPPONENT }?.key
    val soleOpponentIndex = roleOf.entries.firstOrNull { it.value == SeatRole.OPPONENT }?.key
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (partnerIndex != null) {
            // zIndex à frente da mesa (vem depois, no mesmo Column): o parceiro e os adversários ficam por cima dela.
            Box(Modifier.fillMaxWidth().zIndex(1f)) {
                leftIndex?.let { SeatItem(it, OpponentAvatar, Modifier.align(Alignment.TopStart).offset(x = OpponentInset, y = OpponentDrop)) }
                rightIndex?.let { SeatItem(it, OpponentAvatar, Modifier.align(Alignment.TopEnd).offset(x = -OpponentInset, y = OpponentDrop)) }
                SeatItem(partnerIndex, PartnerAvatar, Modifier.align(Alignment.TopCenter))
            }
        } else if (soleOpponentIndex != null) {
            Box(Modifier.fillMaxWidth()) {
                SeatItem(soleOpponentIndex, PartnerAvatar, Modifier.align(Alignment.Center))
            }
        }
        Image(
            painterResource(R.drawable.home_table),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.92f).aspectRatio(1080f / 316f).offset(y = -TableRise).padding(bottom = 4.dp),
            contentScale = ContentScale.FillWidth,
        )
        SeatItem(viewerSeat)
    }
}

/** Altura do parceiro (duplas) ou do único adversário (individual): maior, para ficar à frente dos demais. */
private val PartnerAvatar = 108.dp

/** Altura dos adversários em duplas: um pouco menor que a do parceiro. */
private val OpponentAvatar = 80.dp

/** Quanto os adversários descem em relação ao parceiro, quase encostando na mesa. */
private val OpponentDrop = 18.dp

/** Quanto os adversários entram por baixo do parceiro (robôs grandes e próximos uns dos outros). */
private val OpponentInset = 26.dp

/** Quanto a mesa sobe por cima dos perfis (docs/telas.md 3.3): precisa sobrepor pelo menos um pouco. */
private val TableRise = 14.dp

/** Tamanho do avatar de um lugar. */
private val SeatAvatar = 56.dp
