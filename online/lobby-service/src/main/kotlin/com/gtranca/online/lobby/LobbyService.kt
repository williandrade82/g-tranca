package com.gtranca.online.lobby

import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.LobbyLimits
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatView
import com.gtranca.online.contracts.TableId
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TableView
import com.gtranca.online.contracts.Visibilidade
import kotlin.random.Random

data class LobbyConfig(
    /** Mesa ABERTA sem atividade some depois disto (§18.2: vale enquanto a mesa existir). */
    val openTtlMs: Long = 30 * 60_000L,
    val closedRetentionMs: Long = 10 * 60_000L,
    val playingTtlMs: Long = 6 * 3_600_000L,
)

/**
 * Regras da mesa online (§18.2, §18.4). SALDO: sem economy-service nesta fase, a aposta NAO e verificada
 * ao entrar (§18.2 exigira saldo >= aposta); [ErrorCode.SALDO_INSUFICIENTE] esta declarado mas nunca e devolvido.
 */
class LobbyService(
    private val store: TableStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val config: LobbyConfig = LobbyConfig(),
) {
    fun createTable(host: Guest, rules: TableRules): TableView {
        validate(rules)
        sweep()
        val now = clock()
        val seats = List(rules.mode.seats) { if (it == 0) Seat.Humano(host.id, host.name) else Seat.Vago }
        repeat(MAX_ID_ATTEMPTS) {
            val table = Table(TableId.generate(random), host, rules, seats, TableState.ABERTA, now)
            if (store.insert(table)) return table.toView()
        }
        lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Não foi possível gerar o id da mesa. Tente de novo.", 503)
    }

    /** Consulta; quem esta sentado renova a atividade da mesa aberta. */
    fun get(guest: Guest, rawId: String): TableView {
        sweep()
        val table = store.update(idOf(rawId)) { t ->
            if (t.state == TableState.ABERTA && t.seatOf(guest.id) >= 0) t.touched(clock()) else t
        } ?: missing()
        return table.toView()
    }

    fun join(guest: Guest, rawId: String, seat: Int?): TableView = mutate(rawId) { t ->
        requireOpen(t)
        if (t.seatOf(guest.id) >= 0) return@mutate t // idempotente
        val target = if (seat != null) {
            checkSeat(t, seat)
            seat
        } else {
            t.seats.indexOfFirst { it is Seat.Vago }
        }
        if (target < 0) lobbyError(ErrorCode.MESA_CHEIA, "A mesa está cheia.", 409)
        if (t.seats[target] !is Seat.Vago) lobbyError(ErrorCode.MESA_CHEIA, "Esse lugar já está ocupado.", 409)
        t.withSeat(target, Seat.Humano(guest.id, guest.name))
    }

    fun leave(guest: Guest, rawId: String): TableView = mutate(rawId) { t ->
        if (t.host.id == guest.id) {
            lobbyError(ErrorCode.SEM_PERMISSAO, "O anfitrião não sai da mesa: encerre a mesa.", 403)
        }
        requireOpen(t)
        val idx = t.seatOf(guest.id)
        if (idx < 0) t else t.withSeat(idx, Seat.Vago)
    }

    fun seatRobot(guest: Guest, rawId: String, seat: Int): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        requireOpen(t)
        checkSeat(t, seat)
        if (t.seats[seat] !is Seat.Vago) lobbyError(ErrorCode.LUGAR_INVALIDO, "Só um lugar vago recebe robô.", 409)
        t.withSeat(seat, Seat.Robo)
    }

    fun removeHuman(guest: Guest, rawId: String, seat: Int): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        requireOpen(t)
        checkSeat(t, seat)
        val s = t.seats[seat]
        if (s !is Seat.Humano || s.guestId == t.host.id) {
            lobbyError(ErrorCode.LUGAR_INVALIDO, "Esse lugar não tem um jogador para remover.", 409)
        }
        t.withSeat(seat, Seat.Vago)
    }

    /** §18.2: o anfitrião inicia quando todos os lugares estão preenchidos. */
    fun start(guest: Guest, rawId: String): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        requireOpen(t)
        if (t.seats.any { it is Seat.Vago }) {
            lobbyError(ErrorCode.LUGARES_VAGOS, "Preencha todos os lugares (com jogadores ou robôs) para iniciar.", 409)
        }
        t.copy(state = TableState.EM_JOGO)
    }

    fun close(guest: Guest, rawId: String): TableView = mutate(rawId) { t ->
        requireHost(t, guest)
        if (t.state == TableState.ENCERRADA) t else t.copy(state = TableState.ENCERRADA)
    }

    private fun mutate(rawId: String, f: (Table) -> Table): TableView {
        sweep()
        val table = store.update(idOf(rawId)) { t ->
            val next = f(t)
            if (next === t) t else next.touched(clock())
        } ?: missing()
        return table.toView()
    }

    private fun sweep() {
        val now = clock()
        store.removeIf { t ->
            val idle = now - t.lastActivityMs
            when (t.state) {
                TableState.ABERTA -> idle > config.openTtlMs
                TableState.ENCERRADA -> idle > config.closedRetentionMs
                TableState.EM_JOGO -> idle > config.playingTtlMs
            }
        }
    }

    private fun idOf(raw: String): String = TableId.normalize(raw) ?: missing()

    private fun missing(): Nothing = lobbyError(ErrorCode.MESA_INEXISTENTE, "Mesa não encontrada. Confira o id.", 404)

    private fun requireHost(t: Table, g: Guest) {
        if (t.host.id != g.id) lobbyError(ErrorCode.SEM_PERMISSAO, "Só o anfitrião pode fazer isso.", 403)
    }

    private fun requireOpen(t: Table) {
        when (t.state) {
            TableState.ABERTA -> Unit
            TableState.EM_JOGO -> lobbyError(ErrorCode.MESA_JA_INICIADA, "O jogo desta mesa já começou.", 409)
            TableState.ENCERRADA -> lobbyError(ErrorCode.MESA_INEXISTENTE, "Esta mesa foi encerrada.", 404)
        }
    }

    private fun checkSeat(t: Table, seat: Int) {
        if (seat !in t.seats.indices) lobbyError(ErrorCode.LUGAR_INVALIDO, "Lugar inexistente nesta mesa.", 400)
    }

    private fun validate(r: TableRules) {
        fun bad(msg: String): Nothing = lobbyError(ErrorCode.REQUISICAO_INVALIDA, msg, 400)
        if (r.visibilidade != Visibilidade.PRIVADA) bad("Mesas públicas ainda não estão disponíveis.")
        if (r.rulesJson.isBlank() || r.rulesJson.length > LobbyLimits.MAX_RULES_JSON) bad("Regras da mesa inválidas ou grandes demais.")
        if (r.targetScore !in 1..LobbyLimits.MAX_TARGET_SCORE) bad("Pontuação-alvo inválida.")
        if (r.bet !in 0..LobbyLimits.MAX_BET) bad("Aposta inválida.")
    }

    private fun Table.withSeat(i: Int, s: Seat) = copy(seats = seats.toMutableList().also { it[i] = s })

    companion object {
        private const val MAX_ID_ATTEMPTS = 50
    }
}

fun Table.toView() = TableView(
    id = id,
    hostId = host.id,
    hostName = host.name,
    state = state,
    rules = rules,
    seats = seats.mapIndexed { i, s ->
        when (s) {
            Seat.Vago -> SeatView(i, SeatKind.VAGO)
            Seat.Robo -> SeatView(i, SeatKind.ROBO)
            is Seat.Humano -> SeatView(i, SeatKind.HUMANO, s.guestId, s.name)
        }
    },
)
