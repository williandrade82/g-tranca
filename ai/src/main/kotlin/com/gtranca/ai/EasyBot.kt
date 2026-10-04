package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RuleResult
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bot de dificuldade **Fácil**: heurísticas simples de prioridade, com erros propositais.
 *
 * 1. Início da jogada: pega o lixo sempre que houver um plano legal (o que leva mais cartas da mão à
 *    mesa, evitando gastar coringa e sujar canastra limpa); senão compra do monte.
 * 2. Durante a jogada: baixa o conjunto ou acréscimo que leva mais cartas à mesa (bônus se forma
 *    canastra). O motor só oferece baixas que deixam ao menos 2 cartas na mão, exceto quando ficar
 *    sem cartas leva ao morto ou à batida (§8); o bot não refaz essa validação. Não suja canastra
 *    limpa (§6.3/§7.3: a regra permite, sujar é escolha de estratégia; a suja vale 100 pontos a
 *    menos, §7.2, e o coringa só devolve +10 como carta na mesa, §12.1), a não ser que isso esvazie a mão.
 * 3. Descarte: a carta menos útil, isto é, a mais isolada (sem pares nem vizinhas de naipe e que não
 *    cabe nos conjuntos do lado). O 3 preto é descartado de preferência (trava o lixo, §5.3) e o
 *    coringa é evitado.
 *
 * Erros propositais: em cada decisão, com probabilidade [mistakeRate], o bot compra do monte em vez de
 * pegar o lixo, deixa de baixar (e passa ao descarte) ou descarta uma carta qualquer.
 *
 * Só escolhe entre as ações recebidas; nunca valida regras.
 */
class EasyBot(
    private val random: Random,
    private val mistakeRate: Double = DEFAULT_MISTAKE_RATE,
) : BotPlayer {

    init {
        require(mistakeRate in 0.0..1.0) { "mistakeRate deve estar entre 0 e 1: $mistakeRate" }
    }

    override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "Sem ações legais" }
        val chosen = when (view.phase) {
            Phase.AWAITING_DRAW -> chooseDraw(view, legal)
            Phase.PLAYING -> chooseMeld(view, legal) ?: chooseDiscard(view, legal)
            Phase.FINISHED -> null
        }
        return chosen ?: legal.first()
    }

    // ---------- etapa 1: monte ou lixo ----------

    private fun chooseDraw(view: PlayerView, legal: List<Action>): Action? {
        val takes = legal.filterIsInstance<Action.TakeDiscardPile>()
        val draw = legal.firstOrNull { it == Action.DrawFromStock }
        if (takes.isNotEmpty() && (draw == null || !mistake())) {
            return takes.maxBy { takeScore(view, it.plan) }
        }
        return draw ?: legal.firstOrNull { it == Action.DeclineDraw }
    }

    private fun takeScore(view: PlayerView, plan: DiscardPlan): Double {
        val top = view.discardTop
        return when (plan) {
            is DiscardPlan.NewMeld -> meldScore(plan.handCards, plan.handCards + listOfNotNull(top), resultSize = plan.handCards.size + 1)
            is DiscardPlan.AddToMeld -> {
                val target = view.ownMeld(plan.meldId)
                val added = plan.handCards + listOfNotNull(top)
                val dirty = if (target != null && dirtiesCleanCanasta(target, added)) DIRTY_PENALTY else 0.0
                meldScore(plan.handCards, added, resultSize = (target?.cards?.size ?: 0) + added.size) - dirty
            }
        }
    }

    // ---------- etapa 2: baixar ----------

    private fun chooseMeld(view: PlayerView, legal: List<Action>): Action? {
        val candidates = legal.mapNotNull { action ->
            when (action) {
                is Action.CreateMeld -> action to meldScore(action.cards, action.cards, resultSize = action.cards.size)
                is Action.AddToMeld -> {
                    val target = view.ownMeld(action.meldId) ?: return@mapNotNull null
                    val emptiesHand = action.cards.size == view.hand.size
                    if (dirtiesCleanCanasta(target, action.cards) && !emptiesHand) return@mapNotNull null
                    action to meldScore(action.cards, action.cards, resultSize = target.cards.size + action.cards.size)
                }
                else -> null
            }
        }
        if (candidates.isEmpty() || mistake()) return null
        return candidates.maxBy { it.second }.first
    }

    /**
     * Pontuação de uma baixa: mais cartas da mão à mesa é melhor; formar canastra (§7.1) dá bônus;
     * gastar coringa da mão custa um pouco.
     */
    private fun meldScore(fromHand: List<Card>, added: List<Card>, resultSize: Int): Double {
        var score = fromHand.size * CARD_WEIGHT
        if (resultSize >= CANASTA_SIZE && resultSize - added.size < CANASTA_SIZE) score += CANASTA_BONUS
        if (fromHand.any { it.isWild }) score -= WILD_COST
        return score
    }

    /** §6.3/§7.3 o acréscimo leva coringa a uma canastra limpa, que passa a ser suja. */
    private fun dirtiesCleanCanasta(target: Meld, added: List<Card>): Boolean =
        added.any { it.isWild } && target.isCleanCanasta()

    // ---------- etapa 3: descartar ----------

    private fun chooseDiscard(view: PlayerView, legal: List<Action>): Action? {
        val discards = legal.filterIsInstance<Action.Discard>()
        if (discards.isEmpty()) return null
        if (mistake()) {
            val naturals = discards.filterNot { it.card.isWild }
            return naturals.ifEmpty { discards }.random(random)
        }
        val scored = discards.map { it to discardUtility(view, it.card) }
        val best = scored.minOf { it.second }
        return scored.filter { it.second == best }.map { it.first }.random(random)
    }

    /** Utilidade de manter [card] na mão (menor = melhor candidata ao descarte). */
    internal fun discardUtility(view: PlayerView, card: Card): Double {
        if (card.isWild) return WILD_UTILITY
        if (card.isBlackThree) return BLACK_THREE_UTILITY
        val others = view.hand.toMutableList().apply { remove(card) }.filter { !it.isWild && !it.rank.isThree }
        var utility = 0.0
        utility += others.count { it.rank == card.rank } * SAME_RANK_WEIGHT
        for (other in others) {
            if (other.suit != card.suit) continue
            when (abs(other.rank.ordinal - card.rank.ordinal)) {
                1 -> utility += NEIGHBOR_WEIGHT
                2 -> utility += NEAR_NEIGHBOR_WEIGHT
            }
        }
        val fitsOwnMeld = view.tables[view.side.index].melds.any { it.meld.add(listOf(card)) is RuleResult.Ok }
        if (fitsOwnMeld) utility += FITS_MELD_WEIGHT
        // Desempate: entre cartas igualmente isoladas, livra-se das que mais penalizam na mão (§12.2).
        if (card.rank >= Rank.JACK) utility -= HIGH_CARD_TIEBREAK
        return utility
    }

    // ---------- utilitários ----------

    private fun mistake(): Boolean = random.nextDouble() < mistakeRate

    private fun PlayerView.ownMeld(id: MeldId): Meld? = tables[side.index].meld(id)

    companion object {
        const val DEFAULT_MISTAKE_RATE: Double = 0.1

        private val CANASTA_SIZE = RuleSet.DEFAULT.minCanastaSize
        private const val CARD_WEIGHT = 10.0
        private const val CANASTA_BONUS = 30.0
        private const val WILD_COST = 5.0
        private const val DIRTY_PENALTY = 50.0

        private const val WILD_UTILITY = 1_000.0
        private const val BLACK_THREE_UTILITY = -10.0
        private const val SAME_RANK_WEIGHT = 4.0
        private const val NEIGHBOR_WEIGHT = 3.0
        private const val NEAR_NEIGHBOR_WEIGHT = 1.0
        private const val FITS_MELD_WEIGHT = 6.0
        private const val HIGH_CARD_TIEBREAK = 0.5
    }
}
