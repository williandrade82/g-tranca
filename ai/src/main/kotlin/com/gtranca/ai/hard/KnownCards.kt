package com.gtranca.ai.hard

import com.gtranca.ai.PublicHistory
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Seat

/**
 * Cartas sabidamente na mão de cada outro assento, prontas para `determinize`.
 *
 * O [PublicHistory] remove cartas "por cópia equivalente" (as ações usam cartas representativas), então a
 * cópia física que ele guarda pode já estar visível (na mesa, no lixo, na própria mão) enquanto a outra
 * cópia, de mesmo valor e naipe, é a que de fato ficou na mão alheia. A determinização compara a cópia
 * exata ([Card.deck]) e recusa cartas visíveis ou repetidas; por isso, para cada carta sabida:
 * - se a cópia exata está oculta e ainda não foi usada, fica;
 * - senão, se a outra cópia está oculta e ainda não foi usada, troca por ela;
 * - senão, é omitida (a informação ficou velha: a carta já saiu daquela mão).
 *
 * Também omite 3 vermelhos (§6.5 nunca ficam na mão), o próprio assento e o excesso acima do tamanho da mão.
 * O resultado nunca faz `determinize` lançar exceção por causa de `known` (coberto por teste).
 */
internal fun normalizeKnown(view: PlayerView, history: PublicHistory): Map<Seat, List<Card>> =
    normalizeKnown(view, view.mode.seats.associateWith { history.knownHand(it) })

/** Versão sobre um mapa qualquer de cartas sabidas (ver a outra sobrecarga). */
internal fun normalizeKnown(view: PlayerView, known: Map<Seat, List<Card>>): Map<Seat, List<Card>> {
    val visible = HashSet<Card>().apply {
        addAll(view.hand)
        addAll(view.discardPile)
        view.redThrees.forEach { addAll(it) }
        view.tables.forEach { addAll(it.allCards()) }
    }
    val used = HashSet<Card>()
    val result = LinkedHashMap<Seat, List<Card>>()
    for (seat in view.mode.seats) {
        if (seat == view.seat) continue
        val capacity = view.handSizes.getOrElse(seat.index) { 0 }
        val cards = mutableListOf<Card>()
        for (card in known[seat].orEmpty()) {
            if (cards.size >= capacity) break
            if (card.isRedThree) continue
            val chosen = listOf(card, card.otherCopy()).firstOrNull { it !in visible && it !in used } ?: continue
            used += chosen
            cards += chosen
        }
        if (cards.isNotEmpty()) result[seat] = cards
    }
    return result
}

/** A outra cópia física do mesmo valor e naipe (§1 dois baralhos). */
private fun Card.otherCopy(): Card = Card(rank, suit, 1 - deck)
