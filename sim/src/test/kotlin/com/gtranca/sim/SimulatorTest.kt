package com.gtranca.sim

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.HardBotConfig
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
        // o tempo de decisão (medido) é o único campo que varia de execução para execução
        simulator.runGame(99).copy(timing = emptyList()) shouldBe simulator.runGame(99).copy(timing = emptyList())
    }

    @Test
    fun `le as opcoes do bot dificil`() {
        val config = SimConfig.parse(
            arrayOf(
                "--sides", "dificil,medio", "--hard-iterations", "30", "--hard-rollout-turns", "6",
                "--hard-exploration", "0.5", "--hard-candidates", "3", "--hard-tree-depth", "2",
                "--hard-margin", "0.01", "--hard-min-visits", "4", "--threads", "2",
            ),
        )
        config.sides shouldBe listOf("dificil", "medio")
        config.threads shouldBe 2
        config.hardConfig shouldBe HardBotConfig(
            iterations = 30, timeLimitMillis = null, explorationConstant = 0.5, candidatesPerKind = 3,
            rolloutTurns = 6, treeDepth = 2, overrideMargin = 0.01, minVisitsToOverride = 4,
        )
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--hard-iterations", "0")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--hard-iterations", "x")) }
    }

    @Test
    fun `opcoes invalidas do dificil saem com codigo 2 e a mensagem de uso, sem stack trace`() {
        val invalid = listOf(
            arrayOf("--hard-tree-depth", "0"),
            arrayOf("--hard-margin", "-0.1"),
            arrayOf("--hard-min-visits", "-1"),
            arrayOf("--hard-confidence", "-1"),
            arrayOf("--hard-confidence", "NaN"),
            arrayOf("--hard-exploration", "abc"),
            arrayOf("--hard-iterations"),
            arrayOf("--hard-time-ms", "0"),
            arrayOf("--threads", "0"),
        )
        for (args in invalid) {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            runSimulation(args, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) shouldBe 2
            val message = err.toString("UTF-8")
            message shouldContain "Erro:"
            message shouldContain "Uso:"
            message.contains("	at ") shouldBe false // sem stack trace
            out.size() shouldBe 0
        }
    }

    @Test
    fun `dificil contra medio termina sem falhas nos dois modos e mede o tempo de decisao`() {
        for (mode in GameMode.entries) {
            val config = SimConfig(
                games = 1, seed = 21, mode = mode, target = 300, sides = listOf("dificil", "medio"),
                checkInvariants = true, hardIterations = 4, hardRolloutTurns = 4,
            )
            val outcome = Simulator(config).runGame(config.seed)
            outcome.failure shouldBe null
            outcome.winner shouldNotBe null
            outcome.timing[0].decisions shouldNotBe 0
            Report(config, listOf(outcome), elapsedMillis = 1).render() shouldContain "Tempo por decisão"
        }
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
        // §3.5 / §6.5 o 3 vermelho só fica na mão de quem ainda não começou a 1ª vez (ou com morto indireto pendente)
        val redThree = dealt.allCards().first { it.isRedThree }
        // base: nenhum 3 vermelho em mão (movidos para o monte), todos já começaram a 1ª vez
        val hidden = dealt.hands.flatten().filter { it.isRedThree }
        val base = dealt.copy(
            hands = dealt.hands.map { hand -> hand.filterNot { it.isRedThree } },
            stock = dealt.stock + hidden,
            turnsBegun = GameMode.INDIVIDUAL.seatCount,
        )
        val cur = base.currentSeat
        val other = Seat(1 - cur.index)
        fun withRedThreeIn(seat: Seat, state: RoundState): RoundState {
            val card = redThree
            val stripped = state.copy(
                hands = state.hands.map { it - card },
                stock = state.stock - card,
                redThrees = state.redThrees.map { it - card },
                mortos = state.mortos.map { it - card },
            )
            return stripped.copy(hands = stripped.hands.mapIndexed { i, h -> if (i == seat.index) h + card else h })
        }
        // quem já trocou os seus não pode ter 3 vermelho na mão
        sim.invariantViolation(withRedThreeIn(other, base)) shouldBe
            "3 vermelho na mão do assento ${other.index}, que já trocou os seus"
        // mas pode, se ainda não começou a 1ª vez (turnsBegun = 1: só o da vez começou)
        sim.invariantViolation(withRedThreeIn(other, base.copy(turnsBegun = 1))) shouldBe null
        // ou se pegou o morto indireto e ainda não começou a vez seguinte (§9.4)
        sim.invariantViolation(withRedThreeIn(other, base.copy(unsettledMortoSeats = listOf(other)))) shouldBe null
        // nunca o assento da vez, mesmo que ainda não tenha começado (o motor troca antes de ele decidir)
        sim.invariantViolation(withRedThreeIn(cur, base.copy(turnsBegun = 0))) shouldBe
            "3 vermelho na mão do assento da vez (${cur.index})"
        // §9.1 morto pego fica vazio (as cartas vão para a mão)
        val takenWithCards = dealt.copy(mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Available))
        sim.invariantViolation(takenWithCards)!! shouldContain "morto 0"
    }
}
