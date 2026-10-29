package com.gtranca.ui.profile

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.foundation.layout.width

import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gtranca.data.RefusalAlert
import com.gtranca.game.DeckColor
import com.gtranca.ui.cards.CardBack
import com.gtranca.ui.cards.CardSize
import com.gtranca.game.PlayerProfile
import com.gtranca.ui.persona.MedalColors
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GChoiceChip
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing

/** Perfil do jogador (§14.1): nome e avatar (foto ou medalhão com a inicial e a cor), com pré-visualização. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onRules: () -> Unit = {},
    /** §16 porta das permissões do Nearby, pedida ao ligar o Wi-Fi do jogo; `null` = liga direto (testes). */
    accessGate: (@Composable (onReady: () -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var askingWifi by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (askingWifi && accessGate != null) {
        // Recusar deixa a chave desligada.
        accessGate({ askingWifi = false; viewModel.onWifiToggle(true) }, { askingWifi = false })
    }
    val profile = state.profile
    val youName = stringResource(R.string.side_you)
    GBackground(Modifier.fillMaxSize().testTag("profile-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.profile_title))
            GPanel(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val preview = profile.copy(hasPhoto = state.photo != PhotoState.NONE).persona(youName, viewModel.previewPhoto)
                    PersonaAvatar(preview, 112.dp, Modifier.semantics { contentDescription = preview.firstName }.testTag("profile-preview"))
                }
                PhotoButtons(state, viewModel)
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = viewModel::onName,
                    label = { Text(stringResource(R.string.profile_name)) },
                    supportingText = { Text(stringResource(R.string.profile_name_hint, PlayerProfile.MAX_NAME)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("profile-name"),
                )

                // §14.1 sem foto, o avatar é o medalhão com a inicial: cor à escolha.
                Section(stringResource(R.string.profile_color)) {
                    Swatches(MedalColors, profile.color, "medal-color", viewModel::onColor)
                }
                // §14.1 cor do verso das cartas: azul, verde ou vermelho.
                Section(stringResource(R.string.profile_deck)) {
                    DeckChoices(profile.deck, viewModel::onDeck)
                }
            }
            GPanel(Modifier.fillMaxWidth()) {
                val sound = LocalSound.current
                val soundLabel = stringResource(R.string.profile_sound)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(state.soundOn, role = Role.Switch) { on ->
                            viewModel.onSoundToggle(on)
                            // Pré-escuta ao ligar (a chave do app ainda pode estar desligada neste instante).
                            if (on) sound.play(SoundEffect.CHIME, force = true)
                        }
                        .testTag("profile-sound"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(soundLabel, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.profile_sound_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.soundOn, onCheckedChange = null)
                }
            }
            // §16 Wi-Fi do jogo: liga o multijogador por perto (grava na hora; ao ligar, explicação e permissões).
            GPanel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(state.wifiOn, role = Role.Switch) { on ->
                            if (!on) viewModel.onWifiToggle(false)
                            else if (accessGate != null) askingWifi = true
                            else viewModel.onWifiToggle(true)
                        }
                        .testTag("profile-wifi"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_wifi), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.profile_wifi_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.wifiOn, onCheckedChange = null)
                }
                // §16.2 ver as fotos dos outros jogadores nas mesas compartilhadas (a própria vai de qualquer forma).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(state.showSharedPhotos, role = Role.Switch) { viewModel.onShowSharedPhotos(it) }
                        .testTag("profile-shared-photos"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_shared_photos), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.profile_shared_photos_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.showSharedPhotos, onCheckedChange = null)
                }
            }
            // Tour guiado (3.16): zera o registro; cada tela mostra o tour de novo na próxima vez.
            GPanel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(state.tourAuto, role = Role.Switch) { viewModel.onTourToggle(it) }
                        .testTag("profile-tour"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_tour), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.profile_tour_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.tourAuto, onCheckedChange = null)
                }
            }
            // Ações impedidas: o que a mesa faz quando o motor recusa uma jogada (grava na hora).
            GPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.profile_refusal), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.profile_refusal_hint), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(RefusalAlert.NONE, R.string.profile_refusal_none_choice, "profile-refusal-none"),
                            Triple(RefusalAlert.SUBTLE, R.string.profile_refusal_subtle_choice, "profile-refusal-subtle"),
                            Triple(RefusalAlert.EXPLAIN, R.string.profile_refusal_explain_choice, "profile-refusal-explain"),
                        ).forEach { (alert, label, tag) ->
                            GButton(
                                onClick = { viewModel.onRefusalAlert(alert) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(tag),
                                kind = if (state.refusalAlert == alert) GButtonKind.Primary else GButtonKind.Secondary,
                            ) { Text(stringResource(label), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
            // Velocidade das animações: normal ou 30% mais lenta (grava na hora; vale também para o botão da mesa).
            GPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.profile_speed), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.profile_speed_hint), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to R.string.profile_speed_normal, true to R.string.profile_speed_slow).forEach { (slow, label) ->
                            GButton(
                                onClick = { viewModel.onSpeedChange(slow) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(if (slow) "profile-speed-slow" else "profile-speed-normal"),
                                kind = if (state.slowAnimations == slow) GButtonKind.Primary else GButtonKind.Secondary,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    com.gtranca.ui.speed.SpeedIcon(slow = slow, color = androidx.compose.material3.LocalContentColor.current)
                                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }
            // §14.4 regras personalizadas do jogador (padrão de "Personalizada" ao criar mesa).
            GButton(onRules, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("profile-rules"), kind = GButtonKind.Secondary) {
                Text(stringResource(R.string.profile_my_rules), style = MaterialTheme.typography.titleMedium)
            }
            val unnamed = stringResource(R.string.profile_unnamed)
            GButton(
                onClick = {
                    viewModel.onSave(unnamed)
                    onDone()
                },
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("profile-save"),
            ) { Text(stringResource(R.string.profile_save), style = MaterialTheme.typography.titleMedium) }
            GButton(onDone, Modifier.fillMaxWidth().testTag("profile-cancel"), kind = GButtonKind.Text) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

/** §14.1 escolher, trocar ou remover a foto do avatar (galeria do aparelho, sem pedir permissão). */
@Composable
private fun PhotoButtons(state: ProfileUiState, viewModel: ProfileViewModel) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.onPhotoPicked(it.toString()) } }
    val hasPhoto = state.photo != PhotoState.NONE
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GButton(
            onClick = {
                picker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("profile-photo-pick"),
            enabled = !state.importingPhoto,
            kind = GButtonKind.Secondary,
        ) { Text(stringResource(if (hasPhoto) R.string.profile_photo_change else R.string.profile_photo_pick)) }
        if (hasPhoto) {
            GButton(
                onClick = viewModel::onRemovePhoto,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("profile-photo-remove"),
                kind = GButtonKind.Text,
            ) { Text(stringResource(R.string.profile_photo_remove)) }
        }
    }
    if (state.photoError) {
        Text(stringResource(R.string.profile_photo_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (hasPhoto) {
        Text(stringResource(R.string.profile_photo_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Opção selecionável com um desenho: aro verde quando escolhida. */
@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, description: String, tag: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag)
            .border(if (selected) 3.dp else 0.dp, if (selected) GColors.Yellow else Color.Transparent, CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Fileira de cores selecionáveis (cor do medalhão). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Color>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        colors.forEachIndexed { index, color ->
            Box(
                Modifier
                    .size(44.dp)
                    .selectable(selected = selected == index, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = "${index + 1}" }
                    .testTag("$tag-$index"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(color, CircleShape)
                        .border(if (selected == index) 3.dp else 1.dp, if (selected == index) GColors.Yellow else GColors.CardBorder, CircleShape),
                )
            }
        }
    }
}


/** §14.1 as três cores do baralho, cada uma com o verso de uma carta; a escolhida leva o aro dourado. */
@Composable
private fun DeckChoices(selected: DeckColor, onSelect: (DeckColor) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
        DeckColor.entries.forEach { deck ->
            val label = stringResource(deck.labelRes())
            val isSelected = deck == selected
            Column(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(deck) })
                    .semantics { contentDescription = label }
                    .testTag("deck-color-${deck.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(
                    Modifier
                        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) GColors.Gold else GColors.Steel.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(4.dp),
                ) { CardBack(size = CardSize.MEDIUM, describe = false, deck = deck) }
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (isSelected) GColors.Gold else GColors.Steel)
            }
        }
    }
}

@androidx.annotation.StringRes
private fun DeckColor.labelRes(): Int = when (this) {
    DeckColor.AZUL -> R.string.deck_blue
    DeckColor.VERDE -> R.string.deck_green
    DeckColor.VERMELHO -> R.string.deck_red
}
