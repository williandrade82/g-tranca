package com.gtranca.ui.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §13.1, §14.6 o que o modal de desistência mostra das apostas. */
class ResignStakesTest {
    @Test
    fun `§14_6 todos apostam o valor da mesa e cada vencedor recebe o dobro`() {
        ResignStakes.of(100, 2) shouldBe ResignStakes(100, 200, 200)
        ResignStakes.of(50, 4) shouldBe ResignStakes(50, 200, 100)
        ResignStakes.of(0, 4) shouldBe null
    }
}
