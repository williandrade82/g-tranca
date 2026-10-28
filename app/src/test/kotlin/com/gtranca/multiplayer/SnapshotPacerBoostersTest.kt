package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Suit
import com.gtranca.game.BotSeatPlayer
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.ai.createBot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * §17.3 a fila de vistas espera o seletor de boosters deste aparelho: nada da partida aparece antes do Seguir, e depois dele as
 * vistas que já chegaram (distribuição e descartes dos outros) são mostradas na ordem de sempre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotPacerBoostersTest {

    private val robot = Seat(1)

    private val base: GameSnapshot = GameController(
        GameConfig(GameMode.DUPLAS, Difficulty.FACIL),
        gameSeed = 7,
        players = GameMode.DUPLAS.seats.map { BotSeatPlayer(createBot(Difficulty.FACIL, Random(it.index.toLong()))) },
    ).state.value.let { it.copy(stage = Stage.PLAYING, roundNumber = 1, view = it.view.copy(redThreeLog = emptyList())) }

    private fun after(previous: GameSnapshot, seat: Seat, action: Action): GameSnapshot {
        val event = PublicEvent.of(previous.view.discardPile, seat, action)
        return previous.copy(turnEvents = previous.turnEvents.mapIndexed { i, list -> if (i == seat.index) list + event else list })
    }

    @Test
    fun `§17_3 o gate segura a primeira vista e as seguintes ate o Seguir, depois tudo segue em ordem`() = runTest {
        val draw = after(base, robot, Action.DrawFromStock)
        val discard = after(draw, robot, Action.Discard(Card(Rank.SEVEN, Suit.SPADES)))
        val shown = mutableListOf<GameSnapshot>()
        val gated = mutableListOf<Int>()
        val seguir = CompletableDeferred<Unit>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }, boostersGate = { round -> gated += round; seguir.await() }) { shown += it }
        val job = launch { pacer.run(base) }
        listOf(draw, discard).forEach(pacer::offer)
        runCurrent()
        // espera o seletor da partida 1: nenhuma vista foi mostrada
        gated shouldBe listOf(1)
        shown.size shouldBe 0
        advanceTimeBy(60_000)
        shown.size shouldBe 0
        seguir.complete(Unit)
        runCurrent()
        shown shouldBe listOf(draw, discard)
        job.cancel()
    }

    @Test
    fun `§17_3 sem gate (testes e mesa sem boosters) a fila anda como sempre`() = runTest {
        val draw = after(base, robot, Action.DrawFromStock)
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }) { shown += it }
        val job = launch { pacer.run(base) }
        pacer.offer(draw)
        runCurrent()
        shown shouldBe listOf(draw)
        job.cancel()
    }

    @Test
    fun `§17_3 o gate e chamado a cada vista da partida e volta na hora quando o assento ja escolheu`() = runTest {
        val draw = after(base, robot, Action.DrawFromStock)
        val shown = mutableListOf<GameSnapshot>()
        val gated = mutableListOf<Int>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }, boostersGate = { gated += it }) { shown += it }
        val job = launch { pacer.run(base) }
        pacer.offer(draw)
        runCurrent()
        gated shouldBe listOf(1, 1) // a inicial e a seguinte (o motor de gate decide se espera)
        shown shouldBe listOf(draw)
        job.cancel()
    }
}
