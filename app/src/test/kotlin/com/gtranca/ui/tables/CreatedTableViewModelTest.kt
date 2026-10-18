package com.gtranca.ui.tables

import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.PersonaGenerator
import com.gtranca.game.Tables
import com.gtranca.ui.GameRoute
import com.gtranca.ui.NewTableRoute
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** §14.4 mesa criada pelo jogador: mostra os personagens sorteados, permite sortear outros e começa com eles. */
class CreatedTableViewModelTest {
    private val duplas = GameConfig(GameMode.DUPLAS, Difficulty.DIFICIL, 2000, RuleSet.PAULISTA)

    @Test
    fun `§14_4 a mesa criada traz os personagens sorteados com a semente, no nivel da dificuldade`() {
        val vm = CreatedTableViewModel(duplas, seed = 123)
        val table = vm.table.value
        table.id shouldBe Tables.CREATED_TABLE_ID
        table.seed shouldBe 123
        table.players shouldBe PersonaGenerator.opponents(123, 3, Difficulty.DIFICIL)
        table.toConfig() shouldBe duplas
    }

    @Test
    fun `§14_4 sortear de novo troca os personagens e a semente e mantem a configuracao`() {
        val vm = CreatedTableViewModel(duplas, seed = 5)
        val before = vm.table.value
        val seen = mutableSetOf(before.players)
        repeat(10) {
            vm.reroll()
            vm.table.value.toConfig() shouldBe duplas
            vm.table.value.players shouldBe PersonaGenerator.opponents(vm.table.value.seed, 3, Difficulty.DIFICIL)
            seen += vm.table.value.players
        }
        vm.table.value.seed shouldNotBe before.seed
        (seen.size > 5) shouldBe true
    }

    @Test
    fun `§14_4 individual traz um personagem`() {
        CreatedTableViewModel(GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 1500), seed = 9).table.value.players.size shouldBe 1
    }

    @Test
    fun `§14_4 a rota da mesa criada guarda configuracao e semente e o jogo comeca com ela`() {
        val route = NewTableRoute.of(duplas, 777)
        route.toConfig() shouldBe duplas
        route.seed shouldBe 777
        // o jogo começa com a semente da mesa mostrada
        GameRoute.of(route.toConfig(), route.seed).seed shouldBe 777
    }
}
