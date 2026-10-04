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
                state.hands.flatten().none { it.isRedThree } shouldBe true
            }
        }
    }

    @Test
    fun `reposicoes da distribuicao batem com a mao original e o registro`(): Unit = runBlocking {
        // §3.5 / §6.5 reposições guardadas por assento (dealReplacements): mão final = cartas não 3 vermelho da mão
        // original + reposições, na ordem (logo, reposições ⊆ mão); reposições do assento + 3 vermelhos que vieram como
        // reposição (cadeia) = trocas atDeal do assento no registro. Na distribuição o monte nunca acaba (≥ 38 cartas
        // contra no máximo 4 reposições), então cada troca tem reposição.
        checkAll(300, Arb.long(), Arb.enum<GameMode>(), Arb.int(0, 3)) { seed, mode, first ->
            val firstSeat = Seat(first % mode.seatCount)
            val deck = Deck.shuffled(Random(seed))
            val state = dealFromOrderedDeck(mode, deck, firstSeat)
            val order = mode.seatsInPlayOrder(firstSeat)
            val handCards = RuleSet.DEFAULT.cardsPerHand * mode.seatCount
            for (seat in mode.seats) {
                val original = deck.subList(0, handCards).filterIndexed { i, _ -> order[i % mode.seatCount] == seat }
                val replacements = state.dealReplacementsOf(seat)
                state.handOf(seat) shouldBe original.filterNot { it.isRedThree } + replacements
                state.handOf(seat).containsAll(replacements) shouldBe true
                replacements.none { it.isRedThree } shouldBe true
                val logged = state.redThreeLog.filter { it.seat == seat && it.atDeal }
                val chained = logged.count { it.card !in original }
                (replacements.size + chained) shouldBe logged.size
                replacements.size shouldBe original.count { it.isRedThree }
                state.viewFor(seat).ownDealReplacements shouldBe replacements
            }
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
