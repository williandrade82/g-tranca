package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable

/**
 * Máquina de estados da partida (§4.3, §5, §6.5, §8–§11). Funções puras sobre [RoundState].
 *
 * [validate], [apply] e [legalActions] compartilham a mesma lógica ([step]): uma ação é válida
 * se e somente se [step] produz um novo estado.
 *
 * Efeitos automáticos (não são ações):
 * - §6.5 / §9.4 3 vermelho que entra na mão vai para a mesa do lado e é reposto do monte, em cadeia;
 * - §10.1 monte vazio quando é preciso comprar (compra ou reposição): o primeiro morto disponível
 *   (menor índice) vira monte; sem monte nem morto, a reposição de 3 vermelho não acontece;
 * - §9.2 / §9.3 ficar sem cartas com o lado sem morto e morto disponível: o jogador pega o primeiro
 *   morto disponível. No morto indireto (descarte da última carta) o morto vai para a mão já no
 *   descarte, mas a vez passa: o jogador só joga com ele na sua próxima vez;
 * - §11 ficar sem cartas com o lado já tendo morto e ao menos uma canastra: batida;
 * - §8 / §11.2 se, após a compra, o jogador ficou com 1 carta (3 vermelho sem reposição) e não
 *   pode bater descartando-a, a partida termina sem vencedor;
 * - §10.2 / §11.2 ao passar a vez, se o próximo jogador não pode comprar (monte vazio, nenhum morto
 *   disponível) nem pegar o lixo, a partida termina sem vencedor.
 */
object RoundEngine {

    /** Valida [action] do [seat] no [state], devolvendo o motivo da recusa. */
    fun validate(state: RoundState, seat: Seat, action: Action, rules: RuleSet = RuleSet.DEFAULT): RuleResult<Unit> =
        when (val result = step(state, seat, action, rules)) {
            is RuleResult.Ok -> RuleResult.Ok(Unit)
            is RuleResult.Failure -> result
        }

    /** Aplica [action]. Lança [IllegalArgumentException] se ela for inválida (ver [validate]). */
    fun apply(state: RoundState, seat: Seat, action: Action, rules: RuleSet = RuleSet.DEFAULT): RoundState =
        step(state, seat, action, rules).getOrThrow()

    /**
     * Ações válidas do [seat], **completas a menos de cartas idênticas**: toda ação aceita por [validate]
     * está na lista, ou está uma equivalente que difere só pela cópia do baralho de alguma carta
     * (mesmo valor e naipe). Cada classe aparece uma vez, com cartas representativas.
     *
     * Inclui, conforme a fase: [Action.DrawFromStock], [Action.DeclineDraw] (§10.2), todos os planos de
     * [Action.TakeDiscardPile] (§5.1); todos os [Action.CreateMeld] e [Action.AddToMeld] (de qualquer
     * tamanho) e todos os [Action.Discard]. Ver [LegalActions] para a enumeração.
     */
    fun legalActions(state: RoundState, seat: Seat, rules: RuleSet = RuleSet.DEFAULT): List<Action> =
        LegalActions.generate(state, seat, rules)

    /** Núcleo único de validação e transição. */
    internal fun step(state: RoundState, seat: Seat, action: Action, rules: RuleSet): RuleResult<RoundState> {
        if (state.phase == Phase.FINISHED) return fail(ActionError.ROUND_FINISHED)
        if (seat != state.currentSeat) return fail(ActionError.NOT_YOUR_TURN)
        val result = when (action) {
            Action.DrawFromStock -> drawFromStock(state, seat, rules)
            Action.DeclineDraw -> declineDraw(state)
            is Action.TakeDiscardPile -> takeDiscardPile(state, seat, action.plan, rules)
            is Action.CreateMeld -> meldFromHand(state, seat, action.cards, rules) { it.createMeld(action.cards, rules) }
            is Action.AddToMeld ->
                meldFromHand(state, seat, action.cards, rules) { it.addToMeld(action.meldId, action.cards, rules) }
            is Action.Discard -> discard(state, seat, action.card, rules)
        }
        return result.clearingDealReplacements(seat)
    }

    /**
     * §3.5 / §6.5 as reposições da distribuição só valem até a primeira ação do assento: a partir daí uma carta
     * pode sair da mão e voltar (ex.: pelo lixo) e deixaria de ser "reposição da distribuição".
     */
    private fun RuleResult<RoundState>.clearingDealReplacements(seat: Seat): RuleResult<RoundState> {
        val next = (this as? RuleResult.Ok)?.value ?: return this
        if (next.dealReplacementsOf(seat).isEmpty()) return this
        return RuleResult.Ok(next.copy(dealReplacements = next.dealReplacements.replaceAt(seat.index, emptyList())))
    }

    // ---------- ações ----------

    /** §4.3 etapa 1; §10.1 morto vira monte; §6.5 3 vermelho comprado. */
    private fun drawFromStock(state: RoundState, seat: Seat, rules: RuleSet): RuleResult<RoundState> {
        if (state.phase != Phase.AWAITING_DRAW) return fail(ActionError.ALREADY_DREW)
        val (afterDraw, card) = drawOne(state)
        if (card == null) return fail(ActionError.STOCK_EXHAUSTED)
        val drawn = afterDraw.withHand(seat, afterDraw.handOf(seat) + card).copy(phase = Phase.PLAYING)
        val settled = settleRedThrees(drawn, seat)
        // §8 última carta sem reposição: só acontece se um 3 vermelho comprado ficou sem reposição
        // (a mão nunca começa a vez vazia). Sem poder bater descartando essa carta: fim sem vencedor (§11.2).
        if (settled.handOf(seat).size == 1 && !canGoOut(settled, seat, rules)) {
            return RuleResult.Ok(settled.finish(RoundResult.NoWinner))
        }
        return RuleResult.Ok(settled)
    }

    /** §10.2 recusar a compra com monte vazio e sem morto disponível: fim sem vencedor. */
    private fun declineDraw(state: RoundState): RuleResult<RoundState> {
        if (state.phase != Phase.AWAITING_DRAW) return fail(ActionError.ALREADY_DREW)
        if (!cannotDraw(state)) return fail(ActionError.DECLINE_NOT_ALLOWED)
        return RuleResult.Ok(state.finish(RoundResult.NoWinner))
    }

    /** §5.1 / §5.2 pegar o lixo levando o topo à mesa conforme o [plan]; §5.4 vale também com coringa no topo. */
    private fun takeDiscardPile(state: RoundState, seat: Seat, plan: DiscardPlan, rules: RuleSet): RuleResult<RoundState> {
        if (state.phase != Phase.AWAITING_DRAW) return fail(ActionError.ALREADY_DREW)
        val top = state.discardTop ?: return fail(ActionError.DISCARD_PILE_EMPTY) // §5.5
        if (top.isBlackThree) return fail(ActionError.DISCARD_PILE_LOCKED) // §5.3
        val hand = state.handOf(seat)
        val handCards = when (plan) {
            is DiscardPlan.NewMeld -> plan.handCards
            is DiscardPlan.AddToMeld -> plan.handCards
        }
        if (!hand.containsAll(handCards)) return fail(ActionError.CARD_NOT_IN_HAND)
        val side = state.mode.sideOf(seat)
        val table = state.tableOf(side)
        val newTable = when (plan) {
            is DiscardPlan.NewMeld -> {
                if (handCards.size < 2) return fail(ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS)
                table.createMeld(handCards + top, rules)
            }
            is DiscardPlan.AddToMeld -> table.addToMeld(plan.meldId, handCards + top, rules)
        }
        val updatedTable = when (newTable) {
            is RuleResult.Failure -> return newTable
            is RuleResult.Ok -> newTable.value
        }
        val newHand = hand - handCards.toSet() + state.discardPile.dropLast(1) // §5.2
        val taken = state.withHand(seat, newHand).withTable(side, updatedTable)
            .copy(discardPile = emptyList(), phase = Phase.PLAYING)
        keepCardsError(taken, seat, rules)?.let { return fail(it) }
        return resolveEmptyHand(taken, seat, rules)
    }

    /** §4.3 etapa 2: baixar conjunto novo ou acrescentar, com cartas da mão. */
    private inline fun meldFromHand(
        state: RoundState,
        seat: Seat,
        cards: List<Card>,
        rules: RuleSet,
        operation: (SideTable) -> RuleResult<SideTable>,
    ): RuleResult<RoundState> {
        if (state.phase != Phase.PLAYING) return fail(ActionError.MUST_DRAW_FIRST)
        val hand = state.handOf(seat)
        if (!hand.containsAll(cards)) return fail(ActionError.CARD_NOT_IN_HAND)
        val side = state.mode.sideOf(seat)
        val table = when (val result = operation(state.tableOf(side))) {
            is RuleResult.Failure -> return result
            is RuleResult.Ok -> result.value
        }
        val melded = state.withHand(seat, hand - cards.toSet()).withTable(side, table)
        keepCardsError(melded, seat, rules)?.let { return fail(it) }
        return resolveEmptyHand(melded, seat, rules)
    }

    /** §8 descarte; §9.3 morto indireto; §11 batida descartando a última carta. */
    private fun discard(state: RoundState, seat: Seat, card: Card, rules: RuleSet): RuleResult<RoundState> {
        if (state.phase != Phase.PLAYING) return fail(ActionError.MUST_DRAW_FIRST)
        val hand = state.handOf(seat)
        if (card !in hand) return fail(ActionError.CARD_NOT_IN_HAND)
        if (card.isRedThree) return fail(ActionError.CANNOT_DISCARD_RED_THREE)
        val discarded = state.withHand(seat, hand - card).copy(discardPile = state.discardPile + card)
        val resolved = when (val result = resolveEmptyHand(discarded, seat, rules)) {
            is RuleResult.Failure -> return result
            is RuleResult.Ok -> result.value
        }
        if (resolved.phase == Phase.FINISHED) return RuleResult.Ok(resolved)
        return RuleResult.Ok(passTurn(resolved, seat, rules))
    }

    // ---------- efeitos automáticos ----------

    /**
     * §8 / §9 / §11 se a mão do [seat] ficou vazia: batida (lado com morto e canastra), morto
     * (lado sem morto e morto disponível) ou jogada inválida.
     */
    private fun resolveEmptyHand(state: RoundState, seat: Seat, rules: RuleSet): RuleResult<RoundState> {
        if (state.handOf(seat).isNotEmpty()) return RuleResult.Ok(state)
        val side = state.mode.sideOf(seat)
        if (state.hasTakenMorto(side)) {
            return if (canGoOut(state, seat, rules)) {
                RuleResult.Ok(state.finish(RoundResult.GoOut(side, seat))) // §11.1
            } else {
                fail(ActionError.NO_CANASTA_TO_GO_OUT)
            }
        }
        val index = state.firstAvailableMorto() ?: return fail(ActionError.NO_MORTO_AVAILABLE) // §9.5
        val withMorto = state
            .withHand(seat, state.mortos[index])
            .copy(
                mortos = state.mortos.replaceAt(index, emptyList()),
                mortoStatus = state.mortoStatus.replaceAt(index, MortoStatus.Taken(side)),
            )
        return RuleResult.Ok(settleRedThrees(withMorto, seat)) // §9.4
    }

    /**
     * §8 manter cartas para descartar: depois de baixar, acrescentar ou pegar o lixo, sobrar exatamente
     * 1 carta só é válido se ficar sem cartas for permitido (morto ou batida); senão, devem sobrar 2.
     */
    private fun keepCardsError(state: RoundState, seat: Seat, rules: RuleSet): ActionError? =
        if (state.handOf(seat).size == 1 && !canEmptyHand(state, seat, rules)) ActionError.MUST_KEEP_CARD_TO_DISCARD else null

    /** §8 ficar sem cartas é permitido: resulta em batida (§11.1) ou em pegar o morto (§9). */
    private fun canEmptyHand(state: RoundState, seat: Seat, rules: RuleSet): Boolean {
        val side = state.mode.sideOf(seat)
        return if (state.hasTakenMorto(side)) canGoOut(state, seat, rules) else state.firstAvailableMorto() != null
    }

    /** §11.1 o lado do [seat] já pegou o morto e tem ao menos uma canastra na mesa. */
    private fun canGoOut(state: RoundState, seat: Seat, rules: RuleSet): Boolean {
        val side = state.mode.sideOf(seat)
        return state.hasTakenMorto(side) && state.tableOf(side).melds.any { it.meld.isCanasta(rules) }
    }

    /** §4.2 passa a vez; §10.2 / §11.2 fim sem vencedor se o próximo não pode comprar nem pegar o lixo. */
    private fun passTurn(state: RoundState, seat: Seat, rules: RuleSet): RoundState {
        val next = state.copy(currentSeat = state.mode.nextSeat(seat), phase = Phase.AWAITING_DRAW)
        if (cannotDraw(next) && !LegalActions.canTakeDiscardPile(next, next.currentSeat, rules)) {
            return next.finish(RoundResult.NoWinner)
        }
        return next
    }

    /**
     * §6.5 baixa cada 3 vermelho da mão do [seat] e repõe do monte, em cadeia. Cada 3 vermelho baixado entra
     * no registro público [RoundState.redThreeLog] (§3.5 / §6.5) com o [seat] e `atDeal = false`.
     */
    private fun settleRedThrees(state: RoundState, seat: Seat): RoundState {
        var current = state
        val side = current.mode.sideOf(seat)
        while (true) {
            val redThree = current.handOf(seat).firstOrNull { it.isRedThree } ?: return current
            current = current
                .withHand(seat, current.handOf(seat) - redThree)
                .copy(
                    redThrees = current.redThrees.replaceAt(side.index, current.redThreesOf(side) + redThree),
                    redThreeLog = current.redThreeLog + RedThreeLaid(seat, redThree, atDeal = false),
                )
            val (afterDraw, replacement) = drawOne(current)
            current = if (replacement == null) afterDraw else afterDraw.withHand(seat, afterDraw.handOf(seat) + replacement)
        }
    }

    /** Tira a carta do topo do monte; §10.1 com monte vazio, o primeiro morto disponível vira monte. */
    private fun drawOne(state: RoundState): Pair<RoundState, Card?> {
        var current = state
        if (current.stock.isEmpty()) {
            val index = current.firstAvailableMorto() ?: return current to null
            current = current.copy(
                stock = current.mortos[index],
                mortos = current.mortos.replaceAt(index, emptyList()),
                mortoStatus = current.mortoStatus.replaceAt(index, MortoStatus.BecameStock),
            )
        }
        if (current.stock.isEmpty()) return current to null
        return current.copy(stock = current.stock.drop(1)) to current.stock.first()
    }

    /** §10.2 não há como comprar: monte vazio e nenhum morto disponível. */
    private fun cannotDraw(state: RoundState): Boolean = state.stock.isEmpty() && state.firstAvailableMorto() == null

    // ---------- utilitários ----------

    private fun fail(error: RuleError): RuleResult<Nothing> = RuleResult.Failure(error)

    private fun RoundState.finish(result: RoundResult): RoundState = copy(phase = Phase.FINISHED, result = result)

    private fun RoundState.withHand(seat: Seat, hand: List<Card>): RoundState =
        copy(hands = hands.replaceAt(seat.index, hand))

    private fun RoundState.withTable(side: Side, table: SideTable): RoundState =
        copy(tables = tables.replaceAt(side.index, table))

    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
}
