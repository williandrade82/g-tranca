package com.gtranca.ui.game

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * §18.4 relógio da vez (mesa online com tempo de jogada): o assento da vez e o instante (relógio monotônico do aparelho,
 * `SystemClock.elapsedRealtime`) em que o tempo dele esgota.
 */
data class TurnDeadline(val seat: Int, val deadlineElapsedMs: Long, val limitMs: Long)

/** O relógio da vez da mesa em jogo; `null` = sem relógio (jogo local, mesa local, tempo indeterminado). */
val LocalTurnClock = compositionLocalOf<StateFlow<TurnDeadline?>?> { null }
