package com.gtranca.ui.online

import com.gtranca.data.OnlineGuest
import com.gtranca.data.Settings
import com.gtranca.data.SettingsRepository
import com.gtranca.engine.model.GameMode
import com.gtranca.online.client.LobbyApi
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
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

    override suspend fun createTable(token: String, rules: TableRules): TableView {
        enter("createTable", token)
        val me = who(token)
        val seats = List(rules.mode.seats) { if (it == 0) SeatView(0, SeatKind.HUMANO, me.id, me.name) else SeatView(it, SeatKind.VAGO) }
        val id = "ABC23${4 + tables.size}"
        return TableView(id, me.id, me.name, TableState.ABERTA, rules, seats).also { tables[id] = it }
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
        val target = t.seats.indexOfFirst { it.kind == SeatKind.VAGO }
        if (target < 0) error(ErrorCode.MESA_CHEIA)
        return put(t, t.seats.map { if (it.index == target) SeatView(target, SeatKind.HUMANO, me.id, me.name) else it })
    }

    override suspend fun leave(token: String, tableId: String): TableView {
        enter("leave", token)
        val me = who(token)
        val t = find(tableId)
        if (t.hostId == me.id) error(ErrorCode.SEM_PERMISSAO, 403)
        return put(t, t.seats.map { if (it.guestId == me.id) SeatView(it.index, SeatKind.VAGO) else it })
    }

    private fun host(token: String, tableId: String): TableView {
        val t = find(tableId)
        if (t.hostId != who(token).id) error(ErrorCode.SEM_PERMISSAO, 403)
        return t
    }

    override suspend fun seatRobot(token: String, tableId: String, seat: Int): TableView {
        enter("seatRobot", token)
        val t = host(token, tableId)
        if (t.seats[seat].kind != SeatKind.VAGO) error(ErrorCode.LUGAR_INVALIDO)
        return put(t, t.seats.map { if (it.index == seat) SeatView(seat, SeatKind.ROBO) else it })
    }

    override suspend fun removeHuman(token: String, tableId: String, seat: Int): TableView {
        enter("removeHuman", token)
        val t = host(token, tableId)
        if (t.seats[seat].kind != SeatKind.HUMANO || t.seats[seat].guestId == t.hostId) error(ErrorCode.LUGAR_INVALIDO)
        return put(t, t.seats.map { if (it.index == seat) SeatView(seat, SeatKind.VAGO) else it })
    }

    override suspend fun start(token: String, tableId: String): TableView {
        enter("start", token)
        val t = host(token, tableId)
        if (t.seats.any { it.kind == SeatKind.VAGO }) error(ErrorCode.LUGARES_VAGOS)
        return put(t, t.seats, TableState.EM_JOGO)
    }

    override suspend fun close(token: String, tableId: String): TableView {
        enter("close", token)
        return put(host(token, tableId), host(token, tableId).seats, TableState.ENCERRADA)
    }

    private fun put(t: TableView, seats: List<SeatView>, state: TableState = t.state): TableView =
        t.copy(seats = seats, state = state).also { tables[t.id] = it }

    /** Um segundo jogador entra "por fora" (outro celular). */
    fun otherJoins(tableId: String, name: String = "Beto"): GuestIdentity {
        val g = GuestIdentity("g${++counter}", name, "tok-other$counter").also { guests[it.id] = it }
        val t = tables.getValue(tableId)
        val target = t.seats.indexOfFirst { it.kind == SeatKind.VAGO }
        put(t, t.seats.map { if (it.index == target) SeatView(target, SeatKind.HUMANO, g.id, g.name) else it })
        return g
    }

    fun setState(tableId: String, state: TableState) {
        val t = tables.getValue(tableId)
        put(t, t.seats, state)
    }

    companion object {
        val rules = TableRules(TableMode.INDIVIDUAL, "{}")
    }
}
