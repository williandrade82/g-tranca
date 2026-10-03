package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PublicEventTest {

    @Test
    fun `pegar o lixo publica as cartas do lixo sem o topo`() {
        // §5.2 o topo vai à mesa e o restante do lixo vai para a mão; §5.6 o lixo é aberto, então é público
        val before = scenario(hand = "9C 9D 5H 6H", discard = "7H KD 9S")
        val take = RoundEngine.legalActions(before, Seat(0)).filterIsInstance<Action.TakeDiscardPile>().first()

        val event = PublicEvent.of(before.discardPile, Seat(0), take)

        event.seat shouldBe Seat(0)
        event.action shouldBe take
        event.takenFromDiscard shouldBe cards("7H KD")
    }

    @Test
    fun `comprar, baixar e descartar nao publicam cartas do lixo`() {
        val awaiting = scenario(hand = "9C 9D 5H 6H", discard = "7H KD 9S")
        PublicEvent.of(awaiting.discardPile, Seat(0), Action.DrawFromStock).takenFromDiscard.shouldBeEmpty()

        val playing = scenario(hand = "9C 9D 9H 6H", phase = Phase.PLAYING, discard = "7H KD")
        PublicEvent.of(playing.discardPile, Seat(0), Action.CreateMeld(cards("9C 9D 9H"))).takenFromDiscard.shouldBeEmpty()
        PublicEvent.of(playing.discardPile, Seat(0), Action.Discard(cards("6H").single())).takenFromDiscard.shouldBeEmpty()
    }
}
