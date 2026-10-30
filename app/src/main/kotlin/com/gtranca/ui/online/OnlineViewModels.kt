package com.gtranca.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.SettingsRepository
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
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

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: OnlineException) {
    Result.failure(e)
}

private fun Throwable.problem(): OnlineProblem = (this as? OnlineException)?.toProblem() ?: OnlineProblem.UNEXPECTED

/**
 * Posições de duplas (§16.2, §18.2): assentos 0 e 2 = Dupla 1, 1 e 3 = Dupla 2. Devolve (dupla, posição na dupla), de 1.
 */
fun duplaPosition(seat: Int): Pair<Int, Int> = (seat % 2 + 1) to (seat / 2 + 1)

/** Ordem de exibição: Dupla 1 – 1, Dupla 1 – 2, Dupla 2 – 1, Dupla 2 – 2. */
val DUPLA_ORDER = listOf(0, 2, 1, 3)

// ---------------------------------------------------------------------------------------------------------------
// 3.21 Jogar online (lista de mesas do servidor)
// ---------------------------------------------------------------------------------------------------------------

enum class OnlineHomeStatus { LOADING, NO_SERVER, READY, ERROR }

data class OnlineHomeUiState(
    val status: OnlineHomeStatus = OnlineHomeStatus.LOADING,
    /** Nome do convidado (Etapa A: o nome do Perfil no momento em que o convidado foi criado). */
    val name: String = "",
    val myId: String = "",
    val problem: OnlineProblem? = null,
    /** Mesas do servidor: abertas primeiro, depois em jogo (§18.2). `null` = ainda não chegou a 1ª lista. */
    val tables: List<TableView>? = null,
    val onlinePlayers: Int = 0,
    /** A última consulta da lista falhou; segue tentando sozinho. */
    val listProblem: OnlineProblem? = null,
    /** Mesa tocada: mostra as opções e "Ocupar este lugar" antes de entrar. */
    val selected: TableView? = null,
    val joining: Boolean = false,
    /** Já entrou: a tela abre a sala e chama [OnlineHomeViewModel.onJoinedHandled]. */
    val joinedId: String? = null,
) {
    /** Mesa aberta em que eu já estou sentado (voltar à sala em vez de ocupar outro lugar). */
    val myTable: TableView? get() = tables?.firstOrNull { t -> t.state == TableState.ABERTA && t.seats.any { it.guestId == myId } }
}

/** Mesa em que dá para entrar: aberta e com lugar livre, ou aberta e já com o meu lugar. */
fun TableView.canEnter(myId: String): Boolean =
    state == TableState.ABERTA && (seats.any { it.kind == SeatKind.VAGO } || seats.any { it.guestId == myId })

/**
 * Porta do online (3.21, §18.1, §18.2). Sem servidor no Perfil: NO_SERVER. Com servidor: garante o convidado provisório
 * e fica READY, consultando a lista de mesas a cada [pollMs]; falha de rede/versão vira ERROR com "Tentar de novo".
 *
 * @param accountFor conta para o servidor informado, ou `null` se o texto não é um endereço válido.
 */
class OnlineHomeViewModel(
    private val settings: SettingsRepository,
    private val pollMs: Long = 3_000,
    private val accountFor: (String) -> OnlineAccount?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineHomeUiState())
    val uiState: StateFlow<OnlineHomeUiState> = _uiState.asStateFlow()

    private var account: OnlineAccount? = null

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
            val acc = server.takeIf { it.isNotBlank() }?.let(accountFor)
            account = acc
            if (acc == null) {
                _uiState.value = OnlineHomeUiState(OnlineHomeStatus.NO_SERVER)
                return@launch
            }
            val guest = attempt { acc.guest() }.getOrElse {
                _uiState.value = OnlineHomeUiState(OnlineHomeStatus.ERROR, problem = it.problem())
                return@launch
            }
            _uiState.value = OnlineHomeUiState(OnlineHomeStatus.READY, name = guest.name, myId = guest.id)
            while (isActive) {
                refreshList(acc)
                delay(pollMs)
            }
        }
    }

    private suspend fun refreshList(acc: OnlineAccount) {
        attempt { acc.call { api, guest -> guest.id to api.tables(guest.token) } }.fold(
            onSuccess = { (myId, list) ->
                _uiState.update { s ->
                    // A mesa escolhida acompanha a lista (alguém pode ter ocupado o último lugar).
                    val selected = s.selected?.let { sel -> list.tables.firstOrNull { it.id == sel.id } }?.takeIf { it.canEnter(myId) }
                    s.copy(myId = myId, tables = list.tables, onlinePlayers = list.onlinePlayers, listProblem = null, selected = selected)
                }
            },
            onFailure = { e -> _uiState.update { it.copy(listProblem = e.problem()) } },
        )
    }

    /** Tocar numa mesa: a minha mesa reabre a sala direto; outra mostra as opções e "Ocupar este lugar". */
    fun onSelect(table: TableView) {
        val s = _uiState.value
        if (!table.canEnter(s.myId) || s.joining) return
        if (table.seats.any { it.guestId == s.myId }) {
            _uiState.update { it.copy(joinedId = table.id, selected = null) }
            return
        }
        _uiState.update { it.copy(selected = table, problem = null) }
    }

    fun onDismissSelected() = _uiState.update { it.copy(selected = null) }

    /** "Ocupar este lugar": entra no primeiro lugar livre da mesa escolhida. */
    fun onOccupy() {
        val acc = account ?: return
        val table = _uiState.value.selected ?: return
        if (_uiState.value.joining) return
        _uiState.update { it.copy(joining = true, problem = null) }
        viewModelScope.launch {
            attempt { acc.call { api, guest -> api.join(guest.token, table.id) } }.fold(
                onSuccess = { t -> _uiState.update { it.copy(joining = false, selected = null, joinedId = t.id) } },
                onFailure = { e -> _uiState.update { it.copy(joining = false, selected = null, problem = e.problem()) } },
            )
        }
    }

    fun onJoinedHandled() = _uiState.update { it.copy(joinedId = null) }

    fun onProblemShown() = _uiState.update { it.copy(problem = null) }
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
    /** Escolhendo a quem entregar a mesa (§18.2). */
    val choosingHost: Boolean = false,
    val ended: OnlineRoomEnd? = null,
) {
    val started: Boolean get() = table?.state == TableState.EM_JOGO

    /** §18.2: o anfitrião inicia quando quiser; os lugares livres viram robôs. */
    val canStart: Boolean get() = isHost && !busy && table?.state == TableState.ABERTA
    val mySeat: Int get() = table?.seats?.indexOfFirst { it.guestId == myId } ?: -1

    /** Humanos a quem o anfitrião pode entregar a mesa (todos os outros da sala). */
    val passCandidates: List<Int>
        get() = table?.seats?.filter { it.kind == SeatKind.HUMANO && it.guestId != myId }?.map { it.index }.orEmpty()
}

/**
 * Sala da mesa (3.23, §18.2). A lista de lugares é atualizada consultando o servidor a cada [pollMs] (o lobby não envia
 * eventos); a consulta também mantém a sala ativa no servidor.
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
            onSuccess = { (guest, table) -> applyTable(table, guest.id) },
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

    private fun applyTable(table: TableView, myId: String) {
        val mine = table.seats.any { it.guestId == myId }
        if (mine) wasSeated = true
        _uiState.update {
            val ended = when {
                table.state == TableState.ENCERRADA -> OnlineRoomEnd.CLOSED
                wasSeated && !mine && table.state == TableState.ABERTA -> OnlineRoomEnd.REMOVED
                else -> it.ended
            }
            val isHost = table.hostId == myId
            it.copy(
                table = table,
                myId = myId,
                isHost = isHost,
                choosingHost = it.choosingHost && isHost,
                connectionLost = false,
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
                    applyTable(table, guest.id)
                    after(table)
                },
                onFailure = { e -> _uiState.update { it.copy(busy = false, problem = e.problem()) } },
            )
        }
    }

    /** Anfitrião: tira um jogador da mesa. */
    fun onRemove(seat: Int) = act({ api, token -> api.removeHuman(token, tableId, seat) })

    /** Anfitrião: inicia o jogo; os lugares livres viram robôs (§18.2). */
    fun onStart() = act({ api, token -> api.start(token, tableId) })

    /** Sai da mesa (o lugar fica livre); se era o anfitrião, o cargo passa ao próximo pela ordem de entrada (§18.2). */
    fun onLeave() = act({ api, token -> api.leave(token, tableId) }, after = {
        _uiState.update { it.copy(ended = OnlineRoomEnd.LEFT) }
    })

    /** Entregar a mesa (§18.2): abre a escolha entre os outros humanos da sala. */
    fun onAskPass() = _uiState.update { if (it.isHost && it.passCandidates.isNotEmpty()) it.copy(choosingHost = true) else it }
    fun onDismissPass() = _uiState.update { it.copy(choosingHost = false) }

    fun onPassTo(seat: Int) {
        _uiState.update { it.copy(choosingHost = false) }
        act({ api, token -> api.passHost(token, tableId, seat) })
    }

    fun onProblemShown() = _uiState.update { it.copy(problem = null) }

    override fun onCleared() {
        poller?.cancel()
    }
}
