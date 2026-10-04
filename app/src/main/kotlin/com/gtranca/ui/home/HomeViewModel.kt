package com.gtranca.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.gtranca.data.SettingsRepository
import com.gtranca.data.SavedGameStore
import com.gtranca.data.SavedGame
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.game.isAsciiDigits
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
    /** Jogo salvo que pode ser continuado, ou `null`. */
    val saved: SavedSummary? = null,
    /** "Novo jogo" com jogo salvo: pedindo confirmação (o salvo será perdido). */
    val confirmNewGame: Boolean = false,
) {
    /** Pontuação-alvo válida (inteiro positivo), ou `null`. */
    val targetScore: Int? get() = parseTargetScore(targetText)

    /** O texto é mostrado como digitado (nunca truncado); o erro explica por que não vale. */
    val targetError: TargetError?
        get() {
            if (targetScore != null) return null
            val digits = targetText.trim()
            val onlyDigits = digits.isAsciiDigits()
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

/**
 * Resumo do jogo salvo para o botão "Continuar": modo, dificuldade, partida e placar (o lado do humano primeiro).
 * Nada da semente nem das cartas.
 */
data class SavedSummary(
    val mode: GameMode,
    val difficulty: Difficulty,
    val roundNumber: Int,
    val ownTotal: Int,
    val otherTotal: Int,
) {
    companion object {
        fun of(game: SavedGame): SavedSummary? {
            val difficulty = Difficulty.entries.firstOrNull { it.id == game.config.difficultyId } ?: return null
            // O humano senta no assento 0, do lado 0.
            return SavedSummary(game.config.mode, difficulty, game.match.roundNumber, game.match.totals[0], game.match.totals[1])
        }
    }
}

/**
 * Início (§14): modo pré-selecionado com o último escolhido; dificuldade e alvo com os padrões. Mostra o jogo salvo
 * para continuar e pede confirmação antes de um novo jogo apagá-lo.
 */
class HomeViewModel(
    private val settings: SettingsRepository? = null,
    private val savedGames: SavedGameStore? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu no modo nesta tela: a preferência salva não o sobrescreve. */
    private var modeTouched = false

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                val lastMode = repo.settings.first().lastMode
                if (!modeTouched) _uiState.update { it.copy(mode = lastMode) }
            }
        }
        refresh()
    }

    /** Relê o jogo salvo (ao voltar para a tela, ele pode ter mudado ou terminado). */
    fun refresh() {
        val store = savedGames ?: return
        viewModelScope.launch {
            val summary = store.load()?.let(SavedSummary::of)
            _uiState.update { it.copy(saved = summary, confirmNewGame = it.confirmNewGame && summary != null) }
        }
    }

    fun onModeChange(mode: GameMode) {
        modeTouched = true
        _uiState.update { it.copy(mode = mode) }
    }

    fun onDifficultyChange(difficulty: Difficulty) = _uiState.update { it.copy(difficulty = difficulty) }

    fun onTargetChange(text: String) = _uiState.update { it.copy(targetText = text) }

    /**
     * "Novo jogo": com jogo salvo, pede confirmação e devolve `null`; senão devolve a configuração para começar (e
     * lembra o modo, §14).
     */
    fun onNewGame(): GameConfig? {
        val state = _uiState.value
        if (state.saved != null) {
            _uiState.update { it.copy(confirmNewGame = true) }
            return null
        }
        return start(state)
    }

    /** Confirmou que o jogo salvo será perdido: devolve a configuração para começar. */
    fun onConfirmNewGame(): GameConfig? {
        _uiState.update { it.copy(confirmNewGame = false) }
        return start(_uiState.value)
    }

    fun onDismissNewGame() = _uiState.update { it.copy(confirmNewGame = false) }

    private fun start(state: HomeUiState): GameConfig? {
        val config = state.toConfig() ?: return null
        settings?.let { repo -> viewModelScope.launch { repo.setLastMode(config.mode) } }
        return config
    }
}
