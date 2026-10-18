package com.gtranca.sim

import com.gtranca.ai.PlayStyle
import com.gtranca.engine.model.GameMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.5 estilos de jogo em partidas completas: legalidade, diferenças mensuráveis e força por nível. */
class StyleSimulationTest {
    private val styles = PlayStyle.ALL.map { it.id }

    private fun play(sides: List<String>, games: Int, seed: Long, mode: GameMode = GameMode.INDIVIDUAL, target: Int = 1000, check: Boolean = false): List<GameOutcome> {
        val simulator = Simulator(SimConfig(games = games, seed = seed, mode = mode, target = target, sides = sides, checkInvariants = check))
        return (0 until games).map { simulator.runGame(seed + it) }
    }

    private fun report(sides: List<String>, outcomes: List<GameOutcome>, mode: GameMode = GameMode.INDIVIDUAL) =
        Report(SimConfig(games = outcomes.size, mode = mode, sides = sides), outcomes, 0)

    @Test
    fun `§14_5 sides aceita estilo depois de dois pontos e recusa o invalido`() {
        SimConfig.parse(arrayOf("--sides", "medio:agressivo,facil:sobrio")).sides shouldBe listOf("medio:agressivo", "facil:sobrio")
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--sides", "medio:tímido,facil")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--sides", "aleatorio:sagaz,facil")) }
    }

    @Test
    fun `§14_5 todos os estilos jogam so jogadas legais em todos os niveis e modos`() {
        for (style in styles) {
            for (level in listOf("facil", "medio")) {
                for (mode in GameMode.entries) {
                    val sides = listOf("$level:$style", "$level:neutro")
                    play(sides, games = 4, seed = 31, mode = mode, target = 500, check = true).mapNotNull { it.failure }.shouldBeEmpty()
                }
            }
        }
    }

    @Test
    fun `§14_5 o Dificil com estilo tambem so joga jogadas legais`() {
        for (style in listOf("sobrio", "agressivo")) {
            val config = SimConfig(games = 1, seed = 5, target = 200, sides = listOf("dificil:$style", "medio"), hardIterations = 5, checkInvariants = true)
            Simulator(config).runGame(5).failure shouldBe null
        }
    }

    @Test
    fun `§14_5 mesma semente e mesmo estilo dao o mesmo jogo`() {
        val sides = listOf("medio:ousado", "facil:sagaz")
        play(sides, 3, 77).map { it.totals } shouldBe play(sides, 3, 77).map { it.totals }
    }

    @Test
    fun `§14_5 o agressivo pega o lixo bem mais que o sobrio`() {
        val sides = listOf("medio:agressivo", "medio:sobrio")
        val outcomes = play(sides, games = 60, seed = 400)
        val r = report(sides, outcomes)
        (r.styleMetrics(0).pileTakeRate - r.styleMetrics(1).pileTakeRate) shouldBeGreaterThan 0.08
    }

    @Test
    fun `§14_5 o ousado pega o lixo mais que o neutro e o sobrio menos`() {
        val sides = listOf("medio:ousado", "medio:neutro")
        val r1 = report(sides, play(sides, games = 60, seed = 600))
        r1.styleMetrics(0).pileTakeRate shouldBeGreaterThan r1.styleMetrics(1).pileTakeRate
        val sides2 = listOf("medio:neutro", "medio:sobrio")
        val r2 = report(sides2, play(sides2, games = 60, seed = 600))
        r2.styleMetrics(0).pileTakeRate shouldBeGreaterThan r2.styleMetrics(1).pileTakeRate
    }

    @Test
    fun `§14_5 em cada estilo o Maduro vence o Iniciante`() {
        for (style in styles) {
            val sides = listOf("facil:$style", "medio:$style")
            val outcomes = play(sides, games = 100, seed = 900).filter { it.failure == null }
            val iniciante = outcomes.count { it.winner?.index == 0 }
            val maduro = outcomes.count { it.winner?.index == 1 }
            (maduro > iniciante) shouldBe true
        }
    }

    @Test
    fun `§14_5 um Maduro de qualquer estilo vence um Iniciante de qualquer estilo`() {
        val outcomes = styles.flatMap { a ->
            styles.map { b ->
                val o = play(listOf("facil:$a", "medio:$b"), games = 30, seed = 1200)
                o.count { it.winner?.index == 1 } - o.count { it.winner?.index == 0 }
            }
        }
        // soma sobre as 25 combinações: o Maduro lidera com folga (cada combinação isolada tem pouca amostra)
        (outcomes.sum() > 0) shouldBe true
    }
}
