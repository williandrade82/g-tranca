package com.gtranca.ui.home

import com.gtranca.data.GameResult
import com.gtranca.data.StatsRepository
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import com.gtranca.game.WriteQueue
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
    /** Já leu o jogo salvo e o último modo (antes disso, "Novo jogo"/"Continuar" ficam desativados). */
    val loadedSaved: Boolean = true,
    val loadedMode: Boolean = true,
    /** Apagando o salvo antigo para começar um novo jogo (botões desativados). */
    val starting: Boolean = false,
    /** Jogo novo pronto para abrir (o salvo antigo já foi apagado): a tela navega e avisa [HomeViewModel.onStartHandled]. */
    val pendingStart: GameConfig? = null,
) {
    /** Pode agir nos botões de jogo (tudo carregado, nada em andamento). */
    val ready: Boolean get() = loadedSaved && loadedMode && !starting && pendingStart == null

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

    val canStart: Boolean get() = targetScore != null && ready

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
    /** Identificador do jogo salvo (as estatísticas contam cada jogo uma vez). */
    val gameId: String = "",
) {
    companion object {
        fun of(game: SavedGame): SavedSummary? {
            val difficulty = Difficulty.entries.firstOrNull { it.id == game.config.difficultyId } ?: return null
            // O humano senta no assento 0, do lado 0.
            return SavedSummary(game.config.mode, difficulty, game.match.roundNumber, game.match.totals[0], game.match.totals[1], game.gameId)
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
    /** Fila de gravações do app: ler e apagar o salvo depois das gravações pendentes do jogo que acabou de sair. */
    private val writes: WriteQueue? = null,
    /** Estatísticas: abandonar o jogo salvo por um novo conta como derrota por desistência. */
    private val stats: StatsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(loadedSaved = savedGames == null, loadedMode = settings == null))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu no modo nesta tela: a preferência salva não o sobrescreve. */
    private var modeTouched = false

    init {
        settings?.let { repo ->
            viewModelScope.launch {
                // Preferências ilegíveis: modo padrão (nunca derruba o app).
                val lastMode = repo.settings.map { it.lastMode }.catch { emit(GameMode.INDIVIDUAL) }.first()
                _uiState.update { if (modeTouched) it.copy(loadedMode = true) else it.copy(mode = lastMode, loadedMode = true) }
            }
        }
        refresh()
    }

    /** Relê o jogo salvo (ao voltar para a tela, ele pode ter mudado ou terminado). */
    fun refresh() {
        val store = savedGames ?: return
        _uiState.update { it.copy(loadedSaved = false) }
        viewModelScope.launch {
            val game = if (writes != null) writes.read { store.load() } else safely { store.load() }
            val summary = game?.let(SavedSummary::of)
            _uiState.update {
                it.copy(saved = summary, confirmNewGame = it.confirmNewGame && summary != null, loadedSaved = true)
            }
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
        if (!state.ready) return null
        if (state.saved != null) {
            _uiState.update { it.copy(confirmNewGame = true) }
            return null
        }
        return start(state)
    }

    /**
     * Confirmou que o jogo salvo será perdido: apaga-o primeiro (assim a morte do processo nunca retoma o antigo) e
     * só então publica [HomeUiState.pendingStart].
     */
    fun onConfirmNewGame() {
        val state = _uiState.value
        _uiState.update { it.copy(confirmNewGame = false) }
        if (!state.ready) return
        val config = start(state) ?: return
        _uiState.update { it.copy(starting = true) }
        val abandoned = state.saved
        viewModelScope.launch {
            // O jogo salvo abandonado conta como desistência (derrota) antes de ser apagado.
            if (abandoned != null && stats != null) {
                val record = suspend { stats.record(abandoned.gameId, abandoned.mode, abandoned.difficulty.id, GameResult.RESIGNATION) }
                if (writes != null) writes.run { record() } else safely { record() }
            }
            savedGames?.let { store -> if (writes != null) writes.run { store.clear() } else safely { store.clear() } }
            _uiState.update { it.copy(starting = false, saved = null, pendingStart = config) }
        }
    }

    /** A tela abriu o jogo de [HomeUiState.pendingStart]. */
    fun onStartHandled() = _uiState.update { it.copy(pendingStart = null) }

    fun onDismissNewGame() = _uiState.update { it.copy(confirmNewGame = false) }

    private fun start(state: HomeUiState): GameConfig? {
        val config = state.toConfig() ?: return null
        settings?.let { repo ->
            if (writes != null) writes.enqueue { repo.setLastMode(config.mode) } else viewModelScope.launch { safely { repo.setLastMode(config.mode) } }
        }
        return config
    }

    /** E/S que falha não derruba a tela: vale como "nada". */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
