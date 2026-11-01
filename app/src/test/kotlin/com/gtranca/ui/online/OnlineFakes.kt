package com.gtranca.ui.online

import com.gtranca.data.OnlineGuest
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.Coins
import com.gtranca.engine.Shop
import com.gtranca.engine.model.GameMode
import com.gtranca.online.client.EconomyApi
import com.gtranca.online.client.LobbyApi
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.EconomyErrorCode
import com.gtranca.online.contracts.EconomyStateDto
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableListView
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Configurações em memória (só o que o online usa). */
class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun setLastMode(mode: GameMode) = state.update { it.copy(lastMode = mode) }
    override suspend fun setHandSort(id: String) = state.update { it.copy(handSortId = id) }
    override suspend fun setOnlineServer(server: String) = state.update {
        it.copy(onlineServer = server.trim(), onlineGuest = if (server.trim() != it.onlineServer) null else it.onlineGuest)
    }

    override suspend fun setOnlineGuest(guest: OnlineGuest?) = state.update { it.copy(onlineGuest = guest) }
}

/** Lobby falso em memória, com as mesmas recusas do servidor real (códigos de [ErrorCode]). */
class FakeLobby : LobbyApi {
    /** Jogadores online informados na lista (§18.2). */
    var onlinePlayers = 0
    val guests = mutableMapOf<String, GuestIdentity>()
    val tables = mutableMapOf<String, TableView>()
    val calls = mutableListOf<String>()

    /** Se não for nulo, a próxima chamada falha com isto (e volta a nulo). */
    var failNext: OnlineException? = null

    /** Falha todas as chamadas enquanto não for nulo. */
    var failAll: OnlineException? = null
    private var counter = 0

    /** Tokens que o servidor "esqueceu" (reinício do lobby). */
    val forgotten = mutableSetOf<String>()

    fun api(error: OnlineException): Nothing = throw error

    private fun enter(name: String, token: String? = null) {
        calls += name
        failAll?.let { throw it }
        failNext?.let { failNext = null; throw it }
        if (token != null && (token in forgotten || guests.values.none { it.token == token })) {
            throw OnlineException(OnlineErrorKind.UNAUTHENTICATED, ErrorCode.NAO_AUTENTICADO, 401)
        }
    }

    private fun who(token: String) = guests.values.first { it.token == token }

    private fun error(code: ErrorCode, status: Int = 409): Nothing = throw OnlineException(OnlineErrorKind.API, code, status)

    override suspend fun createGuest(name: String): GuestIdentity {
        enter("createGuest")
        val id = "g${++counter}"
        return GuestIdentity(id, name, "tok-$id").also { guests[id] = it }
    }

    /** §18.2: o jogador nao cria mesa. */
    override suspend fun createTable(token: String, rules: TableRules): TableView {
        enter("createTable", token)
        error(ErrorCode.SEM_PERMISSAO, 403)
    }

    /** Uma mesa do servidor aberta e vazia (duplas, §18.2). */
    fun serverTable(): String {
        val id = "ABC23${4 + tables.size}"
        tables[id] = TableView(id, "", "", TableState.ABERTA, rules, List(4) { SeatView(it, SeatKind.VAGO) })
        return id
    }

    private val entryOrder = mutableMapOf<String, MutableList<String>>()

    override suspend fun tables(token: String): TableListView {
        enter("tables", token)
        val sorted = tables.values.filter { it.state != TableState.ENCERRADA }.sortedBy { if (it.state == TableState.ABERTA) 0 else 1 }
        return TableListView(onlinePlayers, sorted)
    }

    val photos = mutableMapOf<String, String>()

    override suspend fun move(token: String, tableId: String, seat: Int): TableView {
        enter("move", token)
        val me = who(token)
        val t = find(tableId)
        val from = t.seats.indexOfFirst { it.guestId == me.id }
        if (from < 0) error(ErrorCode.LUGAR_INVALIDO)
        if (t.seats[seat].kind != SeatKind.VAGO) error(ErrorCode.MESA_CHEIA)
        return put(t, t.seats.map {
            when (it.index) {
                from -> SeatView(from, SeatKind.VAGO)
                seat -> t.seats[from].copy(index = seat)
                else -> it
            }
        })
    }

    override suspend fun setPhoto(token: String, photoBase64: String?) {
        enter("setPhoto", token)
        val me = who(token)
        if (photoBase64 == null) photos.remove(me.id) else photos[me.id] = photoBase64
    }

    override suspend fun photo(token: String, photoUrl: String): String? {
        enter("photo", token)
        return photos[photoUrl.removePrefix("/api/lobby/guests/").substringBefore('/')]
    }

    override suspend fun passHost(token: String, tableId: String, seat: Int): TableView {
        enter("passHost", token)
        val t = host(token, tableId)
        val target = t.seats[seat]
        if (target.kind != SeatKind.HUMANO || target.guestId == t.hostId) error(ErrorCode.LUGAR_INVALIDO)
        return t.copy(hostId = target.guestId!!, hostName = target.name!!).also { tables[t.id] = it }
    }

    private fun find(id: String): TableView = tables[id] ?: error(ErrorCode.MESA_INEXISTENTE, 404)

    override suspend fun table(token: String, tableId: String): TableView {
        enter("table", token)
        return find(tableId)
    }

    override suspend fun join(token: String, tableId: String, seat: Int?): TableView {
        enter("join", token)
        val me = who(token)
        val t = find(tableId)
        if (t.state != TableState.ABERTA) error(ErrorCode.MESA_JA_INICIADA)
        if (t.seats.any { it.guestId == me.id }) return t
        val target = seat ?: t.seats.indexOfFirst { it.kind == SeatKind.VAGO }
        if (target < 0 || t.seats[target].kind != SeatKind.VAGO) error(ErrorCode.MESA_CHEIA)
        return sit(t, target, me)
    }

    override suspend fun leave(token: String, tableId: String): TableView {
        enter("leave", token)
        val me = who(token)
        return vacate(find(tableId), me.id)
    }

    private fun host(token: String, tableId: String): TableView {
        val t = find(tableId)
        if (t.hostId != who(token).id) error(ErrorCode.SEM_PERMISSAO, 403)
        return t
    }

    override suspend fun seatRobot(token: String, tableId: String, seat: Int): TableView {
        enter("seatRobot", token)
        error(ErrorCode.SEM_PERMISSAO, 403) // §18.2: os robos entram ao iniciar
    }

    override suspend fun removeHuman(token: String, tableId: String, seat: Int): TableView {
        enter("removeHuman", token)
        val t = host(token, tableId)
        if (t.seats[seat].kind != SeatKind.HUMANO || t.seats[seat].guestId == t.hostId) error(ErrorCode.LUGAR_INVALIDO)
        return vacate(t, t.seats[seat].guestId!!)
    }

    override suspend fun start(token: String, tableId: String): TableView {
        enter("start", token)
        val t = host(token, tableId)
        if (t.state != TableState.ABERTA) error(ErrorCode.MESA_JA_INICIADA)
        // §18.2: os lugares livres viram robos
        return put(t, t.seats.map { if (it.kind == SeatKind.VAGO) SeatView(it.index, SeatKind.ROBO) else it }, TableState.EM_JOGO)
    }

    override suspend fun close(token: String, tableId: String): TableView {
        enter("close", token)
        return put(host(token, tableId), host(token, tableId).seats, TableState.ENCERRADA)
    }

    /** Senta e, se a mesa estava vazia, faz anfitriao (o 1o a entrar, §18.2). */
    private fun sit(t: TableView, seat: Int, g: GuestIdentity): TableView {
        entryOrder.getOrPut(t.id) { mutableListOf() } += g.id
        val seated = put(t, t.seats.map { if (it.index == seat) SeatView(seat, SeatKind.HUMANO, g.id, g.name) else it })
        return if (t.hostId.isEmpty()) seated.copy(hostId = g.id, hostName = g.name).also { tables[t.id] = it } else seated
    }

    /** Libera o lugar; se era o anfitriao, o cargo passa ao proximo pela ordem de entrada (§18.2). */
    private fun vacate(t: TableView, guestId: String): TableView {
        entryOrder[t.id]?.remove(guestId)
        val freed = put(t, t.seats.map { if (it.guestId == guestId) SeatView(it.index, SeatKind.VAGO) else it })
        if (t.hostId != guestId) return freed
        val next = entryOrder[t.id]?.firstOrNull()?.let { id -> freed.seats.first { it.guestId == id } }
        return freed.copy(hostId = next?.guestId.orEmpty(), hostName = next?.name.orEmpty()).also { tables[t.id] = it }
    }

    private fun put(t: TableView, seats: List<SeatView>, state: TableState = t.state): TableView =
        t.copy(seats = seats, state = state).also { tables[t.id] = it }

    /** Um segundo jogador entra "por fora" (outro celular). */
    fun otherJoins(tableId: String, name: String = "Beto"): GuestIdentity {
        val g = GuestIdentity("g${++counter}", name, "tok-other$counter").also { guests[it.id] = it }
        val t = tables.getValue(tableId)
        sit(t, t.seats.indexOfFirst { it.kind == SeatKind.VAGO }, g)
        return g
    }

    fun setState(tableId: String, state: TableState) {
        val t = tables.getValue(tableId)
        put(t, t.seats, state)
    }

    companion object {
        val rules = TableRules(TableMode.DUPLAS, "{}", targetScore = 1500, bet = 50)
    }
}

/**
 * Economia falsa em memória (§18.5), com as mesmas recusas do servidor real (por [EconomyErrorCode]) e o mesmo
 * livro-razão idempotente por chave (repetir a MESMA chave não cobra de novo).
 */
class FakeEconomy(private val initialBalance: Long = 1_000) : EconomyApi {
    private data class Account(var balance: Long, val inventory: MutableMap<String, Int> = mutableMapOf(), var lastDailyClaim: Long? = null)

    private val accounts = mutableMapOf<String, Account>()

    /** Chaves já aplicadas (por conta): simula o livro-razão do servidor. */
    private val appliedKeys = mutableSetOf<Pair<String, String>>()

    /** Toda tentativa de compra, mesmo repetida (para o teste de idempotência contar quantas chegaram). */
    val buyAttempts = mutableListOf<Pair<String, String>>()

    /** Se não for nulo, a próxima chamada falha com isto (e volta a nulo). */
    var failNext: OnlineException? = null

    private fun account(id: String) = accounts.getOrPut(id) { Account(initialBalance) }

    private fun enter() { failNext?.let { failNext = null; throw it } }

    private fun error(code: EconomyErrorCode, status: Int = 409): Nothing =
        throw OnlineException(OnlineErrorKind.API, status = status, message = code.name, economyCode = code)

    override suspend fun economyState(token: String, accountId: String): EconomyStateDto {
        enter()
        val a = account(accountId)
        return EconomyStateDto(a.balance, a.inventory.toMap(), a.lastDailyClaim)
    }

    override suspend fun claimDaily(token: String, accountId: String, today: Long): EconomyStateDto {
        enter()
        val a = account(accountId)
        if (a.lastDailyClaim != null && today <= a.lastDailyClaim!!) error(EconomyErrorCode.JA_RESGATADO)
        a.balance += Coins.DAILY_GAIN
        a.lastDailyClaim = today
        return EconomyStateDto(a.balance, a.inventory.toMap(), a.lastDailyClaim)
    }

    override suspend fun buyOffer(token: String, accountId: String, offerIndex: Int, key: String): EconomyStateDto {
        // Conta mesmo quando falha (failNext), para o teste de idempotência ver as duas tentativas com a mesma chave.
        buyAttempts += accountId to key
        enter()
        val a = account(accountId)
        if (accountId to key in appliedKeys) return EconomyStateDto(a.balance, a.inventory.toMap(), a.lastDailyClaim)
        val offer = Shop.OFFERS.getOrNull(offerIndex) ?: error(EconomyErrorCode.OFERTA_INEXISTENTE, 404)
        if (a.balance < offer.price) error(EconomyErrorCode.SALDO_INSUFICIENTE)
        appliedKeys += accountId to key
        a.balance -= offer.price
        a.inventory[offer.boosterId] = (a.inventory[offer.boosterId] ?: 0) + offer.quantity
        return EconomyStateDto(a.balance, a.inventory.toMap(), a.lastDailyClaim)
    }
}
