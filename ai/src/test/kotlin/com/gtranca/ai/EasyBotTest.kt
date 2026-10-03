package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.viewFor
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalKotest::class)
class EasyBotTest {

    /** Bot sem erros propositais, para testar as heurísticas de forma determinística. */
    private fun perfectBot(seed: Long = 1) = EasyBot(Random(seed), mistakeRate = 0.0)

    // ---------- etapa 1: monte ou lixo ----------

    @Test
    fun `pega o lixo quando ha plano para levar o topo a mesa`() {
        // §5.1 7♦ no topo forma grupo com 7♥ e 7♠ da mão
        val state = scenario(hand = "7H 7S 9C KD 5H", discard = "4S 7D")
        val action = perfectBot().decide(state)
        action.shouldBeInstanceOf<Action.TakeDiscardPile>()
        (action.plan as DiscardPlan.NewMeld).handCards shouldContainExactlyInAnyOrder cards("7H 7S")
    }

    @Test
    fun `compra do monte quando o lixo esta travado por 3 preto`() {
        // §5.3 3 preto no topo trava o lixo
        val state = scenario(hand = "7H 7S 9C KD 5H", discard = "7D 3S")
        perfectBot().decide(state) shouldBe Action.DrawFromStock
    }

    @Test
    fun `com coringa no topo prefere conjunto novo a sujar canastra limpa`() {
        // §5.4 / §7.2 o coringa do topo sujaria a canastra limpa 4…9♥; o grupo de K com o coringa não
        val state = scenario(
            hand = "KS KD QC JD 5C",
            discard = "8C 2D",
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
        )
        val action = perfectBot().decide(state)
        action.shouldBeInstanceOf<Action.TakeDiscardPile>()
        (action.plan as DiscardPlan.NewMeld).handCards shouldContainExactlyInAnyOrder cards("KS KD")
    }

    // ---------- etapa 2: baixar ----------

    @Test
    fun `baixa o maior conjunto possivel`() {
        val state = scenario(hand = "5H 6H 7H 8H KS KD KC 9C 4D", phase = Phase.PLAYING)
        val action = perfectBot().decide(state)
        action.shouldBeInstanceOf<Action.CreateMeld>()
        action.cards shouldContainExactlyInAnyOrder cards("5H 6H 7H 8H")
    }

    @Test
    fun `nao suja canastra limpa com coringa da mao`() {
        // §7.2 sujar a canastra custa 100 pontos; o Fácil não faz isso se não for para esvaziar a mão
        val state = scenario(
            hand = "2C KS QD 9C 5D",
            phase = Phase.PLAYING,
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
        )
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        legal.any { it is Action.AddToMeld && it.cards.any { card -> card.isWild } } shouldBe true
        val action = perfectBot().decide(state)
        action.shouldNotBeInstanceOf<Action.AddToMeld>()
        action.shouldBeInstanceOf<Action.Discard>()
        action.card.isWild shouldBe false
    }

    // ---------- etapa 3: descartar ----------

    @Test
    fun `descarta o 3 preto de preferencia`() {
        // §6.6 / §5.3 o 3 preto descartado trava o lixo para o próximo
        val state = scenario(hand = "3C KS QD 9C 8D 5H", phase = Phase.PLAYING)
        perfectBot().decide(state) shouldBe Action.Discard(cards("3C").single())
    }

    @Test
    fun `mantem o par e descarta a carta isolada`() {
        val state = scenario(hand = "9C 9D KS 5H", phase = Phase.PLAYING)
        val action = perfectBot().decide(state)
        action.shouldBeInstanceOf<Action.Discard>()
        action.card shouldBeIn cards("KS 5H")
        // desempate: a figura penaliza mais na mão (§12.2)
        action.card shouldBe cards("KS").single()
    }

    @Test
    fun `mantem vizinhas de naipe e descarta a isolada`() {
        val state = scenario(hand = "7H 8H 5S QD", phase = Phase.PLAYING)
        val action = perfectBot().decide(state)
        action.shouldBeInstanceOf<Action.Discard>()
        action.card shouldBeIn cards("5S QD")
    }

    @Test
    fun `evita descartar coringa`() {
        val state = scenario(hand = "2C 9C 7D KS JH", phase = Phase.PLAYING)
        repeat(20) { seed ->
            val action = perfectBot(seed.toLong()).decide(state)
            action.shouldBeInstanceOf<Action.Discard>()
            action.card.isWild shouldBe false
        }
    }

    @Test
    fun `com taxa de erro maxima ainda escolhe acao legal`() {
        val state = scenario(hand = "7H 7S 9C KD 5H", discard = "4S 7D")
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        val action = EasyBot(Random(3), mistakeRate = 1.0).chooseAction(state.viewFor(state.currentSeat), legal)
        action shouldBe Action.DrawFromStock // erro proposital: ignora o lixo
    }

    // ---------- propriedades ----------

    @Test
    fun `sempre devolve acao legal em partidas completas`() = runBlocking<Unit> {
        for (mode in GameMode.entries) {
            checkAll(PropTestConfig(iterations = 50, seed = 20_261_002), Arb.long()) { seed ->
                val bots = List(mode.seatCount) { EasyBot(Random(seed + it)) }
                playRound(mode, seed, bots) // confere cada ação contra legalActions
            }
        }
    }

    @Test
    fun `mesma semente produz as mesmas escolhas`() = runBlocking<Unit> {
        checkAll(PropTestConfig(iterations = 10, seed = 7), Arb.long()) { seed ->
            for (mode in GameMode.entries) {
                fun run() = playRound(mode, seed, List(mode.seatCount) { EasyBot(Random(seed * 31 + it)) })
                run() shouldBe run()
            }
        }
    }
}
