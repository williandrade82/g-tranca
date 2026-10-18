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
        return groupRedThreeLog(current.roundNumber, mode, log.withIndex().drop(seen))
    }
}

/**
 * §3.5 agrupa as entradas [entries] do registro (um único trecho novo do log, que vem de uma só ação do motor) em avisos:
 * 3 vermelhos consecutivos do mesmo assento e do mesmo momento, sem cadeia, viram **um** aviso com várias cartas; os que
 * chegaram como reposição em cadeia (§6.5) ficam como avisos seguintes. Não agrupa entradas de ações diferentes: use-a só
 * com o trecho novo entre dois snapshots.
 */
internal fun groupRedThreeLog(
    roundNumber: Int,
    mode: com.gtranca.engine.model.GameMode,
    entries: List<IndexedValue<com.gtranca.engine.model.RedThreeLaid>>,
): List<RedThreeNotice> {
    val groups = mutableListOf<MutableList<IndexedValue<com.gtranca.engine.model.RedThreeLaid>>>()
    for (item in entries) {
        val last = groups.lastOrNull()
        val head = last?.first()?.value
        val joins = head != null && !head.chained && !item.value.chained &&
            head.seat == item.value.seat && head.atTurnStart == item.value.atTurnStart
        if (joins) last!!.add(item) else groups.add(mutableListOf(item))
    }
    return groups.map { group ->
        val first = group.first()
        RedThreeNotice(
            id = roundNumber * 1_000L + first.index,
            side = mode.sideOf(first.value.seat),
            seat = first.value.seat,
            cards = group.map { it.value.card },
            atTurnStart = first.value.atTurnStart,
            chained = first.value.chained,
        )
    }
}

/**
 * Reparte entre as trocas [swaps] do humano (na ordem) as reposições: cada 3 vermelho baixado tem uma. As primeiras
 * reposições são as cartas das trocas em cadeia (3 vermelhos que chegaram como reposição); as demais, as cartas
 * [ordered] que ficam na mão (a última troca leva o que sobrar). Devolve (ids das trocas em cadeia entregues, cartas da
 * mão) por troca.
 */
internal fun allocateReplacements(
    swaps: List<RedThreeNotice>,
    ordered: List<Card>,
): Pair<Map<Long, List<Long>>, Map<Long, Set<Card>>> {
    val chained = swaps.filter { it.chained }
    var chainedAt = 0
    var orderedAt = 0
    val delivers = mutableMapOf<Long, List<Long>>()
    val kept = mutableMapOf<Long, Set<Card>>()
    swaps.forEachIndexed { p, swap ->
        val ids = mutableListOf<Long>()
        repeat(swap.cards.size) { if (chainedAt < chained.size) ids += chained[chainedAt++].id }
        val need = swap.cards.size - ids.size
        val mine = if (p == swaps.lastIndex) ordered.drop(orderedAt) else ordered.drop(orderedAt).take(need)
        orderedAt += need
        if (ids.isNotEmpty()) delivers[swap.id] = ids
        if (mine.isNotEmpty()) kept[swap.id] = mine.toSet()
    }
    return delivers to kept
}
