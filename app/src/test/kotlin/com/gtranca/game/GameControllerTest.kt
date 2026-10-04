package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class GameControllerTest {

    /** Bots como no `:sim`: dificuldade por lado, `Random(botSeed(semente, assento))`. */
    private fun simLikePlayers(mode: GameMode, sides: List<Difficulty>, gameSeed: Long): List<SeatPlayer> =
        mode.seats.map { seat ->
            BotSeatPlayer(createBot(sides[mode.sideOf(seat).index], Random(botSeed(gameSeed, seat.index))))
        }

    /**
     * Equivalência com o simulador: mesmos bots e sementes ⇒ mesmo vencedor e mesmos totais finais (§13).
     * Valores obtidos com `.\gradlew.bat :sim:run --args="--games 1 --mode <modo> --sides medio,facil
     * --seed <semente> --target 1000"` ("Pontuação final média" com 1 jogo = total final).
     */
    @ParameterizedTest(name = "{0} semente {1}")
    @CsvSource(
        "INDIVIDUAL, 11, 1, 772, 1460",
        "INDIVIDUAL, 12, 0, 1122, 900",
        "INDIVIDUAL, 13, 0, 1200, -90",
        "DUPLAS, 21, 0, 1743, 1142",
    )
    fun `jogo so de bots reproduz o simulador`(mode: GameMode, seed: Long, winner: Int, total0: Int, total1: Int) = runTest {
        val config = GameConfig(mode, Difficulty.MEDIO, targetScore = 1000)
        val controller = GameController(
            config, seed, simLikePlayers(mode, listOf(Difficulty.MEDIO, Difficulty.FACIL), seed),
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
        )
        controller.run()

        val state = controller.state.value
        state.stage shouldBe Stage.GAME_OVER
        state.winner shouldBe Side(winner)
        state.totals shouldBe listOf(total0, total1)
        controller.currentMatch.seed shouldNotBe 0L
    }

    @Test
    fun `semente dos bots segue a formula do simulador`() {
        botSeed(11, 0) shouldBe 11L * 1_000_003L + 1
        botSeed(11, 3) shouldBe 11L * 1_000_003L + 4
    }

    /** Bot que registra o que recebe, delegando a decisão ao Fácil. */
    private class SpyBot(seed: Long) : BotPlayer {
        private val delegate = createBot(Difficulty.FACIL, Random(seed))
        val views = mutableListOf<PlayerView>()
        val events = mutableListOf<PublicEvent>()
        var newRounds = 0
        override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
            views += view
            return delegate.chooseAction(view, legal)
        }
        override fun observe(event: PublicEvent) { events += event }
        override fun onNewRound() { newRounds++ }
    }

    /**
     * Faz as vezes da interface na encenação das trocas de 3 vermelho da distribuição (§3.5): sem ela, o
     * controlador com humano não libera a 1ª jogada.
     */
    private fun TestScope.acknowledgeDeals(controller: GameController) {
        backgroundScope.launch {
            controller.state.collect { if (it.stage == Stage.PLAYING) controller.dealPresentationDone(it.roundNumber) }
        }
    }

    private fun TestScope.humanGame(
        dispatcher: TestDispatcher,
        delay: Long = 0,
        seed: Long = 5,
        target: Int = 3000,
    ): Triple<GameController, HumanPlayer, SpyBot> {
        val human = HumanPlayer()
        val spy = SpyBot(seed)
        val controller = GameController(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, target), seed, listOf(human, BotSeatPlayer(spy)),
            computeDispatcher = dispatcher, botDelayMillis = delay,
        )
        acknowledgeDeals(controller)
        return Triple(controller, human, spy)
    }

    @Test
    fun `humano joga pela interface e o bot so recebe a propria vista e todos os eventos`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, human, spy) = humanGame(dispatcher)
        val job = launch { controller.run() }
        advanceUntilIdle()

        val state = controller.state.value
        state.isHumanTurn shouldBe true
        state.view.seat shouldBe Seat(0)
        state.view.hand shouldBe controller.currentMatch.currentRound.handOf(Seat(0))
        spy.newRounds shouldBe 1

        // Ação fora de legal é recusada; a de legal é aceita.
        human.submit(Action.Discard(state.view.hand.first())) shouldBe false
        human.submit(Action.DrawFromStock) shouldBe true
        advanceUntilIdle()

        val afterDraw = controller.state.value
        afterDraw.view.phase shouldBe Phase.PLAYING
        afterDraw.turnEvents[0].map { it.action } shouldBe listOf(Action.DrawFromStock)
        spy.events.last() shouldBe PublicEvent(Seat(0), Action.DrawFromStock)
        spy.views.forEach { it.seat shouldBe Seat(1) }
        job.cancel()
    }

    @Test
    fun `descarte do humano passa a vez ao bot, que joga depois da pausa`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, human, spy) = humanGame(dispatcher, delay = 700)
        val job = launch { controller.run() }
        // Se o bot começa, ele joga a vez inteira depois das pausas.
        advanceUntilIdle()
        controller.state.value.isHumanTurn shouldBe true
        human.submit(Action.DrawFromStock) shouldBe true
        advanceUntilIdle()
        val discard = controller.state.value.humanLegal.filterIsInstance<Action.Discard>().first()
        val decisionsBefore = spy.views.size
        human.submit(discard) shouldBe true
        runCurrent()

        val waiting = controller.state.value
        if (waiting.stage == Stage.PLAYING && waiting.view.currentSeat == Seat(1)) {
            waiting.thinkingSeat shouldBe Seat(1)
            advanceTimeBy(699)
            spy.views.size shouldBe decisionsBefore
            advanceTimeBy(2)
            spy.views.size shouldBe decisionsBefore + 1
        }
        job.cancel()
    }

    @Test
    fun `explain devolve o motivo do motor sem expor o estado`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, _, _) = humanGame(dispatcher)
        val job = launch { controller.run() }
        advanceUntilIdle()
        val hand = controller.state.value.view.hand
        // §4.3 antes de comprar não se baixa.
        val id = controller.state.value.humanRequestId
        controller.explain(Action.CreateMeld(hand.take(3)), id).shouldNotBeNull()
        controller.explain(Action.DrawFromStock, id).shouldBeNull()
        job.cancel()
    }

    @Test
    fun `explain valida contra a situacao do pedido ao humano e nada responde fora dele`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, human, _) = humanGame(dispatcher)
        val job = launch { controller.run() }
        advanceUntilIdle()
        human.waiting.value shouldBe true
        human.submit(Action.DrawFromStock) shouldBe true
        // Enviada a jogada, o humano já não está sendo esperado, mesmo antes de o controlador retomar.
        human.waiting.value shouldBe false
        advanceUntilIdle()
        val hand = controller.state.value.view.hand
        // Agora na fase de jogar: descartar uma carta da mão é válido; comprar de novo, não (§4.3).
        val id = controller.state.value.humanRequestId
        controller.explain(Action.Discard(hand.first()), id).shouldBeNull()
        controller.explain(Action.DrawFromStock, id).shouldNotBeNull()
        // Pedido antigo (o da compra): nada a explicar, mesmo sendo a vez do humano.
        controller.explain(Action.DrawFromStock, id - 1).shouldBeNull()
        job.cancel()
        advanceUntilIdle()
        // Sem pedido em aberto (jogo cancelado), não há situação a explicar.
        controller.explain(Action.DrawFromStock, id).shouldBeNull()
    }

    @Test
    fun `fim de partida espera a confirmacao do humano`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, human, _) = humanGame(dispatcher, target = 1)
        val job = launch { controller.run() }
        // O humano joga sempre a primeira ação legal até a partida acabar.
        var guard = 0
        while (controller.state.value.stage == Stage.PLAYING && guard++ < 5_000) {
            advanceUntilIdle()
            val state = controller.state.value
            if (state.isHumanTurn) {
                val legal = state.humanLegal
                val action = legal.firstOrNull { it is Action.Discard } ?: legal.first()
                action shouldBeIn legal
                human.submit(action)
            }
        }
        advanceUntilIdle()
        val ended = controller.state.value
        ended.history.size shouldBe 1
        if (ended.stage == Stage.ROUND_OVER) {
            // §13 alvo 1 sem vencedor: empate no maior total ou ninguém chegou; aguarda a confirmação.
            controller.continueToNextRound() shouldBe true
            controller.continueToNextRound() shouldBe false
            advanceUntilIdle()
            controller.state.value.roundNumber shouldBe 2
        } else {
            ended.stage shouldBe Stage.GAME_OVER
            ended.winner.shouldNotBeNull()
            controller.continueToNextRound() shouldBe false
        }
        job.cancel()
    }

    @Test
    fun `lixo pego pelo humano chega aos bots com as cartas levadas e cada partida avisa os bots`() = runTest {
        // §5.2 o lixo sem o topo vai para a mão; §5.6 ele é aberto, então o evento é público.
        // §15.1 cada nova partida é distribuída: os bots zeram a memória (onNewRound).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, human, spy) = humanGame(dispatcher, target = 100_000)
        val job = launch { controller.run() }
        var humanTake: PublicEvent? = null
        var guard = 0
        while (controller.state.value.stage == Stage.PLAYING && guard++ < 5_000) {
            advanceUntilIdle()
            val state = controller.state.value
            if (!state.isHumanTurn) continue
            val legal = state.humanLegal
            // Só pega lixos com pelo menos 2 cartas, para haver cartas levadas à mão.
            val take = legal.firstOrNull { it is Action.TakeDiscardPile }?.takeIf { state.view.discardPile.size >= 2 }
            if (take != null && humanTake == null) humanTake = PublicEvent(Seat(0), take, state.view.discardPile.dropLast(1))
            human.submit(take ?: legal.firstOrNull { it is Action.Discard } ?: legal.first()) shouldBe true
        }
        advanceUntilIdle()
        controller.state.value.stage shouldBe Stage.ROUND_OVER
        spy.events shouldContain humanTake.shouldNotBeNull()
        humanTake!!.takenFromDiscard.isNotEmpty() shouldBe true

        controller.continueToNextRound() shouldBe true
        advanceUntilIdle()
        spy.newRounds shouldBe 2
        job.cancel()
    }

    @Test
    fun `cada pedido ao humano tem identificador e respostas a pedido antigo sao recusadas`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, _, _) = humanGame(dispatcher)
        val job = launch { controller.run() }
        advanceUntilIdle()
        val first = controller.state.value.humanRequestId
        (first > 0) shouldBe true
        controller.submit(Action.DrawFromStock, first) shouldBe true
        advanceUntilIdle()
        val second = controller.state.value.humanRequestId
        (second > first) shouldBe true
        // §4.3 a resposta ao pedido da compra, chegando atrasada, não é aplicada ao pedido seguinte.
        val discard = controller.state.value.humanLegal.filterIsInstance<Action.Discard>().first()
        controller.submit(discard, first) shouldBe false
        controller.submit(discard, second) shouldBe true
        job.cancel()
    }

    @Test
    fun `§13_1 desistencia na vez do humano - outro lado vence e a partida nao e pontuada`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (controller, _, _) = humanGame(dispatcher)
        val job = launch { controller.run() }
        advanceUntilIdle()
        controller.state.value.isHumanTurn shouldBe true
        val roundBefore = controller.currentMatch.currentRound

        controller.resign() shouldBe true
        val ended = controller.state.value
        ended.stage shouldBe Stage.GAME_OVER
        ended.resigned shouldBe true
        ended.winner shouldBe Side(1)
        ended.totals shouldBe listOf(0, 0)
        ended.history shouldBe emptyList()
        ended.humanLegal shouldBe emptyList()
        advanceUntilIdle()
        job.isCompleted shouldBe true
        // Nada mais acontece depois: nem jogada, nem nova partida, nem segunda desistência.
        controller.currentMatch.currentRound shouldBe roundBefore
        controller.submit(Action.DrawFromStock, ended.humanRequestId) shouldBe false
        controller.continueToNextRound() shouldBe false
        controller.resign() shouldBe false
        controller.state.value shouldBe ended
    }

    @Test
    fun `§13_1 desistencia com bot pensando descarta a decisao dele e encerra o laco`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var controller: GameController
        var decisions = 0
        // Bot que, no meio da decisão (busca de CPU), vê o humano desistir.
        val resigningBot = object : BotPlayer {
            private val delegate = createBot(Difficulty.FACIL, Random(1))
            override fun chooseAction(view: PlayerView, legal: List<Action>): Action {
                decisions++
                controller.resign()
                return delegate.chooseAction(view, legal)
            }
        }
        val human = HumanPlayer()
        controller = GameController(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), 5, listOf(human, BotSeatPlayer(resigningBot)),
            computeDispatcher = dispatcher, botDelayMillis = 0,
        )
        acknowledgeDeals(controller)
        val job = launch { controller.run() }
        advanceUntilIdle()
        if (controller.state.value.isHumanTurn) {
            controller.submit(Action.DrawFromStock, controller.state.value.humanRequestId) shouldBe true
            advanceUntilIdle()
            val discard = controller.state.value.humanLegal.filterIsInstance<Action.Discard>().first()
            controller.submit(discard, controller.state.value.humanRequestId) shouldBe true
        }
        advanceUntilIdle()
        decisions shouldBe 1
        job.isCompleted shouldBe true
        controller.state.value.stage shouldBe Stage.GAME_OVER
        controller.state.value.winner shouldBe Side(1)
        // A jogada escolhida pelo bot depois da desistência não foi aplicada: ainda é a vez dele, antes de comprar.
        controller.currentMatch.currentRound.currentSeat shouldBe Seat(1)
        controller.currentMatch.currentRound.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `§3_5 e §6_5 trocas lidas do registro publico - so as novas, uma vez cada, com quem trocou`() = runTest {
        // Jogo só de bots: acumulando as trocas novas de cada snapshot, cada partida mostra exatamente o registro do
        // motor, na ordem; cada carta é um 3 vermelho do lado de quem trocou.
        val mode = GameMode.DUPLAS
        val seed = 21L
        val controller = GameController(
            GameConfig(mode, Difficulty.MEDIO, targetScore = 1000), seed,
            simLikePlayers(mode, listOf(Difficulty.MEDIO, Difficulty.FACIL), seed),
            computeDispatcher = StandardTestDispatcher(testScheduler), botDelayMillis = 0,
        )
        var previous: GameSnapshot? = null
        val shown = mutableListOf<RedThreeNotice>()
        var checks = 0
        val collector = launch {
            controller.state.collect { snapshot ->
                if (previous?.roundNumber != snapshot.roundNumber) shown.clear()
                shown += LogRedThreeSource.newSwaps(previous, snapshot)
                previous = snapshot
                val log = snapshot.view.redThreeLog
                shown.map { it.seat to it.cards.single() } shouldBe log.map { it.seat to it.card }
                shown.map { it.atDeal } shouldBe log.map { it.atDeal }
                shown.forEach { notice ->
                    notice.cards.single().isRedThree shouldBe true
                    notice.side shouldBe mode.sideOf(notice.seat)
                }
                shown.map { it.id }.distinct().size shouldBe shown.size
                checks++
            }
        }
        controller.run()
        collector.cancel()
        controller.state.value.stage shouldBe Stage.GAME_OVER
        (checks > 1) shouldBe true
    }

    @Test
    fun `§3_5 com humano, a 1a jogada espera a encenacao das trocas da distribuicao`() = runTest {
        // Procura uma semente com 3 vermelho na distribuição.
        val dispatcher = StandardTestDispatcher(testScheduler)
        var checked = false
        for (seed in 1L..40L) {
            val human = HumanPlayer()
            val spy = SpyBot(seed)
            val controller = GameController(
                GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), seed, listOf(human, BotSeatPlayer(spy)),
                computeDispatcher = dispatcher, botDelayMillis = 0,
            )
            val job = launch { controller.run() }
            advanceUntilIdle()
            val state = controller.state.value
            if (state.view.redThreeLog.none { it.atDeal }) {
                job.cancel()
                continue
            }
            // Ninguém jogou nem foi chamado a jogar.
            state.isHumanTurn shouldBe false
            spy.views shouldBe emptyList()
            state.turnEvents.all { it.isEmpty() } shouldBe true
            // Confirmação de outra partida é ignorada; a da partida atual libera o jogo.
            controller.dealPresentationDone(state.roundNumber + 1) shouldBe false
            controller.dealPresentationDone(state.roundNumber) shouldBe true
            controller.dealPresentationDone(state.roundNumber) shouldBe false
            advanceUntilIdle()
            (controller.state.value.isHumanTurn || spy.views.isNotEmpty()) shouldBe true
            job.cancel()
            checked = true
            break
        }
        checked shouldBe true
    }
}
