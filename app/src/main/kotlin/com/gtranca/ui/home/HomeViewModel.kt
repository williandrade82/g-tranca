package com.gtranca.ui.home

import com.gtranca.data.GameResult
import com.gtranca.data.StatsRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.Coins
import com.gtranca.engine.Wallet
import kotlinx.coroutines.flow.catch
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.gtranca.game.WriteQueue
import com.gtranca.data.SavedGameStore
import com.gtranca.data.SavedGame
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.Difficulty
import com.gtranca.engine.model.GameMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado da tela inicial: jogo salvo para continuar e "Novo jogo" (que abre as mesas, §14.4). */
data class HomeUiState(
    /** Jogo salvo que pode ser continuado, ou `null`. */
    val saved: SavedSummary? = null,
    /** "Novo jogo" com jogo salvo: pedindo confirmação (o salvo será perdido). */
    val confirmNewGame: Boolean = false,
    /** Já leu o jogo salvo (antes disso, "Novo jogo"/"Continuar" ficam desativados). */
    val loadedSaved: Boolean = true,
    /** Apagando o salvo antigo para começar um novo jogo (botões desativados). */
    val starting: Boolean = false,
    /** Novo jogo liberado (o salvo antigo já foi apagado): a tela abre as mesas e avisa [HomeViewModel.onStartHandled]. */
    val pendingNewGame: Boolean = false,
    /** §14.6 saldo da carteira e se o ganho diário de hoje está disponível. */
    val balance: Int = Coins.INITIAL_BALANCE,
    val canClaimDaily: Boolean = false,
    /** §16.12 sessão multijogador guardada para "Continuar mesa", ou `null`. */
    val session: SessionSummary? = null,
) {
    /** Pode agir nos botões de jogo (tudo carregado, nada em andamento). */
    val ready: Boolean get() = loadedSaved && !starting && !pendingNewGame
}

/** §16.12 o que o botão "Continuar mesa" mostra da sessão guardada: o anfitrião e o assento (nunca o token). */
data class SessionSummary(val hostName: String, val seat: Int, val wasHost: Boolean) {
    companion object {
        fun of(session: com.gtranca.data.MultiplayerSession) = SessionSummary(session.hostName, session.seat + 1, session.wasHost)
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
    /** §14.6 aposta do jogo salvo (perdida se ele for abandonado por um novo). */
    val stake: Int = 0,
) {
    companion object {
        fun of(game: SavedGame): SavedSummary? {
            val difficulty = Difficulty.entries.firstOrNull { it.id == game.config.difficultyId } ?: return null
            // O humano senta no assento 0, do lado 0.
            return SavedSummary(game.config.mode, difficulty, game.match.roundNumber, game.match.totals[0], game.match.totals[1], game.gameId, game.config.stake)
        }
    }
}

/** Início: mostra o jogo salvo para continuar e pede confirmação antes de um novo jogo apagá-lo. */
class HomeViewModel(
    private val savedGames: SavedGameStore? = null,
    /** Fila de gravações do app: ler e apagar o salvo depois das gravações pendentes do jogo que acabou de sair. */
    private val writes: WriteQueue? = null,
    /** Estatísticas: abandonar o jogo salvo por um novo conta como derrota por desistência. */
    private val stats: StatsRepository? = null,
    /** §14.6 carteira local (saldo e ganho diário). */
    private val wallet: WalletRepository? = null,
    /** §16.12 a sessão multijogador guardada (espaço separado do jogo salvo contra robôs). */
    private val sessions: com.gtranca.data.MultiplayerSessionStore? = null,
    /** Dia de hoje (dias desde 1970, data do aparelho); injetável nos testes. */
    private val today: () -> Long = { java.time.LocalDate.now().toEpochDay() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(loadedSaved = savedGames == null))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var lastWallet = Wallet()

    init {
        refresh()
        wallet?.let { repo ->
            viewModelScope.launch {
                repo.wallet.catch { emit(Wallet()) }.collect { w ->
                    lastWallet = w
                    publishWallet()
                }
            }
        }
    }

    private fun publishWallet() = _uiState.update { it.copy(balance = lastWallet.balance, canClaimDaily = lastWallet.canClaimDaily(today())) }

    /** §14.6 resgata o ganho diário (um por dia); o saldo e o botão se atualizam pela carteira. */
    fun onClaimDaily() {
        val repo = wallet ?: return
        viewModelScope.launch {
            val claim = suspend { repo.claimDaily(today()) }
            if (writes != null) writes.run { claim() } else safely { claim() }
        }
    }

    /** Relê o jogo salvo (ao voltar para a tela, ele pode ter mudado ou terminado). */
    fun refresh() {
        publishWallet() // o dia pode ter virado desde a última vez
        sessions?.let { repo ->
            viewModelScope.launch {
                val session = safely { repo.load() }
                _uiState.update { it.copy(session = session?.let(SessionSummary::of)) }
            }
        }
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

    /** "Novo jogo": com jogo salvo, pede confirmação e devolve `false`; senão `true` (abrir as mesas). */
    fun onNewGame(): Boolean {
        val state = _uiState.value
        if (!state.ready) return false
        if (state.saved != null) {
            _uiState.update { it.copy(confirmNewGame = true) }
            return false
        }
        return true
    }

    /**
     * Confirmou que o jogo salvo será perdido: apaga-o primeiro (assim a morte do processo nunca retoma o antigo) e
     * só então publica [HomeUiState.pendingNewGame].
     */
    fun onConfirmNewGame() {
        val state = _uiState.value
        _uiState.update { it.copy(confirmNewGame = false) }
        if (!state.ready) return
        _uiState.update { it.copy(starting = true) }
        val abandoned = state.saved
        viewModelScope.launch {
            // O jogo salvo abandonado conta como desistência (derrota) antes de ser apagado.
            if (abandoned != null && stats != null) {
                val record = suspend { stats.recordBet(abandoned.gameId, abandoned.mode, abandoned.difficulty.id, GameResult.RESIGNATION, stake = abandoned.stake, payout = 0) }
                if (writes != null) writes.run { record() } else safely { record() }
            }
            savedGames?.let { store -> if (writes != null) writes.run { store.clear() } else safely { store.clear() } }
            _uiState.update { it.copy(starting = false, saved = null, pendingNewGame = true) }
        }
    }

    /** A tela abriu as mesas depois de [HomeUiState.pendingNewGame]. */
    fun onStartHandled() = _uiState.update { it.copy(pendingNewGame = false) }

    fun onDismissNewGame() = _uiState.update { it.copy(confirmNewGame = false) }

    /** E/S que falha não derruba a tela: vale como "nada". */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
