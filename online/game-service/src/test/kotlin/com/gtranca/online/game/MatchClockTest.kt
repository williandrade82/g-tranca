package com.gtranca.online.game

import com.gtranca.engine.model.Side
import com.gtranca.online.contracts.AbandonReason
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatChange
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.TurnClock
import com.gtranca.engine.model.Seat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Relogio de teste: o tempo so anda quando o teste manda (nenhum sleep real). */
class FakeClock {
    private val ms = AtomicLong(0)
    val now: () -> Long = { ms.get() }
    val wall: () -> Long = { WALL_BASE + ms.get() }
    fun advance(delta: Long) = ms.addAndGet(delta)

    companion object {
        const val WALL_BASE = 1_700_000_000_000L
    }
}

/**
 * §18.4 (etapa 1.3c): relogio de jogada, abandono, evento de abandono, mesa encerrada e inatividade. Todos com o
 * relogio injetado: avanca-se o [FakeClock] e qualquer comando seguinte (o `inspect`) ja encontra os prazos disparados.
 */
class MatchClockTest {
    private class Rig(
        val session: MatchSession,
        val clock: FakeClock,
        val ended: CopyOnWriteArrayList<MatchEnd>,
        private val job: Job,
    ) {
        fun stop() {
            session.shutdown()
            job.cancel()
        }

        /** Espera os robos jogarem ate chegar a vez de um humano com relogio. */
        suspend fun awaitHumanTurn(): Inspection {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val info = session.inspect()
                if (info.turnLeftMs != null) return info
                delay(5)
            }
            error("nao chegou a vez de um humano")
        }
    }

    private fun rig(
        mode: TableMode,
        seats: List<SeatSpec>,
        tempo: TempoDeJogada = TempoDeJogada.INDETERMINADO,
        punicao: Punicao = Punicao.SEM_PUNICAO,
        bet: Int = 0,
        target: Int = 3000,
        config: GameConfig = FAST,
        seed: Long = 42,
    ): Rig {
        val clock = FakeClock()
        val ended = CopyOnWriteArrayList<MatchEnd>()
        val rules = TableRules(
            mode = mode, rulesJson = "{}", targetScore = target, bet = bet, tempoDeJogada = tempo, punicao = punicao,
            robotLevel = BotLevel.FACIL,
        )
        val session = MatchSession("ABC234", rules, seats, seed, config, clock.now, clock.wall, onEnded = { ended += it })
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        return Rig(session, clock, ended, job)
    }

    private suspend fun Rig.humans(): Pair<TestPlayer, TestPlayer> {
        val a = TestPlayer(session, "ga")
        val b = TestPlayer(session, "gb")
        a.attach()
        b.attach()
        session.inspect()
        a.drain()
        b.drain()
        return a to b
    }

    private fun Pair<TestPlayer, TestPlayer>.owner(seat: Int) = if (first.seat == seat) first else second
    private fun Pair<TestPlayer, TestPlayer>.other(seat: Int) = if (first.seat == seat) second else first

    // ------------------------------------------------------------------------------------------ relogio

    @Test
    fun `18_4 tempo esgotado e abandono em duplas, o robo assume o assento e o jogo segue`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), TempoDeJogada.UM_MINUTO)
        val players = r.humans()
        val turn = r.awaitHumanTurn().match.currentRound.currentSeat.index
        val loser = players.owner(turn)
        val other = players.other(turn)

        r.clock.advance(59_999)
        r.session.inspect().let {
            it.abandonments.shouldBeEmpty()
            it.turnLeftMs shouldBe 1
        }
        r.clock.advance(1) // 60 s: esgotou
        val after = r.session.inspect()
        after.abandonments.single().let {
            it.reason shouldBe AbandonReason.TEMPO
            it.seat shouldBe turn
        }
        loser.drain()
        loser.resigned shouldBe true
        loser.link.closeInfo shouldNotBe null
        val seatsMsg = other.drain().filterIsInstance<SeatsChanged>().last { it.change == SeatChange.RESIGNED }
        seatsMsg.reason shouldBe AbandonReason.TEMPO
        seatsMsg.seats[turn].kind shouldBe SeatKind.ROBO
        seatsMsg.seats[turn].resigned shouldBe true
        // Sem volta (§18.4): o assento e do robo ate o fim.
        TestPlayer(r.session, loser.guestId).attach() shouldBe AttachResult.Abandoned
        // O robo joga pelo assento e o jogo continua para o outro humano.
        r.session.inspect().stage shouldBe TableStage.PLAYING
        r.stop()
    }

    @Test
    fun `18_4 a vista informa o prazo da vez a todos os assentos e a reconexao ve o que resta`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), TempoDeJogada.UM_MINUTO)
        val players = r.humans()
        val turn = r.awaitHumanTurn().match.currentRound.currentSeat.index
        // Uma nova vista chega a cada mudanca; pega a atual pedindo a reconexao do dono da vez.
        val owner = players.owner(turn)
        val other = players.other(turn)
        r.clock.advance(20_000)
        owner.detach() // queda: nao para o relogio
        r.session.inspect()
        val back = TestPlayer(r.session, owner.guestId)
        back.attach()
        r.session.inspect()
        back.drain()
        back.view!!.turn shouldBe TurnClock(Seat(turn), 60_000, 40_000)
        // Quem ficou na mesa recebeu o relogio na vista do inicio da vez (todos os assentos veem o prazo).
        other.drain()
        other.view!!.turn shouldBe TurnClock(Seat(turn), 60_000, 60_000)
        r.stop()
    }

    @Test
    fun `18_4 cinco minutos e o limite do tempo CINCO_MINUTOS`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), TempoDeJogada.CINCO_MINUTOS)
        val (a, _) = r.humans()
        val info = r.awaitHumanTurn()
        info.turnLeftMs shouldBe 300_000
        r.clock.advance(299_999)
        r.session.inspect().abandonments.shouldBeEmpty()
        r.clock.advance(1)
        r.session.inspect().abandonments.single().reason shouldBe AbandonReason.TEMPO
        a.drain()
        r.stop()
    }

    @Test
    fun `18_4 queda de conexao nao para o relogio da vez com tempo definido`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), TempoDeJogada.UM_MINUTO)
        val players = r.humans()
        val turn = r.awaitHumanTurn().match.currentRound.currentSeat.index
        players.owner(turn).detach()
        r.clock.advance(30_000)
        r.session.inspect().let {
            it.abandonments.shouldBeEmpty()
            it.turnLeftMs shouldBe 30_000 // continuou contando durante a queda
        }
        r.clock.advance(30_000)
        r.session.inspect().abandonments.single().let {
            it.reason shouldBe AbandonReason.TEMPO
            it.seat shouldBe turn
        }
        r.stop()
    }

    @Test
    fun `18_4 a vez inteira e uma contagem so, descartar passa o relogio para o proximo humano`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2(), TempoDeJogada.UM_MINUTO)
        val players = r.humans()
        val first = players.owner(r.session.inspect().match.currentRound.currentSeat.index)
        val second = players.other(first.seat)
        r.clock.advance(10_000)
        first.send(com.gtranca.engine.Action.DrawFromStock) // comprar nao reinicia a contagem da vez
        r.session.inspect().turnLeftMs shouldBe 50_000
        first.drain()
        first.send(first.view!!.legal.filterIsInstance<com.gtranca.engine.Action.Discard>().first())
        r.session.inspect().turnLeftMs shouldBe 60_000 // vez do outro humano: contagem nova
        second.drain()
        second.view!!.turn!!.seat shouldBe Seat(second.seat)
        r.stop()
    }

    @Test
    fun `18_4 no tempo indeterminado a mesa espera e nao ha relogio`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2(), config = FAST.copy(idleTtlMs = 1_000))
        val (a, b) = r.humans()
        r.clock.advance(10 * 3_600_000L)
        val info = r.session.inspect()
        info.abandonments.shouldBeEmpty()
        info.turnLeftMs shouldBe null
        info.end shouldBe null
        a.view!!.turn shouldBe null
        b.view!!.turn shouldBe null
        r.session.isFinished shouldBe false // humanos conectados: nao e descartado, nem depois de horas
        r.stop()
    }

    // ------------------------------------------------------------------------------------------ 1x1

    @Test
    fun `18_4 1x1 entre humanos, tempo esgotado entrega a vitoria ao outro na hora e nao entra robo`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2(), TempoDeJogada.UM_MINUTO)
        val players = r.humans()
        val turn = r.awaitHumanTurn().match.currentRound.currentSeat.index
        val loser = players.owner(turn)
        val winner = players.other(turn)
        r.clock.advance(60_000)
        val info = r.session.inspect()
        info.stage shouldBe TableStage.GAME_OVER
        info.winner shouldBe Side(winner.seat)
        info.abandonments.single().reason shouldBe AbandonReason.TEMPO
        info.match.totals.all { it == 0 } shouldBe true // a partida em andamento nao e pontuada (§13.1)
        info.turnLeftMs shouldBe null
        r.ended.single().reason shouldBe GameEndReason.ABANDONO

        winner.drain()
        winner.view!!.stage shouldBe TableStage.GAME_OVER
        winner.view!!.winner shouldBe Side(winner.seat)
        winner.view!!.endReason shouldBe GameEndReason.ABANDONO
        winner.view!!.turn shouldBe null
        val seats = winner.received.filterIsInstance<SeatsChanged>().last { it.change == SeatChange.RESIGNED }
        seats.reason shouldBe AbandonReason.TEMPO
        seats.seats.map { it.kind } shouldBe listOf(SeatKind.HUMANO, SeatKind.HUMANO) // nenhum robo
        seats.seats[loser.seat].resigned shouldBe true
        loser.drain()
        loser.resigned shouldBe true
        TestPlayer(r.session, loser.guestId).attach() shouldBe AttachResult.Abandoned
        r.stop()
    }

    @Test
    fun `18_4 1x1 entre humanos, desistir entrega a vitoria ao outro na hora`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2())
        val (a, b) = r.humans()
        a.resign()
        val info = r.session.inspect()
        info.stage shouldBe TableStage.GAME_OVER
        info.winner shouldBe Side(b.seat)
        info.abandonments.single().reason shouldBe AbandonReason.DESISTIU
        b.drain()
        b.view!!.winner shouldBe Side(b.seat)
        b.view!!.history shouldNotBe null
        r.ended.single().reason shouldBe GameEndReason.ABANDONO
        // Depois do fim ninguem desiste de novo: nada muda.
        b.resign()
        r.session.inspect().abandonments.size shouldBe 1
        r.stop()
    }

    @Test
    fun `18_4 1x1 contra um robo, quem desiste e trocado por robo (a excecao e so entre dois humanos)`() = runBlocking {
        val seats = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Robot(com.gtranca.online.contracts.BotProfile.JOVEM, BotLevel.FACIL))
        val r = rig(TableMode.INDIVIDUAL, seats)
        val a = TestPlayer(r.session, "ga")
        a.attach()
        a.resign()
        r.session.inspect().abandonments.single().reason shouldBe AbandonReason.DESISTIU
        r.ended.none { it.reason == GameEndReason.ABANDONO } shouldBe true // sem vitoria na hora: os robos terminam o jogo
        r.stop()
    }

    // ------------------------------------------------------------------------------------------ evento de abandono

    @Test
    fun `18_4 evento de abandono traz motivo, instante, segundos desde o inicio e a punicao da mesa`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), punicao = Punicao.PERDE_APOSTA, bet = 10)
        val (a, b) = r.humans()
        r.clock.advance(30_000)
        a.resign()
        r.session.inspect()
        r.clock.advance(31_000) // 61 s do jogo
        b.resign()
        val events = r.session.inspect().abandonments
        events.size shouldBe 2
        events[0].let {
            it.reason shouldBe AbandonReason.DESISTIU
            it.seat shouldBe a.seat
            it.secondsSinceStart shouldBe 30
            it.elapsedMs shouldBe 30_000
            it.atEpochMs shouldBe FakeClock.WALL_BASE + 30_000
            it.punicao shouldBe Punicao.PERDE_APOSTA
            it.bet shouldBe 10
            it.round shouldBe 1
            it.withinGrace shouldBe true
            it.betRefunded shouldBe true // dentro dos 60 s a aposta e devolvida
        }
        events[1].let {
            it.secondsSinceStart shouldBe 61
            it.withinGrace shouldBe false
            it.betRefunded shouldBe false // PERDE_APOSTA depois dos 60 s
        }
        // Sem dados sensiveis na forma textual usada em log.
        events.forEach { it.toString() shouldNotContain it.guestId }
        r.stop()
    }

    @Test
    fun `18_4 os 60 s sao os primeiros 60 s do jogo, e sem punicao a aposta volta sempre`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), punicao = Punicao.PERDE_APOSTA, bet = 5)
        val (a, b) = r.humans()
        r.clock.advance(59_999)
        a.resign()
        r.session.inspect()
        r.clock.advance(1) // exatamente 60 s: ja nao e "nos primeiros 60 segundos"
        b.resign()
        r.session.inspect().abandonments.map { it.withinGrace } shouldBe listOf(true, false)
        r.stop()

        val free = rig(TableMode.DUPLAS, duplasSeats(), punicao = Punicao.SEM_PUNICAO, bet = 5)
        val (c, _) = free.humans()
        free.clock.advance(10 * 60_000)
        c.resign()
        free.session.inspect().abandonments.single().let {
            it.withinGrace shouldBe false
            it.betRefunded shouldBe true // sem punicao: recebe a aposta de volta (§18.4)
        }
        free.stop()
    }

    @Test
    fun `18_4 o abandono por tempo tambem e registrado com o motivo TEMPO e os segundos do jogo`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), TempoDeJogada.UM_MINUTO, Punicao.PERDE_APOSTA, bet = 20)
        r.humans()
        r.awaitHumanTurn()
        r.clock.advance(60_000)
        r.session.inspect().abandonments.single().let {
            it.reason shouldBe AbandonReason.TEMPO
            it.secondsSinceStart shouldBe 60
            it.betRefunded shouldBe false
        }
        r.stop()
    }

    // ------------------------------------------------------------------------------------------ mesa encerrada

    @Test
    fun `18_4 anfitriao encerra a mesa em jogo, e abandono dele, nenhum robo assume e todos sao avisados`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), punicao = Punicao.PERDE_APOSTA, bet = 10)
        val (a, b) = r.humans()
        r.session.closeByHost("zz") shouldBe CloseResult.NotInMatch
        r.clock.advance(90_000)
        r.session.closeByHost("ga") shouldBe CloseResult.Closed

        a.drain()
        b.drain()
        a.received.any { it is TableClosed } shouldBe true
        b.received.any { it is TableClosed } shouldBe true
        // Nenhum robo assume uma mesa encerrada: nao ha troca de assento.
        b.received.filterIsInstance<SeatsChanged>().none { it.change == SeatChange.RESIGNED } shouldBe true
        a.link.closeInfo!!.code shouldBe 1000
        b.link.closeInfo!!.code shouldBe 1000

        val end = r.ended.single()
        end.reason shouldBe GameEndReason.ANFITRIAO_ENCERROU
        end.othersRefunded shouldBe true
        end.abandonments.single().let {
            it.reason shouldBe AbandonReason.ANFITRIAO_ENCERROU
            it.seat shouldBe 0
            it.betRefunded shouldBe false // a punicao da mesa vale para o anfitriao (PERDE_APOSTA, passou de 60 s)
        }
        r.session.isFinished shouldBe true
        TestPlayer(r.session, "ga").attach() shouldBe AttachResult.Closed
        r.session.closeByHost("ga") shouldBe CloseResult.AlreadyOver
        r.stop()
    }

    @Test
    fun `18_4 anfitriao que ja tinha abandonado nao gera um segundo abandono ao encerrar a mesa`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats())
        val (a, _) = r.humans()
        a.resign()
        r.session.inspect()
        r.session.closeByHost("ga") shouldBe CloseResult.Closed
        r.ended.single().abandonments.map { it.reason } shouldBe listOf(AbandonReason.DESISTIU)
        r.stop()
    }

    @Test
    fun `18_4 encerrar uma mesa cujo jogo ja terminou nao faz nada`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2())
        val (a, _) = r.humans()
        a.resign() // 1x1: o jogo acaba na hora
        r.session.inspect().stage shouldBe TableStage.GAME_OVER
        r.session.closeByHost("gb") shouldBe CloseResult.AlreadyOver
        r.session.inspect().abandonments.size shouldBe 1
        r.ended.size shouldBe 1
        r.stop()
    }

    @Test
    fun `18_4 jogo terminado pela pontuacao avisa uma vez com o motivo PONTUACAO`() = runBlocking {
        val r = rig(TableMode.DUPLAS, duplasSeats(), target = 150, seed = 7)
        val (a, b) = r.humans()
        listOf(async { a.playToEnd() }, async { b.playToEnd() }).awaitAll()
        r.session.inspect().stage shouldBe TableStage.GAME_OVER
        a.view!!.endReason shouldBe GameEndReason.PONTUACAO
        r.ended.single().reason shouldBe GameEndReason.PONTUACAO
        r.stop()
    }

    // ------------------------------------------------------------------------------------------ inatividade

    @Test
    fun `18_4 sem humanos conectados por tempo demais o jogo e descartado por inatividade`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2(), config = FAST.copy(idleTtlMs = 1_000))
        val (a, b) = r.humans()
        a.detach()
        b.detach()
        r.session.inspect()
        r.clock.advance(999)
        r.session.inspect().end shouldBe null
        r.clock.advance(1)
        r.session.inspect().end!!.reason shouldBe GameEndReason.INATIVIDADE
        r.ended.single().reason shouldBe GameEndReason.INATIVIDADE
        r.session.isFinished shouldBe true
        r.stop()
    }

    @Test
    fun `18_4 voltar a mesa reinicia a contagem da inatividade`() = runBlocking {
        val r = rig(TableMode.INDIVIDUAL, humans2(), config = FAST.copy(idleTtlMs = 1_000))
        val (a, b) = r.humans()
        a.detach()
        b.detach()
        r.session.inspect()
        r.clock.advance(900)
        val back = TestPlayer(r.session, "ga")
        back.attach()
        r.clock.advance(5_000) // com um humano conectado nada e descartado
        r.session.inspect().end shouldBe null
        back.detach()
        r.session.inspect()
        r.clock.advance(999)
        r.session.inspect().end shouldBe null
        r.clock.advance(1)
        r.session.inspect().end!!.reason shouldBe GameEndReason.INATIVIDADE
        r.stop()
    }
}
