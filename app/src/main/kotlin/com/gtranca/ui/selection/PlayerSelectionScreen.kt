package com.gtranca.ui.selection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import com.gtranca.game.GameConfig
import com.gtranca.game.Persona
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.Slot
import com.gtranca.ui.home.labelRes
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.tables.WifiButton
import com.gtranca.ui.tables.WifiControl
import com.gtranca.ui.tables.BottomButton
import com.gtranca.ui.tables.TableFrame
import com.gtranca.ui.tables.modeLabel
import com.gtranca.ui.tables.rulesName
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.tour.TourButton
import com.gtranca.ui.tour.TourHost
import com.gtranca.ui.tour.Tours
import com.gtranca.ui.tour.tourTarget
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect

/**
 * *Seleção de jogadores* (docs/telas.md 3.3): a mesa de lugares, a lista dos 4 robôs, o bloco Multiplayer com os jogadores
 * próximos livres, "Completar com robôs" e "Começar Partida". As opções (3.2) aparecem só como resumo.
 *
 * @param free jogadores próximos livres (com o Wi-Fi do jogo ligado); `recruitedBy` de outro recrutador = "Recrutado".
 * @param myKey chave de presença deste aparelho (para distinguir "recrutado por mim" de "recrutado por outro").
 * @param photos fotos dos jogadores próximos por chave (já vazio se o jogador não quer vê-las).
 * @param starting a imagem "Prepare-se!" está na tela (Começar Partida tocado): nada mais responde.
 */
@Composable
fun PlayerSelectionScreen(
    vm: PlayerSelectionViewModel,
    self: Persona,
    hostName: String,
    wifi: WifiControl?,
    free: List<PresenceAdvert>,
    myKey: String?,
    photos: Map<String, ByteArray>,
    starting: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val config: GameConfig = vm.config
    val level = BotLevel.of(config.difficulty)
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    // Avisos breves (toque primeiro num lugar vago; não deu para chamar).
    LaunchedEffect(vm) {
        vm.notices.collectLatest {
            val text = context.getString(if (it == SelectionNotice.PICK_SEAT_FIRST) R.string.selection_hint_seat else R.string.selection_cannot_call)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    TourHost(Tours.SELECTION, Tours.selectionSteps, autoStart = true) { startTour ->
        TableFrame(
            stringResource(R.string.selection_title),
            "player-selection",
            bottom = {
                BottomButton(stringResource(R.string.back), "selection-back", GButtonKind.Secondary, enabled = !starting, onClick = onBack)
                BottomButton(stringResource(R.string.selection_start), "start-match", GButtonKind.Primary, enabled = ui.layout.canStart && !starting, onClick = onStart)
            },
            titleExtra = { TourButton(startTour) },
        ) {
            // As opções (3.2) só como resumo: não mudam aqui.
            Text(
                stringResource(R.string.selection_summary, modeLabel(config.mode), stringResource(config.difficulty.labelRes()), config.stake, rulesName(config.rules)),
                Modifier.testTag("selection-summary"),
                style = MaterialTheme.typography.bodyMedium,
                color = GColors.Steel,
            )
            if (ui.movingFrom != null) {
                Text(stringResource(R.string.selection_move_hint), Modifier.testTag("selection-move-hint"), style = MaterialTheme.typography.bodyMedium, color = GColors.Gold)
            }
            SeatTable(
                ui.layout, viewerSeat = 0, level = level, self = self, hostName = hostName, photos = photos,
                modifier = Modifier.fillMaxWidth().tourTarget("seat-table").testTag("seat-table"),
                interactive = !starting,
                selectedSeat = ui.selectedSeat, movingFrom = ui.movingFrom, menuSeat = ui.menuSeat,
                onSeatTap = vm::onSeatTap, onDrop = vm::move, onRemove = vm::onRemove, onMoveStart = vm::onMoveStart, onDismissMenu = vm::onDismissMenu,
            )
            RobotRow(ui.layout.usedPersonas, level, vm::onRobotTap)
            MultiplayerBlock(vm, ui, wifi, free, myKey, photos)
            GButton(
                vm::onFill,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).tourTarget("fill-with-robots").testTag("fill-with-robots"),
                enabled = !starting,
                kind = GButtonKind.Secondary,
            ) { Text(stringResource(R.string.selection_fill)) }
        }
    }
}

/** A lista horizontal dos 4 robôs: foto de apresentação em círculo e o nome embaixo; esmaecido quando já sentado. */
@Composable
private fun RobotRow(used: Set<String>, level: BotLevel, onTap: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().tourTarget("robot-row"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.selection_robots), style = MaterialTheme.typography.titleSmall, color = GColors.Champagne)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BotProfile.entries.forEach { profile ->
                val seated = profile.code in used
                val description = stringResource(if (seated) R.string.a11y_robot_seated else R.string.a11y_robot_available, profile.botName)
                Column(
                    Modifier
                        .width(72.dp)
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .clickable(enabled = !seated) { onTap(profile.code) }
                        .testTag("robot-${profile.code}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SealedAvatar(56.dp, null, dim = seated) { RobotPhoto(profile, level, 56.dp) }
                    Text(profile.botName, style = MaterialTheme.typography.bodySmall, color = GColors.Champagne, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** O bloco Multiplayer: o título, o Wi-Fi do jogo e, ligado, os jogadores próximos livres (chamáveis, chamados, recrutados). */
@Composable
private fun MultiplayerBlock(
    vm: PlayerSelectionViewModel,
    ui: SelectionUi,
    wifi: WifiControl?,
    free: List<PresenceAdvert>,
    myKey: String?,
    photos: Map<String, ByteArray>,
) {
    Column(Modifier.fillMaxWidth().tourTarget("multiplayer-block"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.selection_multiplayer), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = GColors.Champagne)
            wifi?.let { WifiButton(it.on, it.toggle, tag = "selection-wifi") }
        }
        if (wifi?.on != true) return@Column
        if (free.isEmpty() && ui.layout.remotes.isEmpty()) {
            Text(stringResource(R.string.selection_nobody_free), Modifier.testTag("selection-nobody-free"), style = MaterialTheme.typography.bodySmall, color = GColors.Steel)
            return@Column
        }
        // Quem já está nos lugares aparece mesmo se a busca o perdeu por um instante.
        val shown = free + ui.layout.remotes.mapNotNull { (_, slot) ->
            val key = (slot as? Slot.Called)?.key ?: (slot as? Slot.Recruited)?.key
            val name = (slot as? Slot.Called)?.name ?: (slot as? Slot.Recruited)?.name
            if (key != null && name != null && free.none { it.key == key }) PresenceAdvert(key, name, recruitedBy = myKey) else null
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            shown.forEach { who ->
                val seat = ui.layout.seatOf(who.key)
                val slot = seat?.let { ui.layout.slots[it] }
                val elsewhere = slot == null && who.recruitedBy != null && who.recruitedBy != myKey
                val seal = when {
                    slot is Slot.Called -> Seal.CALLED
                    slot is Slot.Recruited -> Seal.RECRUITED
                    elsewhere -> Seal.RECRUITED
                    else -> null
                }
                val description = when {
                    slot is Slot.Called -> stringResource(R.string.a11y_called, who.name)
                    slot is Slot.Recruited -> stringResource(R.string.a11y_recruited, who.name)
                    elsewhere -> stringResource(R.string.a11y_recruited_elsewhere, who.name)
                    else -> stringResource(R.string.a11y_player_available, who.name)
                }
                val persona = Persona(who.name, color = who.key.hashCode().mod(Persona.COLORS), photoBytes = photos[who.key])
                Column(
                    Modifier
                        .width(72.dp)
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .clickable(enabled = slot == null && !elsewhere) { vm.onPlayerTap(who) }
                        .testTag("nearby-free-${who.key}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SealedAvatar(56.dp, seal, dim = slot is Slot.Called) { PersonaAvatar(persona, 56.dp) }
                    Text(who.name, style = MaterialTheme.typography.bodySmall, color = GColors.Champagne, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A imagem "Prepare-se! O jogo vai começar!" em tela cheia (docs/telas.md 4), sem botões; engole os toques. */
@Composable
fun GetReadyImage(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(GColors.Midnight)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .testTag("get-ready"),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.banner_prepare_se),
            stringResource(R.string.mp_get_ready),
            Modifier.fillMaxWidth().padding(16.dp),
            contentScale = ContentScale.FillWidth,
        )
    }
}
