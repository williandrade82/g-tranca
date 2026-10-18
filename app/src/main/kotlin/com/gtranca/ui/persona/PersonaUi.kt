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
 * §14.5 pose de cada lado nas tabelas de pontos: quem lidera o placar acumulado ([totals], por `Side.index`) na de
 * vencedor, o outro na de perdedor; empate = pensador.
 */
fun leaderPoses(totals: List<Int>, sides: List<Side>): Map<Side, Pose> {
    val best = sides.maxOf { totals[it.index] }
    val leaders = sides.filter { totals[it.index] == best }
    return sides.associateWith { side ->
        when {
            leaders.size == sides.size -> Pose.PENSADOR
            side in leaders -> Pose.VENCEDOR
            else -> Pose.PERDEDOR
        }
    }
}

/** Avatares lado a lado, levemente sobrepostos e com aro branco (um lado de duplas tem dois jogadores). */
@Composable
fun AvatarRow(personas: List<Persona>, size: Dp, modifier: Modifier = Modifier, pose: Pose = Pose.PENSADOR) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-(size * 0.22f)), verticalAlignment = Alignment.CenterVertically) {
        personas.forEach { PersonaAvatar(it, size, Modifier.border(2.dp, Color.White, CircleShape), pose = pose) }
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
