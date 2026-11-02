package com.gtranca.online.game

import com.gtranca.engine.Action
import com.gtranca.online.contracts.AbandonReason
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.BoosterConfirmFailed
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.BoosterPrompt
import com.gtranca.online.contracts.BoostersConfirmed
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.RejectReason
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.SeatChange
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.Welcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §17.3/§18.3 o seletor de boosters do inicio do jogo online: a mao so chega depois de confirmar (ou do prazo do
 * servidor esgotar), o debito e sempre assincrono (nunca bloqueia o ator) e nunca confia no que o cliente escolheu.
 */
class BoosterSelectorSessionTest {

    /** Economia falsa: registra cada pedido de debito (com o matchId usado) e devolve o que [respond] decidir. */
    private class FakeBoosterEconomy(
        @Volatile var down: Boolean = false,
        private val respond: (List<String>) -> Set<String> = { it.toSet() },
    ) : EconomyClient {
        val calls = CopyOnWriteArrayList<List<String>>()
        val matchIds = CopyOnWriteArrayList<String>()

        override suspend fun hold(betId: String, amount: Long, accounts: List<String>): Set<String> = accounts.toSet()
        override suspend fun settle(betId: String, payouts: List<BetPayout>) = Unit

        override suspend fun debitBoosters(matchId: String, seat: Int, accountId: String, boosterIds: List<String>): Set<String> {
            calls += boosterIds
            matchIds += matchId
            if (down) throw RuntimeException("economia fora do ar (teste)")
            return respond(boosterIds)
        }
    }

    /** Acumula o que chega em [link] ate [predicate] bater (sem sleep real alem do passo curto de poll). */
    private suspend fun TestPlayer.drainUntil(timeoutMs: Long = 5_000, predicate: (List<ServerMessage>) -> Boolean): List<ServerMessage> {
        val acc = mutableListOf<ServerMessage>()
        withTimeout(timeoutMs) {
            while (true) {
                acc += drain()
                if (predicate(acc)) return@withTimeout
                delay(5)
            }
        }
        return acc
    }

    @Test
    fun `17_3 o assento recebe o seletor antes da mao, e so a mao depois de confirmar (ja debitado)`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        val firstA = a.drain()
        firstA.filterIsInstance<BoosterPrompt>().single().skillAllowed shouldBe true
        firstA.filterIsInstance<SeatUpdate>().shouldBeEmpty() // §17.3 "sem ver a distribuicao das cartas"
        b.drain().filterIsInstance<BoosterPrompt>().single()

        a.selectBoosters(listOf("b1"))
        val after = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1")
        // §17.3/§17.4: a vista de quando o seletor apareceu (guardada) chega primeiro, depois a vista fresca de agora.
        val seatUpdates = after.filterIsInstance<SeatUpdate>()
        seatUpdates.isNotEmpty() shouldBe true
        seatUpdates.last().view.hand.isNotEmpty() shouldBe true
        eco.calls.single() shouldBe listOf("b1")
        run.stop()
    }

    @Test
    fun `17_3 17_4 b3 nunca e aceito online e ids fora do catalogo somem antes do debito`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1", "b3", "lixo-fora-do-catalogo", "b2"))
        val msgs = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        msgs.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1", "b2")
        eco.calls.single() shouldBe listOf("b1", "b2")
        run.stop()
    }

    @Test
    fun `17_9 mesa que nao permite boosters de skill tambem os filtra antes do debito`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val rules = rulesOf(TableMode.INDIVIDUAL).copy(skillBoostersAllowed = false)
        val session = MatchSession("ABC234", rules, humans2(), 42, FAST, economy = eco)
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain().filterIsInstance<BoosterPrompt>().single().skillAllowed shouldBe false
        a.selectBoosters(listOf("b1", "b2")) // b1 e b2 sao skill (§17.1): nenhum passa
        val msgs = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        msgs.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe emptyList()
        eco.calls.shouldBeEmpty() // selecao ficou vazia: nem chama a economia
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 sem confirmar a tempo, o PROPRIO SERVIDOR segue sem boosters (prazo contado por ele)`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val clock = FakeClock()
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        val prompt = a.drain().filterIsInstance<BoosterPrompt>().single()
        prompt.deadlineMs shouldBe 30_000

        clock.advance(29_999)
        session.inspect() // acorda o ator: fireDueTimers roda com o relogio avancado, mas ainda nao venceu
        a.drain().filterIsInstance<BoostersConfirmed>().shouldBeEmpty()

        clock.advance(1) // 30 s: venceu
        session.inspect()
        val after = a.drain()
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe emptyList()
        after.filterIsInstance<SeatUpdate>().isNotEmpty() shouldBe true
        eco.calls.shouldBeEmpty() // nada foi escolhido: nada a debitar
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 o prazo do servidor usa o ULTIMO RASCUNHO, nunca descarta a selecao por o Seguir nao ter chegado a tempo`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val clock = FakeClock()
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain()
        // Rascunhos (nunca confirma com Seguir): o jogador mudou de ideia uma vez, sem nunca tocar em Seguir.
        a.selectBoosters(listOf("b1"), final = false)
        a.selectBoosters(listOf("b1", "b2"), final = false)
        session.inspect() // sincroniza: os dois rascunhos acima ja foram processados (mesma fila, em ordem)
        eco.calls.shouldBeEmpty() // rascunho nunca debita

        clock.advance(30_000)
        session.inspect()
        val after = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1", "b2")
        eco.calls.single() shouldBe listOf("b1", "b2")
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 o prazo do servidor tenta de novo se o debito falhar, sem perder o rascunho`() = runBlocking {
        val eco = FakeBoosterEconomy(down = true)
        val clock = FakeClock()
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1"), final = false) // rascunho gravado; nunca confirma explicitamente
        session.inspect() // sincroniza: o rascunho acima ja foi processado antes do relogio avancar

        clock.advance(30_000)
        session.inspect() // 1a tentativa do prazo: a economia esta fora do ar
        val failed = a.drainUntil { it.any { m -> m is BoosterConfirmFailed } }
        failed.filterIsInstance<SeatUpdate>().shouldBeEmpty() // ainda nao revelou a mao

        eco.down = false
        clock.advance(5_000) // recuo entre tentativas
        session.inspect() // 2a tentativa: a economia ja voltou
        val after = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1") // o rascunho nao se perdeu
        eco.calls.size shouldBe 2 // a 1a tentativa (que falhou) tambem chamou a economia
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 esgotadas as tentativas do prazo (economia fora do ar o tempo todo), o servidor desiste e segue sem boosters`() = runBlocking {
        val eco = FakeBoosterEconomy(down = true)
        val clock = FakeClock()
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1"), final = false)
        session.inspect() // sincroniza: o rascunho acima ja foi processado antes do relogio avancar

        clock.advance(30_000)
        repeat(3) { // as 3 tentativas do prazo (BOOSTER_MAX_DEADLINE_ATTEMPTS), todas com a economia fora do ar
            session.inspect()
            a.drainUntil { it.any { m -> m is BoosterConfirmFailed } }
            clock.advance(5_000)
        }
        session.inspect() // esgotou: desiste sem tentar de novo
        val after = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe emptyList() // risco residual aceito (docs/seguranca-pendencias.md)
        eco.calls.size shouldBe 3 // nenhuma 4a tentativa
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 erro de rede ao confirmar nao fecha a conexao, e o cliente pode tentar de novo`() = runBlocking {
        val eco = FakeBoosterEconomy(down = true)
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1"))
        val failed = a.drainUntil { it.any { m -> m is BoosterConfirmFailed } }
        failed.filterIsInstance<BoosterConfirmFailed>().single().message shouldNotBe ""
        failed.filterIsInstance<SeatUpdate>().shouldBeEmpty() // ainda nao revelou a mao

        eco.down = false
        a.selectBoosters(listOf("b1")) // tenta de novo (a selecao nao se perdeu na tela, §17.3/telas.md)
        val ok = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        ok.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1")
        run.stop()
    }

    @Test
    fun `17_3 depois da 1a tentativa a selecao trava, nunca debita duas listas diferentes`() = runBlocking {
        val eco = FakeBoosterEconomy(down = true)
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1")) // 1a tentativa (final): pode ja ter debitado no banco, mas a resposta se perde
        a.drainUntil { it.any { m -> m is BoosterConfirmFailed } }

        eco.down = false
        // O jogador muda de ideia na tela e toca Tentar de novo com outra selecao: o servidor IGNORA a troca (a lista
        // ja esta travada na 1a tentativa) e tenta de novo so o que foi tentado antes.
        a.selectBoosters(listOf("b2"))
        val after = a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        after.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1")
        eco.calls shouldBe listOf(listOf("b1"), listOf("b1")) // nunca chamou com b2
        run.stop()
    }

    @Test
    fun `17_3 17_4 quem esta no seletor nao perde jogada, recebe a fila em ordem ao confirmar`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val p1 = TestPlayer(run.session, "ga")
        val p2 = TestPlayer(run.session, "gb")
        p1.attach()
        p2.attach()
        val info = run.session.inspect()
        val attachVersion = info.version
        // "mover" confirma e joga; "waiter" fica presa no seletor de proposito (nunca confirma agora).
        val (mover, waiter) = if (info.match.currentRound.currentSeat.index == p1.seat) p1 to p2 else p2 to p1
        mover.drain()
        waiter.drain()

        mover.selectBoosters(listOf("b1"))
        withTimeout(15_000) {
            val playJob = launch { mover.playToEnd(autoConfirmBoosters = false) }
            while (run.session.inspect().version <= attachVersion) delay(5)
            playJob.cancel()
        }
        waiter.drain().filterIsInstance<SeatUpdate>().shouldBeEmpty() // nada chegou: ficou guardado no servidor

        waiter.selectBoosters(listOf("b1"))
        val after = waiter.drainUntil { it.any { m -> m is BoostersConfirmed } }
        val confirmedAt = after.indexOfFirst { it is BoostersConfirmed }
        after.subList(0, confirmedAt).filterIsInstance<SeatUpdate>().shouldBeEmpty() // nenhuma mao antes de confirmar
        val seatUpdates = after.filterIsInstance<SeatUpdate>()
        // A vista de quando "waiter" entrou + ao menos a 1a jogada de "mover" (guardadas) + a vista fresca de agora.
        (seatUpdates.size > 1) shouldBe true
        seatUpdates.first().view.version shouldBe attachVersion
        // Em ordem, nunca volta (a vista fresca final pode repetir a versao da ultima guardada, se nada mudou entre
        // a ultima jogada de "mover" e "waiter" confirmar).
        val versions = seatUpdates.map { it.view.version }
        versions.zipWithNext().all { (x, y) -> y >= x } shouldBe true
        run.stop()
    }

    @Test
    fun `17_3 jogada antes de confirmar os boosters e recusada mesmo na vez do assento`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        val info = run.session.inspect()
        val current = info.match.currentRound.currentSeat.index
        val onTurn = if (a.seat == current) a else b
        onTurn.drain()
        // Forja uma jogada (nenhum dos dois recebeu `legal` ainda: nem tem como montar uma de verdade).
        run.session.submit(onTurn.seat, onTurn.link, PlayAction(1, 1, Action.DrawFromStock))
        run.session.inspect() // sincroniza: o ator ja processou o PlayAction acima (mesma fila, em ordem)
        val msgs = onTurn.drain()
        msgs.filterIsInstance<Rejected>().single().reason shouldBe RejectReason.BOOSTERS_PENDING
        run.stop()
    }

    @Test
    fun `17_3 o jogo nao espera quem ainda nao escolheu, so quando chega a vez dele`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.DUPLAS, duplasSeats(), economy = eco, seed = 7)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        a.drain()
        b.drain()
        val before = run.session.inspect()
        // "ga" confirma e, se for a vez dela (ou chegar a ser), joga sozinha pelas `legal` recebidas (como um robo
        // faria); se a vez e de um robo, o jogo avanca pelo robo mesmo assim. "gb" nunca responde ao seletor: o teste
        // nao deve depender de qual assento comeca a partida.
        a.selectBoosters(listOf("b1"))
        withTimeout(15_000) {
            val job = launch { a.playToEnd(autoConfirmBoosters = false) }
            while (run.session.inspect().version <= before.version) delay(5)
            job.cancel()
        }
        run.session.inspect().version shouldBeGreaterThan before.version
        b.drain().filterIsInstance<SeatUpdate>().shouldBeEmpty() // "gb" continua sem ver a propria mao
        run.stop()
    }

    @Test
    fun `17_3 18_2 choosingBoosters reflete quem ainda nao escolheu, e some com BOOSTERS_CHOSEN`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        val b = TestPlayer(run.session, "gb")
        a.attach()
        b.attach()
        val welcomeA = a.drain().filterIsInstance<Welcome>().single()
        welcomeA.seats.first { it.index == a.seat }.choosingBoosters shouldBe true
        welcomeA.seats.first { it.index == b.seat }.choosingBoosters shouldBe true

        b.selectBoosters(emptyList())
        val changed = b.drainUntil { it.any { m -> m is SeatsChanged && m.change == SeatChange.BOOSTERS_CHOSEN } }
        val seatsMsg = changed.filterIsInstance<SeatsChanged>().last { it.change == SeatChange.BOOSTERS_CHOSEN }
        seatsMsg.seats.first { it.index == b.seat }.choosingBoosters shouldBe false
        seatsMsg.seats.first { it.index == a.seat }.choosingBoosters shouldBe true
        run.stop()
    }

    @Test
    fun `17_3 18_2 o MatchRegistry gera um boosterMatchId diferente por jogo, mesmo repetindo a mesma mesa`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val reg = MatchRegistry(FAST, economy = eco)
        suspend fun playOneGame(seed: Long): MatchSession {
            val (session, _) = reg.create(CreateMatchRequest("ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), seed))
            val a = TestPlayer(session, "ga")
            a.attach()
            a.drain()
            a.selectBoosters(listOf("b1"))
            a.drainUntil { it.any { m -> m is BoostersConfirmed } }
            return session
        }
        val session1 = playOneGame(42)
        session1.shutdown()
        withTimeout(5_000) { while (!session1.isFinished) delay(5) }

        // Mesmo tableId "ABC234" (a mesma mesa, §18.2, hospeda jogos sucessivos): sem um id de jogo proprio, a chave de
        // idempotencia do debito encontraria a do jogo anterior e pularia o debito "de graca" no jogo novo.
        playOneGame(43)
        eco.calls.size shouldBe 2 // os DOIS jogos chamaram a economia
        eco.matchIds.toSet().size shouldBe 2 // com matchId DIFERENTE: nenhum encontraria a chave do outro jogo
        eco.matchIds[0] shouldNotBe eco.matchIds[1]
        reg.close()
    }

    @Test
    fun `17_3 sem economia configurada, o seletor nem aparece (compatibilidade com a fase anterior)`() = runBlocking {
        val run = startSession(TableMode.INDIVIDUAL, humans2()) // economy = null (padrao)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        val msgs = a.drain()
        msgs.filterIsInstance<BoosterPrompt>().shouldBeEmpty()
        msgs.filterIsInstance<SeatUpdate>().isNotEmpty() shouldBe true
        run.stop()
    }

    @Test
    fun `17_3 reconectar num assento ja confirmado repete BoostersConfirmed sem debitar de novo`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val run = startSession(TableMode.INDIVIDUAL, humans2(), economy = eco)
        val a = TestPlayer(run.session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1"))
        a.drainUntil { it.any { m -> m is BoostersConfirmed } }
        eco.calls.size shouldBe 1

        a.attach() // reconecta (mesmo guestId): nova conexao, a antiga e substituida (§18.4)
        val msgs2 = a.drain()
        msgs2.filterIsInstance<BoostersConfirmed>().single().boosters shouldBe listOf("b1")
        msgs2.filterIsInstance<SeatUpdate>().isNotEmpty() shouldBe true
        eco.calls.size shouldBe 1 // nao debitou de novo
        run.stop()
    }

    @Test
    fun `18_4 assento que nunca conectou nao trava a mesa (o relogio normal da vez conta mesmo sem o seletor aparecer)`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val clock = FakeClock()
        val rules = rulesOf(TableMode.INDIVIDUAL).copy(tempoDeJogada = TempoDeJogada.UM_MINUTO)
        val session = MatchSession("ABC234", rules, humans2(), 42, FAST, clock.now, clock.wall, economy = eco)
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain()
        val before = session.inspect()
        if (before.match.currentRound.currentSeat.index == a.seat) {
            // "ga" precisa jogar e passar a vez para "gb" (que nunca vai conectar) antes do teste continuar.
            a.selectBoosters(listOf("b1"))
            a.drainUntil { it.any { m -> m is BoostersConfirmed } }
            withTimeout(15_000) {
                val playJob = launch { a.playToEnd(autoConfirmBoosters = false) }
                while (session.inspect().match.currentRound.currentSeat.index == a.seat) delay(5)
                playJob.cancel()
            }
        }
        // Agora e a vez de "gb", que nunca conectou: o seletor nunca apareceu a ele (nenhum prazo de 30s foi armado).
        session.inspect().match.currentRound.currentSeat.index shouldNotBe a.seat
        session.inspect().turnLeftMs shouldNotBe null // o relogio NORMAL da mesa (1 min) ja conta mesmo assim
        clock.advance(60_000)
        withTimeout(5_000) { while (session.inspect().abandonments.isEmpty()) delay(5) }
        session.inspect().abandonments.single().reason shouldBe AbandonReason.TEMPO
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 18_4 desistir sem confirmar os boosters nunca debita (1x1 contra robo)`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val clock = FakeClock()
        val seats = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Robot(BotProfile.JOVEM, BotLevel.FACIL))
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), seats, 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        a.attach()
        a.drain()
        a.selectBoosters(listOf("b1"), final = false) // rascunho; nunca toca em Seguir
        session.inspect()
        a.resign() // §13.1/§18.4: sem outro humano, o jogo termina na hora (ela perde)
        session.inspect()
        clock.advance(60_000) // bem alem do prazo de 30 s do seletor
        session.inspect()
        eco.calls.shouldBeEmpty() // nunca debitou por um booster que ela nunca chegou a usar
        session.shutdown()
        job.cancel()
    }

    @Test
    fun `17_3 18_4 o oponente desistir enquanto voce ainda escolhe os boosters tambem nunca debita`() = runBlocking {
        val eco = FakeBoosterEconomy()
        val clock = FakeClock()
        val session = MatchSession(
            "ABC234", rulesOf(TableMode.INDIVIDUAL), humans2(), 42, FAST, clock.now, clock.wall,
            economy = eco, boosterDeadlineMs = 30_000,
        )
        val job = CoroutineScope(Dispatchers.Default).launch { session.run() }
        val a = TestPlayer(session, "ga")
        val b = TestPlayer(session, "gb")
        a.attach()
        b.attach()
        a.drain()
        b.drain()
        a.selectBoosters(listOf("b1"), final = false) // "ga" ainda esta no seletor (rascunho, nunca confirma)
        session.inspect()
        b.resign() // 1x1 entre humanos (§13.1/§18.4): "ga" vence na hora, o jogo acaba por ela nao ter feito nada
        session.inspect()
        clock.advance(60_000) // o prazo do seletor de "ga" venceria aqui, se o jogo nao tivesse acabado
        session.inspect()
        eco.calls.shouldBeEmpty() // o fim do jogo impede qualquer debito novo, mesmo so no assento de quem ficou
        session.shutdown()
        job.cancel()
    }
}
