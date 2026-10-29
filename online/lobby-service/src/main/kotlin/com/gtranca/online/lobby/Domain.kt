package com.gtranca.online.lobby

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState

/** Erro de regra de negocio ou de entrada, ja com codigo tipado, mensagem em pt-BR e status HTTP. */
class LobbyException(val code: ErrorCode, override val message: String, val status: Int) : RuntimeException(message) {
    fun toApiError() = ApiError(code, message)
}

fun lobbyError(code: ErrorCode, message: String, status: Int): Nothing = throw LobbyException(code, message, status)

data class Guest(val id: String, val name: String)

sealed interface Seat {
    data object Vago : Seat
    data class Humano(val guestId: String, val name: String) : Seat
    data object Robo : Seat
}

/** Mesa imutavel; cada acao produz uma copia (o [TableStore] garante a atomicidade). */
data class Table(
    val id: String,
    val host: Guest,
    val rules: TableRules,
    val seats: List<Seat>,
    val state: TableState,
    val lastActivityMs: Long,
    /** Passagem ao game-service em andamento: a mesa segue ABERTA mas nada mais muda (ver [LobbyService.beginStart]). */
    val starting: Boolean = false,
) {
    fun seatOf(guestId: String): Int = seats.indexOfFirst { it is Seat.Humano && it.guestId == guestId }
    fun touched(now: Long) = copy(lastActivityMs = now)
}

/**
 * Armazenamento das mesas. Nesta fase so existe a versao em MEMORIA (perde tudo ao reiniciar o pod);
 * a interface permite trocar por Postgres sem mexer nas regras.
 */
interface TableStore {
    /** Insere se o id nao estiver em uso por uma mesa nao encerrada. Mesa encerrada com o mesmo id e substituida. */
    fun insert(table: Table): Boolean
    fun find(id: String): Table?

    /** Aplica [transform] de forma atomica; null se a mesa nao existe. Excecao de [transform] nao altera nada. */
    fun update(id: String, transform: (Table) -> Table): Table?
    fun removeIf(predicate: (Table) -> Boolean): Int
    fun count(): Int
}

class InMemoryTableStore : TableStore {
    private val tables = HashMap<String, Table>()

    @Synchronized
    override fun insert(table: Table): Boolean {
        val current = tables[table.id]
        if (current != null && current.state != TableState.ENCERRADA) return false
        tables[table.id] = table
        return true
    }

    @Synchronized
    override fun find(id: String): Table? = tables[id]

    @Synchronized
    override fun update(id: String, transform: (Table) -> Table): Table? {
        val current = tables[id] ?: return null
        val next = transform(current)
        tables[id] = next
        return next
    }

    @Synchronized
    override fun removeIf(predicate: (Table) -> Boolean): Int {
        val before = tables.size
        tables.values.removeIf(predicate)
        return before - tables.size
    }

    @Synchronized
    override fun count(): Int = tables.size
}
