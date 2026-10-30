package com.gtranca.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.LobbyLimits
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.TempoDeJogada
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: OnlineException) {
    Result.failure(e)
}

private fun Throwable.problem(): OnlineProblem = (this as? OnlineException)?.toProblem() ?: OnlineProblem.UNEXPECTED

// ---------------------------------------------------------------------------------------------------------------
// 3.21 Jogar online
// ---------------------------------------------------------------------------------------------------------------

enum class OnlineHomeStatus { LOADING, NO_SERVER, READY, ERROR }

data class OnlineHomeUiState(
    val status: OnlineHomeStatus = OnlineHomeStatus.LOADING,
    /** Nome do convidado (Etapa A: o nome do Perfil no momento em que o convidado foi criado). */
    val name: String = "",
    val problem: OnlineProblem? = null,
)

/**
 * Porta do online (3.21, §18.1). Sem servidor no Perfil: NO_SERVER. Com servidor: garante o convidado provisório no
 * servidor (cria se ainda não existe) e fica READY; falha de rede/versão vira ERROR com "Tentar de novo".
 *
 * @param accountFor conta para o servidor informado, ou `null` se o texto não é um endereço válido.
 */
class OnlineHomeViewModel(
    private val settings: SettingsRepository,
    private val accountFor: (String) -> OnlineAccount?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineHomeUiState())
    val uiState: StateFlow<OnlineHomeUiState> = _uiState.asStateFlow()

    fun retry() = load()

    /** Lê tudo de novo (ao voltar do Perfil, com o servidor possivelmente trocado, ou de uma sala). */
    fun refresh() = load()

    private var loadJob: Job? = null

    init {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.value = OnlineHomeUiState()
        loadJob = viewModelScope.launch {
            val server = settings.settings.catch { emit(com.gtranca.data.Settings()) }.first().onlineServer
            val account = server.takeIf { it.isNotBlank() }?.let(accountFor)
            if (account == null) {
                _uiState.value = OnlineHomeUiState(OnlineHomeStatus.NO_SERVER)
                return@launch
            }
            attempt { account.guest() }.fold(
                onSuccess = { _uiState.value = OnlineHomeUiState(OnlineHomeStatus.READY, name = it.name) },
                onFailure = { _uiState.value = OnlineHomeUiState(OnlineHomeStatus.ERROR, problem = it.problem()) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 3.23 Criar mesa online
// ---------------------------------------------------------------------------------------------------------------

enum class OnlineRulesChoice { CLASSICA, PAULISTA }

data class OnlineCreateUiState(
    val mode: GameMode = GameMode.INDIVIDUAL,
    val rules: OnlineRulesChoice = OnlineRulesChoice.CLASSICA,
    val targetText: String = DEFAULT_TARGET.toString(),
    val skillBoosters: Boolean = true,
    val tempo: TempoDeJogada = TempoDeJogada.INDETERMINADO,
    val punicao: Punicao = Punicao.SEM_PUNICAO,
    val robotLevel: BotLevel = BotLevel.FACIL,
    val creating: Boolean = false,
    val problem: OnlineProblem? = null,
    /** Id da mesa criada: a tela abre a sala e chama [OnlineCreateViewModel.onCreatedHandled]. */
    val createdId: String? = null,
) {
    val target: Int? get() = targetText.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..LobbyLimits.MAX_TARGET_SCORE }
    val canCreate: Boolean get() = !creating && target != null

    companion object {
        const val DEFAULT_TARGET = 3000
    }
}

/** Criar mesa (3.23, §18.2, §18.4). Etapa A: aposta fixa em 0 (sem economia online, §18.5). */
class OnlineCreateViewModel(
    private val account: OnlineAccount,
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineCreateUiState())
    val uiState: StateFlow<OnlineCreateUiState> = _uiState.asStateFlow()

    /** O jogador já mexeu: a leitura tardia da última montagem não sobrescreve. */
    private var touched = false

    init {
        // Modo e pontuação-alvo partem da última montagem da mesa (3.2); só leitura.
        settings?.let { repo ->
            viewModelScope.launch {
                val setup = repo.settings.catch { }.first().tableSetup ?: return@launch
                if (!touched) {
                    _uiState.update {
                        it.copy(mode = setup.mode, targetText = setup.target.ifBlank { OnlineCreateUiState.DEFAULT_TARGET.toString() })
                    }
                }
            }
        }
    }

    private fun edit(change: (OnlineCreateUiState) -> OnlineCreateUiState) {
        touched = true
        _uiState.update { change(it).copy(problem = null) }
    }

    fun onMode(mode: GameMode) = edit { it.copy(mode = mode) }
    fun onRules(rules: OnlineRulesChoice) = edit { it.copy(rules = rules) }
    fun onTarget(text: String) = edit { it.copy(targetText = text.filter(Char::isDigit).take(6)) }
    fun onSkillBoosters(allowed: Boolean) = edit { it.copy(skillBoosters = allowed) }
    fun onTempo(tempo: TempoDeJogada) = edit { it.copy(tempo = tempo) }
    fun onPunicao(punicao: Punicao) = edit { it.copy(punicao = punicao) }
    fun onRobotLevel(level: BotLevel) = edit { it.copy(robotLevel = level) }

    fun onCreatedHandled() = _uiState.update { it.copy(createdId = null) }

    fun onCreate() {
        val state = _uiState.value
        if (state.creating) return
        val target = state.target ?: return _uiState.update { it.copy(problem = OnlineProblem.INVALID_TARGET) }
        _uiState.update { it.copy(creating = true, problem = null) }
        viewModelScope.launch {
            val rules = TableRules(
                mode = if (state.mode == GameMode.DUPLAS) TableMode.DUPLAS else TableMode.INDIVIDUAL,
                rulesJson = RULES_JSON.encodeToString(RuleSet.serializer(), state.rules.ruleSet()),
                targetScore = target,
                bet = 0,
                skillBoostersAllowed = state.skillBoosters,
                tempoDeJogada = state.tempo,
                punicao = state.punicao,
                robotLevel = state.robotLevel,
            )
            attempt { account.call { api, guest -> api.createTable(guest.token, rules) } }.fold(
                onSuccess = { table -> _uiState.update { it.copy(creating = false, createdId = table.id) } },
                onFailure = { e -> _uiState.update { it.copy(creating = false, problem = e.problem()) } },
            )
        }
    }

    private fun OnlineRulesChoice.ruleSet(): RuleSet = when (this) {
        OnlineRulesChoice.CLASSICA -> RuleSet.CLASSICA
        OnlineRulesChoice.PAULISTA -> RuleSet.PAULISTA
    }

    private companion object {
        val RULES_JSON = Json { encodeDefaults = true }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 3.24 Entrar numa mesa
// ---------------------------------------------------------------------------------------------------------------

data class OnlineJoinUiState(
    val idText: String = "",
    val loading: Boolean = false,
    val problem: OnlineProblem? = null,
    /** O convite (a mesa consultada), antes de ocupar o lugar. */
    val invite: TableView? = null,
    /** Já ocupou o lugar: a tela abre a sala. */
    val joinedId: String? = null,
) {
    val canGo: Boolean get() = !loading && idText.isNotBlank()
}

/** Entrar por id (3.24, §18.2): consulta a mesa, mostra o convite e só entra quando o jogador aceita ("Ocupar este lugar"). */
class OnlineJoinViewModel(private val account: OnlineAccount) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineJoinUiState())
    val uiState: StateFlow<OnlineJoinUiState> = _uiState.asStateFlow()

    fun onId(text: String) = _uiState.update {
        it.copy(idText = text.filter(Char::isLetterOrDigit).take(TableId.LENGTH).uppercase(), problem = null, invite = null)
    }

    fun onJoinedHandled() = _uiState.update { it.copy(joinedId = null) }

    /** "Entrar": id fora do formato é recusado aqui, sem chamar o servidor. */
    fun onGo() {
        val id = TableId.normalize(_uiState.value.idText)
            ?: return _uiState.update { it.copy(problem = OnlineProblem.INVALID_ID, invite = null) }
        _uiState.update { it.copy(loading = true, problem = null, invite = null) }
        viewModelScope.launch {
            attempt { account.call { api, guest -> api.table(guest.token, id) } }.fold(
                onSuccess = { table ->
                    val problem = when (table.state) {
                        TableState.ABERTA -> if (table.seats.any { it.kind == SeatKind.VAGO }) null else OnlineProblem.TABLE_FULL
                        TableState.EM_JOGO -> OnlineProblem.ALREADY_STARTED
                        TableState.ENCERRADA -> OnlineProblem.TABLE_NOT_FOUND
                    }
                    // Quem já está sentado (voltando à mesa) entra direto, sem o convite.
                    _uiState.update { it.copy(loading = false, problem = problem, invite = table.takeIf { problem == null }) }
                },
                onFailure = { e -> _uiState.update { it.copy(loading = false, problem = e.problem()) } },
            )
        }
    }

    /** "Ocupar este lugar": entrar de fato (primeiro lugar vago). */
    fun onOccupy() {
        val invite = _uiState.value.invite ?: return
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            attempt { account.call { api, guest -> api.join(guest.token, invite.id) } }.fold(
                onSuccess = { table -> _uiState.update { it.copy(loading = false, joinedId = table.id) } },
                onFailure = { e -> _uiState.update { it.copy(loading = false, problem = e.problem(), invite = null) } },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 3.23 Mesa online (a sala)
// ---------------------------------------------------------------------------------------------------------------

/** Por que a sala acabou para este jogador (a tela volta a 3.21 mostrando o aviso). */
enum class OnlineRoomEnd { CLOSED, NOT_FOUND, REMOVED, LEFT }

data class OnlineRoomUiState(
    val table: TableView? = null,
    /** O convidado é o anfitrião da mesa. */
    val isHost: Boolean = false,
    /** Id do convidado (para achar o próprio lugar). */
    val myId: String = "",
    val busy: Boolean = false,
    val problem: OnlineProblem? = null,
    /** A última consulta falhou por rede; segue tentando sozinho. */
    val connectionLost: Boolean = false,
    val confirmClose: Boolean = false,
    val ended: OnlineRoomEnd? = null,
) {
    val started: Boolean get() = table?.state == TableState.EM_JOGO
    val canStart: Boolean get() = isHost && !busy && table?.state == TableState.ABERTA && table.seats.none { it.kind == SeatKind.VAGO }
    val mySeat: Int get() = table?.seats?.indexOfFirst { it.guestId == myId } ?: -1
}

/**
 * Sala da mesa (3.23, §18.2). A lista de lugares é atualizada consultando o servidor a cada [pollMs] (o lobby não envia
 * eventos); a consulta também mantém a mesa aberta no servidor.
 */
class OnlineRoomViewModel(
    private val account: OnlineAccount,
    val tableId: String,
    private val pollMs: Long = 2_000,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineRoomUiState())
    val uiState: StateFlow<OnlineRoomUiState> = _uiState.asStateFlow()

    private var poller: Job? = null

    /** Já vi o meu lugar na mesa (para distinguir "ainda não entrei" de "fui removido"). */
    private var wasSeated = false

    init {
        poller = viewModelScope.launch {
            val guest = attempt { account.guest() }.getOrNull()
            if (guest != null) _uiState.update { it.copy(myId = guest.id) }
            while (isActive && _uiState.value.ended == null) {
                refresh()
                delay(pollMs)
            }
        }
    }

    /** Uma consulta ao servidor agora. */
    suspend fun refresh() {
        attempt { account.call { api, guest -> guest to api.table(guest.token, tableId) } }.fold(
            onSuccess = { (guest, table) -> applyTable(table, guest.id, connected = true) },
            onFailure = { e -> onRefreshFailed(e) },
        )
    }

    private fun onRefreshFailed(e: Throwable) {
        val problem = e.problem()
        _uiState.update {
            when (problem) {
                OnlineProblem.TABLE_NOT_FOUND -> it.copy(ended = OnlineRoomEnd.NOT_FOUND)
                OnlineProblem.NETWORK, OnlineProblem.MAINTENANCE -> it.copy(connectionLost = true)
                else -> it.copy(problem = problem)
            }
        }
    }

    private fun applyTable(table: TableView, myId: String, connected: Boolean) {
        val mine = table.seats.any { it.guestId == myId }
        if (mine) wasSeated = true
        _uiState.update {
            val ended = when {
                table.state == TableState.ENCERRADA -> OnlineRoomEnd.CLOSED
                wasSeated && !mine && table.state == TableState.ABERTA -> OnlineRoomEnd.REMOVED
                else -> it.ended
            }
            it.copy(
                table = table,
                myId = myId,
                isHost = table.hostId == myId,
                connectionLost = !connected,
                busy = false,
                ended = ended,
            )
        }
    }

    private fun act(block: suspend (com.gtranca.online.client.LobbyApi, String) -> TableView, after: (TableView) -> Unit = {}) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            attempt { account.call { api, guest -> guest to block(api, guest.token) } }.fold(
                onSuccess = { (guest, table) ->
                    applyTable(table, guest.id, connected = true)
                    after(table)
                },
                onFailure = { e -> _uiState.update { it.copy(busy = false, problem = e.problem()) } },
            )
        }
    }

    /** Anfitrião: lugar vago vira robô. */
    fun onSeatRobot(seat: Int) = act({ api, token -> api.seatRobot(token, tableId, seat) })

    /** Anfitrião: tira um jogador da mesa. */
    fun onRemove(seat: Int) = act({ api, token -> api.removeHuman(token, tableId, seat) })

    /** Anfitrião: inicia o jogo (todos os lugares preenchidos). */
    fun onStart() = act({ api, token -> api.start(token, tableId) })

    /** Convidado: sai da mesa (o lugar fica vago). */
    fun onLeave() = act({ api, token -> api.leave(token, tableId) }, after = {
        _uiState.update { it.copy(ended = OnlineRoomEnd.LEFT) }
    })

    fun onAskClose() = _uiState.update { it.copy(confirmClose = true) }
    fun onDismissClose() = _uiState.update { it.copy(confirmClose = false) }

    /** Anfitrião: encerra a mesa (depois da confirmação). */
    fun onConfirmClose() {
        _uiState.update { it.copy(confirmClose = false) }
        act({ api, token -> api.close(token, tableId) })
    }

    fun onProblemShown() = _uiState.update { it.copy(problem = null) }

    override fun onCleared() {
        poller?.cancel()
    }
}
