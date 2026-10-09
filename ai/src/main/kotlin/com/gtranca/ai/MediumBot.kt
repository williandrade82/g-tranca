package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.Suit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Bot de dificuldade **Médio** (padrão, §14): heurísticas com pesos ([MediumWeights]).
 *
 * 1. **Pegar o lixo** (§5): compara o valor do lixo (cartas que vêm para a mão, §5.2, mais o conjunto
 *    baixado com o topo) com o de comprar do monte. Gastar coringa da mão e sujar conjunto ou canastra
 *    limpa custam; com coringa no topo (§5.4) o custo depende de onde ele vai: num conjunto novo,
 *    num conjunto limpo ou numa canastra limpa (que vira suja, −100, §7.2). Um lixo grande compensa.
 * 2. **Baixar** (§4.3 etapa 2): leva cartas à mesa (ritmo para o morto, §9), com bônus por formar
 *    canastra (limpa > suja; a primeira do lado é condição para bater, §11.1) e um bônus grande por
 *    esvaziar a mão (morto direto, §9.2, ou batida, §11.1) ou ficar com 1 carta para descartá-la
 *    (morto indireto, §9.3, ou batida). Sujar canastra limpa (§6.3/§7.3) só vale a pena quando ajuda
 *    a fechar a mão (pegar o morto ou bater, o que também impede o adversário de pontuar mais). Gastar
 *    coringa da mão tem custo, menor quando forma a primeira canastra, quando o lado já pode bater ou
 *    quando o adversário está perto de bater (coringa na mão vale −10, §12.2).
 *    O 3 preto só fica de reserva com a mão grande e enquanto o lado ainda não pode bater.
 * 3. **Descartar** (§8): minimiza (utilidade de manter a carta) + (risco de alimentar o próximo
 *    jogador, que é sempre adversário, §4.2). O risco é a chance estimada de ele levar a carta à mesa
 *    (§5.1: cabe na mesa dele, ou forma conjunto com cartas que ele sabidamente tem, pegas do lixo,
 *    ou com cartas ocultas, por probabilidade) vezes o valor do lixo para ele. O 3 preto trava o lixo
 *    (§5.3, risco zero): é guardado enquanto o lixo vale pouco e descartado quando o lixo fica valioso.
 *    Cooperação em duplas: cartas que cabem nos conjuntos do lado (inclusive os do parceiro, §6.4),
 *    sobretudo as que fecham canastra, têm utilidade alta e não são descartadas.
 *
 * A memória ([PublicHistory]) vem só de [observe] e é zerada em [onNewRound]. Cada assento tem a
 * sua instância. Só escolhe entre as ações recebidas; nunca valida regras (usa [Meld.add] apenas
 * para estimar se uma carta serve num conjunto já na mesa).
 */
class MediumBot(
    private val random: Random,
    private val weights: MediumWeights = MediumWeights(),
) : BotPlayer {

    private val history = PublicHistory()

    override fun observe(event: PublicEvent) = history.record(event)

    override fun onNewRound() = history.clear()

    override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
        require(legal.isNotEmpty()) { "Sem ações legais" }
        val chosen = when (view.phase) {
            Phase.AWAITING_DRAW -> chooseDraw(view, legal)
            Phase.PLAYING -> chooseMeld(view, legal) ?: chooseDiscard(view, legal) ?: chooseMeld(view, legal, force = true)
            Phase.FINISHED -> null
        }
        return chosen ?: legal.first()
    }

    /**
     * Ações candidatas para a busca do bot Difícil: as melhores de [legal] segundo esta heurística, na ordem
     * de preferência do Médio (a primeira é a que ele escolheria, a menos de desempates no descarte).
     *
     * - Início da jogada: a escolha do Médio, comprar do monte ou recusar (§10.2), quando legais, e os
     *   [perKind] melhores planos de pegar o lixo (§5.1).
     * - Durante a jogada: os [perKind] melhores conjuntos/acréscimos e os [perKind] melhores descartes; se o
     *   Médio baixaria, as baixas vêm primeiro, senão os descartes.
     *
     * Devolve sempre elementos de [legal] (não vazia se [legal] não for). Não usa o RNG.
     */
    internal fun rankedCandidates(view: PlayerView, legal: List<Action>, perKind: Int): List<Action> {
        if (legal.size <= 1) return legal
        val result = LinkedHashSet<Action>()
        when (view.phase) {
            Phase.AWAITING_DRAW -> {
                chooseDraw(view, legal)?.let(result::add)
                legal.filter { it == Action.DrawFromStock || it == Action.DeclineDraw }.forEach(result::add)
                legal.filterIsInstance<Action.TakeDiscardPile>()
                    .map { it to takeScore(view, it.plan) }
                    .sortedByDescending { it.second }
                    .take(perKind)
                    .forEach { result += it.first }
            }
            Phase.PLAYING -> {
                val ctx = Context(view)
                val melds = legal.mapNotNull { action ->
                    when (action) {
                        is Action.CreateMeld -> action to meldScore(ctx, action.cards, target = null)
                        is Action.AddToMeld -> ctx.ownMeld(action.meldId.value)?.let { action to meldScore(ctx, action.cards, it) }
                        else -> null
                    }
                }.sortedByDescending { it.second }.take(perKind)
                val discards = legal.filterIsInstance<Action.Discard>()
                    .map { it to discardCost(ctx, it.card) }
                    .sortedBy { it.second }
                    .take(perKind)
                    .map { it.first }
                val wouldMeld = melds.isNotEmpty() && (discards.isEmpty() || melds.first().second >= weights.meldThreshold)
                if (wouldMeld) {
                    melds.forEach { result += it.first }
                    result += discards
                } else {
                    result += discards
                    melds.forEach { result += it.first }
                }
            }
            Phase.FINISHED -> Unit
        }
        return result.toList().ifEmpty { legal }
    }

    // =====================================================================================
    // Etapa 1: monte ou lixo
    // =====================================================================================

    private fun chooseDraw(view: PlayerView, legal: List<Action>): Action? {
        val takes = legal.filterIsInstance<Action.TakeDiscardPile>()
        val draw = legal.firstOrNull { it == Action.DrawFromStock }
        val decline = legal.firstOrNull { it == Action.DeclineDraw }
        if (takes.isEmpty()) return draw ?: decline
        val best = takes.map { it to takeScore(view, it.plan) }.maxBy { it.second }
        return when {
            draw != null -> if (best.second > weights.drawValue) best.first else draw
            // §10.2 sem monte nem morto: pegar o lixo mantém a partida viva; recusar a encerra sem vencedor.
            decline != null && best.second <= 0.0 && !sideHasMorto(view) -> decline
            else -> best.first
        }
    }

    /** Valor de pegar o lixo com o [plan] (§5.1, §5.2), na escala de [MediumWeights]. */
    internal fun takeScore(view: PlayerView, plan: DiscardPlan): Double {
        val top = view.discardTop ?: return Double.NEGATIVE_INFINITY
        val gained = view.discardPile.dropLast(1)
        val ctx = Context(view)
        var score = gained.sumOf { gainValue(ctx, it) }
        // risco de ficar com o lixo na mão se o adversário bater em seguida (§12.2)
        score -= ctx.threat * weights.handPenaltyRisk * gained.size

        val handCards: List<Card>
        val target: Meld?
        when (plan) {
            is DiscardPlan.NewMeld -> { handCards = plan.handCards; target = null }
            is DiscardPlan.AddToMeld -> { handCards = plan.handCards; target = ctx.ownMeld(plan.meldId.value) }
        }
        val added = handCards + top
        val resultSize = (target?.cards?.size ?: 0) + added.size
        val resultHasWild = (target?.hasWild ?: false) || added.any { it.isWild }
        score += handCards.size * weights.cardToTable + weights.gainNatural // o topo vai direto à mesa
        score += canastaBonus(ctx, before = target?.cards?.size ?: 0, after = resultSize, dirty = resultHasWild)
        if (handCards.any { it.isWild }) score -= weights.wildUse
        if (top.isWild) {
            // §5.4 coringa do topo: onde ele vai decide o custo
            score -= when {
                target == null -> weights.wildTopNewMeld
                target.isCleanCanasta() -> weights.dirtyCleanCanasta // §6.3/§7.3 a canastra limpa vira suja
                else -> weights.wildTopDirtiesMeld
            }
        } else if (target != null && target.isCleanCanasta() && handCards.any { it.isWild }) {
            score -= weights.dirtyCleanCanasta
        }
        return score
    }

    /** Valor, para mim, de uma carta que vem do lixo para a mão. */
    private fun gainValue(ctx: Context, card: Card): Double = when {
        card.isWild -> weights.gainWild
        card.isBlackThree -> weights.gainBlackThree
        else -> {
            val synergy = ctx.view.hand.any { it.rank == card.rank && !it.isWild } ||
                ctx.view.hand.any { it.suit == card.suit && !it.isWild && !it.rank.isThree && abs(it.rank.ordinal - card.rank.ordinal) == 1 } ||
                ctx.fitsOwnMeld(card)
            weights.gainNatural + if (synergy) weights.gainSynergy else 0.0
        }
    }

    /** §9.1 o lado do jogador já pegou um morto (condição de batida, §11.1). */
    private fun sideHasMorto(view: PlayerView): Boolean = view.mortoStatus.any { it == MortoStatus.Taken(view.side) }

    // =====================================================================================
    // Etapa 2: baixar
    // =====================================================================================

    private fun chooseMeld(view: PlayerView, legal: List<Action>, force: Boolean = false): Action? {
        val ctx = Context(view)
        val scored = legal.mapNotNull { action ->
            when (action) {
                is Action.CreateMeld -> action to meldScore(ctx, action.cards, target = null)
                is Action.AddToMeld -> {
                    val target = ctx.ownMeld(action.meldId.value) ?: return@mapNotNull null
                    action to meldScore(ctx, action.cards, target)
                }
                else -> null
            }
        }
        if (scored.isEmpty()) return null
        val best = scored.maxBy { it.second }
        return if (force || best.second >= weights.meldThreshold) best.first else null
    }

    /** Valor de baixar [cards] da mão, num conjunto novo ([target] nulo) ou acrescentando a [target]. */
    internal fun meldScore(ctx: Context, cards: List<Card>, target: Meld?): Double {
        val view = ctx.view
        val before = target?.cards?.size ?: 0
        val after = before + cards.size
        val usesWild = cards.any { it.isWild }
        val dirty = usesWild || (target?.hasWild ?: false)
        val formsCanasta = before < CANASTA_SIZE && after >= CANASTA_SIZE
        val remaining = view.hand.size - cards.size
        // §8 o motor (legalActions) só oferece baixas que deixam 0 ou 1 carta na mão quando ficar sem
        // cartas leva ao morto (§9.2/§9.3) ou à batida (§11.1); senão, exige ao menos 2. Para uma ação
        // legal, portanto, sobrar no máximo 1 carta já significa fechar a mão: o bot não refaz a regra.
        val closesHand = remaining <= 1

        var score = cards.size * weights.cardToTable
        score += canastaBonus(ctx, before, after, dirty)
        if (remaining == 0) score += weights.emptyHand else if (closesHand) score += weights.oneCardLeft
        if (usesWild && !closesHand) {
            // coringa para formar a primeira canastra (condição para bater, §11.1) é bem gasto;
            // perto do fim, coringa na mão é só penalidade (−10, §12.2)
            val factor = when {
                formsCanasta && !ctx.sideHasCanasta -> 0.25
                ctx.canGoOut -> weights.wildUseGoingOutFactor
                else -> 1.0
            }
            score -= weights.wildUse * factor * (1.0 - ctx.threat)
        }
        if (usesWild && target != null && target.isCleanCanasta() && !closesHand) {
            score -= weights.dirtyCleanCanasta // §6.3/§7.3 sujar a canastra limpa custa 100 (§7.2)
        }
        return score
    }

    private fun canastaBonus(ctx: Context, before: Int, after: Int, dirty: Boolean): Double {
        if (before >= CANASTA_SIZE || after < CANASTA_SIZE) return 0.0
        val base = if (dirty) weights.dirtyCanastaBonus else weights.cleanCanastaBonus
        if (ctx.sideHasCanasta) return base
        // §12.1 a primeira canastra vira os 3 vermelhos do lado de −100 para +100.
        val redThrees = ctx.view.redThrees.getOrNull(ctx.ownSide.index)?.size ?: 0
        return base + weights.firstCanastaBonus + redThrees * weights.redThreeSwing
    }

    // =====================================================================================
    // Etapa 3: descartar
    // =====================================================================================

    private fun chooseDiscard(view: PlayerView, legal: List<Action>): Action? {
        val discards = legal.filterIsInstance<Action.Discard>()
        if (discards.isEmpty()) return null
        val ctx = Context(view)
        val scored = discards.map { it to discardCost(ctx, it.card) }
        val best = scored.minOf { it.second }
        return scored.filter { it.second <= best + TIE_EPSILON }.map { it.first }.random(random)
    }

    /** Custo de descartar [card]: utilidade de mantê-la + risco de alimentar o próximo adversário. */
    internal fun discardCost(ctx: Context, card: Card): Double = keepValue(ctx, card) + feedRisk(ctx, card)

    /** Utilidade de manter [card] na mão. */
    internal fun keepValue(ctx: Context, card: Card): Double {
        if (card.isWild) return weights.wildKeep
        if (card.isBlackThree) {
            // §5.3 reserva para travar o lixo depois; perto de pegar o morto ou de bater, só atrapalha
            val reserve = !ctx.canGoOut && ctx.view.hand.size >= weights.blackThreeReserveMinHand
            return if (reserve) weights.blackThreeReserve else weights.blackThreeDump
        }
        val others = ctx.view.hand.toMutableList().apply { remove(card) }.filter { !it.isWild && !it.rank.isThree }
        var value = minOf(others.count { it.rank == card.rank }, 2) * weights.pair
        val neighborRanks = others.filter { it.suit == card.suit }.map { it.rank }.distinct()
        for (rank in neighborRanks) {
            when (abs(rank.ordinal - card.rank.ordinal)) {
                1 -> value += weights.neighbor
                2 -> value += weights.nearNeighbor
            }
        }
        // §6.4 os conjuntos são do lado: em duplas, inclui os do parceiro
        val fitting = ctx.ownMelds.filter { it.add(listOf(card)) is RuleResult.Ok }
        if (fitting.isNotEmpty()) {
            value += weights.fitsOwnMeld
            if (fitting.any { it.cards.size == CANASTA_SIZE - 1 }) value += weights.completesOwnCanasta
        }
        if (card.rank >= Rank.JACK) value -= weights.highCardPenalty // §12.2 −10 contra −8
        return value
    }

    /**
     * Risco de alimentar o próximo jogador (sempre adversário, §4.2) ao descartar [card]: chance de ele
     * poder pegar o lixo com [card] no topo (§5.1) vezes o valor do lixo para ele.
     */
    internal fun feedRisk(ctx: Context, card: Card): Double {
        val probability = takeProbability(ctx, card)
        if (probability <= 0.0) return 0.0
        return probability * pileValueForOpponent(ctx, card)
    }

    /** Chance estimada de o próximo jogador levar [card] do topo do lixo à mesa (§5.1, §5.3, §5.4). */
    internal fun takeProbability(ctx: Context, card: Card): Double {
        if (card.isBlackThree) return 0.0 // §5.3 3 preto no topo trava o lixo
        if (card.isWild) return weights.wildTakeProbability // §5.4
        if (ctx.opponentMelds.any { it.add(listOf(card)) is RuleResult.Ok }) return 1.0 // §5.1 acréscimo
        val next = ctx.nextSeat
        val known = history.knownHand(next)
        val pWild = ctx.pHas(known.count { it.isWild }, ctx.unseenWilds)

        // grupo: 2 naturais do número (ou 1 + coringa) na mão do próximo
        val knownRank = known.count { it.rank == card.rank }
        val unseenRank = Suit.entries.sumOf { ctx.unseenCopies(card.rank, it) }
        val dist = (0..unseenRank).map { k -> binomial(unseenRank, k, ctx.q) }
        fun pCount(atLeast: Int): Double = (0..unseenRank).filter { knownRank + it >= atLeast }.sumOf { dist[it] }
        var pGroup = pCount(2) + (pCount(1) - pCount(2)) * pWild
        if (history.discardsOf(next).any { it.rank == card.rank }) pGroup *= weights.discardedRankDiscount

        // sequência do naipe: 2 vizinhas, ou 1 vizinha (até 2 passos) + coringa
        fun has(offset: Int): Double {
            val ordinal = card.rank.ordinal + offset
            if (ordinal < Rank.FOUR.ordinal || ordinal > Rank.ACE.ordinal) return 0.0
            val rank = Rank.entries[ordinal]
            return ctx.pHas(known.count { it.rank == rank && it.suit == card.suit }, ctx.unseenCopies(rank, card.suit))
        }
        val pairs = listOf(-2 to -1, -1 to 1, 1 to 2)
        val pTwoNeighbors = 1.0 - pairs.fold(1.0) { acc, (a, b) -> acc * (1.0 - has(a) * has(b)) }
        val pOneNeighbor = 1.0 - listOf(-2, -1, 1, 2).fold(1.0) { acc, o -> acc * (1.0 - has(o)) }
        val pSequence = 1.0 - (1.0 - pTwoNeighbors) * (1.0 - pWild * pOneNeighbor)

        // §5.1 o topo vai a uma sequência dele na mesa junto com a carta do buraco (ou um coringa) da mão dele.
        val pBridge = weights.bridgeTakeFactor * ctx.opponentMelds.maxOfOrNull { meld -> bridgeProbability(ctx, meld, card, known, pWild) }.let { it ?: 0.0 }

        val pNew = 1.0 - (1.0 - pGroup.coerceIn(0.0, 1.0)) * (1.0 - pSequence.coerceIn(0.0, 1.0))
        return 1.0 - (1.0 - pNew) * (1.0 - pBridge.coerceIn(0.0, 1.0))
    }

    /**
     * Chance de o próximo levar [card] à sequência [meld] do lado dele com uma carta da mão: falta exatamente um
     * valor entre a ponta da sequência e [card] (a natural desse valor, ou um coringa se a sequência não tem).
     */
    private fun bridgeProbability(ctx: Context, meld: Meld, card: Card, known: List<Card>, pWild: Double): Double {
        val kind = meld.kind as? com.gtranca.engine.model.MeldKind.Sequence ?: return 0.0
        if (kind.suit != card.suit) return 0.0
        val gapRank = when (card.rank.ordinal) {
            meld.highRank.ordinal + 2 -> meld.highRank.ordinal + 1
            meld.lowRank.ordinal - 2 -> meld.lowRank.ordinal - 1
            else -> return 0.0
        }
        val rank = Rank.entries.getOrNull(gapRank) ?: return 0.0
        if (rank.isWild || rank.isThree) return 0.0
        val pNatural = ctx.pHas(known.count { it.rank == rank && it.suit == card.suit }, ctx.unseenCopies(rank, card.suit))
        val pWithWild = if (meld.hasWild) 0.0 else pWild
        return 1.0 - (1.0 - pNatural) * (1.0 - pWithWild)
    }

    /** Valor, para o adversário, do lixo com [card] no topo (§5.2: ele leva todas as cartas). */
    private fun pileValueForOpponent(ctx: Context, card: Card): Double {
        val pile = ctx.view.discardPile + card
        val value = weights.feedBase + pile.sumOf { c ->
            when {
                c.isWild -> weights.oppWild
                c.rank.isThree -> 0.0
                else -> weights.oppNatural + if (ctx.opponentMelds.any { it.add(listOf(c)) is RuleResult.Ok }) weights.oppFitsMeld else 0.0
            }
        }
        return value * (1.0 + weights.threatFeedMultiplier * ctx.threat)
    }

    // =====================================================================================
    // Contexto derivado da vista (e só dela + histórico público)
    // =====================================================================================

    internal inner class Context(val view: PlayerView) {
        val ownSide: Side = view.side
        val opponentSide: Side = Side(1 - ownSide.index)
        val ownMelds: List<Meld> = view.tables[ownSide.index].melds.map { it.meld }
        val opponentMelds: List<Meld> = view.tables[opponentSide.index].melds.map { it.meld }
        val sideHasMorto: Boolean = view.mortoStatus.any { it == MortoStatus.Taken(ownSide) }
        val sideHasCanasta: Boolean = ownMelds.any { it.isCanasta() }

        /** §9.5 sem morto disponível, bate-se sem ter pego o morto. */
        val noMortoLeft: Boolean = view.mortoStatus.none { it == MortoStatus.Available }

        /** §11.1 o lado já cumpre morto (ou não há mais morto, §9.5) e canastra: basta esvaziar a mão para bater. */
        val canGoOut: Boolean = (sideHasMorto || noMortoLeft) && sideHasCanasta

        /** §4.2 o próximo no sentido anti-horário é sempre adversário (em duplas, os parceiros ficam opostos). */
        val nextSeat: Seat = view.mode.nextSeat(view.seat)

        /**
         * Ameaça de o adversário bater logo (0..1): lado com morto (§9) e canastra (§11.1) e mão pequena.
         */
        val threat: Double = run {
            val oppHasMorto = view.mortoStatus.any { it == MortoStatus.Taken(opponentSide) }
            // §9.5 sem morto disponível, o adversário também pode bater sem ter pego o morto.
            val oppMayGoOut = oppHasMorto || noMortoLeft
            val oppHasCanasta = opponentMelds.any { it.isCanasta() }
            val oppMinHand = view.mode.seats.filter { view.mode.sideOf(it) == opponentSide }.minOf { view.handSizes[it.index] }
            var t = 0.0
            if (oppHasMorto) t += 0.4
            // Heurística (não é regra): só soma pelo risco de batida de quem pode bater e tem canastra, ou mão pequena com morto.
            if (oppMayGoOut && oppHasCanasta) t += 0.3
            if (oppMayGoOut && oppMinHand <= 4 && (oppHasMorto || oppHasCanasta)) t += 0.3
            t
        }

        private val visible: List<Card> = view.hand + view.discardPile + view.redThrees.flatten() +
            view.tables.flatMap { it.allCards() } +
            view.mode.seats.filter { it != view.seat }.flatMap { history.knownHand(it) }

        private val visibleCount: Map<Pair<Rank, Suit>, Int> = visible.groupingBy { it.rank to it.suit }.eachCount()

        /** Cartas ainda ocultas (mãos alheias, monte e mortos), sem contar as sabidas. */
        private val unseenTotal: Int = (Deck.SIZE - visible.size).coerceAtLeast(1)

        /** Chance de uma carta oculta qualquer estar na parte desconhecida da mão do próximo jogador. */
        val q: Double = run {
            val unknownInNextHand = (view.handSizes[nextSeat.index] - history.knownHand(nextSeat).size).coerceAtLeast(0)
            (unknownInNextHand.toDouble() / unseenTotal).coerceIn(0.0, 1.0)
        }

        fun unseenCopies(rank: Rank, suit: Suit): Int = (COPIES - (visibleCount[rank to suit] ?: 0)).coerceAtLeast(0)

        val unseenWilds: Int = Suit.entries.sumOf { unseenCopies(Rank.TWO, it) }

        /** Chance de o próximo ter ao menos uma carta de uma classe com [known] cópias sabidas e [unseen] ocultas. */
        fun pHas(known: Int, unseen: Int): Double = if (known > 0) 1.0 else 1.0 - (1.0 - q).pow(unseen)

        fun ownMeld(id: Int): Meld? = view.tables[ownSide.index].melds.firstOrNull { it.id.value == id }?.meld

        fun fitsOwnMeld(card: Card): Boolean = ownMelds.any { it.add(listOf(card)) is RuleResult.Ok }
    }

    companion object {
        private val CANASTA_SIZE = RuleSet.DEFAULT.minCanastaSize
        private const val COPIES = 2 // §1 dois baralhos
        private const val TIE_EPSILON = 1e-9

        private fun binomial(n: Int, k: Int, p: Double): Double {
            var c = 1.0
            for (i in 0 until k) c = c * (n - i) / (i + 1)
            return c * p.pow(k) * (1 - p).pow(n - k)
        }
    }
}
