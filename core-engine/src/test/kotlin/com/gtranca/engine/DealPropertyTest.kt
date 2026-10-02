package com.gtranca.engine

import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundState
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
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
