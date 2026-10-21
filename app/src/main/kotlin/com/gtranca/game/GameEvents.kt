package com.gtranca.game

import com.gtranca.engine.PublicEvent
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side

/**
 * Fluxo de eventos do [GameController] (§16.6): cada jogada processada é publicada uma vez, em ordem, para quem
 * precisar retransmiti-la (o anfitrião do multijogador) ou só processá-la (as telas). Contém **só informação pública**
 * (a mesma de [PublicEvent]); nunca mãos, monte, mortos nem semente (§16.4).
 */
sealed interface GameEvent {

    /** Uma nova partida foi distribuída (a primeira ou as seguintes; não é emitido ao retomar um jogo salvo). */
    data class RoundStarted(val roundNumber: Int) : GameEvent

    /**
     * A ação pública [event] foi aplicada na partida [roundNumber]; [redThrees] são os 3 vermelhos que ela fez baixar
     * (compra, reposição em cadeia, morto e o início da vez do próximo jogador), todos públicos (§6.5).
     */
    data class ActionApplied(
        val roundNumber: Int,
        val event: PublicEvent,
        val redThrees: List<RedThreeLaid>,
    ) : GameEvent

    /** A partida [roundNumber] terminou e foi pontuada (§12). */
    data class RoundScored(val roundNumber: Int) : GameEvent

    /** O jogo terminou (§13); [resigned] se foi por desistência (§13.1). */
    data class GameFinished(val winner: Side, val resigned: Boolean) : GameEvent

    /** §16.8, §16.9 o jogador do [seat] foi trocado: por um robô ([byBot]) ou por outro humano. */
    data class SeatReplaced(val seat: Seat, val byBot: Boolean) : GameEvent
}

/**
 * Quem espera o quê do aparelho (§16.6).
 *
 * - [LOCAL_ANIMATED]: um humano com a tela deste aparelho. O controlador espera a encenação das trocas de 3 vermelho,
 *   a animação da distribuição e a pausa entre as jogadas dos robôs, para o humano acompanhar.
 * - [FREE]: multijogador. O controlador **só pára quando precisa da decisão de um humano**; cada tela anima por conta
 *   própria e só entrega a vez ao seu jogador depois de terminar as próprias animações.
 */
enum class PacingPolicy { LOCAL_ANIMATED, FREE }
