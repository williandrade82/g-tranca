package com.gtranca

import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import com.gtranca.engine.viewFor
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** O :app enxerga o motor e os bots, e os testes locais rodam com JUnit 5. */
class ModuleWiringTest {

    @Test
    fun `bot de cada dificuldade escolhe uma acao legal na primeira jogada`() {
        val round = startMatch(GameMode.INDIVIDUAL, random = Random(1)).currentRound
        val seat = round.currentSeat
        val legal = RoundEngine.legalActions(round, seat)
        for (difficulty in Difficulty.entries) {
            legal shouldContain createBot(difficulty, Random(2)).chooseAction(round.viewFor(seat), legal)
        }
    }
}
