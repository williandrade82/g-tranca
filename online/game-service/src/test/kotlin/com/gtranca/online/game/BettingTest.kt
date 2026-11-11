package com.gtranca.online.game

import com.gtranca.online.contracts.AbandonReason
import com.gtranca.online.contracts.BetPayout
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.CreateMatchRequest
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.PayoutKind.DEVOLUCAO
import com.gtranca.online.contracts.PayoutKind.PERDA
import com.gtranca.online.contracts.PayoutKind.PREMIO
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.TableMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** §14.6 / §16.13 / §18.2 / §18.4 retencao e liquidacao das apostas no game-service. */
class BettingTest {
    private val bet = 50L

    // Duplas: assentos 0 e 2 = lado 0; 1 e 3 = lado 1.
    private val duplaSides = listOf(0, 1, 0, 1)

    private fun abandon(guest: String, seat: Int, reason: AbandonReason, elapsedMs: Long, punicao: Punicao = Punicao.PERDE_APOSTA) =
        AbandonEvent(
            seat, guest, reason, round = 1, atEpochMs = 0, elapsedMs = elapsedMs, punicao = punicao, bet = bet.toInt(),
            withinGrace = elapsedMs < 60_000,
            betRefunded = punicao == Punicao.SEM_PUNICAO || elapsedMs < 60_000 || reason == AbandonReason.TODOS_DESCONECTADOS,
        )

    private fun end(
        reason: GameEndReason, winner: Int?, humans: Map<String, Int>, abandons: List<AbandonEvent> = emptyList(),
        sides: List<Int> = duplaSides,
    ) = MatchEnd(reason, 0, 0, reason == GameEndReason.ANFITRIAO_ENCERROU, abandons, winner, sides, humans)

    private fun List<BetPayout>.of(g: String) = single { it.accountId == g }.let { it.kind to it.amount }

    @Test
    fun `16_13 dupla de dois humanos vence por pontuacao, cada um recebe 2A e os perdedores perdem`() {
        val h = mapOf("a" to 0, "b" to 2, "c" to 1, "d" to 3)
        val p = settleBets(end(GameEndReason.PONTUACAO, 0, h), h.keys, bet)
        p.of("a") shouldBe (PREMIO to 100L)
        p.of("b") shouldBe (PREMIO to 100L)
        p.of("c") shouldBe (PERDA to 0L)
        p.of("d") shouldBe (PERDA to 0L)
    }

    @Test
    fun `16_13 humano vencedor com robo parceiro recebe so a sua parte, a do robo se perde`() {
        val h = mapOf("a" to 0, "c" to 1)
        val p = settleBets(end(GameEndReason.PONTUACAO, 0, h), h.keys, bet)
        p.of("a") shouldBe (PREMIO to 100L) // 2A, nunca o pote inteiro
        p.of("c") shouldBe (PERDA to 0L)
        p.sumOf { it.amount } shouldBe 100L
    }

    @Test
    fun `16_13 no 1x1 o vencedor leva o pote inteiro 2A`() {
        val h = mapOf("a" to 0, "b" to 1)
        val p = settleBets(end(GameEndReason.PONTUACAO, 1, h, sides = listOf(0, 1)), h.keys, bet)
        p.of("b") shouldBe (PREMIO to 100L)
        p.of("a") shouldBe (PERDA to 0L)
    }

    @Test
    fun `18_4 perde a aposta, abandono aos 59,999 s devolve e aos 60 s perde`() {
        val h = mapOf("a" to 0, "b" to 2, "c" to 1)
        val early = settleBets(end(GameEndReason.PONTUACAO, 0, h, listOf(abandon("a", 0, AbandonReason.DESISTIU, 59_999))), h.keys, bet)
        early.of("a") shouldBe (DEVOLUCAO to 50L)
        val late = settleBets(end(GameEndReason.PONTUACAO, 0, h, listOf(abandon("a", 0, AbandonReason.DESISTIU, 60_000))), h.keys, bet)
        late.of("a") shouldBe (PERDA to 0L) // §16.13: no lado vencedor a parte de quem desistiu se perde
        late.of("b") shouldBe (PREMIO to 100L)
    }

    @Test
    fun `18_4 tempo esgotado vale como abandono e perde a aposta depois dos 60 s`() {
        val h = mapOf("a" to 0, "b" to 2)
        val p = settleBets(end(GameEndReason.PONTUACAO, 1, h, listOf(abandon("a", 0, AbandonReason.TEMPO, 300_000))), h.keys, bet)
        p.of("a") shouldBe (PERDA to 0L)
        p.of("b") shouldBe (PERDA to 0L)
    }

    @Test
    fun `18_4 sem punicao quem abandona recebe a aposta de volta`() {
        val h = mapOf("a" to 0, "b" to 2)
        val ev = abandon("a", 0, AbandonReason.DESISTIU, 600_000, Punicao.SEM_PUNICAO)
        settleBets(end(GameEndReason.PONTUACAO, 1, h, listOf(ev)), h.keys, bet).of("a") shouldBe (DEVOLUCAO to 50L)
    }

    @Test
    fun `18_4 1x1 entre humanos, quem abandona perde e o outro leva o pote`() {
        val h = mapOf("a" to 0, "b" to 1)
        val p = settleBets(
            end(GameEndReason.ABANDONO, 1, h, listOf(abandon("a", 0, AbandonReason.DESISTIU, 120_000)), sides = listOf(0, 1)), h.keys, bet,
        )
        p.of("a") shouldBe (PERDA to 0L)
        p.of("b") shouldBe (PREMIO to 100L)
    }

    @Test
    fun `18_4 nunca se joga sem humano, o ultimo humano sai e o lado dele perde, so robos no lado vencedor`() {
        val h = mapOf("a" to 0, "c" to 1)
        val evs = listOf(abandon("c", 1, AbandonReason.DESISTIU, 100_000), abandon("a", 0, AbandonReason.DESISTIU, 200_000))
        val p = settleBets(end(GameEndReason.ABANDONO, 1, h, evs), h.keys, bet)
        p.of("a") shouldBe (PERDA to 0L)
        p.of("c") shouldBe (PERDA to 0L) // abandonou: a parte do assento (agora robo) se perde
    }

    @Test
    fun `18_4 anfitriao encerra, ele sofre a punicao e os demais recebem a aposta de volta, inclusive no 1x1`() {
        val h = mapOf("a" to 0, "b" to 2, "c" to 1)
        val p = settleBets(
            end(GameEndReason.ANFITRIAO_ENCERROU, null, h, listOf(abandon("a", 0, AbandonReason.ANFITRIAO_ENCERROU, 90_000))), h.keys, bet,
        )
        p.of("a") shouldBe (PERDA to 0L)
        p.of("b") shouldBe (DEVOLUCAO to 50L)
        p.of("c") shouldBe (DEVOLUCAO to 50L)
        val one = mapOf("a" to 0, "b" to 1)
        val q = settleBets(
            end(GameEndReason.ANFITRIAO_ENCERROU, null, one, listOf(abandon("a", 0, AbandonReason.ANFITRIAO_ENCERROU, 10_000)), listOf(0, 1)),
            one.keys, bet,
        )
        q.of("a") shouldBe (DEVOLUCAO to 50L) // dentro dos 60 s
        q.of("b") shouldBe (DEVOLUCAO to 50L)
    }

    @Test
    fun `18_4 todos desconectados devolve a todos, e quem desistiu antes mantem a propria punicao`() {
        val h = mapOf("a" to 0, "b" to 2, "c" to 1)
        val evs = listOf(
            abandon("c", 1, AbandonReason.DESISTIU, 120_000),
            abandon("a", 0, AbandonReason.TODOS_DESCONECTADOS, 500_000),
            abandon("b", 2, AbandonReason.TODOS_DESCONECTADOS, 500_000),
        )
        val p = settleBets(end(GameEndReason.TODOS_DESCONECTADOS, null, h, evs), h.keys, bet)
        p.of("a") shouldBe (DEVOLUCAO to 50L)
        p.of("b") shouldBe (DEVOLUCAO to 50L)
        p.of("c") shouldBe (PERDA to 0L)
    }

    @Test
    fun `14_6 quem jogou sem aposta (sem saldo ao iniciar) nada recebe, e erro do servidor devolve`() {
        val h = mapOf("a" to 0, "b" to 2)
        settleBets(end(GameEndReason.PONTUACAO, 0, h), setOf("a"), bet).map { it.accountId } shouldBe listOf("a")
        settleBets(end(GameEndReason.ERRO, null, h), h.keys, bet).map { it.kind }.toSet() shouldBe setOf(DEVOLUCAO)
    }

    @Test
    fun `18_7 operador encerra a mesa travada, todos os humanos recebem a aposta de volta, nenhum e punido`() {
        val h = mapOf("a" to 0, "b" to 2, "c" to 1)
        // Sem abandonments: ninguem foi "culpado" pelo fechamento (diferente de ANFITRIAO_ENCERROU, que registra um).
        val p = settleBets(end(GameEndReason.ENCERRADO_PELO_OPERADOR, null, h), h.keys, bet)
        p.of("a") shouldBe (DEVOLUCAO to 50L)
        p.of("b") shouldBe (DEVOLUCAO to 50L)
        p.of("c") shouldBe (DEVOLUCAO to 50L)
    }

    // ------------------------------------------------------------------------------------------ registro + economia

    private class FakeEconomy(@Volatile var down: Boolean = false) : EconomyClient {
        val holds = CopyOnWriteArrayList<Pair<String, List<String>>>()
        val settles = CopyOnWriteArrayList<Pair<String, List<BetPayout>>>()
        override suspend fun hold(betId: String, amount: Long, accounts: List<String>): Set<String> {
            if (down) throw EconomyUnavailableException("fora")
            holds += betId to accounts
            return accounts.toSet()
        }

        override suspend fun settle(betId: String, payouts: List<BetPayout>) {
            if (down) throw EconomyUnavailableException("fora")
            settles += betId to payouts
        }

        // Boosters (§17.3) nao sao o foco destes testes de aposta: aplica tudo o que for pedido, sem rede.
        override suspend fun debitBoosters(matchId: String, seat: Int, accountId: String, boosterIds: List<String>): Set<String> {
            if (down) throw EconomyUnavailableException("fora")
            return boosterIds.toSet()
        }
    }

    private fun request(seats: List<SeatSpec>, mode: TableMode = TableMode.INDIVIDUAL) =
        CreateMatchRequest("ABC234", rulesOf(mode).copy(bet = 50, punicao = Punicao.PERDE_APOSTA), seats, seed = 7)

    private val humanVsRobot = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Robot(BotProfile.JOVEM, BotLevel.FACIL))

    @Test
    fun `18_2 economia fora do ar ao iniciar, o jogo nao comeca e a retencao e devolvida quando ela volta`() = runBlocking {
        val eco = FakeEconomy(down = true)
        val reg = MatchRegistry(FAST, economy = eco, settleRetryDelaysMs = listOf(20, 20, 20, 20, 20, 20, 20, 20))
        shouldThrow<MatchCreateException> { reg.createWithBets(request(humanVsRobot)) }.status shouldBe 503
        reg.count shouldBe 0
        eco.down = false
        withTimeout(5_000) { while (eco.settles.isEmpty()) delay(10) }
        eco.settles.single().second.single() shouldBe BetPayout("ga", DEVOLUCAO, 50)
        reg.close()
    }

    @Test
    fun `18_2 retem ao iniciar uma vez so e 18_4 desistir nos primeiros 60 s devolve a aposta`() = runBlocking {
        val eco = FakeEconomy()
        val reg = MatchRegistry(FAST, economy = eco, settleRetryDelaysMs = listOf(10))
        val (session, created) = reg.createWithBets(request(humanVsRobot))
        created shouldBe true
        reg.createWithBets(request(humanVsRobot)).second shouldBe false // repetido: nao retem de novo
        eco.holds.size shouldBe 1
        eco.holds.single().first.startsWith("ABC234-") shouldBe true
        val a = TestPlayer(session, "ga")
        a.attach()
        a.resign()
        withTimeout(5_000) { while (eco.settles.isEmpty()) delay(10) }
        val (betId, payouts) = eco.settles.single()
        betId shouldBe eco.holds.single().first
        payouts.single() shouldBe BetPayout("ga", DEVOLUCAO, 50)
        reg.close()
    }

    @Test
    fun `16_13 jogo real ate o fim por pontuacao liquida premio ou perda conforme o vencedor`() = runBlocking {
        val eco = FakeEconomy()
        val reg = MatchRegistry(FAST, economy = eco, settleRetryDelaysMs = listOf(10))
        val seats = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Human("gb", "Beto"))
        val (session, _) = reg.createWithBets(request(seats))
        val a = TestPlayer(session, "ga")
        val b = TestPlayer(session, "gb")
        a.attach()
        b.attach()
        kotlinx.coroutines.coroutineScope {
            launch { a.playToEnd() }
            launch { b.playToEnd() }
        }
        withTimeout(5_000) { while (eco.settles.isEmpty()) delay(10) }
        val winner = session.inspect().winner!!.index
        val payouts = eco.settles.single().second.associate { it.accountId to (it.kind to it.amount) }
        payouts[if (winner == 0) "ga" else "gb"] shouldBe (PREMIO to 100L)
        payouts[if (winner == 0) "gb" else "ga"] shouldBe (PERDA to 0L)
        reg.close()
    }

    @Test
    fun `18_7 operador encerra mesa travada em jogo de verdade, a economia devolve a aposta aos dois humanos`() = runBlocking {
        val eco = FakeEconomy()
        val reg = MatchRegistry(FAST, economy = eco, settleRetryDelaysMs = listOf(10))
        val seats = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Human("gb", "Beto"))
        val (session, _) = reg.createWithBets(request(seats))
        val a = TestPlayer(session, "ga")
        val b = TestPlayer(session, "gb")
        a.attach()
        b.attach()
        session.inspect()
        a.drain()
        b.drain()

        reg.closeMatchByOperator("ABC234", "mesa travada ha 2 horas") shouldBe CloseResult.Closed

        a.drain()
        b.drain()
        a.received.filterIsInstance<com.gtranca.online.contracts.TableClosed>().single().message shouldBe "A mesa foi encerrada pelo suporte."
        a.received.none { it is com.gtranca.online.contracts.SeatsChanged && it.change == com.gtranca.online.contracts.SeatChange.RESIGNED } shouldBe true

        withTimeout(5_000) { while (eco.settles.isEmpty()) delay(10) }
        val payouts = eco.settles.single().second.associate { it.accountId to (it.kind to it.amount) }
        payouts["ga"] shouldBe (DEVOLUCAO to 50L)
        payouts["gb"] shouldBe (DEVOLUCAO to 50L)
        reg.close()
    }
}
