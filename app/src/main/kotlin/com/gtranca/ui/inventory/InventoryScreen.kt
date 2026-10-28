package com.gtranca.ui.inventory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gtranca.R
import com.gtranca.data.InventoryRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Loadout
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.TabularNums
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Um booster do armário: o catálogo, a quantidade que o jogador tem e se está marcado para a próxima partida (§17.2). */
data class InventoryItem(val def: BoosterDef, val quantity: Int, val selected: Boolean)

/** Aviso discreto quando uma marcação não cabe (docs/telas.md 3.18). */
enum class InventoryNotice { MAX_SELECTED, NO_STOCK }

data class InventoryUi(
    val items: List<InventoryItem> = emptyList(),
    /** Saldo de moedas (item do inventário, só para ver: não se marca); `null` enquanto lê. */
    val balance: Int? = null,
    val notice: InventoryNotice? = null,
) {
    val selectedCount: Int get() = items.count { it.selected }
}

/** §17.2 Inventário: o estoque e a marcação de até 3 boosters para a próxima partida, gravada na hora. */
class InventoryViewModel(
    private val repository: InventoryRepository,
    wallet: WalletRepository? = null,
) : ViewModel() {
    private val notice = MutableStateFlow<InventoryNotice?>(null)

    val state: StateFlow<InventoryUi> = combine(
        repository.inventory,
        repository.selection,
        wallet?.wallet?.map<com.gtranca.engine.Wallet, Int?> { it.balance }?.catch { emit(null) } ?: flowOf<Int?>(null),
        notice,
    ) { inventory, selection, balance, notice ->
        InventoryUi(
            items = repository.catalog.map { InventoryItem(it, inventory.quantity(it.id), it.id in selection) },
            balance = balance,
            notice = notice,
        )
    }
        .catch { emit(InventoryUi()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, InventoryUi())

    /** Marca ou desmarca [id]; a marca que não cabe (quarta, sem estoque) só mostra o aviso. */
    fun onToggle(id: String) {
        viewModelScope.launch {
            try {
                val inventory = repository.inventory.first()
                val current = repository.selection.first()
                val next = Loadout.toggle(current, id, inventory, repository.catalog)
                if (next == current) {
                    notice.value = if (Loadout.isSelectable(id, inventory, repository.catalog)) InventoryNotice.MAX_SELECTED else InventoryNotice.NO_STOCK
                } else {
                    notice.value = null
                    repository.setSelection(next)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Falha de disco: a marcação continua a de antes.
            }
        }
    }
}

/** Ícone do booster na bandeja e no seletor (docs/boosters); desconhecido: sem imagem. */
fun boosterIconRes(id: String): Int? = when (id) {
    BoosterCatalog.GREEN_LED -> R.drawable.booster_b1
    BoosterCatalog.RED_LED -> R.drawable.booster_b2
    BoosterCatalog.REACTIONS -> R.drawable.booster_b3
    else -> null
}

/** Ícone do armário (grade 3 × 3 de nichos), desenhado no código, para a barra do Início. */
@Composable
fun InventoryIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        drawRoundRect(color, Offset(stroke / 2, stroke / 2), Size(w - stroke, h - stroke), CornerRadius(w * 0.1f), style = Stroke(stroke))
        for (i in 1..2) {
            drawLine(color, Offset(w * i / 3f, stroke), Offset(w * i / 3f, h - stroke), stroke * 0.8f)
            drawLine(color, Offset(stroke, h * i / 3f), Offset(w - stroke, h * i / 3f), stroke * 0.8f)
        }
    }
}

/** Posição dos nichos na imagem do armário (frações da largura e da altura; `booster-stock-v1.png`, 3 × 3). */
private object Shelf {
    const val ASPECT = 1000f / 914f
    val nicheCenterX = listOf(0.215f, 0.499f, 0.783f)
    val nicheCenterY = listOf(0.2325f, 0.5175f, 0.7925f)
    const val NICHE_W = 0.255f
    const val NICHE_H = 0.265f

    /** O ícone fica na cavidade de cima do nicho. */
    val iconCenterY = listOf(0.205f, 0.487f, 0.765f)
    const val ICON_W = 0.17f

    /** A plaquinha do nome (embaixo à esquerda) e o aro da quantidade (embaixo à direita). */
    val plateCenterX = listOf(0.186f, 0.470f, 0.752f)
    val ringCenterX = listOf(0.311f, 0.597f, 0.878f)
    val labelCenterY = listOf(0.335f, 0.620f, 0.874f)
    const val PLATE_W = 0.165f
    const val PLATE_H = 0.045f
    const val RING_D = 0.05f
}

@Composable
fun InventoryScreen(viewModel: InventoryViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    GBackground(Modifier.fillMaxSize().testTag("inventory-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GTitle(stringResource(R.string.inventory_title), Modifier.semantics { heading() })
            Text(
                stringResource(R.string.inventory_hint, Loadout.MAX),
                style = MaterialTheme.typography.bodyMedium,
                color = GColors.OnTable,
                textAlign = TextAlign.Center,
            )
            Shelf(ui, viewModel::onToggle)
            Text(
                stringResource(R.string.inventory_selected_count, ui.selectedCount, Loadout.MAX),
                style = MaterialTheme.typography.titleMedium,
                color = GColors.Gold,
                modifier = Modifier.testTag("inventory-selected"),
            )
            when (ui.notice) {
                InventoryNotice.MAX_SELECTED -> Notice(stringResource(R.string.inventory_notice_max, Loadout.MAX))
                InventoryNotice.NO_STOCK -> Notice(stringResource(R.string.inventory_notice_stock))
                null -> Unit
            }
            GButton(onBack, Modifier.heightIn(min = 48.dp).testTag("inventory-back"), kind = GButtonKind.Text) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = GColors.Ivory,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(GColors.Midnight.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .border(1.dp, GColors.Gold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .testTag("inventory-notice"),
    )
}

/** O armário com 9 nichos: as moedas primeiro (só para ver) e os boosters do catálogo, na ordem. */
@Composable
private fun Shelf(ui: InventoryUi, onToggle: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
        val w = maxWidth
        val h = w / Shelf.ASPECT
        Box(Modifier.size(w, h)) {
            Image(painterResource(R.drawable.booster_stock), null, Modifier.size(w, h), contentScale = ContentScale.FillBounds)
            val coinName = stringResource(R.string.inventory_coins)
            val slots = buildList {
                ui.balance?.let { add(Slot("coins", coinName, R.drawable.coin_bag, it, selected = false, enabled = false)) }
                ui.items.forEach { add(Slot(it.def.id, it.def.name, boosterIconRes(it.def.id), it.quantity, it.selected, enabled = true)) }
            }.take(9)
            slots.forEachIndexed { index, slot -> Niche(slot, index % 3, index / 3, w, h, onToggle) }
        }
    }
}

private data class Slot(val id: String, val name: String, val icon: Int?, val quantity: Int, val selected: Boolean, val enabled: Boolean)

@Composable
private fun Niche(slot: Slot, col: Int, row: Int, w: Dp, h: Dp, onToggle: (String) -> Unit) {
    val empty = slot.quantity <= 0 && slot.enabled
    val description = stringResource(if (slot.selected) R.string.inventory_slot_desc_selected else R.string.inventory_slot_desc, slot.name, slot.quantity)
    fun xAt(fraction: Float, size: Dp) = w * fraction - size / 2
    fun yAt(fraction: Float, size: Dp) = h * fraction - size / 2
    val nicheW = w * Shelf.NICHE_W
    val nicheH = h * Shelf.NICHE_H
    val shape = RoundedCornerShape(10.dp)
    // O nicho todo é o alvo do toque e leva o aro dourado quando marcado.
    Box(
        Modifier
            .offset(xAt(Shelf.nicheCenterX[col], nicheW), yAt(Shelf.nicheCenterY[row], nicheH))
            .size(nicheW, nicheH)
            .then(if (slot.selected) Modifier.border(3.dp, GColors.Gold, shape).background(GColors.Gold.copy(alpha = 0.12f), shape) else Modifier)
            .then(if (slot.enabled) Modifier.clickable(role = Role.Button) { onToggle(slot.id) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("booster-slot-${slot.id}"),
    )
    slot.icon?.let { icon ->
        val iconW = w * Shelf.ICON_W
        Image(
            painterResource(icon),
            null,
            Modifier
                .offset(xAt(Shelf.nicheCenterX[col], iconW), yAt(Shelf.iconCenterY[row], iconW * 0.8f))
                .size(iconW, iconW * 0.8f)
                .alpha(if (empty) 0.35f else 1f),
            contentScale = ContentScale.Fit,
        )
    }
    val plateW = w * Shelf.PLATE_W
    val plateH = h * Shelf.PLATE_H
    Box(
        Modifier.offset(xAt(Shelf.plateCenterX[col], plateW), yAt(Shelf.labelCenterY[row], plateH)).size(plateW, plateH),
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            slot.name,
            TextStyle(color = Color(0xFF3B2410), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center),
            minFontSp = 6,
        )
    }
    val ring = w * Shelf.RING_D
    Box(
        Modifier.offset(xAt(Shelf.ringCenterX[col], ring), yAt(Shelf.labelCenterY[row], ring)).size(ring),
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            slot.quantity.toString(),
            TextStyle(color = GColors.Gold, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, textAlign = TextAlign.Center, fontFeatureSettings = TabularNums),
            Modifier.testTag("booster-qty-${slot.id}"),
            minFontSp = 6,
        )
    }
}
