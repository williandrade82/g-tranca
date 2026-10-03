package com.gtranca.ai.hard

import com.gtranca.engine.dealRound
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.viewFor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SampledWorldTest {

    @Test
    fun `vista de um mundo sorteado e igual a do motor, inclusive o registro de 3 vermelhos`() {
        // §3.5 / §6.5 o registro de quem baixou cada 3 vermelho é público e entra na vista dos jogadores simulados
        for (mode in GameMode.entries) {
            val state = (0L until 500L).map { dealRound(mode, Random(it)) }.first { it.redThreeLog.isNotEmpty() }
            for (seat in mode.seats) state.sampledView(seat) shouldBe state.viewFor(seat)
        }
    }
}
