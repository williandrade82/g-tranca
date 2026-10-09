package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * O atalho de validação de `legalActions` (sem montar o estado seguinte) devolve exatamente a mesma lista, na mesma
 * ordem, que a validação de referência por `RoundEngine.step` (§4.3, §5, §6, §8, §9, §11), em milhares de estados de
 * partidas aleatórias nos dois modos.
 */
class LegalActionsFastPathTest {

    private fun states(mode: GameMode, seed: Long): List<RoundState> {
        val random = Random(seed)
        var state = dealRound(mode, random)
        val seen = mutableListOf<RoundState>()
        while (state.phase != Phase.FINISHED && seen.size < 400) {
            seen += state
            val legal = LegalActions.generateReference(state, state.currentSeat, RuleSet.DEFAULT)
            if (legal.isEmpty()) break
            // Prefere baixar e pegar o lixo, para chegar a mãos pequenas, canastras e mortos.
            val melds = legal.filter { it !is Action.Discard && it !is Action.DrawFromStock && it !is Action.DeclineDraw }
            val action = if (melds.isNotEmpty() && random.nextInt(3) != 0) melds.random(random) else legal.random(random)
            state = RoundEngine.apply(state, state.currentSeat, action)
        }
        return seen
    }

    @Test
    fun `atalho e referencia devolvem a mesma lista de jogadas`() {
        var checked = 0
        for (mode in GameMode.entries) {
            for (seed in 1L..30L) {
                for (state in states(mode, seed)) {
                    val seat = state.currentSeat
                    withClue("$mode semente $seed") {
                        LegalActions.generate(state, seat, RuleSet.DEFAULT) shouldBe LegalActions.generateReference(state, seat, RuleSet.DEFAULT)
                    }
                    checked++
                }
            }
        }
        (checked > 2_000) shouldBe true
    }

    @Test
    fun `medicao - tempo do atalho contra a referencia`() {
        val all = GameMode.entries.flatMap { mode -> (100L..110L).flatMap { states(mode, it) } }
        fun time(block: (RoundState) -> Unit): Long {
            repeat(2) { all.forEach(block) } // aquecimento
            val start = System.nanoTime()
            repeat(3) { all.forEach(block) }
            return (System.nanoTime() - start) / 3
        }
        val reference = time { LegalActions.generateReference(it, it.currentSeat, RuleSet.DEFAULT) }
        val fast = time { LegalActions.generate(it, it.currentSeat, RuleSet.DEFAULT) }
        println("legalActions em ${all.size} estados: referência ${reference / 1_000_000} ms, atalho ${fast / 1_000_000} ms")
    }
}
