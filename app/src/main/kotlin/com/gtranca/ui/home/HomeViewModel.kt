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

/** Problema na pontuação-alvo digitada. */
enum class TargetError {
    /** Não é um inteiro positivo (vazio, zero, sinal, letras…). */
    INVALID,

    /** Só dígitos, mas maior que o maior inteiro aceito. */
    TOO_LARGE,
}

/** Estado da tela inicial (§14): modo, dificuldade (padrão médio) e pontuação-alvo (padrão 3000). */
data class HomeUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val difficulty: Difficulty = Difficulty.MEDIO,
    val targetText: String = RuleSet.DEFAULT.defaultTargetScore.toString(),
) {
    /** Pontuação-alvo válida (inteiro positivo), ou `null`. */
    val targetScore: Int? get() = parseTargetScore(targetText)

    /** O texto é mostrado como digitado (nunca truncado); o erro explica por que não vale. */
    val targetError: TargetError?
        get() {
            if (targetScore != null) return null
            val digits = targetText.trim()
            val onlyDigits = digits.isNotEmpty() && digits.all(Char::isDigit)
            return if (onlyDigits && digits.trimStart('0').isNotEmpty() && digits.toIntOrNull() == null) {
                TargetError.TOO_LARGE
            } else {
                TargetError.INVALID
            }
        }

    val canStart: Boolean get() = targetScore != null

    fun toConfig(): GameConfig? = targetScore?.let { GameConfig(mode, difficulty, it) }

    companion object {
        const val MAX_TARGET: Int = Int.MAX_VALUE
    }
}

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onModeChange(mode: GameMode) = _uiState.update { it.copy(mode = mode) }

    fun onDifficultyChange(difficulty: Difficulty) = _uiState.update { it.copy(difficulty = difficulty) }

    fun onTargetChange(text: String) = _uiState.update { it.copy(targetText = text) }
}
