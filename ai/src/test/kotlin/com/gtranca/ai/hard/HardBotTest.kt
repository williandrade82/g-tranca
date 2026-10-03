package com.gtranca.ai.hard

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.HardBotConfig
import com.gtranca.ai.MediumBot
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.cards
import com.gtranca.ai.createBot
import com.gtranca.ai.playRound
import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.dealRound
import com.gtranca.engine.determinize
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.viewFor
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalKotest::class)
class HardBotTest {

    /**
     * Orçamento pequeno (só iterações): testes rápidos e determinísticos. Sem as travas da escolha
     * conservadora, para que a busca de fato troque a escolha do Médio às vezes.
     */
    private val small = HardBotConfig(
        iterations = 6,
        timeLimitMillis = null,
        rolloutTurns = 2,
        minVisitsToOverride = 1,
        overrideMargin = 0.0,
        overrideConfidence = 0.0,
    )

    /** Orçamento dos cenários táticos: o suficiente para a busca enxergar a jogada. */
    private val tactical = HardBotConfig(iterations = 120, timeLimitMillis = null)

    private fun BotPlayer.decide(state: RoundState): Action =
        chooseAction(state.viewFor(state.currentSeat), RoundEngine.legalActions(state, state.currentSeat))

    /** Joga a vez inteira do assento da vez com o [bot]; devolve o estado ao passar a vez ou ao fim. */
    private fun playTurn(bot: BotPlayer, start: RoundState): RoundState {
        var state = start
        val seat = state.currentSeat
        while (state.phase != Phase.FINISHED && state.currentSeat == seat) {
            val action = bot.decide(state)
            RoundEngine.legalActions(state, seat) shouldContain action
            state = RoundEngine.apply(state, seat, action)
        }
        return state
    }

    // ---------- cenários táticos ----------

    @Test
    fun `bate quando pode e vale a pena`() {
        // §11.1 o lado já tem morto e canastra: baixar os reis e descartar o 5♣ é bater (+100, §12.1)
        val state = fullScenario(
            hand = "KS KD KC 5C",
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Available),
        )
        val end = playTurn(HardBot(Random(3), tactical), state)
        end.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `nao descarta a carta que deixa o adversario bater pegando o lixo`() {
        // O adversário já tem morto e canastra (§11.1) e sabidamente só JH e JS na mão (pegou-os do lixo,
        // §5.6). Com o J♦ no topo ele pega o lixo com os dois valetes (§5.1) e fica sem cartas: bate.
        val state = fullScenario(
            hand = "JD 5S 8D",
            opponentHand = "JH JS",
            opponentHandSize = 2,
            opponentMelds = listOf("4C 5C 6C 7C 8C 9C"),
            mortoStatus = listOf(MortoStatus.Available, MortoStatus.Taken(Side(1))),
        )
        val bot = HardBot(Random(5), tactical)
        bot.observe(PublicEvent(Seat(1), Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(0))), cards("JH JS")))
        val action = bot.decide(state)
        action.shouldBeInstanceOf<Action.Discard>()
        action.card.rank.symbol shouldNotBe 'J'
    }

    // ---------- orçamento e determinismo ----------

    @Test
    fun `com uma unica acao legal devolve direto, sem busca`() {
        val state = fullScenario(hand = "KS KD KC 5C", phase = Phase.AWAITING_DRAW, discard = "3S")
        val legal = RoundEngine.legalActions(state, Seat(0))
        legal shouldBe listOf(Action.DrawFromStock) // §5.3 3 preto no topo trava o lixo
        val bot = HardBot(Random(1), HardBotConfig.DEFAULT)
        bot.chooseAction(state.viewFor(Seat(0)), legal) shouldBe Action.DrawFromStock
        bot.lastIterations shouldBe 0
    }

    @Test
    fun `o teto de tempo interrompe a busca`() {
        var now = 0L
        val clock = { now += 1_000_000L; now } // cada leitura do relógio avança 1 ms
        val config = HardBotConfig(iterations = 10_000, timeLimitMillis = 10, rolloutTurns = 1)
        val bot = HardBot(Random(1), config, clock)
        val state = fullScenario(hand = "KS KD 7C 5C 9H 9D JS", ownMelds = listOf("4H 5H 6H"))
        val action = bot.decide(state)
        RoundEngine.legalActions(state, Seat(0)) shouldContain action
        bot.lastIterations shouldBeInRange 1..10
    }

    @Test
    fun `mesma semente e orcamento por iteracoes produzem as mesmas escolhas`() = runBlocking<Unit> {
        checkAll(PropTestConfig(iterations = 1, seed = 31), Arb.long()) { seed ->
            for (mode in GameMode.entries) {
                fun run() = playRound(mode, seed, bots(mode, seed))
                run() shouldBe run()
            }
        }
    }

    // ---------- propriedades ----------

    @Test
    fun `sempre devolve acao legal em partidas completas`() = runBlocking<Unit> {
        for (mode in GameMode.entries) {
            checkAll(PropTestConfig(iterations = 2, seed = 20_261_003), Arb.long()) { seed ->
                playRound(mode, seed, bots(mode, seed)) // confere cada ação contra legalActions
            }
        }
    }

    /** Difícil no lado 0 e Médio no lado 1. */
    private fun bots(mode: GameMode, seed: Long): List<BotPlayer> = List(mode.seatCount) { i ->
        if (mode.sideOf(Seat(i)).index == 0) HardBot(Random(seed + i), small) else MediumBot(Random(seed + i))
    }

    @Test
    fun `as candidatas da busca sao acoes legais, com a preferida do Medio primeiro, na compra e na jogada`() {
        val states = mutableListOf<RoundState>()
        var state = dealRound(GameMode.DUPLAS, Random(8))
        val bots = List(4) { MediumBot(Random(it.toLong())) }
        while (state.phase != Phase.FINISHED) {
            states += state
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
            val taken = if (action is Action.TakeDiscardPile) state.discardPile.dropLast(1) else emptyList()
            state = RoundEngine.apply(state, seat, action)
            bots.forEach { it.observe(PublicEvent(seat, action, taken)) }
        }
        val scorer = MediumBot(Random(0))
        var playingChecked = 0
        for (s in states) {
            val view = s.viewFor(s.currentSeat)
            val legal = RoundEngine.legalActions(s, s.currentSeat)
            val candidates = scorer.rankedCandidates(view, legal, perKind = 3)
            candidates.isEmpty() shouldBe false
            candidates.all { it in legal } shouldBe true
            candidates.toSet().size shouldBe candidates.size
            (candidates.size <= 3 + 3 + 2) shouldBe true
            if (s.phase == Phase.AWAITING_DRAW) candidates.first() shouldBe scorer.chooseAction(view, legal)
            if (s.phase == Phase.PLAYING) {
                // §4.3 etapas 2 e 3: no máximo 3 baixas e 3 descartes, e a ordem segue a decisão do Médio
                candidates.count { it is Action.CreateMeld || it is Action.AddToMeld } shouldBeInRange 0..3
                candidates.count { it is Action.Discard } shouldBeInRange 0..3
                candidates.none { it is Action.DrawFromStock || it is Action.TakeDiscardPile } shouldBe true
                when (val medium = scorer.chooseAction(view, legal)) {
                    is Action.Discard -> candidates.first().shouldBeInstanceOf<Action.Discard>() // desempate ao acaso
                    else -> candidates.first() shouldBe medium
                }
                playingChecked++
            }
        }
        (playingChecked > 20) shouldBe true
    }

    @Test
    fun `confianca zero desliga de fato o teste do erro-padrao`() {
        val loose = HardBotConfig(minVisitsToOverride = 1, overrideMargin = 0.0, overrideConfidence = 0.0)
        // com 1 visita o erro-padrão é infinito: 0 × ∞ seria NaN e bloquearia a troca
        passesOverride(0.05, Double.POSITIVE_INFINITY, visits = 1, config = loose) shouldBe true
        passesOverride(-0.01, Double.POSITIVE_INFINITY, visits = 1, config = loose) shouldBe false
        val strict = HardBotConfig(minVisitsToOverride = 1, overrideMargin = 0.0, overrideConfidence = 1.0)
        passesOverride(0.05, Double.POSITIVE_INFINITY, visits = 1, config = strict) shouldBe false
        passesOverride(0.05, 0.01, visits = 1, config = strict) shouldBe true
        passesOverride(0.05, 0.10, visits = 1, config = strict) shouldBe false
        passesOverride(0.05, 0.01, visits = 1, config = HardBotConfig(minVisitsToOverride = 2, overrideConfidence = 0.0)) shouldBe false
        passesOverride(0.005, 0.0, visits = 10, config = HardBotConfig(overrideMargin = 0.01, overrideConfidence = 0.0)) shouldBe false
    }

    @Test
    fun `se a busca falhar joga como o Medio e registra a falha`() {
        val state = fullScenario(hand = "KS KD 7C 5C 9H 9D JS", ownMelds = listOf("4H 5H 6H"))
        val brokenClock = { throw IllegalStateException("relógio quebrado (falha injetada)") }
        val bot = HardBot(Random(1), HardBotConfig(timeLimitMillis = 800), brokenClock)
        val action = bot.decide(state)
        action shouldBe MediumBot(Random(0)).decide(state)
        bot.searchFailures shouldBe 1
        bot.lastIterations shouldBe 0
    }

    @Test
    fun `o teto de tempo atingido fica registrado e o orcamento por iteracoes nao o atinge`() {
        var now = 0L
        val clock = { now += 1_000_000L; now }
        val state = fullScenario(hand = "KS KD 7C 5C 9H 9D JS", ownMelds = listOf("4H 5H 6H"))
        val capped = HardBot(Random(1), HardBotConfig(iterations = 10_000, timeLimitMillis = 5, rolloutTurns = 1), clock)
        capped.decide(state)
        capped.timeLimitHits shouldBe 1
        val byIterations = HardBot(Random(1), HardBotConfig(iterations = 3, timeLimitMillis = null, rolloutTurns = 1))
        byIterations.decide(state)
        byIterations.timeLimitHits shouldBe 0
        byIterations.lastIterations shouldBe 3
    }

    // ---------- ocultação ----------

    /** Partida real jogada pelo Médio até uma decisão do assento 0 com várias ações; devolve o estado e os eventos. */
    private fun realDecisionPoint(mode: GameMode, seed: Long, minActions: Int): Pair<RoundState, List<PublicEvent>> {
        var state = dealRound(mode, Random(seed))
        val bots = List(mode.seatCount) { MediumBot(Random(seed + it)) }
        val events = mutableListOf<PublicEvent>()
        while (true) {
            check(state.phase != Phase.FINISHED) { "a partida acabou antes do ponto de decisão" }
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            if (seat == Seat(0) && events.size >= minActions && legal.size > 2) return state to events
            val action = bots[seat.index].chooseAction(state.viewFor(seat), legal)
            val taken = if (action is Action.TakeDiscardPile) state.discardPile.dropLast(1) else emptyList()
            state = RoundEngine.apply(state, seat, action)
            events += PublicEvent(seat, action, taken)
            bots.forEach { it.observe(events.last()) }
        }
    }

    @Test
    fun `estados com a mesma vista e cartas ocultas diferentes levam a mesma acao`() {
        val config = HardBotConfig(iterations = 12, timeLimitMillis = null, rolloutTurns = 4, minVisitsToOverride = 2, overrideConfidence = 0.0)
        for (mode in GameMode.entries) {
            val (real, events) = realDecisionPoint(mode, seed = 41, minActions = 30)
            val view = real.viewFor(Seat(0))
            // outro mundo coerente com a mesma vista: mãos alheias, monte e mortos sorteados de novo
            val other = view.determinize(Random(12_345))
            other.viewFor(Seat(0)) shouldBe view
            (other.hands != real.hands || other.stock != real.stock || other.mortos != real.mortos) shouldBe true

            fun decide(state: RoundState): Action {
                val bot = HardBot(Random(77), config)
                events.forEach(bot::observe)
                return bot.decide(state)
            }
            decide(other) shouldBe decide(real)
        }
    }

    @Test
    fun `chooseAction nao altera o historico publico nem a vista`() {
        val (state, events) = realDecisionPoint(GameMode.DUPLAS, seed = 43, minActions = 40)
        val bot = HardBot(Random(5), small)
        events.forEach(bot::observe)
        val view = state.viewFor(Seat(0))
        val viewBefore = view.copy()
        val eventsBefore = bot.observedEvents()
        val knownBefore = bot.knownHands(view)
        bot.chooseAction(view, RoundEngine.legalActions(state, Seat(0)))
        bot.observedEvents() shouldBe eventsBefore
        bot.observedEvents() shouldBe events
        bot.knownHands(view) shouldBe knownBefore
        view shouldBe viewBefore
    }

    // ---------- fábrica ----------

    @Test
    fun `createBot cria o Dificil com o orcamento padrao`() {
        createBot(Difficulty.DIFICIL, Random(1)).shouldBeInstanceOf<HardBot>()
        HardBotConfig.DEFAULT.timeLimitMillis shouldBe 800L
        HardBotConfig.DEFAULT.iterations shouldBe HardBotConfig.DEFAULT_ITERATIONS
        HardBotConfig.DEFAULT.withoutTimeLimit().timeLimitMillis shouldBe null
    }
}
