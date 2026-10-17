package com.gtranca.ui.persona

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.gtranca.game.Persona
import com.gtranca.ui.profile.ProfilePhoto
import com.gtranca.ui.theme.GColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cores do medalhão do jogador (§14.1); o índice vem de [com.gtranca.game.PlayerProfile.color]. */
internal val MedalColors = listOf(0xFF6C5BD4, 0xFFD23A4F, 0xFF1F9E6B, 0xFFD99A1E, 0xFF2F8FD6, 0xFFE0702A, 0xFF1AA3A3, 0xFFD6528F).map { Color(it) }

/**
 * Avatar de um jogador num círculo (§14.1, §14.5): o personagem do jogador virtual (pose do pensador, a pequena), a foto
 * pessoal do jogador ou, sem foto, o medalhão com a inicial na cor escolhida. Sem texto: quem usa dá a descrição
 * (o nome) à tela.
 */
@Composable
fun PersonaAvatar(persona: Persona, size: Dp, modifier: Modifier = Modifier, animate: Boolean = false, pose: Pose = Pose.PENSADOR) {
    persona.character?.let { character ->
        if (pose == Pose.VENCEDOR || pose == Pose.PERDEDOR) {
            // rosto quadrado da pose (tabelas de pontos): parado
            Image(
                androidx.compose.ui.res.painterResource(avatarRes(character, pose)),
                null,
                modifier.size(size).clip(CircleShape).background(GColors.Indigo),
                contentScale = ContentScale.Crop,
            )
            return
        }
        CharacterPortrait(
            character,
            Pose.PENSADOR,
            modifier.size(size).clip(CircleShape).background(GColors.Indigo),
            animate = animate,
            effects = false,
            contentScale = ContentScale.Crop,
        )
        return
    }
    // §14.1 foto pessoal do jogador: lida do arquivo (relida quando muda); enquanto carrega ou se falhar, vale o medalhão.
    val photo = persona.photo?.let { path ->
        val version = ProfilePhoto.version
        produceState<ImageBitmap?>(null, path, version) {
            value = withContext(Dispatchers.IO) {
                runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }.value
    }
    if (photo != null) {
        Image(photo, null, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    Box(modifier.size(size).clip(CircleShape).background(MedalColors[persona.color]), contentAlignment = Alignment.Center) {
        Text(
            persona.initial,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() },
        )
    }
}
