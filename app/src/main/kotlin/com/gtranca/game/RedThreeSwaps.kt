package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side

/**
 * Troca pública de 3 vermelho (§3.5, §6.5) a encenar: o 3 vermelho [cards] foi à mesa do [side], baixado por [seat]
 * no início da vez ([atTurnStart], §3.5) ou durante a jogada (compra, morto, reposição em cadeia). A carta de reposição nunca é
 * conhecida aqui. [id] identifica a troca na partida (estável entre snapshots), e cresce com o tempo.
 */
data class RedThreeNotice(val id: Long, val side: Side, val seat: Seat, val cards: List<Card>, val atTurnStart: Boolean)

/**
 * Origem das trocas públicas de 3 vermelho mostradas pela interface: dado o snapshot anterior já mostrado
 * ([previous], `null` no início do jogo) e o novo, devolve as trocas novas em ordem cronológica.
 */
fun interface RedThreeSwapSource {
    fun newSwaps(previous: GameSnapshot?, current: GameSnapshot): List<RedThreeNotice>
}

/**
 * Trocas lidas do registro público do motor (`PlayerView.redThreeLog`, §3.5/§6.5): as entradas além das já vistas
 * na mesma partida (diferença de tamanho do log). Só entradas novas são tratadas: numa partida restaurada, o log pode
 * não cobrir 3 vermelhos antigos da mesa.
 */
object LogRedThreeSource : RedThreeSwapSource {
    /** Espaço de identificadores por partida (o log de uma partida tem no máximo 4 entradas). */
    private const val IDS_PER_ROUND = 1_000L

    override fun newSwaps(previous: GameSnapshot?, current: GameSnapshot): List<RedThreeNotice> {
        val log = current.view.redThreeLog
        val seen = if (previous != null && previous.roundNumber == current.roundNumber) previous.view.redThreeLog.size else 0
        val mode = current.view.mode
        return log.withIndex().drop(seen).map { (index, entry) ->
            RedThreeNotice(
                id = current.roundNumber * IDS_PER_ROUND + index,
                side = mode.sideOf(entry.seat),
                seat = entry.seat,
                cards = listOf(entry.card),
                atTurnStart = entry.atTurnStart,
            )
        }
    }
}
