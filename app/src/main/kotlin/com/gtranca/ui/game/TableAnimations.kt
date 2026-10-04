package com.gtranca.ui.game

import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameSnapshot
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.cardClass

/** Ponto lógico da mesa de onde sai ou aonde chega uma carta animada; a tela sabe onde cada um está. */
sealed interface AnimAnchor {
    data object Stock : AnimAnchor
    data object DiscardPile : AnimAnchor
    data class Morto(val index: Int) : AnimAnchor

    /** Outro assento (mão representada por cartas viradas). */
    data class SeatHand(val seat: Int) : AnimAnchor

    /** A mão do humano. */
    data object OwnHand : AnimAnchor

    /** Um conjunto na mesa do lado (§6.4); se ainda não estiver na tela, cai na área do lado. */
    data class Meld(val side: Int, val meldId: Int) : AnimAnchor

    /** Área de jogos do lado (alvo de reserva). */
    data class SideArea(val side: Int) : AnimAnchor

    /** Área de 3 vermelhos do lado (§6.5). */
    data class RedThrees(val side: Int) : AnimAnchor
}

/**
 * Uma carta voando de [from] a [to]. [card] `null` = carta virada (informação oculta: compra e reposição de outro
 * assento). [delayMillis] escalona várias cartas do mesmo movimento.
 */
data class CardFlight(val id: Long, val from: AnimAnchor, val to: AnimAnchor, val card: Card?, val delayMillis: Long = 0)

/** §7 um conjunto virou canastra (limpa ou suja) neste instante: destaque curto. */
data class CanastaFlash(val id: Long, val side: Int, val meldId: Int, val clean: Boolean)

/**
 * Deriva as animações da mesa das diferenças entre dois snapshots e dos eventos públicos ([GameSnapshot.turnEvents]).
 * Nada de estado oculto: de outros assentos só cartas viradas ou cartas já públicas (descarte, lixo, conjuntos,
 * 3 vermelho). Puro (sem Android), testável na JVM.
 */
object TableAnimations {

    /** Espaço entre cartas do mesmo movimento. */
    const val STAGGER_MILLIS: Long = 70

    /** Máximo de cartas animadas num movimento (o lixo pode ter muitas; as demais chegam sem voo). */
    const val MAX_CARDS_PER_MOVE: Int = 5

    private fun holder(seat: Seat, viewer: Seat): AnimAnchor =
        if (seat == viewer) AnimAnchor.OwnHand else AnimAnchor.SeatHand(seat.index)

    /** Eventos novos de cada assento entre [old] e [new], na mesma partida. */
    fun newEvents(old: GameSnapshot, new: GameSnapshot): List<PublicEvent> {
        if (old.roundNumber != new.roundNumber || old === new) return emptyList()
        return new.turnEvents.indices.flatMap { seat ->
            val before = old.turnEvents.getOrElse(seat) { emptyList() }
            val after = new.turnEvents[seat]
            when {
                after == before -> emptyList()
                after.size >= before.size && after.take(before.size) == before -> after.drop(before.size)
                else -> after // vez nova do assento: a lista recomeçou
            }
        }
    }

    /**
     * Voos e destaques de canastra de [old] para [new]. [hiddenFromView] são cartas da mão do humano ainda ocultas
     * (encenação de 3 vermelho): a compra que as trouxe voa virada.
     */
    fun derive(
        old: GameSnapshot,
        new: GameSnapshot,
        hiddenFromView: Set<Card>,
        nextId: () -> Long,
    ): Pair<List<CardFlight>, List<CanastaFlash>> {
        val events = newEvents(old, new)
        if (events.isEmpty()) return emptyList<CardFlight>() to emptyList()
        val viewer = new.viewerSeat
        val mode = new.view.mode
        val flights = mutableListOf<CardFlight>()
        val gained = (new.view.hand.toSet() - old.view.hand.toSet()) - hiddenFromView

        for (event in events) {
            val from = holder(event.seat, viewer)
            val side = mode.sideOf(event.seat).index
            when (val action = event.action) {
                Action.DrawFromStock -> {
                    // §10.1 se um morto virou monte agora, a carta sai dele.
                    val becameStock = new.view.mortoStatus.indices.firstOrNull { i ->
                        new.view.mortoStatus[i] == MortoStatus.BecameStock && old.view.mortoStatus.getOrNull(i) != MortoStatus.BecameStock
                    }
                    val source = becameStock?.let { AnimAnchor.Morto(it) } ?: AnimAnchor.Stock
                    val card = if (event.seat == viewer) gained.singleOrNull() else null
                    flights += CardFlight(nextId(), source, from, card)
                }
                Action.DeclineDraw -> Unit
                is Action.Discard -> flights += CardFlight(nextId(), from, AnimAnchor.DiscardPile, action.card)
                is Action.TakeDiscardPile -> {
                    val target = when (val plan = action.plan) {
                        is DiscardPlan.AddToMeld -> AnimAnchor.Meld(side, plan.meldId.value)
                        is DiscardPlan.NewMeld -> meldFor(old, new, side, plan.handCards)
                    }
                    val handCards = when (val plan = action.plan) {
                        is DiscardPlan.AddToMeld -> plan.handCards
                        is DiscardPlan.NewMeld -> plan.handCards
                    }
                    // §5.1 o topo vai do lixo ao conjunto (derivado do conjunto, não do snapshot anterior, que pode
                    // ter sido pulado), e as cartas da mão do plano vão junto da mão até lá.
                    takenTop(old, new, target, handCards)?.let { flights += CardFlight(nextId(), AnimAnchor.DiscardPile, target, it) }
                    handCards.take(MAX_CARDS_PER_MOVE).forEachIndexed { i, card ->
                        flights += CardFlight(nextId(), from, target, card, STAGGER_MILLIS * i)
                    }
                    event.takenFromDiscard.takeLast(MAX_CARDS_PER_MOVE).forEachIndexed { i, card ->
                        flights += CardFlight(nextId(), AnimAnchor.DiscardPile, from, card, STAGGER_MILLIS * (i + 1))
                    }
                }
                is Action.CreateMeld -> {
                    val target = meldFor(old, new, side, action.cards)
                    action.cards.take(MAX_CARDS_PER_MOVE).forEachIndexed { i, card ->
                        flights += CardFlight(nextId(), from, target, card, STAGGER_MILLIS * i)
                    }
                }
                is Action.AddToMeld -> action.cards.take(MAX_CARDS_PER_MOVE).forEachIndexed { i, card ->
                    flights += CardFlight(nextId(), from, AnimAnchor.Meld(side, action.meldId.value), card, STAGGER_MILLIS * i)
                }
            }
        }
        return flights to canastaFlashes(old, new, nextId)
    }

    /**
     * Topo do lixo levado ao conjunto [target] (§5.1): as cartas que entraram no conjunto, menos as da mão do plano
     * (por valor e naipe). `null` se não der para identificar uma só.
     */
    private fun takenTop(old: GameSnapshot, new: GameSnapshot, target: AnimAnchor, handCards: List<Card>): Card? {
        val meld = target as? AnimAnchor.Meld ?: return null
        val before = old.view.tables[meld.side].melds.firstOrNull { it.id.value == meld.meldId }?.meld?.cards.orEmpty()
        val after = new.view.tables[meld.side].melds.firstOrNull { it.id.value == meld.meldId }?.meld?.cards ?: return null
        val added = after.filter { it !in before }.toMutableList()
        for (card in handCards) added.firstOrNull { it.cardClass == card.cardClass }?.let { added.remove(it) }
        return added.singleOrNull()
    }

    /** Conjunto novo do lado que contém as cartas (por valor e naipe); se não achar, a área do lado. */
    private fun meldFor(old: GameSnapshot, new: GameSnapshot, side: Int, cards: List<Card>): AnimAnchor {
        val oldIds = old.view.tables[side].melds.map { it.id }.toSet()
        val created = new.view.tables[side].melds.filter { it.id !in oldIds }
        val classes = cards.map { it.cardClass }
        val match = created.lastOrNull { meld -> meld.meld.cards.map { it.cardClass }.containsAll(classes) } ?: created.lastOrNull()
        return match?.let { AnimAnchor.Meld(side, it.id.value) } ?: AnimAnchor.SideArea(side)
    }

    /** §7 conjuntos que viraram canastra entre os snapshots (inclusive os criados já com 6+ cartas). */
    fun canastaFlashes(old: GameSnapshot, new: GameSnapshot, nextId: () -> Long): List<CanastaFlash> {
        if (old.roundNumber != new.roundNumber) return emptyList()
        return new.view.tables.indices.flatMap { side ->
            val before = old.view.tables[side].melds.associateBy { it.id }
            new.view.tables[side].melds.mapNotNull { tableMeld ->
                val wasCanasta = before[tableMeld.id]?.meld?.isCanasta() == true
                if (tableMeld.meld.isCanasta() && !wasCanasta) {
                    CanastaFlash(nextId(), side, tableMeld.id.value, tableMeld.meld.isClean)
                } else {
                    null
                }
            }
        }
    }

    /** De onde vem a reposição de uma troca de 3 vermelho encenada (§6.5). */
    sealed interface Replacement {
        /** Sem voo de reposição (ex.: 3 vermelho que veio no morto, §9.4: as cartas do morto aparecem como novas). */
        data object None : Replacement

        /** Carta virada do monte até o assento (reposição de outro assento: oculta). */
        data object FaceDown : Replacement

        /** Cartas reveladas do monte até a mão do humano (a reposição dele, ou o próximo 3 vermelho da cadeia). */
        data class Revealed(val cards: List<Card>) : Replacement
    }

    /**
     * §6.5 troca de 3 vermelho encenada: o 3 vermelho vai de [fromMorto] (se veio no morto, §9.4) ou da mão de quem
     * trocou até a área de 3 vermelhos do lado, e a reposição chega do monte conforme [replacement].
     */
    fun redThreeSwap(
        notice: RedThreeNotice,
        viewer: Seat,
        replacement: Replacement,
        nextId: () -> Long,
        fromMorto: Int? = null,
    ): List<CardFlight> {
        val holder = holder(notice.seat, viewer)
        val origin = fromMorto?.let { AnimAnchor.Morto(it) } ?: holder
        val flights = notice.cards.map { CardFlight(nextId(), origin, AnimAnchor.RedThrees(notice.side.index), it) }.toMutableList()
        when (replacement) {
            Replacement.None -> Unit
            Replacement.FaceDown -> flights += CardFlight(nextId(), AnimAnchor.Stock, holder, null, STAGGER_MILLIS * 2)
            is Replacement.Revealed -> replacement.cards.take(MAX_CARDS_PER_MOVE).forEachIndexed { i, card ->
                flights += CardFlight(nextId(), AnimAnchor.Stock, holder, card, STAGGER_MILLIS * (i + 2))
            }
        }
        return flights
    }
}
