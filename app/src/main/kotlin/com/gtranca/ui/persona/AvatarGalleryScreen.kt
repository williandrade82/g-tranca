package com.gtranca.ui.persona

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.game.Gender
import com.gtranca.game.Persona
import com.gtranca.game.PersonaGenerator
import com.gtranca.game.Profession
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing
import kotlin.random.Random

/** Nome da profissão no gênero do perfil (médico/médica). */
@StringRes
fun Profession.labelRes(gender: Gender): Int {
    val male = gender == Gender.MALE
    return when (this) {
        Profession.DOCTOR -> if (male) R.string.profession_doctor_m else R.string.profession_doctor_f
        Profession.CHEF -> if (male) R.string.profession_chef_m else R.string.profession_chef_f
        Profession.ENGINEER -> if (male) R.string.profession_engineer_m else R.string.profession_engineer_f
        Profession.TEACHER -> if (male) R.string.profession_teacher_m else R.string.profession_teacher_f
        Profession.FIREFIGHTER -> if (male) R.string.profession_firefighter_m else R.string.profession_firefighter_f
        Profession.SCIENTIST -> R.string.profession_scientist
        Profession.PILOT -> R.string.profession_pilot
        Profession.ARTIST -> if (male) R.string.profession_artist_m else R.string.profession_artist_f
    }
}

/**
 * Galeria dos avatares (§14.1): cada profissão nos dois gêneros, em tamanho de tela e em tamanho de pílula, para
 * avaliar o desenho. Temporária: sai quando o perfil do jogador (escolha do avatar) estiver pronto.
 */
@Composable
fun AvatarGalleryScreen(onBack: () -> Unit) {
    // Aparências fixas (mesma semente) para a galeria não mudar a cada abertura.
    val personas = remember {
        val random = Random(7)
        Profession.entries.map { profession ->
            PersonaGenerator.create(random, Gender.MALE, profession) to PersonaGenerator.create(random, Gender.FEMALE, profession)
        }
    }
    GBackground(Modifier.fillMaxSize().testTag("avatar-gallery")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.gallery_title))
            GPanel(Modifier.fillMaxWidth()) {
                personas.forEach { (male, female) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GalleryItem(male)
                        GalleryItem(female)
                    }
                }
            }
            GPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gallery_small), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    personas.forEach { (male, _) -> PersonaAvatar(male, 22.dp) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    personas.forEach { (_, female) -> PersonaAvatar(female, 22.dp) }
                }
            }
            GButton(onBack, Modifier.fillMaxWidth(), kind = GButtonKind.Text) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun GalleryItem(persona: Persona) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PersonaAvatar(persona, 88.dp)
        Text(persona.shortName, style = MaterialTheme.typography.labelLarge, color = GColors.CardBlack, textAlign = TextAlign.Center)
        Text(stringResource(persona.profession.labelRes(persona.gender)), style = MaterialTheme.typography.labelSmall, color = GColors.CardBlack)
    }
}
