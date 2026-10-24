package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Suit
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.ai.createBot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §16.6 a fila de vistas do multijogador: uma jogada por vez, em ordem, no ritmo deste aparelho e com as encenações. */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotPacerTest {

    private val viewer = Seat(0)
    private val robot = Seat(1)

    /** Vista inicial de uma partida de duplas (a do assento 0), sem jogadas. */
    private val base: GameSnapshot = GameController(
        GameConfig(GameMode.DUPLAS, Difficulty.FACIL),
        gameSeed = 7,
        players = GameMode.DUPLAS.seats.map { BotSeatPlayer(createBot(Difficulty.FACIL, Random(it.index.toLong()))) },
    ).state.value.let { it.copy(stage = Stage.PLAYING, roundNumber = 1, view = it.view.copy(redThreeLog = emptyList())) }

    private fun after(previous: GameSnapshot, seat: Seat, action: Action, laid: List<RedThreeLaid> = emptyList()): GameSnapshot {
        val event = PublicEvent.of(previous.view.discardPile, seat, action)
        val events = previous.turnEvents.mapIndexed { i, list -> if (i == seat.index) list + event else list }
        return previous.copy(turnEvents = events, view = previous.view.copy(redThreeLog = previous.view.redThreeLog + laid))
    }

    private val seven = Card(Rank.SEVEN, Suit.SPADES)
    private val redThree = Card(Rank.THREE, Suit.HEARTS)

    @Test
    fun `§16_6 a vez de um robo chega como varios passos, em ordem, um por ritmo, e a vez do jogador por ultimo`() = runTest {
        val draw = after(base, robot, Action.DrawFromStock)
        val discard = after(draw, robot, Action.Discard(seven))
        val myTurn = discard.copy(humanLegal = listOf(Action.DrawFromStock), humanRequestId = 5)
        val shown = mutableListOf<Pair<Long, GameSnapshot>>()
        val pacer = SnapshotPacer({ 700 }, { 0 }, { 0 }) { shown += testScheduler.currentTime to it }
        val job = launch { pacer.run(base) }
        // A rajada do anfitrião chega de uma vez.
        listOf(draw, discard, myTurn).forEach(pacer::offer)
        runCurrent()
        shown.size shouldBe 0
        advanceTimeBy(701)
        shown.map { it.second } shouldBe listOf(draw)
        advanceTimeBy(698)
        shown.map { it.second } shouldBe listOf(draw)
        // A vista com a vez do jogador não traz jogada de outro assento: aparece logo depois do descarte, por último.
        advanceTimeBy(2)
        shown.map { it.second } shouldBe listOf(draw, discard, myTurn)
        shown.map { it.first } shouldBe listOf(700L, 1400L, 1400L)
        job.cancel()
    }

    @Test
    fun `§6_5 com troca de 3 vermelho a proxima jogada espera a encenacao da tela`() = runTest {
        val draw = after(base, robot, Action.DrawFromStock, laid = listOf(RedThreeLaid(robot, redThree)))
        val discard = after(draw, robot, Action.Discard(seven))
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 100 }, { 0 }, { 60_000 }) { shown += it }
        val job = launch { pacer.run(base) }
        pacer.offer(draw)
        pacer.offer(discard)
        advanceTimeBy(10_000)
        shown shouldBe listOf(draw)
        // A tela terminou a troca: só então o descarte (com o ritmo).
        pacer.presentationDone(1, 1)
        advanceTimeBy(101)
        shown shouldBe listOf(draw, discard)
        job.cancel()
    }

    @Test
    fun `§3_5 partida nova espera a distribuicao, e sem aviso segue no limite`() = runTest {
        val first = after(base, robot, Action.DrawFromStock)
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 5_000 }, { 0 }) { shown += it }
        val job = launch { pacer.run(base) }
        pacer.offer(first)
        advanceTimeBy(4_000)
        shown shouldBe emptyList()
        pacer.dealAnimationDone(1)
        runCurrent()
        shown shouldBe listOf(first)
        job.cancel()
        // Sem aviso da tela: segue depois do limite (a mesa nunca trava).
        val shown2 = mutableListOf<GameSnapshot>()
        val pacer2 = SnapshotPacer({ 0 }, { 5_000 }, { 0 }) { shown2 += it }
        val job2 = launch { pacer2.run(base) }
        pacer2.offer(first)
        advanceTimeBy(5_001)
        shown2 shouldBe listOf(first)
        job2.cancel()
        advanceUntilIdle()
    }
}
