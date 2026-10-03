package com.gtranca.ui.home

import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.parseTargetScore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HomeViewModelTest {

    @Test
    fun `padroes de §14 - medio e alvo 3000`() {
        val state = HomeViewModel().uiState.value
        state.difficulty shouldBe Difficulty.MEDIO
        state.targetScore shouldBe 3000
        state.canStart shouldBe true
        state.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000)
    }

    @Test
    fun `alvo deve ser inteiro positivo`() {
        val vm = HomeViewModel()
        vm.onTargetChange("0")
        vm.uiState.value.targetError shouldBe true
        vm.uiState.value.canStart shouldBe false
        vm.uiState.value.toConfig().shouldBeNull()
        vm.onTargetChange("")
        vm.uiState.value.targetError shouldBe true
        vm.onTargetChange("15a0")
        vm.uiState.value.targetText shouldBe "150"
        vm.onTargetChange("1000")
        vm.onDifficultyChange(Difficulty.DIFICIL)
        vm.uiState.value.toConfig() shouldBe GameConfig(GameMode.INDIVIDUAL, Difficulty.DIFICIL, 1000)
    }

    @Test
    fun `leitura da pontuacao alvo`() {
        parseTargetScore(" 2500 ") shouldBe 2500
        parseTargetScore("-5").shouldBeNull()
        parseTargetScore("99999999999").shouldBeNull()
        parseTargetScore("1.5").shouldBeNull()
    }
}
