package com.gtranca.ui.multiplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.Tables
import com.gtranca.net.RotateDirection
import com.gtranca.net.START_DELAY_MILLIS
import com.gtranca.net.SeatOccupant
import com.gtranca.net.SeatingRoom
import com.gtranca.net.SeatingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §16.2 "Mesa só com robôs": a escolha de posições só do jogador, no próprio aparelho e sem rede. Os robôs marcados na
 * montagem ([robots], na ordem da escolha) sentam depois do jogador e os que sobram são sorteados com a [seed] da mesa
 * (§14.5). Ao confirmar, os robôs confirmam e, 2 s depois (§16.15), [start] traz o personagem de cada assento **visto a
 * partir do jogador** (o jogo local o põe no assento 0; §4.1 o primeiro jogador é sorteado, então só a posição relativa
 * importa).
 */
class LocalSeatPicker(
    private val mode: GameMode,
    robots: List<String>,
    seed: Long,
    private val startDelayMillis: Long = START_DELAY_MILLIS,
) : ViewModel() {
    private var room = SeatingRoom.create(
        mode,
        listOf<SeatOccupant>(SeatOccupant.Human(ME)) + robots.take(mode.seatCount - 1).map { SeatOccupant.Robot(it) },
        Tables.personaPool,
        seed,
    )

    private val _seating = MutableStateFlow(room.state)
    val seating: StateFlow<SeatingState> = _seating.asStateFlow()

    private val _start = MutableStateFlow<Map<Int, String>?>(null)

    /** O jogo começa: assento no jogo (relativo ao jogador) → personagem. `null` antes. */
    val start: StateFlow<Map<Int, String>?> = _start.asStateFlow()

    fun rotate(direction: RotateDirection) {
        room = room.rotate(ME, direction) ?: return
        _seating.value = room.state
    }

    fun confirm() {
        room = room.confirm(ME) ?: return
        _seating.value = room.state
        if (room.state.locked) {
            val state = room.state
            viewModelScope.launch {
                delay(startDelayMillis)
                _start.value = Tables.relativeRobots(mode, state.seatOf(ME) ?: Seat(0), state.robots)
            }
        }
    }

    companion object {
        /** Id do jogador na escolha local (como o anfitrião numa mesa da rede). */
        const val ME: Int = 0
    }
}
