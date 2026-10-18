package com.gtranca.game

import com.gtranca.data.FileSavedGameStore
import com.gtranca.data.SavedConfig
import com.gtranca.data.SavedGame
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.startMatch
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §14.5 o nome usado no sorteio dos personagens fica no jogo salvo ("Continuar" mostra os mesmos). */
class SavedPlayerNameTest {
    private val game = SavedGame(
        gameId = "g",
        config = SavedConfig(GameMode.INDIVIDUAL, "facil", 1000),
        gameSeed = 1,
        match = startMatch(GameMode.INDIVIDUAL, 1000, Random(1)),
        events = emptyList(),
        playerName = "Ana",
    )

    @Test
    fun `§14_5 o nome do sorteio e gravado e lido`() {
        FileSavedGameStore.decode(FileSavedGameStore.encode(game))!!.playerName shouldBe "Ana"
        FileSavedGameStore.decode(FileSavedGameStore.encode(game))!!.toRestored().playerName shouldBe "Ana"
    }

    @Test
    fun `§14_5 jogo salvo sem o campo (versao antiga) le o nome como nulo`() {
        val json = FileSavedGameStore.encode(game.copy(playerName = null))
        FileSavedGameStore.decode(json)!!.playerName shouldBe null
        // arquivo antigo: sem a chave
        val withoutKey = json.replace(Regex(""",?\s*"playerName"\s*:\s*null"""), "")
        FileSavedGameStore.decode(withoutKey)!!.playerName shouldBe null
    }

    @Test
    fun `§14_5 savedGameOf grava o nome informado`() {
        val snapshot = SaveSnapshot(game.match, emptyList(), emptyList())
        savedGameOf("g", GameConfig(GameMode.INDIVIDUAL), 1, snapshot, "Bia").playerName shouldBe "Bia"
    }
}
