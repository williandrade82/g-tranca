package com.gtranca.game

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side

/**
 * Troca pública de 3 vermelho (§3.5, §6.5) a encenar: o 3 vermelho [cards] foi à mesa do [side], baixado por [seat]
 * no início da vez ([atTurnStart], §3.5) ou durante a jogada (compra, morto, reposição em cadeia). A carta de reposição nunca é
 * conhecida aqui. [chained]: chegou como reposição em cadeia de outra troca (§3.5, §6.5). [id] identifica a troca na partida (estável entre snapshots), e cresce com o tempo.
 */
data class RedThreeNotice(val id: Long, val side: Side, val seat: Seat, val cards: List<Card>, val atTurnStart: Boolean, val chained: Boolean = false)

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
        // §3.5 3 vermelhos do mesmo jogador baixados juntos (os da mão no início da vez, ou os do morto) viram um aviso só;
        // os que chegam como reposição em cadeia (§6.5) ficam como trocas seguintes.
        val groups = mutableListOf<MutableList<IndexedValue<com.gtranca.engine.model.RedThreeLaid>>>()
        for (item in log.withIndex().drop(seen)) {
            val last = groups.lastOrNull()
            val head = last?.first()?.value
            val joins = head != null && !head.chained && !item.value.chained &&
                head.seat == item.value.seat && head.atTurnStart == item.value.atTurnStart
            if (joins) last!!.add(item) else groups.add(mutableListOf(item))
        }
        return groups.map { group ->
            val first = group.first()
            RedThreeNotice(
                id = current.roundNumber * IDS_PER_ROUND + first.index,
                side = mode.sideOf(first.value.seat),
                seat = first.value.seat,
                cards = group.map { it.value.card },
                atTurnStart = first.value.atTurnStart,
                chained = first.value.chained,
            )
        }
    }
}
