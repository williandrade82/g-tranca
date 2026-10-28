package com.gtranca.ui

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.Tables
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.4 a rota do jogo leva a semente da mesa e os personagens escolhidos, para o jogo usar os que a mesa mostrou. */
class GameRouteTest {
    @Test
    fun `§14_4 a rota guarda a semente, a configuracao e os robos de cada assento`() {
        val config = GameConfig(GameMode.DUPLAS, stake = 50)
        val robots = mapOf(1 to "zen", 2 to "jovem", 3 to "atleta")
        val route = GameRoute.of(config, 7L, robots)
        route.seed shouldBe 7L
        route.toConfig() shouldBe config
        route.robotMap() shouldBe robots
        GameRoute.of(GameConfig()).robotMap() shouldBe emptyMap()
    }

    @Test
    fun `§14_4 a selecao de jogadores leva so a configuracao escolhida`() {
        val config = GameConfig(GameMode.DUPLAS, stake = 150)
        val route = SelectionRoute.of(config)
        route.toConfig() shouldBe config
        route.stake shouldBe 150
    }

    @Test
    fun `§4_1 mesa so com robos - os robos vao para o jogo na posicao relativa ao jogador`() {
        // jogador no assento 2 da escolha de posições: o assento 3 fica à direita dele (1 no jogo), o 0 é o parceiro (2)
        Tables.relativeRobots(GameMode.DUPLAS, Seat(2), mapOf(0 to "zen", 1 to "jovem", 3 to "atleta")) shouldBe
            mapOf(2 to "zen", 3 to "jovem", 1 to "atleta")
        Tables.relativeRobots(GameMode.INDIVIDUAL, Seat(1), mapOf(0 to "zen")) shouldBe mapOf(1 to "zen")
    }
}
