package com.gtranca.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.data.InventoryRepository
import com.gtranca.data.SettingsRepository
import com.gtranca.data.WalletRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.online.client.EconomyApi
import com.gtranca.online.client.LobbyApi
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.EconomyErrorCode
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

/** §14.6 o ganho diário online reinicia à meia-noite UTC (igual ao `economy-service`, nunca o fuso do aparelho). */
private const val MILLIS_PER_DAY = 86_400_000L

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

/** Um booster local que vai migrar (§18.6): id para a chamada ao servidor, nome para mostrar no modal. */
data class MigrateWalletItem(val boosterId: String, val name: String, val quantity: Int)

/**
 * Modal "Trazer carteira local" (docs/telas.md, glossário de modais, §18.6): [coins] e [items] são o saldo e o
 * inventário LOCAIS do aparelho lidos no momento em que o botão foi tocado — [OnlineHomeViewModel.onMigrateConfirm]
 * nunca relê o aparelho, nem para repetir depois de um erro (senão uma mudança no meio do caminho mudaria o que foi
 * prometido no modal).
 */
data class MigrateWalletUi(
    val coins: Int,
    val items: List<MigrateWalletItem>,
    val busy: Boolean = false,
    val problem: OnlineProblem? = null,
)

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
    /** Lugar pedido ao servidor e ainda sem resposta (mesa, assento). */
    val joining: Pair<String, Int>? = null,
    /** Já entrou: a tela abre a sala e chama [OnlineHomeViewModel.onJoinedHandled]. */
    val joinedId: String? = null,
    /** §18.5 saldo da carteira online; `null` enquanto não chegou (a lista de mesas funciona do mesmo jeito). */
    val balance: Long? = null,
    /** §14.6 o ganho diário de hoje ainda não foi resgatado. */
    val canClaimDaily: Boolean = false,
    /** Resgatando o ganho diário agora (evita toque duplo). */
    val claimingDaily: Boolean = false,
    /** §18.6 a conta já trouxe a carteira local (`true` por padrão até a economia carregar, para o botão não pipocar
     * na tela antes da 1ª leitura). */
    val migratedLocal: Boolean = true,
    /** Modal "Trazer carteira local" (§18.6) aberto com o que foi lido do aparelho; `null` = modal fechado. */
    val migrateDialog: MigrateWalletUi? = null,
) {
    /** Mesa aberta em que eu já estou sentado (voltar à sala em vez de ocupar outro lugar). */
    val myTable: TableView? get() = tables?.firstOrNull { t -> t.state == TableState.ABERTA && t.seats.any { it.guestId == myId } }

    /** §18.6 só conta (e-mail/Google) que ainda não migrou vê o botão "Trazer minha carteira". */
    val canMigrateWallet: Boolean get() = !migratedLocal && AccountLimits.isAccountId(myId)
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
    /** A foto do Perfil reduzida (JPEG em Base64), enviada ao servidor ao entrar (§18.2); `null` = sem foto. */
    private val myPhoto: suspend () -> String? = { null },
    /** API da economia do mesmo servidor (§18.5): saldo e ganho diário de 3.21. `null` = sem carteira (testes que não
     * cobrem economia continuam valendo sem precisar informar isto). */
    private val economyFor: (String) -> EconomyApi? = { null },
    /** "Hoje" do ganho diário online: dia UTC desde a época — o MESMO que o economy-service usa para decidir
     * `JA_RESGATADO` (§14.6: quem decide é o servidor, nunca o fuso/relógio do aparelho); injetável nos testes. */
    private val today: () -> Long = { Math.floorDiv(System.currentTimeMillis(), MILLIS_PER_DAY) },
    /** Carteira e inventário LOCAIS do aparelho (§18.6, "Trazer minha carteira"): só leitura, nunca grava aqui — a
     * regra é clara que o saldo/inventário local não muda com a migração. `null` nos testes que não cobrem isto. */
    private val wallet: WalletRepository? = null,
    private val inventory: InventoryRepository? = null,
    // accountFor fica por último (sem valor padrão) para continuar podendo vir como lambda à parte dos parênteses.
    private val accountFor: (String) -> OnlineAccount?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineHomeUiState())
    val uiState: StateFlow<OnlineHomeUiState> = _uiState.asStateFlow()

    private var account: OnlineAccount? = null
    private var economy: EconomyApi? = null
    private var photoCache: OnlinePhotos? = null

    private val _photos = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Fotos dos jogadores das mesas, por id do convidado. */
    val photos: StateFlow<Map<String, ByteArray>> = _photos.asStateFlow()

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
            economy = if (acc != null) economyFor(server) else null
            if (acc == null) {
                _uiState.value = OnlineHomeUiState(OnlineHomeStatus.NO_SERVER)
                return@launch
            }
            val guest = attempt { acc.guest() }.getOrElse {
                _uiState.value = OnlineHomeUiState(OnlineHomeStatus.ERROR, problem = it.problem())
                return@launch
            }
            _uiState.value = OnlineHomeUiState(OnlineHomeStatus.READY, name = guest.name, myId = guest.id)
            // §18.5: saldo e ganho diário, uma vez (não entra no polling da lista de mesas).
            economy?.let { eco -> loadEconomy(acc, eco) }
            // §18.2: a foto do Perfil vai ao servidor (melhor esforço: sem ela, os outros veem a inicial).
            attempt { acc.call { api, g -> api.setPhoto(g.token, myPhoto()) } }
            val cache = OnlinePhotos(acc).also { photoCache = it }
            launch { cache.photos.collect { _photos.value = it } }
            while (isActive) {
                refreshList(acc)
                delay(pollMs)
            }
        }
    }

    private suspend fun refreshList(acc: OnlineAccount) {
        attempt { acc.call { api, guest -> guest.id to api.tables(guest.token) } }.fold(
            onSuccess = { (myId, list) ->
                _uiState.update { s -> s.copy(myId = myId, tables = list.tables, onlinePlayers = list.onlinePlayers, listProblem = null) }
                photoCache?.refresh(list.tables)
            },
            onFailure = { e -> _uiState.update { it.copy(listProblem = e.problem()) } },
        )
    }

    /** §18.5 lê o saldo e se o ganho diário de hoje já foi resgatado; falha fica em silêncio (a lista de mesas segue
     * funcionando sem a carteira — quem tentar a Loja online lê de novo lá). */
    private suspend fun loadEconomy(acc: OnlineAccount, eco: EconomyApi) {
        attempt { acc.callWith(eco) { api, guest -> api.economyState(guest.token, guest.id) } }.fold(
            onSuccess = { dto ->
                val last = dto.lastDailyClaim
                _uiState.update { it.copy(balance = dto.balance, canClaimDaily = last == null || today() > last, migratedLocal = dto.migratedLocal) }
            },
            onFailure = {},
        )
    }

    /** §14.6 resgata o ganho diário online (um por dia, pelo relógio do servidor); o saldo e o botão se atualizam. */
    fun onClaimDaily() {
        val acc = account ?: return
        val eco = economy ?: return
        if (_uiState.value.claimingDaily) return
        _uiState.update { it.copy(claimingDaily = true) }
        viewModelScope.launch {
            attempt { acc.callWith(eco) { api, guest -> api.claimDaily(guest.token, guest.id, today()) } }.fold(
                onSuccess = { dto -> _uiState.update { it.copy(claimingDaily = false, balance = dto.balance, canClaimDaily = false) } },
                onFailure = { e ->
                    _uiState.update { it.copy(claimingDaily = false) }
                    if ((e as? OnlineException)?.economyCode == EconomyErrorCode.JA_RESGATADO) {
                        // Outro aparelho resgatou primeiro: esconde o botão sem esperar outra leitura.
                        _uiState.update { it.copy(canClaimDaily = false) }
                    } else {
                        // Rede ou outra falha: relê para conciliar (o saldo pode já ter mudado).
                        loadEconomy(acc, eco)
                    }
                },
            )
        }
    }

    /**
     * §18.6 toque em "Trazer minha carteira": lê o saldo e o inventário LOCAIS do aparelho (sem gravar nada neles,
     * a migração não muda o save local) e abre o modal com o que vai migrar. Ignorado se o botão não devia estar
     * visível (defesa: convidado, ou conta que já migrou).
     */
    fun onMigrateTap() {
        val w = wallet ?: return
        val inv = inventory ?: return
        if (!_uiState.value.canMigrateWallet) return
        viewModelScope.launch {
            val coins = w.wallet.first().balance
            val quantities = inv.inventory.first().quantities
            // §18.6 o economy-service só aceita ids de BoosterCatalog.DEFAULT (o mesmo catálogo fixo do core-engine,
            // nunca o `assets/boosters.json` local, que pode estar numa versão diferente): filtrar por ele aqui
            // garante que o que o modal promete é sempre aceito, em vez de um REQUISICAO_INVALIDA sem saída.
            val items = quantities.filterValues { it > 0 }
                .mapNotNull { (id, qty) -> BoosterCatalog.DEFAULT.firstOrNull { it.id == id }?.let { MigrateWalletItem(id, it.name, qty) } }
                .sortedBy { it.name }
            _uiState.update { it.copy(migrateDialog = MigrateWalletUi(coins, items)) }
        }
    }

    /** Fecha o modal sem migrar; ignorado enquanto a migração está em andamento. */
    fun onMigrateCancel() {
        val dialog = _uiState.value.migrateDialog ?: return
        if (dialog.busy) return
        _uiState.update { it.copy(migrateDialog = null) }
    }

    /**
     * Confirma a migração (ou repete depois de um erro, "Tentar de novo"): usa os valores já lidos ao abrir o modal
     * ([MigrateWalletUi.coins]/[items]), nunca relê o aparelho. Sucesso atualiza o saldo online e esconde o botão
     * para sempre (a conta já migrou); falha mantém o modal aberto com o motivo, sem perder a tentativa.
     */
    fun onMigrateConfirm() {
        val acc = account ?: return
        val eco = economy ?: return
        val dialog = _uiState.value.migrateDialog ?: return
        if (dialog.busy) return
        _uiState.update { it.copy(migrateDialog = dialog.copy(busy = true, problem = null)) }
        viewModelScope.launch {
            val inventoryMap = dialog.items.associate { it.boosterId to it.quantity }
            attempt { acc.callWith(eco) { api, guest -> api.migrateLocalWallet(guest.token, guest.id, dialog.coins.toLong(), inventoryMap) } }.fold(
                onSuccess = { dto -> _uiState.update { it.copy(balance = dto.balance, migratedLocal = dto.migratedLocal, migrateDialog = null) } },
                onFailure = { e -> _uiState.update { it.copy(migrateDialog = it.migrateDialog?.copy(busy = false, problem = (e as? OnlineException)?.toMigrateProblem() ?: OnlineProblem.UNEXPECTED)) } },
            )
        }
    }

    /** A minha mesa: reabre a sala ("Voltar à sala"). */
    fun onBackToRoom(table: TableView) {
        if (table.seats.any { it.guestId == _uiState.value.myId }) _uiState.update { it.copy(joinedId = table.id) }
    }

    /**
     * Tocar num lugar VAGO (§18.2): pede aquele lugar ao servidor. Aceito, abre a sala; se outro chegou antes, o servidor
     * recusa e a tela avisa "Esse lugar acabou de ser ocupado" e atualiza a mesa.
     */
    fun onSeatTap(table: TableView, seat: Int) {
        val acc = account ?: return
        val s = _uiState.value
        if (s.joining != null || table.state != TableState.ABERTA) return
        if (table.seats.getOrNull(seat)?.kind != SeatKind.VAGO) return
        // §18.2: quem já está sentado nesta mesa troca de lugar; os outros entram.
        val seated = table.seats.any { it.guestId == s.myId }
        _uiState.update { it.copy(joining = table.id to seat, problem = null) }
        viewModelScope.launch {
            attempt { acc.call { api, guest -> if (seated) api.move(guest.token, table.id, seat) else api.join(guest.token, table.id, seat) } }.fold(
                onSuccess = { t ->
                    _uiState.update { it.copy(joining = null, joinedId = t.id.takeIf { !seated }) }
                    if (seated) refreshList(acc)
                },
                onFailure = { e ->
                    val problem = e.problem().let { if (it == OnlineProblem.TABLE_FULL) OnlineProblem.SEAT_TAKEN else it }
                    _uiState.update { it.copy(joining = null, problem = problem) }
                    refreshList(acc)
                },
            )
        }
    }

    fun onJoinedHandled() = _uiState.update { it.copy(joinedId = null) }

    fun onProblemShown() = _uiState.update { it.copy(problem = null) }
}

// ---------------------------------------------------------------------------------------------------------------
// 3.22 Conta online (entrar ou criar conta por e-mail e senha, §18.1)
// ---------------------------------------------------------------------------------------------------------------

data class OnlineAccountUiState(
    val email: String = "",
    val password: String = "",
    /** Chamando o servidor agora (bcrypt é lento de propósito): os dois botões ficam desabilitados. */
    val busy: Boolean = false,
    val problem: OnlineAccountProblem? = null,
    /** Entrou ou criou a conta agora: a tela volta a 3.21 (o nome e o saldo recarregam sozinhos lá). */
    val done: Boolean = false,
) {
    val emailValid: Boolean get() = email.isNotBlank() && email.contains('@')
    val passwordValid: Boolean get() = password.length >= AccountLimits.MIN_PASSWORD
    val canSubmit: Boolean get() = !busy && emailValid && passwordValid

    // Nunca expõe a senha (ex.: logs de depuração), como [com.gtranca.data.OnlineGuest] já faz com o token.
    override fun toString() = "OnlineAccountUiState(email=$email, password=***, busy=$busy, problem=$problem, done=$done)"
}

/**
 * Conta online por e-mail e senha (3.22, §18.1). Chama [LobbyApi.loginEmail]/[LobbyApi.registerEmail] direto (nunca
 * por [OnlineAccount.call]/[OnlineAccount.callWith]: aquele caminho trata 401 como "token do convidado venceu" e
 * criaria um convidado novo a cada senha errada, trocando o guardado e dobrando a contagem do limitador de força
 * bruta do servidor). Sucesso grava a conta com [OnlineAccount.signIn] (a carteira já migrou no servidor, §18.5).
 */
class OnlineAccountViewModel(
    private val account: OnlineAccount,
    private val api: LobbyApi,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineAccountUiState())
    val uiState: StateFlow<OnlineAccountUiState> = _uiState.asStateFlow()

    fun onEmail(text: String) = _uiState.update { it.copy(email = text, problem = null) }
    fun onPassword(text: String) = _uiState.update { it.copy(password = text, problem = null) }

    fun onLogin() = submit(signup = false)
    fun onSignup() = submit(signup = true)

    private fun submit(signup: Boolean) {
        val s = _uiState.value
        if (!s.canSubmit) return
        _uiState.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            // Sem convidado (ex.: 1ª chamada ao servidor falhou): entra/cria do mesmo jeito, sem migrar carteira nenhuma.
            val guestToken = attempt { account.guest() }.getOrNull()?.token
            val email = s.email.trim()
            attempt {
                if (signup) api.registerEmail(email, s.password, null, guestToken) else api.loginEmail(email, s.password, guestToken)
            }.fold(
                onSuccess = { login ->
                    account.signIn(login)
                    _uiState.update { it.copy(busy = false, done = true) }
                },
                onFailure = { e ->
                    val problem = (e as? OnlineException)?.toAccountProblem(signup) ?: OnlineAccountProblem.UNEXPECTED
                    _uiState.update { it.copy(busy = false, problem = problem) }
                },
            )
        }
    }

    fun onDoneHandled() = _uiState.update { it.copy(done = false) }
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
    /** Anfitrião tocou num jogador: confirma se o remove. */
    val askRemove: Int? = null,
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
    private val photoCache = OnlinePhotos(account)

    /** Fotos dos jogadores da mesa, por id do convidado. */
    val photos: StateFlow<Map<String, ByteArray>> = photoCache.photos

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
            onSuccess = { (guest, table) ->
                applyTable(table, guest.id)
                photoCache.refresh(listOf(table))
            },
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

    /** Trocar para um lugar VAGO antes do jogo (§18.2); se outro chegou antes, o servidor recusa. */
    fun onMove(seat: Int) {
        val s = _uiState.value
        if (s.started || s.table?.seats?.getOrNull(seat)?.kind != SeatKind.VAGO) return
        act({ api, token -> api.move(token, tableId, seat) })
    }

    /** Anfitrião tocou num jogador da mesa: pergunta se o remove. */
    fun onAskRemove(seat: Int) = _uiState.update { if (it.isHost) it.copy(askRemove = seat) else it }
    fun onDismissRemove() = _uiState.update { it.copy(askRemove = null) }

    /** Anfitrião: tira um jogador da mesa. */
    fun onRemove(seat: Int) {
        _uiState.update { it.copy(askRemove = null) }
        act({ api, token -> api.removeHuman(token, tableId, seat) })
    }

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
