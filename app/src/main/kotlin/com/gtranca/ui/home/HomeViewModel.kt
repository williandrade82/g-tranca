package com.gtranca.ui.home

import androidx.lifecycle.ViewModel
import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.parseTargetScore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado da tela inicial (§14): modo, dificuldade (padrão médio) e pontuação-alvo (padrão 3000). */
data class HomeUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val targetText: String = RuleSet.DEFAULT.defaultTargetScore.toString(),
) {
    /** Pontuação-alvo válida (inteiro positivo), ou `null`. */
    val targetScore: Int? get() = parseTargetScore(targetText)

    val targetError: Boolean get() = targetScore == null

    /** Fase 1: só o modo individual está disponível na interface. */
    val canStart: Boolean get() = targetScore != null && mode == GameMode.INDIVIDUAL

    fun toConfig(): GameConfig? = targetScore?.takeIf { canStart }?.let { GameConfig(mode, difficulty, it) }
}

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onDifficultyChange(difficulty: Difficulty) = _uiState.update { it.copy(difficulty = difficulty) }

    /** Aceita só dígitos (o teclado é numérico); a validação do inteiro positivo fica em [HomeUiState.targetScore]. */
    fun onTargetChange(text: String) = _uiState.update { it.copy(targetText = text.filter(Char::isDigit).take(9)) }
}
