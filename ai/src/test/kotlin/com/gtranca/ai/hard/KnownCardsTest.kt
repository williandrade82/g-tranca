package com.gtranca.ai.hard

import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.PublicHistory
import com.gtranca.ai.cards
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.dealRound
import com.gtranca.engine.determinize
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.viewFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Normalização das cartas sabidas antes de `determinize`: a determinização compara a cópia física exata
 * ([Card.deck]) e o [PublicHistory] remove "por cópia equivalente", então uma carta sabida pode estar velha
 * (já visível) ou ser a cópia errada. Depois de [normalizeKnown], `determinize` nunca lança.
 */
class KnownCardsTest {

    private fun card(text: String) = cards(text).single()

    @Test
    fun `troca pela outra copia quando a copia exata ja esta visivel`() {
        // 7♥ (cópia 0) está na mesa do adversário; a cópia 1 está oculta: é ela que pode estar na mão dele
        val state = fullScenario(hand = "KS KD 9C", opponentMelds = listOf("7H 7S 7D"), opponentHand = "7H'")
        val view = state.viewFor(Seat(0))
        val known = normalizeKnown(view, mapOf(Seat(1) to listOf(card("7H"))))
        known shouldBe mapOf(Seat(1) to listOf(card("7H'")))
        view.determinize(Random(1), known).handOf(Seat(1)) shouldContainAll listOf(card("7H'"))
    }

    @Test
    fun `omite a carta sabida quando as duas copias estao visiveis`() {
        // o J♠ sabido foi baixado (cópia 0) e a outra cópia está no lixo: a informação ficou velha
        val state = fullScenario(hand = "KS KD 9C", discard = "4D JS'", opponentMelds = listOf("JS JH JD"))
        val view = state.viewFor(Seat(0))
        normalizeKnown(view, mapOf(Seat(1) to listOf(card("JS"), card("JS")))) shouldBe emptyMap()
    }

    @Test
    fun `omite 3 vermelho, o proprio assento, repeticoes e o excesso acima do tamanho da mao`() {
        val state = fullScenario(hand = "KS KD 9C", opponentHandSize = 2)
        val view = state.viewFor(Seat(0))
        val known = normalizeKnown(
            view,
            mapOf(
                Seat(0) to listOf(card("QH")),
                Seat(1) to listOf(card("3H"), card("QC"), card("QC"), card("QC"), card("4S"), card("5S")),
            ),
        )
        // QC duas vezes vira QC e QC' (duas cópias físicas); a terceira e o resto passam do tamanho da mão
        known shouldBe mapOf(Seat(1) to listOf(card("QC"), card("QC'")))
        view.determinize(Random(2), known)
    }

    @Test
    fun `carta sabida que depois foi baixada ou descartada nao quebra a determinizacao`() {
        // O adversário levou 9♣, 9♣' e K♥ do lixo (§5.2), baixou 9♣ (§6) e descartou 9♣' (§8). O K♥ ficou
        // como sabido, mas a vista mostra K♥ baixado (um evento perdido, ou a ação usou a outra cópia):
        // a informação está velha. A cópia K♥' segue oculta, então a normalização troca por ela.
        val history = PublicHistory()
        history.record(PublicEvent(Seat(1), Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(0))), cards("9C 9C' KH")))
        history.record(PublicEvent(Seat(1), Action.CreateMeld(cards("9C 9H 9D")), emptyList()))
        history.record(PublicEvent(Seat(1), Action.Discard(card("9C'")), emptyList()))
        history.knownHand(Seat(1)) shouldBe cards("KH")
        val state = fullScenario(
            hand = "QS QD 5C",
            discard = "9C'",
            opponentMelds = listOf("9C 9H 9D", "KH KS KD"),
            opponentHand = "KH'",
        )
        val view = state.viewFor(Seat(0))
        val known = normalizeKnown(view, history)
        known shouldBe mapOf(Seat(1) to cards("KH'"))
        view.determinize(Random(3), known).handOf(Seat(1)) shouldContainAll cards("KH'")
        // sem normalizar, a determinização recusaria a carta visível
        shouldThrow<IllegalArgumentException> { view.determinize(Random(3), mapOf(Seat(1) to history.knownHand(Seat(1)))) }
    }

    @Test
    fun `cartas sabidas aleatorias normalizadas nunca fazem a determinizacao lancar`() {
        val random = Random(2026)
        for (mode in GameMode.entries) {
            for (view in viewsFromRealRounds(mode, seeds = 0L until 4L, every = 3)) {
                repeat(5) {
                    val fuzz = mode.seats.associateWith { Deck.standard().shuffled(random).take(random.nextInt(0, 30)) }
                    val known = normalizeKnown(view, fuzz)
                    val world = view.determinize(random, known)
                    for ((seat, cards) in known) world.handOf(seat) shouldContainAll cards
                }
            }
        }
    }

    @Test
    fun `com o historico real a determinizacao nunca lanca e preserva as acoes legais`() {
        for (mode in GameMode.entries) {
            for (seed in 0L until 6L) {
                var state = dealRound(mode, Random(seed))
                val bots = List(mode.seatCount) { MediumBot(Random(seed * 7 + it)) }
                val history = PublicHistory() // do assento 0
                val random = Random(seed)
                var count = 0
                while (state.phase != Phase.FINISHED && count < 3_000) {
                    val seat = state.currentSeat
                    val legal = RoundEngine.legalActions(state, seat)
                    if (seat == Seat(0)) {
                        val view = state.viewFor(seat)
                        val world = view.determinize(random, normalizeKnown(view, history))
                        withClue("modo $mode, semente $seed, ação $count") {
                            RoundEngine.legalActions(world, seat) shouldBe legal
                        }
                    }
                    val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
                    val event = PublicEvent.of(state.discardPile, seat, action)
                    state = RoundEngine.apply(state, seat, action)
                    bots.forEach { it.observe(event) }
                    history.record(event)
                    count++
                }
            }
        }
    }

    /** Vistas de todos os assentos ao longo de partidas reais jogadas pelo Médio, uma a cada [every] ações. */
    private fun viewsFromRealRounds(mode: GameMode, seeds: LongRange, every: Int): List<PlayerView> = buildList {
        for (seed in seeds) {
            var state = dealRound(mode, Random(seed))
            val bots = List(mode.seatCount) { MediumBot(Random(seed + it)) }
            var count = 0
            while (state.phase != Phase.FINISHED && count < 3_000) {
                if (count % every == 0) mode.seats.forEach { add(state.viewFor(it)) }
                val seat = state.currentSeat
                val legal = RoundEngine.legalActions(state, seat)
                val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
                val event = PublicEvent.of(state.discardPile, seat, action)
                state = RoundEngine.apply(state, seat, action)
                bots.forEach { it.observe(event) }
                count++
            }
        }
    }
}
