package com.gtranca.data

import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Base da partida salva: o `Match` faz ida e volta pelo JSON no meio de uma partida. */
class MatchJsonTest {

    @Test
    fun `match em andamento faz ida e volta pelo JSON`() {
        for (mode in GameMode.entries) {
            var match = startMatch(mode, targetScore = 1500, random = Random(3))
            repeat(40) {
                val round = match.currentRound
                if (round.phase == Phase.FINISHED) return@repeat
                val seat = round.currentSeat
                match = match.play(seat, RoundEngine.legalActions(round, seat).last())
            }
            val json = Json.encodeToString(Match.serializer(), match)
            Json.decodeFromString(Match.serializer(), json) shouldBe match
        }
    }
}
