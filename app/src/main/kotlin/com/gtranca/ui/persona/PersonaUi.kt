package com.gtranca.ui.persona

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
