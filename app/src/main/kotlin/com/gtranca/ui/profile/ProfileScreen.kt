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
import com.gtranca.game.FaceShape
import com.gtranca.game.Gender
import com.gtranca.game.HairStyle
import com.gtranca.game.Look
import com.gtranca.game.PlayerProfile
import com.gtranca.game.Profession
import com.gtranca.ui.persona.HairColors
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.persona.SkinTones
import com.gtranca.ui.persona.labelRes
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GChoiceChip
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing

/** Perfil do jogador (§14.1): nome e avatar (gênero, profissão, pele, cabelo e barba), com pré-visualização. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
                    val preview = profile.persona(youName)
                    PersonaAvatar(preview, 112.dp, Modifier.semantics { contentDescription = preview.firstName }.testTag("profile-preview"))
                }
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = viewModel::onName,
                    label = { Text(stringResource(R.string.profile_name)) },
                    supportingText = { Text(stringResource(R.string.profile_name_hint, PlayerProfile.MAX_NAME)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("profile-name"),
                )

                Section(stringResource(R.string.profile_gender)) {
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Gender.entries.forEach { gender ->
                            GChoiceChip(
                                stringResource(if (gender == Gender.MALE) R.string.gender_male else R.string.gender_female),
                                selected = profile.gender == gender,
                                onClick = { viewModel.onGender(gender) },
                                modifier = Modifier.weight(1f).testTag("gender-${gender.name.lowercase()}"),
                            )
                        }
                    }
                }

                Section(stringResource(R.string.profile_profession)) {
                    // Quatro por linha em 360 dp; o nome da profissão fica sob cada desenho.
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Profession.entries.forEach { profession ->
                            val option = profile.copy(profession = profession).persona(youName)
                            LabeledChoice(
                                selected = profile.profession == profession,
                                onClick = { viewModel.onProfession(profession) },
                                label = stringResource(profession.labelRes(profile.gender)),
                                tag = "profession-${profession.name.lowercase()}",
                            ) { PersonaAvatar(option, 52.dp) }
                        }
                    }
                }

                Section(stringResource(R.string.profile_face)) {
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FaceShape.entries.forEach { face ->
                            val option = profile.copy(look = profile.look.copy(face = face)).persona(youName)
                            Choice(
                                selected = profile.look.face == face,
                                onClick = { viewModel.onFace(face) },
                                description = stringResource(face.labelRes()),
                                tag = "face-${face.name.lowercase()}",
                            ) { PersonaAvatar(option, 52.dp) }
                        }
                    }
                }

                Section(stringResource(R.string.profile_skin)) {
                    Swatches(SkinTones, profile.look.skin, "skin", viewModel::onSkin)
                }

                Section(stringResource(R.string.profile_hair)) {
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        HairStyle.entries.filter { it.gender == profile.gender }.forEach { style ->
                            val option = profile.copy(look = profile.look.copy(hair = style)).persona(youName)
                            Choice(
                                selected = profile.look.hair == style,
                                onClick = { viewModel.onHair(style) },
                                description = stringResource(style.labelRes()),
                                tag = "hair-${style.name.lowercase()}",
                            ) { PersonaAvatar(option, 44.dp) }
                        }
                    }
                    Swatches(HairColors, profile.look.hairColor, "haircolor", viewModel::onHairColor)
                }

                if (profile.gender == Gender.MALE) {
                    val beardLabel = stringResource(R.string.profile_beard)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(profile.look.beard, role = Role.Switch, onValueChange = viewModel::onBeard)
                            .testTag("profile-beard"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(beardLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = profile.look.beard, onCheckedChange = null)
                    }
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
            GButton(
                onClick = {
                    viewModel.onSave()
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

/** Opção com desenho e nome embaixo; a coluna toda é a opção (o texto é lido uma vez só) e o aro marca o desenho. */
@Composable
private fun LabeledChoice(selected: Boolean, onClick: () -> Unit, label: String, tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(70.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .border(if (selected) 3.dp else 0.dp, if (selected) GColors.Yellow else Color.Transparent, CircleShape)
                .padding(3.dp),
        ) { content() }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = GColors.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Fileira de cores selecionáveis (tom de pele ou de cabelo). */
@Composable
private fun Swatches(colors: List<Color>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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

private fun FaceShape.labelRes(): Int = when (this) {
    FaceShape.OVAL -> R.string.face_oval
    FaceShape.ROUND -> R.string.face_round
    FaceShape.SQUARE -> R.string.face_square
    FaceShape.HEART -> R.string.face_heart
    FaceShape.LONG -> R.string.face_long
}

private fun HairStyle.labelRes(): Int = when (this) {
    HairStyle.SHORT -> R.string.hair_short
    HairStyle.SIDE_SWEEP -> R.string.hair_side_sweep
    HairStyle.CURLY_SHORT -> R.string.hair_curly_short
    HairStyle.BALD -> R.string.hair_bald
    HairStyle.LONG -> R.string.hair_long
    HairStyle.BOB -> R.string.hair_bob
    HairStyle.BUN -> R.string.hair_bun
    HairStyle.PONYTAIL -> R.string.hair_ponytail
    HairStyle.CURLY_LONG -> R.string.hair_curly_long
}
