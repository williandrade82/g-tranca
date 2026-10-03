package com.gtranca.sim

import com.gtranca.engine.model.GameMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SimulatorTest {

    @Test
    fun `le os argumentos do CLI`() {
        val config = SimConfig.parse(
            arrayOf("--games", "7", "--seed", "-3", "--mode", "duplas", "--target", "500", "--sides", "facil,aleatorio", "--check-invariants"),
        )
        config shouldBe SimConfig(
            games = 7, seed = -3, mode = GameMode.DUPLAS, target = 500,
            sides = listOf("facil", "aleatorio"), checkInvariants = true,
        )
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--mode", "trio")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--sides", "facil")) }
        shouldThrow<IllegalArgumentException> { SimConfig.parse(arrayOf("--xyz")) }
    }

    @Test
    fun `jogos curtos terminam sem falhas nos dois modos`() {
        for (mode in GameMode.entries) {
            val config = SimConfig(games = 4, seed = 1, mode = mode, target = 600, checkInvariants = true)
            val simulator = Simulator(config)
            val outcomes = (0 until config.games).map { simulator.runGame(config.seed + it) }
            outcomes.mapNotNull { it.failure } shouldBe emptyList()
            outcomes.forEach { it.winner shouldNotBe null }
            Report(config, outcomes, elapsedMillis = 1).render().contains("Falhas: nenhuma") shouldBe true
        }
    }

    @Test
    fun `mesma semente reproduz o mesmo jogo`() {
        val simulator = Simulator(SimConfig(target = 600, sides = listOf("facil", "aleatorio")))
        simulator.runGame(99) shouldBe simulator.runGame(99)
    }

    @Test
    fun `travamento vira falha com a semente do jogo`() {
        val outcome = Simulator(SimConfig(maxActionsPerRound = 3)).runGame(5)
        outcome.failure?.kind shouldBe FailureKind.ACTION_LIMIT
        outcome.failure?.gameSeed shouldBe 5L
    }
}
