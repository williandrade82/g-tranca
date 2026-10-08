package com.gtranca.ai.hard

import com.gtranca.engine.dealRound
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.determinize
import com.gtranca.engine.model.Seat
import com.gtranca.engine.viewFor
import io.kotest.matchers.ints.shouldBeGreaterThan
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

    @Test
    fun `vista do mundo sorteado copia turnsBegun e unsettledMortoSeats do estado`() {
        // §3.5 / §9.4 sem a cópia, os padrões ("todos já começaram") escondem o 3 vermelho na mão de quem não começou
        val base = fullScenario(hand = "KS KD 7C 5C 9H 9D JS").copy(turnsBegun = 1, unsettledMortoSeats = listOf(Seat(1)))
        val view = base.sampledView(Seat(0))
        view.turnsBegun shouldBe 1
        view.unsettledMortoSeats shouldBe listOf(Seat(1))
        view.hasBegunFirstTurn(Seat(1)) shouldBe false
    }

    @Test
    fun `determinizacao poe 3 vermelho oculto na mao de quem ainda nao comecou, e so nele`() {
        // §3.5 o assento 1 ainda não teve o início da 1ª vez: os 3 vermelhos não vistos podem estar na mão dele
        val notBegun = fullScenario(hand = "KS KD 7C 5C 9H 9D JS").copy(turnsBegun = 1)
        val begun = notBegun.copy(turnsBegun = 2)
        var withRedThree = 0
        for (seed in 0L until 200L) {
            val sampled = notBegun.viewFor(Seat(0)).determinize(Random(seed))
            sampled.handOf(Seat(0)).none { it.isRedThree } shouldBe true // a vez é do assento 0
            if (sampled.handOf(Seat(1)).any { it.isRedThree }) withRedThree++
            sampled.sampledView(Seat(1)).hasBegunFirstTurn(Seat(1)) shouldBe false
            // quem já começou a 1ª vez nunca tem 3 vermelho na mão
            begun.viewFor(Seat(0)).determinize(Random(seed)).handOf(Seat(1)).none { it.isRedThree } shouldBe true
        }
        withRedThree shouldBeGreaterThan 0
    }
}
