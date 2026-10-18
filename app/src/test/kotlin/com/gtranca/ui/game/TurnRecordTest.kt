package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Seat
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.botSeed
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Registro da vez (pílula e diálogo do jogador): toda ação pública aparece, com as cartas dela. */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnRecordTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun c(text: String) = Card.parse(text)
    private fun cards(text: String) = text.split(" ").map(::c)
    private val seat = Seat(1)

    @Test
    fun `§4_3 cada tipo de acao vira entradas do registro com as suas cartas`() {
        PublicEvent(seat, Action.DrawFromStock).record() shouldBe listOf(RecordEntry(ActionKind.DRAW))
        PublicEvent(seat, Action.DeclineDraw).record() shouldBe listOf(RecordEntry(ActionKind.DECLINE))
        PublicEvent(seat, Action.CreateMeld(cards("7H 8H 9H"))).record() shouldBe listOf(RecordEntry(ActionKind.MELD, cards("7H 8H 9H")))
        PublicEvent(seat, Action.AddToMeld(MeldId(0), cards("TH JH"))).record() shouldBe listOf(RecordEntry(ActionKind.ADD, cards("TH JH")))
        PublicEvent(seat, Action.Discard(c("3S"))).record() shouldBe listOf(RecordEntry(ActionKind.DISCARD, cards("3S")))
    }

    @Test
    fun `§5_1 pegar o lixo para acrescentar so o topo a um jogo registra o que foi para a mao e o topo acrescentado`() {
        // O caso do 4♣ do topo levado a um jogo que a dupla já tinha: antes o registro só mostrava o que foi para a mão.
        val event = PublicEvent(
            seat, Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(2), emptyList())),
            takenFromDiscard = cards("3C 3S 7S"), takenTop = c("4C"),
        )
        event.record() shouldBe listOf(
            RecordEntry(ActionKind.TAKE, cards("3C 3S 7S")),
            RecordEntry(ActionKind.ADD, cards("4C")),
        )
    }

    @Test
    fun `§5_1 pegar o lixo com cartas da mao registra tambem as da mao, junto do topo, no jogo novo ou acrescentado`() {
        val newMeld = PublicEvent(
            seat, Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("8C 8D"))), takenFromDiscard = cards("5S"), takenTop = c("8H"),
        )
        newMeld.record() shouldBe listOf(RecordEntry(ActionKind.TAKE, cards("5S")), RecordEntry(ActionKind.MELD, cards("8C 8D 8H")))
        val add = PublicEvent(
            seat, Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(1), cards("5H"))), takenFromDiscard = emptyList(), takenTop = c("6H"),
        )
        add.record() shouldBe listOf(RecordEntry(ActionKind.TAKE, emptyList()), RecordEntry(ActionKind.ADD, cards("5H 6H")))
    }

    @Test
    fun `§5_1 jogo salvo antigo sem o topo do lixo ainda registra o que se sabe`() {
        val event = PublicEvent(seat, Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("8C 8D"))), takenFromDiscard = cards("5S"))
        event.record() shouldBe listOf(RecordEntry(ActionKind.TAKE, cards("5S")), RecordEntry(ActionKind.MELD, cards("8C 8D")))
    }

    @Test
    fun `§4_3 §5_1 numa partida inteira todas as cartas de toda acao aparecem no registro da vez e o topo e o do lixo`() = runTest(dispatcher) {
        val mode = GameMode.DUPLAS
        val seed = 21L
        val controller = GameController(
            GameConfig(mode, Difficulty.MEDIO, targetScore = 1000), seed,
            mode.seats.map { BotSeatPlayer(createBot(Difficulty.MEDIO, Random(botSeed(seed, it.index)))) },
            computeDispatcher = dispatcher, botDelayMillis = 1,
        )
        val snapshots = mutableListOf<GameSnapshot>()
        val collector = launch { controller.state.collect { snapshots += it } }
        controller.run()
        collector.cancel()
        val kinds = mutableSetOf<ActionKind>()
        var takes = 0
        snapshots.filter { it.stage == Stage.PLAYING }.forEach { snapshot ->
            snapshot.turnEvents.forEach { events ->
                val record = events.record()
                kinds += record.map { it.kind }
                // Cada ação rende ao menos uma entrada, e as cartas públicas dela estão no registro.
                events.forEach { event ->
                    val entries = event.record()
                    (entries.isNotEmpty()) shouldBe true
                    when (val a = event.action) {
                        is Action.Discard -> entries.single().cards shouldBe listOf(a.card)
                        is Action.CreateMeld -> entries.single().cards shouldBe a.cards
                        is Action.AddToMeld -> entries.single().cards shouldBe a.cards
                        is Action.TakeDiscardPile -> {
                            takes++
                            // O topo do lixo sempre vai à mesa (§5.1): está na entrada de jogo, com as cartas da mão do plano.
                            val handCards = when (val plan = a.plan) {
                                is DiscardPlan.NewMeld -> plan.handCards
                                is DiscardPlan.AddToMeld -> plan.handCards
                            }
                            entries.first().kind shouldBe ActionKind.TAKE
                            entries.first().cards shouldBe event.takenFromDiscard
                            val laid = entries.last { it.kind == ActionKind.MELD || it.kind == ActionKind.ADD }
                            laid.cards shouldContainAll (handCards + listOfNotNull(event.takenTop))
                            (event.takenTop != null) shouldBe true
                        }
                        else -> Unit
                    }
                }
            }
        }
        (takes > 0) shouldBe true
        kinds shouldContainAll setOf(ActionKind.DRAW, ActionKind.TAKE, ActionKind.MELD, ActionKind.ADD, ActionKind.DISCARD)
    }
}
