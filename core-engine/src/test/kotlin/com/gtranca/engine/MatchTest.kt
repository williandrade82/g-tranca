package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MatchTest {

    /**
     * Partida encerrada sem vencedor em que cada lado pontua uma canastra limpa (+200) e seus 3 vermelhos (+100
     * cada, §12.1, pois há canastra); mortos pegos, mãos vazias.
     */
    private fun roundWith(side0RedThrees: String, side1RedThrees: String, mode: GameMode = GameMode.INDIVIDUAL) = round(mode) {
        redThrees(0, side0RedThrees)
        redThrees(1, side1RedThrees)
        meld(0, "4H 5H 6H 7H 8H 9H")
        meld(1, "4S 5S 6S 7S 8S 9S")
        mortoTaken(0, 0)
        mortoTaken(1, 1)
        result = RoundResult.NoWinner
    }

    /** Jogo com totais prévios e a partida atual já encerrada com [finishedRound]. */
    private fun matchAt(totals: List<Int>, target: Int = 3000, finishedRound: com.gtranca.engine.model.RoundState) =
        startMatch(GameMode.INDIVIDUAL, target, Random(1)).copy(totals = totals, currentRound = finishedRound)

    @Test
    fun `iniciar o jogo distribui a primeira partida`() {
        // §2 jogo com múltiplas partidas; §14 pontuação-alvo padrão 3000
        val m = startMatch(GameMode.DUPLAS, random = Random(5))
        m.targetScore shouldBe 3000
        m.totals shouldContainExactly listOf(0, 0)
        m.history.shouldBeEmpty()
        m.roundNumber shouldBe 1
        m.currentRound.phase shouldBe Phase.AWAITING_DRAW
        m.currentRound.mode shouldBe GameMode.DUPLAS
        m.winner shouldBe null
    }

    @Test
    fun `pontuacao-alvo deve ser inteiro positivo`() {
        // §14 pontuação-alvo: número inteiro positivo
        shouldThrow<IllegalArgumentException> { startMatch(GameMode.INDIVIDUAL, 0, Random(1)) }
        shouldThrow<IllegalArgumentException> { startMatch(GameMode.INDIVIDUAL, -100, Random(1)) }
        startMatch(GameMode.INDIVIDUAL, 500, Random(1)).targetScore shouldBe 500
    }

    @Test
    fun `encerrar a partida acumula os pontos e guarda o detalhamento`() {
        // §12 pontuação ao final da partida; §13 total acumulado
        val m = matchAt(listOf(100, 200), finishedRound = roundWith("3H", "3D 3H'")).finishRound()
        m.totals shouldContainExactly listOf(400, 600)
        m.history shouldHaveSize 1
        m.history[0].number shouldBe 1
        m.history[0].result shouldBe RoundResult.NoWinner
        m.history[0].scores.map { it.total } shouldContainExactly listOf(300, 400)
        m.winner shouldBe null
        m.isAwaitingNextRound shouldBe true
    }

    @Test
    fun `nao se encerra partida em andamento nem duas vezes`() {
        val m = startMatch(GameMode.INDIVIDUAL, random = Random(1))
        shouldThrow<IllegalArgumentException> { m.finishRound() }
        val done = matchAt(listOf(0, 0), finishedRound = roundWith("3H", "")).finishRound()
        shouldThrow<IllegalArgumentException> { done.finishRound() }
    }

    @Test
    fun `atingir exatamente o alvo encerra o jogo`() {
        // §13 total maior ou igual (≥) à pontuação-alvo
        val m = matchAt(listOf(2700, 0), finishedRound = roundWith("3H", "")).finishRound()
        m.totals shouldContainExactly listOf(3000, 200)
        m.winner shouldBe Side(0)
        m.isOver shouldBe true
        shouldThrow<IllegalArgumentException> { m.startNextRound() }
    }

    @Test
    fun `abaixo do alvo o jogo continua`() {
        // §13 só termina com algum lado ≥ alvo
        val m = matchAt(listOf(2600, 0), finishedRound = roundWith("3H", "")).finishRound()
        m.totals shouldContainExactly listOf(2900, 200)
        m.winner shouldBe null
        val next = m.startNextRound()
        next.roundNumber shouldBe 2
        next.currentRound.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `dois lados acima do alvo vence o de maior total`() {
        // §13 se mais de um lado atingir o alvo, vence a maior pontuação
        val m = matchAt(listOf(2950, 2990), finishedRound = roundWith("3H 3D", "3H'")).finishRound()
        m.totals shouldContainExactly listOf(3350, 3290)
        m.winner shouldBe Side(0)
    }

    @Test
    fun `empate no maior total acima do alvo joga-se nova partida`() {
        // §13 empate na maior pontuação: nova partida até desempatar
        val tied = matchAt(listOf(2900, 2900), finishedRound = roundWith("3H", "3D")).finishRound()
        tied.totals shouldContainExactly listOf(3200, 3200)
        tied.winner shouldBe null
        tied.isOver shouldBe false
        val next = tied.startNextRound()
        next.roundNumber shouldBe 2
        // a nova partida desempata
        val decided = next.copy(currentRound = roundWith("3H", "")).finishRound()
        decided.winner shouldBe Side(0)
    }

    @Test
    fun `alvo customizado`() {
        // §14 pontuação-alvo configurável
        val m = matchAt(listOf(400, 0), target = 500, finishedRound = roundWith("3H", "")).finishRound()
        m.winner shouldBe Side(0)
    }

    @Test
    fun `totais podem ficar negativos`() {
        // §12.2 penalidades podem deixar o total negativo
        val penalized = round {
            hand(0, "KS QS")
            mortoTaken(0, 1)
            result = RoundResult.NoWinner
        }
        val m = matchAt(listOf(0, 0), finishedRound = penalized).finishRound()
        m.totals shouldContainExactly listOf(-120, 0)
    }

    @Test
    fun `jogo e reprodutivel pela semente inclusive nas partidas seguintes`() {
        // §1 / §4.1 embaralhamento e sorteio da 1ª partida com RNG semeado; depois, rodízio do primeiro jogador
        val a = startMatch(GameMode.DUPLAS, random = Random(99))
        val b = startMatch(GameMode.DUPLAS, random = Random(99))
        a shouldBe b
        a shouldNotBe startMatch(GameMode.DUPLAS, random = Random(100))
        val nextA = a.copy(currentRound = roundWith("3H", "", GameMode.DUPLAS)).finishRound().startNextRound()
        val nextB = b.copy(currentRound = roundWith("3H", "", GameMode.DUPLAS)).finishRound().startNextRound()
        nextA shouldBe nextB
        nextA.currentRound shouldNotBe a.currentRound
        val nextFirst = GameMode.DUPLAS.nextSeat(roundWith("3H", "", GameMode.DUPLAS).firstSeat)
        nextA.currentRound shouldBe dealRound(GameMode.DUPLAS, roundRandom(a.seed, 1), firstSeat = nextFirst)
        a.currentRound shouldBe dealRound(GameMode.DUPLAS, roundRandom(a.seed, 0))
    }

    @Test
    fun `proxima partida comeca pelo jogador seguinte ao que iniciou a anterior`() {
        // §4.1 sorteio só na 1ª partida; depois, o próximo no sentido anti-horário (§4.2) inicia,
        // com ou sem vencedor na partida anterior
        var m = startMatch(GameMode.DUPLAS, random = Random(42))
        val firsts = mutableListOf(m.currentRound.firstSeat)
        repeat(5) {
            val finished = roundWith("3H", "3D", GameMode.DUPLAS).copy(firstSeat = m.currentRound.firstSeat)
            m = m.copy(currentRound = finished).finishRound().startNextRound()
            firsts += m.currentRound.firstSeat
            m.currentRound.currentSeat shouldBe m.currentRound.firstSeat
        }
        firsts.zipWithNext().forEach { (prev, next) -> next shouldBe GameMode.DUPLAS.nextSeat(prev) }
    }

    @Test
    fun `no individual o primeiro jogador alterna mesmo apos batida`() {
        // §4.1 rodízio 0→1→0 (ou 1→0→1), também quando a partida anterior teve vencedor (batida)
        var m = startMatch(GameMode.INDIVIDUAL, random = Random(7))
        val firsts = mutableListOf(m.currentRound.firstSeat)
        repeat(3) {
            val first = m.currentRound.firstSeat
            val finished = roundWith("3H", "").copy(firstSeat = first, result = RoundResult.GoOut(Side(0), Seat(0)))
            m = m.copy(currentRound = finished).finishRound().startNextRound()
            firsts += m.currentRound.firstSeat
        }
        firsts.zipWithNext().forEach { (prev, next) -> next shouldNotBe prev }
        firsts[2] shouldBe firsts[0]
    }

    @Test
    fun `jogar acao atualiza a partida atual`() {
        // §4.3 ações passam pelo RoundEngine
        val m = startMatch(GameMode.INDIVIDUAL, random = Random(3))
        val after = m.play(m.currentRound.currentSeat, Action.DrawFromStock)
        after.currentRound.phase shouldBe Phase.PLAYING
        shouldThrow<IllegalArgumentException> { after.play(after.currentRound.currentSeat, Action.DrawFromStock) }
    }

    @Test
    fun `Match faz ida e volta em JSON`() {
        val m = matchAt(listOf(2600, 10), finishedRound = roundWith("3H", "3D")).finishRound().startNextRound()
        val json = Json.encodeToString(Match.serializer(), m)
        Json.decodeFromString(Match.serializer(), json) shouldBe m
    }
}
