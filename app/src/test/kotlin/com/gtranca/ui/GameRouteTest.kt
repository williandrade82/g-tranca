package com.gtranca.ui

import com.gtranca.game.GameConfig
import com.gtranca.game.Tables
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.4 a rota do jogo leva a semente da mesa escolhida, para o jogo usar os personagens que a mesa mostrou. */
class GameRouteTest {
    @Test
    fun `§14_4 a rota guarda a semente da mesa e a configuracao`() {
        val table = Tables.botTables(5).first()
        val route = GameRoute.of(table.toConfig(), table.seed)
        route.seed shouldBe table.seed
        route.toConfig() shouldBe table.toConfig()
    }

    @Test
    fun `§14_4 mesa criada pelo jogador nao traz semente (sorteia ao criar)`() {
        GameRoute.of(GameConfig()).seed shouldBe null
    }
}
