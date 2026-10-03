package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Jogos completos (§13) com ações aleatórias de `legalActions` e alvo pequeno: sempre terminam com
 * vencedor, os totais batem com a soma dos detalhamentos (§12) e nenhuma partida trava.
 */
class MatchSimulationPropertyTest {

    private fun choose(legal: List<Action>, random: Random): Action {
        val melds = legal.filter { it is Action.CreateMeld || it is Action.AddToMeld }
        val takes = legal.filterIsInstance<Action.TakeDiscardPile>()
        return when {
            takes.isNotEmpty() && random.nextInt(3) == 0 -> takes.random(random)
            melds.isNotEmpty() && random.nextInt(4) != 0 -> melds.random(random)
            else -> legal.filterNot { it is Action.TakeDiscardPile }.ifEmpty { legal }.random(random)
        }
    }

    private fun playMatch(mode: GameMode, seed: Long, target: Int): Match {
        val random = Random(seed)
        var match = startMatch(mode, target, random)
        var rounds = 0
        while (!match.isOver) {
            var actions = 0
            while (match.currentRound.phase != Phase.FINISHED) {
                val seat = match.currentRound.currentSeat
                val legal = RoundEngine.legalActions(match.currentRound, seat)
                withClue("$mode semente $seed partida ${match.roundNumber}: sem ações (beco sem saída)") {
                    legal.isNotEmpty() shouldBe true
                }
                match = match.play(seat, choose(legal, random))
                // §3.5 / §6.5 registro público de 3 vermelhos coerente com a mesa
                withClue("registro de 3 vermelhos") { match.currentRound.redThreeLogViolation() shouldBe null }
                actions++
                withClue("limite de ações por partida") { (actions < 5_000) shouldBe true }
            }
            match = match.finishRound()
            rounds++
            withClue("limite de partidas por jogo") { (rounds < 200) shouldBe true }
            if (!match.isOver) match = match.startNextRound()
        }
        return match
    }

    @Test
    fun `jogos aleatorios terminam com vencedor e totais consistentes`() {
        val stats = mutableMapOf<String, Int>()
        // alvo pequeno (muitos jogos) e alvo maior (vários partidas por jogo, encadeamento e empates)
        val configs = listOf(300 to 25L, 1500 to 8L)
        for ((target, seeds) in configs) for (mode in GameMode.entries) {
            for (seed in 0L until seeds) {
                val match = playMatch(mode, seed, target = target)
                val winner = match.winner!!
                // §12 totais = soma dos detalhamentos de cada partida
                mode.sides.forEach { side ->
                    match.totals[side.index] shouldBe match.history.sumOf { it.scores[side.index].total }
                }
                // §13 vencedor atingiu o alvo e tem a maior pontuação, sem empate
                (match.totals[winner.index] >= target) shouldBe true
                mode.sides.filter { it != winner }.forEach { other ->
                    (match.totals[winner.index] > match.totals[other.index]) shouldBe true
                }
                Json.decodeFromString(Match.serializer(), Json.encodeToString(Match.serializer(), match)) shouldBe match
                stats.merge("alvo$target/$mode/jogos", 1, Int::plus)
                stats.merge("alvo$target/$mode/partidas", match.history.size, Int::plus)
                stats.merge("alvo$target/$mode/maxPartidas", match.history.size, ::maxOf)
                stats.merge("alvo$target/$mode/vitoriasLado${winner.index}", 1, Int::plus)
            }
        }
        println("Simulação de jogos: $stats")
    }
}
