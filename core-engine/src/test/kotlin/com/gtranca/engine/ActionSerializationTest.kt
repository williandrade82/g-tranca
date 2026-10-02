package com.gtranca.engine

import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.cards
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ActionSerializationTest {

    @Test
    fun `todas as acoes fazem ida e volta em JSON`() {
        // ações serializáveis (log/replay de partidas e partida salva)
        val actions = listOf(
            Action.DrawFromStock,
            Action.DeclineDraw,
            Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("7S 7D"))),
            Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(2), cards("7H'"))),
            Action.CreateMeld(cards("5H 6H 2C")),
            Action.AddToMeld(MeldId(0), cards("8H")),
            Action.Discard(c("3S")),
        )
        actions.forEach { action ->
            val json = Json.encodeToString(Action.serializer(), action)
            Json.decodeFromString(Action.serializer(), json) shouldBe action
        }
    }
}
