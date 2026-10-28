package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleResult

/** §17.7 como uma carta da mão se encaixa nos jogos do adversário. */
enum class OpponentFit { NONE, FITS, CLOSES_CANASTA }

/**
 * §17.7 b2 Yellow-LED. Só informação visível ([PlayerView]); não é jogada e não passa por `legalActions`: pergunta se a carta
 * poderia ser acrescentada (§6.2, §6.3, §7.3) a um conjunto do lado adversário, como se fosse a vez dele.
 */
object RedLed {
    /** §17.7 só na vez do jogador, depois de comprar ou pegar o lixo (baixar ou descartar). */
    fun isActive(view: PlayerView): Boolean = view.currentSeat == view.seat && view.phase == Phase.PLAYING

    fun fit(view: PlayerView, card: Card): OpponentFit {
        if (!isActive(view)) return OpponentFit.NONE
        var result = OpponentFit.NONE
        view.tables.forEachIndexed { side, table ->
            if (side == view.side.index) return@forEachIndexed
            for (placed in table.melds) {
                if (placed.meld.add(listOf(card), view.rules) !is RuleResult.Ok) continue
                // §7.1 fecha a canastra quando o conjunto passa de 6 para 7 cartas.
                if (placed.meld.cards.size + 1 == view.rules.minCanastaSize) return OpponentFit.CLOSES_CANASTA
                result = OpponentFit.FITS
            }
        }
        return result
    }

    /** As cartas da mão que se encaixam (e como); as demais ficam fora do mapa. */
    fun marks(view: PlayerView): Map<Card, OpponentFit> =
        view.hand.associateWith { fit(view, it) }.filterValues { it != OpponentFit.NONE }
}
