package com.gtranca.engine

import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.shouldBeOk
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Partidas aleatórias jogadas só com ações de `legalActions`, com RNG semeado.
 * Invariantes: toda ação listada passa em `validate`; `apply` nunca lança; 104 cartas sem duplicatas;
 * mão vazia só para quem bateu (§8); toda partida termina dentro do limite de jogadas.
 */
class RoundSimulationPropertyTest {

    private data class Outcome(val state: RoundState, val actions: Int, val deadEnd: Boolean)

    /** Política aleatória: prefere baixar/acrescentar a descartar, e às vezes pega o lixo. */
    private fun choose(legal: List<Action>, random: Random): Action {
        val melds = legal.filter { it is Action.CreateMeld || it is Action.AddToMeld }
        val takes = legal.filterIsInstance<Action.TakeDiscardPile>()
        return when {
            takes.isNotEmpty() && random.nextInt(3) == 0 -> takes.random(random)
            melds.isNotEmpty() && random.nextInt(4) != 0 -> melds.random(random)
            else -> legal.filterNot { it is Action.TakeDiscardPile }.ifEmpty { legal }.random(random)
        }
    }

    private fun play(mode: GameMode, seed: Long, maxActions: Int = 5_000): Outcome {
        val random = Random(seed)
        var state = dealRound(mode, random)
        checkInvariants(state)
        var count = 0
        while (state.phase != Phase.FINISHED && count < maxActions) {
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            // ninguém fora da vez tem ação
            mode.seats.filter { it != seat }.forEach { RoundEngine.legalActions(state, it) shouldBe emptyList() }
            if (legal.isEmpty()) return Outcome(state, count, deadEnd = true)
            // as ações listadas já passam por step na geração; confere uma amostra via validate
            legal.shuffled(random).take(3).forEach { RoundEngine.validate(state, seat, it).shouldBeOk() }
            state = RoundEngine.apply(state, seat, choose(legal, random))
            count++
            checkInvariants(state)
        }
        return Outcome(state, count, deadEnd = false)
    }

    private fun checkInvariants(state: RoundState) {
        // §1 104 cartas, sem duplicatas
        state.allCards() shouldContainExactlyInAnyOrder Deck.standard()
        // §8 mão vazia só para quem bateu (§11)
        state.mode.seats.filter { state.handOf(it).isEmpty() }.forEach { seat ->
            val result = state.result
            (result is RoundResult.GoOut && result.seat == seat) shouldBe true
        }
        // §6.5 nenhuma mão guarda 3 vermelho
        state.hands.flatten().none { it.isRedThree } shouldBe true
        // §3.5 / §6.5 registro público: por lado, as cartas registradas são os 3 vermelhos do lado, na mesma ordem
        withClue("registro de 3 vermelhos") { state.redThreeLogViolation() shouldBe null }
    }

    @Test
    fun `partidas aleatorias respeitam as invariantes e terminam`() {
        val stats = mutableMapOf<String, Int>()
        for (mode in GameMode.entries) {
            for (seed in 0L until 100L) {
                val outcome = play(mode, seed)
                val key = when {
                    outcome.deadEnd -> "deadEnd"
                    outcome.state.result is RoundResult.GoOut -> "goOut"
                    outcome.state.result == RoundResult.NoWinner -> "noWinner"
                    else -> "limit"
                }
                stats.merge("$mode/$key", 1, Int::plus)
                // toda partida termina (batida ou sem vencedor) dentro do limite, sem beco sem saída (§8)
                withClue("$mode semente $seed: $key em ${outcome.state.phase}") {
                    (key == "goOut" || key == "noWinner") shouldBe true
                }
                // §serialização: o estado final faz ida e volta em JSON
                val json = Json.encodeToString(RoundState.serializer(), outcome.state)
                Json.decodeFromString(RoundState.serializer(), json) shouldBe outcome.state
            }
        }
        println("Simulação: $stats")
    }
}
