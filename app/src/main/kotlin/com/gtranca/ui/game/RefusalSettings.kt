package com.gtranca.ui.game

import androidx.compose.runtime.staticCompositionLocalOf
import com.gtranca.data.RefusalAlert

/** Como a mesa mostra uma mensagem de [UiMessage]. */
enum class MessageHandling { HIDDEN, SNACK, MODAL }

/**
 * Perfil, "Ações impedidas" (docs/telas.md 3.6, item 6d): o que fazer com a jogada recusada. "Mostre suas cartas para
 * jogar" não é recusa de regra e sempre aparece como faixa.
 */
fun RefusalAlert.handling(message: UiMessage): MessageHandling = when {
    message == UiMessage.ShowCardsToPlay -> MessageHandling.SNACK
    this == RefusalAlert.NONE -> MessageHandling.HIDDEN
    this == RefusalAlert.SUBTLE -> MessageHandling.SNACK
    else -> MessageHandling.MODAL
}

/** A preferência *Ações impedidas* e como mudá-la (grava na hora); vale para o app todo, como o som e a velocidade. */
data class RefusalSettings(val alert: RefusalAlert = RefusalAlert.EXPLAIN, val set: (RefusalAlert) -> Unit = {})

val LocalRefusalSettings = staticCompositionLocalOf { RefusalSettings() }
