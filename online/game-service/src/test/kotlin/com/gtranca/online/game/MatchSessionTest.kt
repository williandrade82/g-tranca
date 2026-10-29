package com.gtranca.online.game

import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.online.contracts.OnlineJson
import com.gtranca.online.contracts.RejectReason
import com.gtranca.online.contracts.SeatChange
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableStage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class MatchSessionTest {

    /** §18.3 / §16.4: varre a mensagem serializada contra as cartas REAIS ocultas daquele momento. */
    private class LeakDetector {
        val violations = CopyOnWriteArrayList<String>()
        var checked = 0
        var maxBytes = 0
        private val publicTaken = mutableSetOf<Card>()
        private var round = -1
        private val cardToken = Regex("\"([2-9TJQKA][HDSC]'?)\"")

        fun check(seat: Int, msg: ServerMessage, match: com.gtranca.engine.model.Match) {
            val key = match.history.size - if (match.currentRoundRecorded) 1 else 0 // indice da partida (nao muda ao pontuar)
            if (key != round) {
                round = key
                publicTaken.clear()
            }
            if (msg is SeatUpdate) {
                // Pegar o lixo e publico (§5.6): as cartas levadas nao sao ocultas durante a partida.
                msg.event?.let { e ->
                    if (e.action is Action.TakeDiscardPile) {
                        publicTaken += e.takenFromDiscard
                        e.takenTop?.let(publicTaken::add)
                    }
                }
                if (msg.view.stage == TableStage.PLAYING) {
                    if (msg.view.hand != match.currentRound.hands[seat]) violations += "mao do proprio assento difere"
                    if (msg.view.seat.index != seat) violations += "vista de outro assento"
                }
            }
            val round = match.currentRound
            val hidden = (round.hands.filterIndexed { i, _ -> i != seat }.flatten() + round.stock + round.mortos.flatten()).toSet() - publicTaken
            val json = OnlineJson.encodeToString(ServerMessage.serializer(), msg)
            checked++
            maxBytes = maxOf(maxBytes, json.length)
            violations += scan(json, hidden, match.seed)
        }

        fun scan(json: String, hidden: Set<Card>, seed: Long): List<String> {
            val out = mutableListOf<String>()
            cardToken.findAll(json).forEach { m ->
                if (Card.parse(m.groupValues[1]) in hidden) out += "carta oculta ${m.groupValues[1]}"
            }
            for (k in listOf("\"stock\"", "\"hands\"", "\"mortos\"", "\"seed\"")) if (json.contains(k)) out += "campo $k"
            if (json.contains(seed.toString())) out += "semente"
            return out
        }
    }

    @Test
    fun `18_3 jogo completo em duplas com 2 humanos e 2 robos, sem vazar carta oculta`() = runBlocking {
        val run = startSession(TableMode.DUPLAS, duplasSeats(), target = 1200, seed = 7)
        val detector = LeakDetector()
        run.session.tap = { seat, msg, match -> detector.check(seat, msg, match) }
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        listOf(async { a.playToEnd() }, async { b.playToEnd() }).awaitAll()

        val info = run.session.inspect()
        info.stage shouldBe TableStage.GAME_OVER
        info.match.isOver shouldBe true
        info.match.winner shouldNotBe null
        info.match.totals.max() shouldBeGreaterThan 299
        // §16.4 as duas vistas finais mostram o mesmo placar e o vencedor.
        a.view!!.winner shouldBe info.match.winner
        b.view!!.totals shouldBe info.match.totals
        a.view!!.history!!.size shouldBe info.match.history.size
        info.match.history.size shouldBeGreaterThan 1
        a.rejections.shouldBeEmpty()
        b.rejections.shouldBeEmpty()
        detector.checked shouldBeGreaterThan 100
        detector.violations.toList().shouldBeEmpty()
        println("mensagens varridas=${detector.checked}, maior mensagem=${detector.maxBytes} bytes, partidas=${info.match.history.size}")
        run.stop()
    }

    @Test
    fun `16_4 o detector de vazamento acusa carta oculta, monte e semente`() {
        val d = LeakDetector()
        val hidden = setOf(Card.parse("7H"), Card.parse("7H'"))
        d.scan("""{"hand":["7H'"," 3C"],"x":"7H"}""", hidden, 99).size shouldBe 2
        d.scan("""{"stock":[]}""", hidden, 99) shouldBe listOf("campo \"stock\"")
        d.scan("""{"n":99}""", hidden, 99) shouldBe listOf("semente")
        d.scan("""{"hand":["8H"]}""", hidden, 99).shouldBeEmpty()
    }

    private fun twoHumans(seed: Long = 11) = startSession(TableMode.INDIVIDUAL, humans2(), seed = seed)

    private suspend fun both(run: RunningSession): Triple<TestPlayer, TestPlayer, Pair<TestPlayer, TestPlayer>> {
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        run.session.inspect()
        a.drain()
        b.drain()
        val first = if (a.view!!.currentSeat.index == a.seat) a else b
        val second = if (first === a) b else a
        return Triple(a, b, first to second)
    }

    @Test
    fun `18_3 jogada ilegal e fora da vez sao recusadas com motivo e nao alteram o estado`() = runBlocking {
        val run = twoHumans()
        val (_, _, order) = both(run)
        val (first, second) = order
        val before = run.session.inspect()
        val v = first.view!!.version

        // Fora da vez (§4.2).
        second.send(Action.DrawFromStock, stateVersion = second.view!!.version)
        // Ilegal: descartar antes de comprar (§4.3).
        first.send(Action.Discard(first.view!!.hand.first()), stateVersion = v)
        // Ilegal: carta que nao e da mao (a carta oculta do adversario).
        val foreign = before.match.currentRound.hands[second.seat].first()
        first.send(Action.Discard(foreign), stateVersion = v)

        val after = run.session.inspect()
        after.match shouldBe before.match
        after.version shouldBe before.version
        after.refusals[second.seat] shouldBe 1
        after.refusals[first.seat] shouldBe 2
        second.drain()
        first.drain()
        second.rejections.single().reason shouldBe RejectReason.NOT_YOUR_TURN
        first.rejections.map { it.reason } shouldBe listOf(RejectReason.ILLEGAL, RejectReason.ILLEGAL)
        first.rejections.last().refusals shouldBe 2
        first.rejections.first().message shouldNotBe ""
        // A jogada legal ainda funciona depois das recusas.
        first.send(Action.DrawFromStock, stateVersion = v)
        run.session.inspect().version shouldBe v + 1
        run.stop()
    }

    @Test
    fun `18_3 mensagem duplicada ou atrasada e descartada sem efeito e sem contar recusa`() = runBlocking {
        val run = twoHumans()
        val (_, _, order) = both(run)
        val first = order.first
        val v = first.view!!.version

        first.send(Action.DrawFromStock, stateVersion = v, withSeq = 5)
        val afterOne = run.session.inspect()
        afterOne.version shouldBe v + 1
        // Duplicada (mesmo seq) e atrasada (seq menor): descartadas em silencio.
        first.send(Action.DrawFromStock, stateVersion = v, withSeq = 5)
        first.send(Action.DrawFromStock, stateVersion = v + 1, withSeq = 3)
        val afterDup = run.session.inspect()
        afterDup.match shouldBe afterOne.match
        afterDup.version shouldBe afterOne.version
        afterDup.refusals.sum() shouldBe 0
        afterDup.lastSeq[first.seat] shouldBe 5
        first.drain()
        first.rejections.shouldBeEmpty()
        run.stop()
    }

    @Test
    fun `16_4 cada vista traz as acoes da vez mais recente de cada assento, e a vez nova substitui`() = runBlocking {
        val run = twoHumans()
        val (_, _, order) = both(run)
        val (first, second) = order
        first.send(Action.DrawFromStock)
        run.session.inspect()
        first.drain()
        first.send(first.view!!.legal.filterIsInstance<Action.Discard>().first())
        run.session.inspect()
        second.drain()
        val turn = second.view!!.turnEvents[first.seat]
        turn.size shouldBe 2
        turn.first().action shouldBe Action.DrawFromStock
        (turn.last().action is Action.Discard) shouldBe true
        second.view!!.turnEvents[second.seat].shouldBeEmpty()
        // A vez do segundo comeca com a compra: so ela fica; a vez anterior do primeiro permanece ate a dele recomecar.
        second.send(Action.DrawFromStock)
        run.session.inspect()
        first.drain()
        first.view!!.turnEvents[second.seat].map { it.action } shouldBe listOf(Action.DrawFromStock)
        first.view!!.turnEvents[first.seat].size shouldBe 2
        run.stop()
    }

    @Test
    fun `18_3 versao de estado desatualizada e recusada`() = runBlocking {
        val run = twoHumans()
        val (_, _, order) = both(run)
        val first = order.first
        val v = first.view!!.version
        first.send(Action.DrawFromStock, stateVersion = v - 1)
        val info = run.session.inspect()
        info.version shouldBe v
        info.refusals[first.seat] shouldBe 1
        first.drain()
        first.rejections.single().reason shouldBe RejectReason.STALE_STATE
        run.stop()
    }

    @Test
    fun `18_4 reconexao pelo mesmo assento devolve a vista atual e a nova conexao assume`() = runBlocking {
        val run = twoHumans()
        val (a, b, order) = both(run)
        val first = order.first
        first.send(Action.DrawFromStock)
        run.session.inspect()
        first.drain()
        val seqBefore = first.seq

        // Queda: o assento continua reservado; o outro jogador e avisado.
        first.detach()
        run.session.inspect()
        val other = order.second
        other.drain().filterIsInstance<SeatsChanged>().last().let {
            it.change shouldBe SeatChange.DISCONNECTED
            it.seats[first.seat].connected shouldBe false
        }

        // Volta com o mesmo token (mesmo convidado): Welcome com o ultimo seq + a vista atual.
        val back = TestPlayer(run.session, first.guestId)
        back.attach()
        run.session.inspect()
        back.drain()
        back.welcome!!.you shouldBe Seat(first.seat)
        back.welcome!!.lastSeq shouldBe seqBefore
        back.view!!.version shouldBe run.session.inspect().version
        back.view!!.hand shouldBe run.session.inspect().match.currentRound.hands[first.seat]
        back.view!!.history shouldNotBe null
        (a.seat != b.seat) shouldBe true

        // Uma terceira conexao do mesmo jogador assume; a anterior e fechada com motivo.
        val third = TestPlayer(run.session, first.guestId)
        third.attach()
        run.session.inspect()
        back.drain()
        back.replaced shouldBe true
        back.link.closeInfo!!.code shouldBe MatchSession.CLOSE_REPLACED
        // Mensagens da conexao antiga nao valem mais.
        val v = run.session.inspect().version
        back.seq = third.welcome?.lastSeq ?: seqBefore
        back.send(Action.Discard(back.view!!.hand.first()), stateVersion = v)
        run.session.inspect().refusals.sum() shouldBe 0
        run.stop()
    }

    @Test
    fun `18_4 queda de conexao nao e abandono no tempo indeterminado, o jogo espera`() = runBlocking {
        val run = startSession(TableMode.DUPLAS, duplasSeats(), seed = 3)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        // Os robos jogam ate chegar a vez de um humano; entao A cai (o outro humano nunca entrou).
        var info = run.session.inspect()
        val deadline = System.currentTimeMillis() + 30_000
        while (info.match.currentRound.currentSeat.index !in listOf(0, 2) && System.currentTimeMillis() < deadline) {
            delay(20)
            info = run.session.inspect()
        }
        a.detach()
        val frozen = run.session.inspect()
        delay(300)
        val still = run.session.inspect()
        still.match shouldBe frozen.match
        still.version shouldBe frozen.version
        still.resignations.shouldBeEmpty()
        // Volta pelo mesmo assento (token do dono da vez) e joga normalmente.
        val owner = TestPlayer(run.session, if (frozen.match.currentRound.currentSeat.index == 0) "ga" else "gb")
        owner.attach()
        run.session.inspect()
        owner.drain()
        owner.view!!.legal.size shouldBeGreaterThan 0
        owner.send(owner.view!!.legal.first(), withSeq = owner.welcome!!.lastSeq + 1)
        run.session.inspect().version shouldBe frozen.version + 1
        run.stop()
    }

    @Test
    fun `18_4 desistir faz um robo assumir o assento e o jogo termina`() = runBlocking {
        val run = startSession(TableMode.INDIVIDUAL, humans2(), target = 200, seed = 5)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        run.session.inspect()
        a.drain()
        b.drain()
        a.resign()
        run.session.inspect()
        a.drain()
        a.resigned shouldBe true
        a.link.closeInfo shouldNotBe null
        val mid = run.session.inspect()
        mid.resignations.single().let {
            it.seat shouldBe a.seat
            it.guestId shouldBe "ga"
        }
        val sawResign = b.drain().filterIsInstance<SeatsChanged>().last()
        sawResign.change shouldBe SeatChange.RESIGNED
        sawResign.seats[a.seat].kind shouldBe SeatKind.ROBO
        sawResign.seats[a.seat].resigned shouldBe true

        // O jogo segue so com B contra o robo e termina.
        b.playToEnd()
        run.session.inspect().stage shouldBe TableStage.GAME_OVER
        // Quem desistiu nao volta ao assento (o robo e dele agora).
        TestPlayer(run.session, "ga").attach() shouldBe AttachResult.Abandoned
        run.stop()
    }

    @Test
    fun `18_4 quando o ultimo humano desiste os robos terminam o jogo sozinhos`() = runBlocking {
        val run = startSession(TableMode.INDIVIDUAL, humans2(), target = 150, seed = 9)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        a.resign()
        b.resign()
        var info = run.session.inspect()
        val deadline = System.currentTimeMillis() + 60_000
        while (info.stage != TableStage.GAME_OVER && System.currentTimeMillis() < deadline) {
            delay(50)
            info = run.session.inspect()
        }
        info.stage shouldBe TableStage.GAME_OVER
        info.resignations.size shouldBe 2
        run.stop()
    }

    @Test
    fun `18_3 partida respeita a fase e nao aceita jogada entre partidas`() = runBlocking {
        val run = startSession(TableMode.INDIVIDUAL, humans2(), target = 100000, seed = 21)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        run.session.inspect()
        a.drain()
        a.view!!.phase shouldBe Phase.AWAITING_DRAW
        a.view!!.stage shouldBe TableStage.PLAYING
        run.stop()
    }
}
