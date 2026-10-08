package com.gtranca.engine

import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DealPropertyTest {

    @Test
    fun `distribuicao conserva as 104 cartas sem duplicatas`(): Unit = runBlocking {
        // §1 104 cartas; §3 / §3.5 nenhuma carta criada ou perdida na distribuição
        for (mode in GameMode.entries) {
            checkAll(200, Arb.long()) { seed ->
                val state = dealRound(mode, Random(seed))
                val all = state.allCards()
                all shouldHaveSize Deck.SIZE
                all.toSet() shouldHaveSize Deck.SIZE
                all shouldContainExactlyInAnyOrder Deck.standard()
                state.handOf(state.firstSeat).none { it.isRedThree } shouldBe true // §3.5 o primeiro jogador já trocou
            }
        }
    }

    @Test
    fun `a mao de quem ainda nao comecou e a distribuida e a do primeiro jogador so perde os 3 vermelhos`(): Unit = runBlocking {
        // §3.5 nada é trocado na distribuição: só o primeiro jogador (início da partida) troca, e o registro (atTurnStart)
        // cobre exatamente os 3 vermelhos dele, incluindo cadeia (§6.5); as demais mãos são as originais.
        checkAll(300, Arb.long(), Arb.enum<GameMode>(), Arb.int(0, 3)) { seed, mode, first ->
            val firstSeat = Seat(first % mode.seatCount)
            val deck = Deck.shuffled(Random(seed))
            val state = dealFromOrderedDeck(mode, deck, firstSeat)
            val order = mode.seatsInPlayOrder(firstSeat)
            val handCards = RuleSet.DEFAULT.cardsPerHand * mode.seatCount
            for (seat in mode.seats) {
                val original = deck.subList(0, handCards).filterIndexed { i, _ -> order[i % mode.seatCount] == seat }
                if (seat == firstSeat) {
                    state.handOf(seat).none { it.isRedThree } shouldBe true
                    state.handOf(seat).containsAll(original.filterNot { it.isRedThree }) shouldBe true
                    state.handOf(seat) shouldHaveSize original.size
                    state.redThreeLog.map { it.card }.containsAll(original.filter { it.isRedThree }) shouldBe true
                } else {
                    state.handOf(seat) shouldBe original
                }
            }
            state.redThreeLog.all { it.seat == firstSeat && it.atTurnStart } shouldBe true
        }
    }

    @Test
    fun `RoundState faz ida e volta em JSON`(): Unit = runBlocking {
        // Estado serializável (partida salva); deve sobreviver à ida e volta sem perda
        checkAll(50, Arb.long(), Arb.enum<GameMode>()) { seed, mode ->
            val state = dealRound(mode, Random(seed))
            val json = Json.encodeToString(RoundState.serializer(), state)
            Json.decodeFromString(RoundState.serializer(), json) shouldBe state
        }
    }

    @Test
    fun `cartas usam a notacao curta no JSON`() {
        val state = dealRound(GameMode.INDIVIDUAL, Random(5))
        val json = Json.encodeToString(RoundState.serializer(), state)
        json.contains("\"${state.stock.first()}\"") shouldBe true
    }
}
