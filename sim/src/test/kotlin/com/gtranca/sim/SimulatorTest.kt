package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.dealRound
import com.gtranca.engine.play
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SimulatorTest {

    @Test
    fun `le os argumentos do CLI`() {
        val config = SimConfig.parse(
            arrayOf("--games", "7", "--seed", "-3", "--mode", "duplas", "--target", "500", "--sides", "facil,aleatorio", "--check-invariants"),
        )
        config shouldBe SimConfig(
            games = 7, seed = -3, mode = GameMode.DUPLAS, target = 500,
            sides = listOf("facil", "aleatorio"), checkInvariants = true,
        )
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--mode", "trio")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--sides", "facil")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--xyz")) }
    }

    @Test
    fun `jogos curtos terminam sem falhas nos dois modos`() {
        for (mode in GameMode.entries) {
            val config = SimConfig(games = 4, seed = 1, mode = mode, target = 600, checkInvariants = true)
            val simulator = Simulator(config)
            val outcomes = (0 until config.games).map { simulator.runGame(config.seed + it) }
            outcomes.mapNotNull { it.failure } shouldBe emptyList()
            outcomes.forEach { it.winner shouldNotBe null }
            Report(config, outcomes, elapsedMillis = 1).render().contains("Falhas: nenhuma") shouldBe true
        }
    }

    @Test
    fun `medio contra facil termina sem falhas nos dois modos`() {
        SimConfig.parse(arrayOf("--sides", "medio,facil")).sides shouldBe listOf("medio", "facil")
        for (mode in GameMode.entries) {
            val config = SimConfig(games = 3, seed = 11, mode = mode, target = 600, sides = listOf("medio", "facil"), checkInvariants = true)
            val simulator = Simulator(config)
            val outcomes = (0 until config.games).map { simulator.runGame(config.seed + it) }
            outcomes.mapNotNull { it.failure } shouldBe emptyList()
            outcomes.forEach { it.winner shouldNotBe null }
        }
    }

    @Test
    fun `mesma semente reproduz o mesmo jogo`() {
        val simulator = Simulator(SimConfig(target = 600, sides = listOf("facil", "aleatorio")))
        simulator.runGame(99) shouldBe simulator.runGame(99)
    }

    @Test
    fun `travamento vira falha com a semente do jogo`() {
        val outcome = Simulator(SimConfig(maxActionsPerRound = 3)).runGame(5)
        outcome.failure?.kind shouldBe FailureKind.ACTION_LIMIT
        outcome.failure?.gameSeed shouldBe 5L
    }

    // ---------- detecção de falhas (bots e motor falsos, injetados pelo construtor interno) ----------

    private fun simulator(
        config: SimConfig = SimConfig(),
        bot: (Seat, Random) -> BotPlayer = { seat, random -> createSimBot(config.sides[config.mode.sideOf(seat).index], random) },
        legal: (RoundState, Seat) -> List<Action> = { state, seat -> RoundEngine.legalActions(state, seat) },
        play: (Match, Seat, Action) -> Match = { match, seat, action -> match.play(seat, action) },
    ) = Simulator(config, bot, legal, play)

    @Test
    fun `bot que devolve acao fora de legal vira ILLEGAL_ACTION`() {
        // §10.2 recusar a compra só é legal com monte vazio e sem morto; no início da partida não é.
        val cheater = object : BotPlayer {
            override fun chooseAction(view: PlayerView, legal: List<Action>): Action = Action.DeclineDraw
        }
        val outcome = simulator(bot = { _, _ -> cheater }).runGame(11)
        outcome.failure?.kind shouldBe FailureKind.ILLEGAL_ACTION
        outcome.failure?.action shouldBe Action.DeclineDraw
        outcome.failure?.gameSeed shouldBe 11L
        outcome.failure?.round shouldBe 1
        outcome.failure?.actionIndex shouldBe 0
        outcome.winner shouldBe null
    }

    @Test
    fun `partida em andamento sem acoes legais vira NO_LEGAL_ACTIONS`() {
        // O motor real sempre oferece ação na partida em andamento; aqui a fonte de ações é falsa.
        val outcome = simulator(legal = { _, _ -> emptyList() }).runGame(12)
        outcome.failure?.kind shouldBe FailureKind.NO_LEGAL_ACTIONS
        outcome.failure?.action shouldBe null
    }

    @Test
    fun `excecao do bot vira EXCEPTION`() {
        val broken = object : BotPlayer {
            override fun chooseAction(view: PlayerView, legal: List<Action>): Action = error("bot quebrado")
        }
        val outcome = simulator(bot = { _, _ -> broken }).runGame(13)
        outcome.failure?.kind shouldBe FailureKind.EXCEPTION
        outcome.failure?.message?.contains("bot quebrado") shouldBe true
    }

    @Test
    fun `transicao que perde carta vira INVARIANT no meio do jogo`() {
        // §1 conservação das 104 cartas; a transição falsa some com uma carta do monte na 3ª ação
        var calls = 0
        val leaky: (Match, Seat, Action) -> Match = { match, seat, action ->
            val next = match.play(seat, action)
            if (++calls == 3) next.copy(currentRound = next.currentRound.copy(stock = next.currentRound.stock.drop(1))) else next
        }
        val outcome = simulator(play = leaky).runGame(14)
        outcome.failure?.kind shouldBe FailureKind.INVARIANT
        outcome.failure?.message!! shouldContain "total de cartas"
        outcome.failure?.gameSeed shouldBe 14L
        outcome.failure?.round shouldBe 1
        outcome.failure?.actionIndex shouldBe 3
        outcome.failure?.action shouldNotBe null
        outcome.winner shouldBe null
    }

    @Test
    fun `distribuicao valida nao viola invariantes`() {
        for (mode in GameMode.entries) {
            val sim = simulator(SimConfig(mode = mode, checkInvariants = true))
            sim.invariantViolation(dealRound(mode, Random(3))) shouldBe null
        }
    }

    @Test
    fun `carta sumida ou repetida viola a conservacao`() {
        // §1 as 104 cartas, sem repetição nem sumiço; verificada mesmo sem --check-invariants
        val sim = simulator()
        val dealt = dealRound(GameMode.INDIVIDUAL, Random(3))
        sim.invariantViolation(dealt.copy(stock = dealt.stock.drop(1)))!! shouldContain "total de cartas"
        val duplicated = dealt.copy(stock = dealt.stock.drop(1) + dealt.hands[0].first())
        sim.invariantViolation(duplicated) shouldBe "cartas repetidas ou faltando"
    }

    @Test
    fun `mao vazia com a partida em andamento viola invariante`() {
        // §8 / §9 / §11 ficar sem cartas leva à batida (fim) ou ao morto, que vai para a mão no mesmo passo
        val sim = simulator(SimConfig(checkInvariants = true))
        val dealt = dealRound(GameMode.INDIVIDUAL, Random(3))
        val emptyHand = dealt.copy(hands = listOf(emptyList(), dealt.hands[1]), stock = dealt.stock + dealt.hands[0])
        sim.invariantViolation(emptyHand) shouldBe "assento 0 sem cartas com a partida em andamento"
        // sem --check-invariants só a conservação é verificada
        simulator().invariantViolation(emptyHand) shouldBe null
    }

    @Test
    fun `3 vermelho na mao e morto pego com cartas violam invariantes`() {
        val sim = simulator(SimConfig(checkInvariants = true))
        val dealt = dealRound(GameMode.INDIVIDUAL, Random(3))
        // §6.5 o 3 vermelho vai para a mesa e é reposto; nunca fica na mão
        val redThree = dealt.allCards().first { it.isRedThree }
        val withRedThree = dealt.copy(
            hands = listOf(dealt.hands[0] + redThree, dealt.hands[1]),
            stock = dealt.stock - redThree,
            redThrees = dealt.redThrees.map { it - redThree },
            mortos = dealt.mortos.map { it - redThree },
        )
        sim.invariantViolation(withRedThree) shouldBe "3 vermelho na mão do assento 0"
        // §9.1 morto pego fica vazio (as cartas vão para a mão)
        val takenWithCards = dealt.copy(mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Available))
        sim.invariantViolation(takenWithCards)!! shouldContain "morto 0"
    }
}
