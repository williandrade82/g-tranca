package com.gtranca.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.TableBoosters
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §17.4 o jogo salvo guarda os boosters de cada assento (e as reações usadas) e o jogo retomado os devolve. */
class SavedBoostersTest {
    private val config = GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
    private val snapshot = SaveSnapshot(startMatch(GameMode.INDIVIDUAL, 3000, Random(1)), emptyList())

    @Test
    fun `§17_4 savedGameOf grava os boosters por assento e toRestored os devolve`() {
        val table = TableBoosters.single(0, listOf("b1", "b3")).useReaction(0)!!
        val saved = savedGameOf("g", config, 1L, snapshot, tableBoosters = table)
        saved.tableBoosters shouldBe table
        saved.toRestored().tableBoosters shouldBe table
        saved.toRestored().tableBoosters.of(0).reactionsLeft shouldBe 2
    }

    @Test
    fun `§17_4 sem boosters o jogo salvo fica como antes`() {
        val saved = savedGameOf("g", config, 1L, snapshot)
        saved.tableBoosters shouldBe TableBoosters()
        saved.toRestored().tableBoosters shouldBe TableBoosters()
    }
}
