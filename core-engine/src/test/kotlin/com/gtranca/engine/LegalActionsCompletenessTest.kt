package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.Suit
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `legalActions` é completo a menos de cartas idênticas: toda ação aceita por `validate`, gerada por
 * força bruta (subconjuntos da mão × destinos × planos de lixo), aparece em `legalActions` ou aparece
 * uma ação equivalente que difere só pela cópia do baralho (mesmo valor e naipe).
 */
class LegalActionsCompletenessTest {

    /** Chave de equivalência: cartas comparadas por valor+naipe, sem ordem. */
    private fun Card.cls() = "${rank.symbol}${suit.symbol}"
    private fun List<Card>.cls() = map { it.cls() }.sorted()

    private fun key(action: Action): Any = when (action) {
        Action.DrawFromStock, Action.DeclineDraw -> action
        is Action.Discard -> "discard" to action.card.cls()
        is Action.CreateMeld -> "create" to action.cards.cls()
        is Action.AddToMeld -> Triple("add", action.meldId, action.cards.cls())
        is Action.TakeDiscardPile -> when (val plan = action.plan) {
            is DiscardPlan.NewMeld -> "takeNew" to plan.handCards.cls()
            is DiscardPlan.AddToMeld -> Triple("takeAdd", plan.meldId, plan.handCards.cls())
        }
    }

    private fun <T> List<T>.subsets(): List<List<T>> =
        (0 until (1 shl size)).map { mask -> filterIndexed { i, _ -> mask and (1 shl i) != 0 } }

    private fun bruteForce(state: RoundState, seat: Seat): List<Action> {
        val hand = state.handOf(seat)
        val table = state.tableOf(state.mode.sideOf(seat))
        val subsets = hand.subsets().filter { it.isNotEmpty() }
        val all = mutableListOf<Action>(Action.DrawFromStock, Action.DeclineDraw)
        hand.forEach { all += Action.Discard(it) }
        subsets.forEach { sub ->
            all += Action.CreateMeld(sub)
            all += Action.TakeDiscardPile(DiscardPlan.NewMeld(sub))
            table.melds.forEach { all += Action.AddToMeld(it.id, sub) }
            table.melds.forEach { all += Action.TakeDiscardPile(DiscardPlan.AddToMeld(it.id, sub)) }
        }
        table.melds.forEach { all += Action.TakeDiscardPile(DiscardPlan.AddToMeld(it.id, emptyList())) }
        return all.filter { RoundEngine.validate(state, seat, it) is RuleResult.Ok }
    }

    /** Cenário aleatório concentrado (um naipe numa janela de valores, um número para grupos e coringas). */
    private fun scenario(random: Random): RoundState {
        val mode = if (random.nextBoolean()) GameMode.INDIVIDUAL else GameMode.DUPLAS
        val suit = Suit.entries.random(random)
        val low = random.nextInt(Rank.FOUR.ordinal, Rank.ACE.ordinal - 4)
        val window = (low..low + 5).map { Rank.entries[it] }
        val groupRank = Rank.entries.filter { it >= Rank.FOUR }.random(random)
        val pool = Deck.standard().filter {
            (it.suit == suit && it.rank in window) || it.rank == groupRank || it.isWild
        }.shuffled(random).toMutableList()
        val others = (Deck.standard() - pool.toSet()).shuffled(random).toMutableList()
        fun takeFrom(list: MutableList<Card>, n: Int) = List(minOf(n, list.size)) { list.removeAt(0) }

        // mesa do lado do jogador da vez: tenta baixar alguns conjuntos com cartas do pool
        val seat = Seat(random.nextInt(mode.seatCount))
        val side = mode.sideOf(seat)
        var table = SideTable()
        repeat(random.nextInt(0, 3)) {
            // até 7 cartas, para que a mesa também tenha canastras (limpas e sujas)
            val attempt = takeFrom(pool, random.nextInt(3, 8))
            when (val r = table.createMeld(attempt)) {
                is RuleResult.Ok -> table = r.value
                is RuleResult.Failure -> pool.addAll(attempt)
            }
        }
        val hand = takeFrom(pool, random.nextInt(1, 8)).filterNot { it.isRedThree }.ifEmpty { takeFrom(others, 1) }
        val phase = if (random.nextBoolean()) Phase.AWAITING_DRAW else Phase.PLAYING
        val discard = if (random.nextInt(4) == 0) emptyList() else takeFrom(others, random.nextInt(0, 3)) + takeFrom(pool, 1)
        val mortoChoice = random.nextInt(4)
        val statuses = when (mortoChoice) {
            0 -> listOf(MortoStatus.Available, MortoStatus.Available)
            1 -> listOf(MortoStatus.Taken(side), MortoStatus.Available)
            2 -> listOf(MortoStatus.Taken(side), MortoStatus.BecameStock)
            else -> listOf(MortoStatus.Taken(Side(1 - side.index)), MortoStatus.BecameStock)
        }
        val mortos = statuses.map { if (it == MortoStatus.Available) takeFrom(others, 11) else emptyList() }
        val stock = if (random.nextInt(3) == 0) emptyList() else takeFrom(others, 5)
        return RoundState(
            mode = mode,
            hands = mode.seats.map { if (it == seat) hand else takeFrom(others, 3).filterNot { c -> c.isRedThree } },
            stock = stock,
            discardPile = discard.filterNot { it.isRedThree },
            mortos = mortos,
            redThrees = List(mode.sideCount) { emptyList() },
            tables = mode.sides.map { if (it == side) table else SideTable() },
            firstSeat = seat,
            currentSeat = seat,
            phase = phase,
            mortoStatus = statuses,
        )
    }

    @Test
    fun `legalActions contem toda acao valida a menos da copia do baralho`() {
        val random = Random(20261002)
        var checkedActions = 0
        repeat(700) { i ->
            val state = scenario(random)
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            val legalKeys = legal.map { key(it) }.toSet()
            // tudo que está listado é válido
            legal.forEach { RoundEngine.validate(state, seat, it) shouldBe RuleResult.Ok(Unit) }
            bruteForce(state, seat).forEach { action ->
                withClue("cenário $i: ação válida não listada: $action\nestado: $state") {
                    (key(action) in legalKeys) shouldBe true
                }
                checkedActions++
            }
        }
        println("Completude: $checkedActions ações válidas conferidas")
        (checkedActions > 2_000) shouldBe true
    }
}
