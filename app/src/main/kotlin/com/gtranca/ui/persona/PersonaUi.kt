package com.gtranca.ui.persona

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gtranca.R
import com.gtranca.game.BotLevel
import com.gtranca.ui.theme.GColors
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.Persona
import com.gtranca.game.PlayerProfile
import com.gtranca.game.SeatRole
import com.gtranca.ui.game.sideName

/** Os perfis de um lado, na ordem dos assentos (a lista [personas] é indexada pelo assento). */
fun personasOfSide(mode: GameMode, side: Side, personas: List<Persona>): List<Persona> =
    personas.filterIndexed { seat, _ -> mode.sideOf(Seat(seat)) == side }

/**
 * §14.5 nomes de um lado para a comunicação: cada um pelo nome completo com a posição (duplas); "Você" para o jogador.
 * Sem perfis ([personas] vazio), lista vazia.
 */
fun callNamesOfSide(mode: GameMode, side: Side, viewerSeat: Seat, personas: List<Persona>): List<String> =
    personas.mapIndexedNotNull { index, persona ->
        val seat = Seat(index)
        if (mode.sideOf(seat) != side) return@mapIndexedNotNull null
        val role = SeatRole.of(mode, seat, viewerSeat)
        if (role == SeatRole.YOU) PlayerProfile.DEFAULT_NAME else persona.callName(mode, role)
    }

/** §14.5 insígnia do nível: 1 a 3 estrelas douradas. */
@Composable
fun LevelStars(level: BotLevel, modifier: Modifier = Modifier, size: TextUnit = 16.sp) {
    val description = stringResource(R.string.level_stars_desc, stringResource(level.labelRes()))
    Row(modifier.semantics { contentDescription = description }.testTag("level-stars"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(level.stars) { Text("★", color = GColors.Gold, fontSize = size, modifier = Modifier.clearAndSetSemantics {}) }
    }
}

/**
 * §14.5 retratos dos personagens no fim da partida ou do jogo: os de [winners] na pose de vencedor e os de [losers] na
 * de perdedor, lado a lado. Quem não é personagem (o jogador) não aparece aqui.
 */
@Composable
fun BotPortraits(winners: List<Persona>, losers: List<Persona>, animate: Boolean, modifier: Modifier = Modifier) {
    val items = winners.mapNotNull { p -> p.character?.let { Triple(p, it, Pose.VENCEDOR) } } +
        losers.mapNotNull { p -> p.character?.let { Triple(p, it, Pose.PERDEDOR) } }
    if (items.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        items.forEach { (persona, character, pose) ->
            CharacterPortrait(
                character,
                pose,
                Modifier.weight(1f, fill = false).widthIn(max = if (items.size == 1) 260.dp else 130.dp).testTag("portrait-${pose.code}"),
                animate = animate,
                description = persona.fullName,
            )
        }
    }
}

/** Avatares lado a lado, levemente sobrepostos e com aro branco (um lado de duplas tem dois jogadores). */
@Composable
fun AvatarRow(personas: List<Persona>, size: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-(size * 0.22f)), verticalAlignment = Alignment.CenterVertically) {
        personas.forEach { PersonaAvatar(it, size, Modifier.border(2.dp, Color.White, CircleShape)) }
    }
}

/**
 * Nome de um lado: no individual, o nome da persona ("Você" para o jogador); em duplas, "Nós" e "Eles". Sem perfis
 * ([personas] vazio), o nome por papel de sempre.
 */
@Composable
fun sideLabel(mode: GameMode, side: Side, viewerSide: Side, personas: List<Persona>): String {
    if (mode == GameMode.INDIVIDUAL) {
        personasOfSide(mode, side, personas).firstOrNull()?.let { return it.fullName }
    }
    return sideName(mode, side, viewerSide)
}
