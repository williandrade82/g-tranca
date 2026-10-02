package com.gtranca.engine

import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

class RoundStateTableTest {

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `mesa de cada lado comeca sem conjuntos`(mode: GameMode) {
        // §6.4 conjuntos pertencem ao lado; na distribuição nenhum conjunto foi baixado
        val state = dealRound(mode, Random(11))
        state.tables shouldHaveSize mode.sideCount
        mode.sides.forEach { state.tableOf(it).melds.shouldBeEmpty() }
    }

    @Test
    fun `cartas na mesa contam nas 104 e sobrevivem ao JSON`() {
        // §1 104 cartas (mãos + monte + lixo + mortos + mesa); §6.4 mesa por lado
        val dealt = dealFromOrderedDeck(GameMode.INDIVIDUAL, Deck104.withMeldInFirstHand(), Seat(0))
        val toMeld = cards("5S 6S 7S")
        val table = dealt.tableOf(Side(0)).createMeld(toMeld).shouldBeOk()
        val state = dealt.copy(
            hands = dealt.hands.mapIndexed { i, h -> if (i == 0) h - toMeld.toSet() else h },
            tables = listOf(table, dealt.tableOf(Side(1))),
        )
        state.allCards() shouldHaveSize 104
        state.allCards() shouldContainExactlyInAnyOrder Deck.standard()
        val json = Json.encodeToString(RoundState.serializer(), state)
        Json.decodeFromString(RoundState.serializer(), json) shouldBe state
    }

    private object Deck104 {
        /** Baralho ordenado em que o assento 0 (primeiro jogador, individual) recebe 5♠, 6♠ e 7♠. */
        fun withMeldInFirstHand() = cards("5S 7H 6S 7C 7S").let { head ->
            val rest = Deck.standard() - head.toSet()
            head + rest.filterNot { it.isRedThree } + rest.filter { it.isRedThree }
        }
    }
}
