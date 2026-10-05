package com.gtranca.ui.sound

import androidx.compose.runtime.staticCompositionLocalOf
import com.gtranca.ui.game.AnimAnchor
import com.gtranca.ui.game.CardFlight

/** Efeitos sonoros do jogo, um por tipo de animação. [gain] é o volume relativo (0 a 1) de cada um. */
enum class SoundEffect(val gain: Float) {
    /** Carta virada (compra, distribuição, carta do lixo para a mão). */
    FLIP(0.55f),

    /** Descarte no lixo. */
    TAP(0.8f),

    /** Cartas deslizando para um jogo na mesa. */
    SLIDE(0.7f),

    /** 3 vermelho indo à mesa. */
    CHIME(0.7f),

    /** Canastra fechada. */
    CANASTA(0.75f),

    /** Vitória. */
    WIN(0.8f),

    /** Derrota. */
    LOSE(0.7f),
}

/** Toca os efeitos; [enabled] é a chave de som do jogador (Perfil). */
interface SoundPlayer {
    var enabled: Boolean

    /** Toca [effect] se o som estiver ligado; com [force], toca mesmo desligado (pré-escuta ao ligar a chave). */
    fun play(effect: SoundEffect, force: Boolean = false)
}

/** Sem som (testes, pré-visualizações e telas fora do app). */
object NoSound : SoundPlayer {
    override var enabled: Boolean = false

    override fun play(effect: SoundEffect, force: Boolean) = Unit
}

val LocalSound = staticCompositionLocalOf<SoundPlayer> { NoSound }

/** Qual som acompanha cada voo de carta (pelo destino; sem destino marcante, pela origem). */
object SoundMap {
    fun forFlight(flight: CardFlight): SoundEffect? = when (flight.to) {
        AnimAnchor.DiscardPile, is AnimAnchor.DiscardCard -> SoundEffect.TAP
        is AnimAnchor.Meld, is AnimAnchor.MeldCard, is AnimAnchor.SideArea -> SoundEffect.SLIDE
        is AnimAnchor.RedThrees, is AnimAnchor.RedThreeCard -> SoundEffect.CHIME
        else -> when (flight.from) {
            AnimAnchor.Stock, is AnimAnchor.Morto, AnimAnchor.DiscardPile, is AnimAnchor.DiscardCard -> SoundEffect.FLIP
            else -> null
        }
    }
}
