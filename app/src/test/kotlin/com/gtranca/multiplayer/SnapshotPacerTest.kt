package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.DealGuess
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Guess
import com.gtranca.engine.model.Phase
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

    /**
     * Vista inicial de uma partida de duplas (a do assento 0), sem jogadas. `RuleSet.DEFAULT` tem
     * `dealGuessBonusEnabled` ligado (§3.6/§14.2): a vista "crua" do controlador nasceria em
     * [Phase.DEAL_GUESS_HAND], mas o que estes testes sempre quiseram dizer com "partida nova, sem jogadas" é uma
     * partida que já passou dessa etapa — por isso a fase é fixada em [Phase.AWAITING_DRAW] aqui. Ver o teste §3.6
     * para o caso em que a vista chega mesmo numa fase de palpite (dedicado, com fixtures próprias).
     */
    private val base: GameSnapshot = GameController(
        GameConfig(GameMode.DUPLAS, Difficulty.FACIL),
        gameSeed = 7,
        players = GameMode.DUPLAS.seats.map { BotSeatPlayer(createBot(Difficulty.FACIL, Random(it.index.toLong()))) },
    ).state.value.let {
        it.copy(stage = Stage.PLAYING, roundNumber = 1, view = it.view.copy(redThreeLog = emptyList(), phase = Phase.AWAITING_DRAW))
    }

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

    /**
     * §3.6 relato 2026-11-14 (mesa multijogador local, convidado): `awaitStaging` detecta "partida nova" (o número
     * mudou) e espera a animação da distribuição ([dealWaitMillis]) — mas, com a distribuição certeira ligada
     * (padrão de hoje), a 1ª vista de uma partida nova chega ainda na fase de palpite ([Phase.DEAL_GUESS_HAND]),
     * **antes** de a distribuição de verdade começar (`GameViewModel.absorb`: `dealing` só vira `true` ao SAIR da
     * fase de palpite, `leavingGuessPhases`, nunca durante ela). O convidado não tem controlador local para
     * terminar essa animação (não existe nenhuma: a mão ainda não foi mesmo distribuída na tela) — a tela nunca
     * chama `dealAnimationDone(1)` nesse instante, então `awaitStaging` ficava bloqueada pelo [dealWaitMillis]
     * inteiro (vários segundos) antes de mostrar a vista do palpite, e de novo a cada palpite seguinte da mesma
     * partida. Isso é a "pausa indevida antes do jogo começar" do relato: a cada partida (não só a 1ª do jogo),
     * com o convidado nunca respondendo ao sinal certo.
     */
    @Test
    fun `§3_6 a vista do palpite da distribuicao certeira nao espera a animacao da distribuicao`() = runTest {
        val deal1Hand = base.copy(view = base.view.copy(phase = Phase.DEAL_GUESS_HAND))
        val deal1Morto = deal1Hand.copy(view = deal1Hand.view.copy(phase = Phase.DEAL_GUESS_MORTO))
        // Sai da fase de palpite: é só aqui que a distribuição de verdade é animada na tela (§3.6).
        val deal1Draw = deal1Morto.copy(view = deal1Morto.view.copy(phase = Phase.AWAITING_DRAW))
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 5_000 }, { 0 }) { shown += it }
        // `initial` já está na tela (nunca passa por `publish`, ver KDoc de `run`): só a 1ª espera (antes do laço)
        // conta aqui, e ela não pode travar nos 5s do `dealWaitMillis` só porque o palpite da mão é "partida nova".
        val job = launch { pacer.run(deal1Hand) }
        runCurrent()
        shown shouldBe emptyList()

        // O palpite do morto (ainda a mesma partida, ainda em fase de palpite): nada para animar, sem espera.
        pacer.offer(deal1Morto)
        runCurrent()
        shown shouldBe listOf(deal1Morto)

        // Sai da fase de palpite: esta vista em si é publicada na hora (como sempre: `publish` vem antes da espera
        // em `run`) — é a PRÓXIMA vista que fica retida até a distribuição de verdade terminar na tela.
        pacer.offer(deal1Draw)
        runCurrent()
        shown shouldBe listOf(deal1Morto, deal1Draw)

        // A 1ª jogada de verdade (depois da distribuição animada): só aparece depois de `dealAnimationDone`, como
        // qualquer outra partida nova — aqui sim a espera deve valer.
        val humanTurn = deal1Draw.copy(humanLegal = listOf(Action.DrawFromStock), humanRequestId = 1)
        pacer.offer(humanTurn)
        advanceTimeBy(4_000)
        shown shouldBe listOf(deal1Morto, deal1Draw)
        pacer.dealAnimationDone(1)
        runCurrent()
        shown shouldBe listOf(deal1Morto, deal1Draw, humanTurn)
        job.cancel()
    }

    /**
     * §3.6 mesma ideia de [SnapshotPacer.presentationWaitMillis] para a troca de 3 vermelho (`§6_5` acima): a vista
     * seguinte, que pode já expor a própria vez de quem acabou de palpitar (mortoDealerSeat == firstSeat em
     * individual, §3.6 `dealGuessSeats`), não "pula a mesa para o fim" antes da reação do palpite ser reconhecida
     * ([SnapshotPacer.guessRevealDone]) — o mesmo invariante do KDoc de [SnapshotPacer.run]. Sem o aviso, a espera
     * tem um limite (rede de segurança: a mesa nunca trava para sempre).
     */
    @Test
    fun `§3_6 a vista seguinte ao palpite resolvido espera o reconhecimento da reacao, com limite`() = runTest {
        val dealerSeat = Seat(0)
        val mortoDealerSeat = Seat(1)
        // A mão já foi resolvida por outro assento (sempre o caso em individual, §3.6 `dealGuessSeats`): este
        // teste é sobre o palpite do MORTO, cuja ação já transiciona para AWAITING_DRAW na mesma vez (`beginTurn`).
        val guessHand = base.copy(view = base.view.copy(phase = Phase.DEAL_GUESS_MORTO, dealGuess = DealGuess(dealerSeat, mortoDealerSeat, hand = Guess(0, 44, true))))
        val guessResolved = guessHand.copy(view = guessHand.view.copy(phase = Phase.AWAITING_DRAW, dealGuess = guessHand.view.dealGuess!!.copy(morto = Guess(0, 22, true))))
        // A vez do próprio `mortoDealerSeat` (individual, §3.6): chega como vista seguinte, SEM nenhuma jogada de
        // outro assento em `turnEvents` (nenhuma pausa de acompanhamento a esperar).
        val ownTurn = guessResolved.copy(humanLegal = listOf(Action.DrawFromStock), humanRequestId = 9)
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }, guessRevealWaitMillis = { 5_000 }) { shown += it }
        val job = launch { pacer.run(guessHand) }
        runCurrent()
        shown shouldBe emptyList()

        pacer.offer(guessResolved)
        pacer.offer(ownTurn)
        runCurrent()
        // O palpite resolvido aparece na hora (a reação some sozinha ou espera o toque, mas a VISTA em si publica
        // de imediato, como qualquer outra) — é a PRÓXIMA vista que fica retida até o reconhecimento.
        shown shouldBe listOf(guessResolved)

        advanceTimeBy(4_999)
        shown shouldBe listOf(guessResolved)

        pacer.guessRevealDone(1, hand = false)
        runCurrent()
        shown shouldBe listOf(guessResolved, ownTurn)
        job.cancel()
    }

    /** Sem o toque (app em segundo plano etc.): a mesa segue depois do limite, nunca trava para sempre. */
    @Test
    fun `§3_6 sem o reconhecimento da reacao, a vista seguinte aparece apos o limite de seguranca`() = runTest {
        val dealerSeat = Seat(0)
        val mortoDealerSeat = Seat(1)
        val guessHand = base.copy(view = base.view.copy(phase = Phase.DEAL_GUESS_MORTO, dealGuess = DealGuess(dealerSeat, mortoDealerSeat, hand = Guess(0, 44, true))))
        val guessResolved = guessHand.copy(view = guessHand.view.copy(phase = Phase.AWAITING_DRAW, dealGuess = guessHand.view.dealGuess!!.copy(morto = Guess(0, 22, true))))
        val ownTurn = guessResolved.copy(humanLegal = listOf(Action.DrawFromStock), humanRequestId = 9)
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }, guessRevealWaitMillis = { 5_000 }) { shown += it }
        val job = launch { pacer.run(guessHand) }
        pacer.offer(guessResolved)
        pacer.offer(ownTurn)
        advanceTimeBy(5_001)
        shown shouldBe listOf(guessResolved, ownTurn)
        job.cancel()
    }

    /**
     * §3.6 reconexão (§16.7): a 1ª vista de uma tela nova já chega com o palpite resolvido de antes (sem "anterior"
     * para comparar) — nunca espera um reconhecimento que a tela não vai mandar (sem replay da animação do palpite).
     */
    @Test
    fun `§3_6 reconexao com o palpite ja resolvido nao espera reconhecimento nenhum`() = runTest {
        val dealGuess = DealGuess(Seat(0), Seat(1), hand = Guess(0, 22, true), morto = Guess(0, 22, true))
        val resumed = base.copy(view = base.view.copy(dealGuess = dealGuess))
        val next = resumed.copy(humanLegal = listOf(Action.DrawFromStock), humanRequestId = 1)
        val shown = mutableListOf<GameSnapshot>()
        val pacer = SnapshotPacer({ 0 }, { 0 }, { 0 }, guessRevealWaitMillis = { 5_000 }) { shown += it }
        val job = launch { pacer.run(resumed) }
        pacer.offer(next)
        runCurrent()
        shown shouldBe listOf(next)
        job.cancel()
    }
}
