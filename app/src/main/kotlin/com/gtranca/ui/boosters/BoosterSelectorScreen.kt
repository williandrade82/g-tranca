package com.gtranca.ui.boosters

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.BoosterKind
import com.gtranca.engine.Loadout
import com.gtranca.ui.inventory.boosterIconRes
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §17.3 o que o seletor mostra: os 3 nichos, o estoque e o nicho que está escolhendo o booster. */
data class BoosterSelectorUi(
    /** Os 3 nichos: o id do booster ou `null` (vazio, com o "+"). */
    val slots: List<String?> = List(Loadout.MAX) { null },
    val catalog: List<BoosterDef> = emptyList(),
    val quantities: Map<String, Int> = emptyMap(),
    /** Nicho vazio tocado: abre a lista de boosters. */
    val picking: Int? = null,
    /** Já leu o inventário (antes disso o Seguir e o prazo esperam). */
    val ready: Boolean = false,
    /** Seguir já foi acionado (botão e prazo só valem uma vez; a seleção trava mesmo que [error] apareça depois). */
    val confirmed: Boolean = false,
    /** §17.9 a mesa permite os boosters de skill; sem isso eles ficam esmaecidos e não entram nos nichos. */
    val allowSkill: Boolean = true,
    /**
     * §18.3 ids sempre fora do seletor nesta mesa (b3/Reações online, telas.md 3.19 "Online"): esmaecidos, com o aviso
     * fixo da lista (`boosters_reactions_online_blocked`); nunca vêm do servidor, é constante do cliente online.
     */
    val blocked: Set<String> = emptySet(),
    /**
     * §18.3 a última tentativa de Seguir falhou (rede ou servidor fora, telas.md 3.19 "Online"): mostra a mensagem e o
     * botão volta a responder para **tentar de novo a mesma seleção já travada** (nunca gera uma lista diferente).
     */
    val error: String? = null,
) {
    /** O booster pode entrar num nicho: a mesa o permite, tem quantidade e ainda não foi escolhido. */
    fun canPick(id: String): Boolean = id !in slots && allowed(id) && (quantities[id] ?: 0) >= (catalog.firstOrNull { it.id == id }?.cost ?: Int.MAX_VALUE)

    /** §17.9/§18.3 a mesa permite o booster [id] (os de interação sempre; os de skill só se [allowSkill]; nunca um [blocked]). */
    fun allowed(id: String): Boolean = id !in blocked && (allowSkill || catalog.firstOrNull { it.id == id }?.kind != BoosterKind.SKILL)

    /** O botão "Seguir" responde: antes do 1º toque, ou de novo depois de um [error] (tentar de novo). */
    val canConfirm: Boolean get() = ready && (!confirmed || error != null)
}

/** O resultado de uma tentativa de Seguir (§17.3): o que valeu, ou a falha com a mensagem para "Tentar de novo". */
sealed interface BoosterConfirmResult {
    data class Ok(val boosters: List<String>) : BoosterConfirmResult
    data class Failed(val message: String) : BoosterConfirmResult
}

/** O que o seletor carrega ao abrir: as quantidades disponíveis e a marcação para pré-preencher os nichos (§17.3). */
data class BoosterSourceState(val quantities: Map<String, Int> = emptyMap(), val marked: List<String> = emptyList())

/**
 * De onde o seletor lê o estoque e para onde manda a escolha (§17.3): o Inventário do aparelho ([LocalBoosterSource],
 * jogo contra robôs e multijogador local) ou o inventário online da conta ([com.gtranca.ui.online.OnlineBoosterSource],
 * §18.3/§18.5). Troca só a fonte; o seletor (`BoosterSelectorViewModel`/`BoosterSelectorScreen`) é o mesmo.
 */
interface BoosterSelectionSource {
    val catalog: List<BoosterDef>

    /** Lido uma vez, ao abrir o seletor. */
    suspend fun load(): BoosterSourceState

    /** §18.3 rascunho (sem travar nada): a fonte local ignora; a online manda ao servidor para o prazo nunca descartar a seleção. */
    suspend fun draft(boosters: List<String>) {}

    /** Seguir (ou tentar de novo a mesma lista depois de um [BoosterConfirmResult.Failed]): confirma [boosters]. */
    suspend fun confirm(boosters: List<String>): BoosterConfirmResult
}

/** [BoosterSelectionSource] do Inventário do aparelho (§17.2): o comportamento de sempre, sem rede. */
class LocalBoosterSource(private val repository: InventoryRepository) : BoosterSelectionSource {
    override val catalog: List<BoosterDef> = repository.catalog

    override suspend fun load(): BoosterSourceState {
        val inventory = repository.inventory.first()
        return BoosterSourceState(catalog.associate { it.id to inventory.quantity(it.id) }, repository.selection.first())
    }

    override suspend fun confirm(boosters: List<String>): BoosterConfirmResult = try {
        BoosterConfirmResult.Ok(repository.confirmLoadout(boosters).boosters)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Inventário ilegível: segue sem boosters (comportamento de sempre, sem servidor para tentar de novo).
        BoosterConfirmResult.Ok(emptyList())
    }
}

/** §17.3 seletor de boosters antes de cada partida: começa com a marcação da fonte; Seguir confirma e entrega o que valeu. */
class BoosterSelectorViewModel(
    private val source: BoosterSelectionSource,
    allowSkill: Boolean = true,
    /** §18.3 ids sempre fora (b3/Reações online); vazio fora do online. */
    blocked: Set<String> = emptySet(),
) : ViewModel() {
    /** Compatibilidade: o Inventário do aparelho (jogo contra robôs, §17.3, e multijogador local, §16.6). */
    constructor(repository: InventoryRepository, allowSkill: Boolean = true) : this(LocalBoosterSource(repository), allowSkill)

    private val _state = MutableStateFlow(BoosterSelectorUi(catalog = source.catalog, allowSkill = allowSkill, blocked = blocked))
    val state: StateFlow<BoosterSelectorUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val loaded = source.load()
                _state.update {
                    it.copy(
                        slots = List(Loadout.MAX) { index -> loaded.marked.filter { id -> it.allowed(id) }.getOrNull(index) },
                        quantities = loaded.quantities,
                        ready = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Inventário ilegível ou servidor fora: segue sem boosters (nichos vazios).
                _state.update { it.copy(ready = true) }
            }
            sendDraft()
        }
    }

    /** Último rascunho mandado (`null` = nenhum ainda): evita mandar de novo a mesma lista sem precisar (ex.: abrir e cancelar a lista de boosters sem trocar nada). */
    private var lastDraft: List<String>? = null

    /** §18.3 manda a marcação de agora como rascunho só se ela mudou desde a última vez (a fonte local ignora). */
    private fun sendDraft() {
        val ui = _state.value
        if (!ui.ready || ui.confirmed) return
        val chosen = ui.slots.filterNotNull().filter(ui::allowed)
        if (chosen == lastDraft) return
        lastDraft = chosen
        viewModelScope.launch { source.draft(chosen) }
    }

    /** Nicho cheio: esvazia; vazio: abre a lista. */
    fun onSlot(index: Int) {
        _state.update {
            if (it.confirmed) it
            else if (it.slots[index] != null) it.copy(slots = it.slots.toMutableList().also { s -> s[index] = null })
            else it.copy(picking = index)
        }
        sendDraft()
    }

    fun onPick(id: String) {
        _state.update {
            val index = it.picking
            if (it.confirmed || index == null || !it.canPick(id)) it.copy(picking = null)
            else it.copy(slots = it.slots.toMutableList().also { s -> s[index] = id }, picking = null)
        }
        sendDraft()
    }

    fun onDismissPick() = _state.update { it.copy(picking = null) }

    /**
     * Seguir (ou fim do prazo, ou "Tentar de novo"): confirma a seleção; a partir do 1º toque os nichos travam (mesmo em
     * caso de falha, §18.3 "nunca gera uma lista diferente" — [BoosterSelectorUi.canConfirm] só libera o botão de novo,
     * para reenviar a MESMA lista).
     */
    fun confirm(onDone: (List<String>) -> Unit) {
        val ui = _state.value
        if (!ui.canConfirm) return
        val chosen = ui.slots.filterNotNull().filter(ui::allowed)
        _state.update { it.copy(confirmed = true, picking = null, error = null) }
        viewModelScope.launch {
            when (val result = source.confirm(chosen)) {
                is BoosterConfirmResult.Ok -> onDone(result.boosters)
                is BoosterConfirmResult.Failed -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    companion object {
        /** §17.3, §16.15 prazo para escolher. */
        const val DEADLINE_SECONDS = 30
    }
}

/** Posição dos nichos e do botão na imagem do seletor (frações; `boosters-selector-v1.png`). */
private object Panel {
    const val ASPECT = 1000f / 914f
    val nicheCenterX = listOf(0.232f, 0.499f, 0.768f)
    const val NICHE_CENTER_Y = 0.636f
    const val NICHE_W = 0.225f
    const val NICHE_H = 0.235f
    const val BUTTON_CENTER_X = 0.706f
    const val BUTTON_CENTER_Y = 0.851f
    const val BUTTON_W = 0.43f
    const val BUTTON_H = 0.12f
    const val TIMER_CENTER_X = 0.274f
    const val TIMER_CENTER_Y = 0.877f
}

/**
 * Tela *Seletor de boosters* (docs/telas.md 3.19). [onConfirmed] recebe o que valeu na partida (já debitado).
 * [deadlineAtMs] é o instante ([SystemClock.elapsedRealtime]) em que o prazo acaba; `null` = os 30 s de sempre a partir
 * da abertura (jogo contra robôs e multijogador local). No online (§18.3) o prazo é do SERVIDOR: passe o instante
 * derivado de `BoosterPrompt.deadlineMs` recebido, para o contador bater com o relógio de quem decide de verdade.
 */
@Composable
fun BoosterSelectorScreen(viewModel: BoosterSelectorViewModel, deadlineAtMs: Long? = null, onConfirmed: (List<String>) -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val localDeadline = rememberSaveable { SystemClock.elapsedRealtime() + BoosterSelectorViewModel.DEADLINE_SECONDS * 1000L }
    val deadline = deadlineAtMs ?: localDeadline
    var remaining by remember { mutableIntStateOf(BoosterSelectorViewModel.DEADLINE_SECONDS) }
    // Sem confirmar em 30 s, vale o que está selecionado, como se tivesse tocado em Seguir (uma vez só: um erro depois
    // não dispara Seguir de novo sozinho, só o toque em "Tentar de novo" reenvia a seleção já travada).
    LaunchedEffect(ui.ready, deadline) {
        if (!ui.ready) return@LaunchedEffect
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            remaining = ((left + 999) / 1000).toInt().coerceIn(0, BoosterSelectorViewModel.DEADLINE_SECONDS)
            if (left <= 0) {
                if (!ui.confirmed) viewModel.confirm(onConfirmed)
                return@LaunchedEffect
            }
            delay(250)
        }
    }
    // O fundo é só o feltro da mesa (sem cartas, jogos nem mãos): a tela parece a mesa de jogo e só o painel do seletor aparece,
    // antes da distribuição.
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(GColors.Table, GColors.TableDark), radius = 1400f))
            .safeDrawingPadding()
            .testTag("boosters-selector"),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(Spacing.sm)) {
            val w = maxWidth
            val h = w / Panel.ASPECT
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(w, h)) {
                Image(painterResource(R.drawable.booster_selector), null, Modifier.size(w, h), contentScale = ContentScale.FillBounds)
                ui.slots.forEachIndexed { index, id -> Niche(ui, index, id, w, h, viewModel) }
                val buttonW = w * Panel.BUTTON_W
                val buttonH = h * Panel.BUTTON_H
                val go = stringResource(R.string.boosters_go)
                // O botão verde está pintado na imagem: a área dele é o alvo do toque (só antes do 1º Seguir; depois de
                // um erro, o botão "Tentar de novo" visível abaixo do painel é que reenvia a mesma seleção já travada).
                Box(
                    Modifier
                        .offset(w * Panel.BUTTON_CENTER_X - buttonW / 2, h * Panel.BUTTON_CENTER_Y - buttonH / 2)
                        .size(buttonW, buttonH)
                        .clickable(enabled = ui.ready && !ui.confirmed, role = Role.Button) { viewModel.confirm(onConfirmed) }
                        .semantics { contentDescription = go }
                        .testTag("boosters-go"),
                )
                val timerW = w * 0.3f
                val timerH = h * 0.06f
                Box(
                    Modifier
                        .offset(w * Panel.TIMER_CENTER_X - timerW / 2, h * Panel.TIMER_CENTER_Y - timerH / 2)
                        .size(timerW, timerH)
                        .semantics { contentDescription = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    FitText(
                        stringResource(R.string.boosters_timer, remaining),
                        TextStyle(color = GColors.Ivory, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center),
                        Modifier.testTag("boosters-timer"),
                        minFontSp = 8,
                    )
                }
            }
            // §18.3 erro de rede ou servidor fora ao confirmar: "Tentar de novo" reenvia a mesma seleção já travada.
            ui.error?.let { message ->
                Text(
                    message,
                    Modifier.padding(top = Spacing.sm).widthIn(max = 560.dp).testTag("boosters-confirm-error"),
                    color = GColors.Champagne,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                GButton(
                    onClick = { viewModel.confirm(onConfirmed) },
                    modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp).testTag("boosters-retry"),
                    kind = GButtonKind.Secondary,
                ) { Text(stringResource(R.string.online_retry)) }
            }
            }
        }
    }
    ui.picking?.let { PickerDialog(ui, viewModel) }
}

@Composable
private fun Niche(ui: BoosterSelectorUi, index: Int, id: String?, w: Dp, h: Dp, viewModel: BoosterSelectorViewModel) {
    val nicheW = w * Panel.NICHE_W
    val nicheH = h * Panel.NICHE_H
    val def = ui.catalog.firstOrNull { it.id == id }
    val description = if (def == null) stringResource(R.string.boosters_slot_empty, index + 1) else stringResource(R.string.boosters_slot_filled, def.name, ui.quantities[def.id] ?: 0)
    Box(
        Modifier
            .offset(w * Panel.nicheCenterX[index] - nicheW / 2, h * Panel.NICHE_CENTER_Y - nicheH / 2)
            .size(nicheW, nicheH)
            .clickable(enabled = ui.ready && !ui.confirmed, role = Role.Button) { viewModel.onSlot(index) }
            .semantics { contentDescription = description }
            .testTag("booster-pick-$index"),
        contentAlignment = Alignment.Center,
    ) {
        if (def != null) {
            boosterIconRes(def.id)?.let { Image(painterResource(it), null, Modifier.fillMaxSize().padding(bottom = nicheH * 0.22f, top = 2.dp), contentScale = ContentScale.Fit) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(def.name, TextStyle(color = GColors.Ivory, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center), minFontSp = 6)
                Text(
                    "×${ui.quantities[def.id] ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColors.Gold,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.testTag("booster-pick-qty-$index"),
                )
            }
        }
    }
}

/** Lista de boosters para o nicho vazio tocado; sem quantidade ou já escolhido: esmaecido e sem toque. */
@Composable
private fun PickerDialog(ui: BoosterSelectorUi, viewModel: BoosterSelectorViewModel) {
    GDialog(
        onDismissRequest = viewModel::onDismissPick,
        confirmButton = {
            GButton(viewModel::onDismissPick, Modifier.heightIn(min = 48.dp), kind = GButtonKind.Text) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.boosters_pick_title)) },
        text = {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("boosters-picker"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!ui.allowSkill) {
                Text(stringResource(R.string.selector_skill_blocked), Modifier.testTag("boosters-skill-blocked"), style = MaterialTheme.typography.bodySmall, color = GColors.Champagne)
            }
            // §18.3 b3 (Reações) ainda não funciona nas mesas online: aviso fixo, nunca vindo do servidor.
            if (ui.blocked.isNotEmpty()) {
                Text(
                    stringResource(R.string.boosters_reactions_online_blocked),
                    Modifier.testTag("boosters-reactions-online-blocked"),
                    style = MaterialTheme.typography.bodySmall,
                    color = GColors.Champagne,
                )
            }
            ui.catalog.forEach { def ->
                val enabled = ui.canPick(def.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (enabled) 1f else 0.4f)
                        .background(GColors.Midnight.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled, role = Role.Button) { viewModel.onPick(def.id) }
                        .padding(Spacing.sm)
                        .testTag("booster-option-${def.id}"),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    boosterIconRes(def.id)?.let { Image(painterResource(it), null, Modifier.size(48.dp), contentScale = ContentScale.Fit) }
                    Column(Modifier.weight(1f)) {
                        Text(def.name, style = MaterialTheme.typography.titleSmall, color = GColors.Gold, fontWeight = FontWeight.Bold)
                        Text(def.summary, style = MaterialTheme.typography.bodySmall, color = GColors.Ivory)
                        Text(stringResource(boosterKindRes(def.kind)), style = MaterialTheme.typography.labelSmall, color = GColors.Champagne)
                    }
                    Text("×${ui.quantities[def.id] ?: 0}", style = MaterialTheme.typography.titleSmall, color = GColors.Gold, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        },
    )
}

/** §17.1 o nome do tipo do booster ("Booster de Skill" / "Booster de Interação"). */
fun boosterKindRes(kind: BoosterKind): Int = when (kind) {
    BoosterKind.SKILL -> R.string.booster_kind_skill
    BoosterKind.INTERACTION -> R.string.booster_kind_interaction
}
