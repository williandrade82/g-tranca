package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §17.3 o jogo salvo guarda os boosters da partida em andamento (já debitados) e o jogo retomado os devolve. */
class SavedBoostersTest {
    private val config = GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
    private val snapshot = SaveSnapshot(startMatch(GameMode.INDIVIDUAL, 3000, Random(1)), emptyList())

    @Test
    fun `§17_3 savedGameOf grava os boosters e toRestored os devolve`() {
        val saved = savedGameOf("g", config, 1L, snapshot, boosters = listOf("b1", "b3"))
        saved.boosters shouldBe listOf("b1", "b3")
        saved.toRestored().boosters shouldBe listOf("b1", "b3")
    }

    @Test
    fun `§17_3 sem boosters o jogo salvo fica como antes`() {
        val saved = savedGameOf("g", config, 1L, snapshot)
        saved.boosters shouldBe emptyList()
        saved.boostersConfirmed shouldBe true
        saved.toRestored().boosters shouldBe emptyList()
    }
}
