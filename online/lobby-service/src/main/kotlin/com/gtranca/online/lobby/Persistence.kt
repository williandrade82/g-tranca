package com.gtranca.online.lobby

import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableState
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Banco do lobby (plano 2.4): mesmo Postgres do economy, mas no schema `lobby` com historico do Flyway proprio
 * (`lobby.flyway_lobby_history`), sem tocar no `public` do economy.
 */
object LobbyDatabase {
    const val SCHEMA = "lobby"

    fun connect(url: String, user: String, password: String, maxPool: Int = 5): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = maxPool
                poolName = "lobby"
                // Nao falha ao criar o pool se o Postgres ainda nao subiu; a espera fica em [migrateWithRetry].
                initializationFailTimeout = -1
            },
        )

    fun migrate(db: DataSource) {
        Flyway.configure().dataSource(db)
            .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
            .table("flyway_lobby_history")
            .locations("classpath:db/lobby")
            .load().migrate()
    }

    fun migrateWithRetry(db: DataSource, sleep: (Long) -> Unit = Thread::sleep, maxDelayMs: Long = 30_000L) =
        retryForever(sleep, maxDelayMs) { migrate(db) }

    /** Repete [block] ate dar certo, com espera dobrando a partir de 1 s, limitada a [maxDelayMs]. Nao derruba a JVM. */
    fun <T> retryForever(sleep: (Long) -> Unit, maxDelayMs: Long, block: () -> T): T {
        var delay = 1_000L
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                System.err.println("AVISO: banco indisponivel (${e.javaClass.simpleName}); nova tentativa em ${delay / 1000}s")
                sleep(delay)
                delay = (delay * 2).coerceAtMost(maxDelayMs)
            }
        }
    }
}

/** Onde o [GuestRegistry] grava convidados e fotos. A memoria continua sendo o cache; o banco e a copia duravel. */
interface GuestPersistence {
    class StoredGuest(val tokenHash: String, val id: String, val name: String, val lastUsedMs: Long)
    class StoredPhoto(val guestId: String, val base64: String, val version: Int)

    fun loadGuests(): List<StoredGuest>
    fun loadPhotos(): List<StoredPhoto>
    fun saveGuest(g: StoredGuest)
    fun touch(tokenHash: String, lastUsedMs: Long)
    fun deleteGuests(tokenHashes: Collection<String>)
    fun savePhoto(p: StoredPhoto)
    fun deletePhotos(guestIds: Collection<String>)
}

class PostgresGuestPersistence(private val db: DataSource) : GuestPersistence {
    override fun loadGuests() = db.connection.use { c ->
        c.prepareStatement("SELECT token_hash, id, nome, ultimo_uso FROM lobby.convidado").executeQuery().use { rs ->
            buildList { while (rs.next()) add(GuestPersistence.StoredGuest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))) }
        }
    }

    override fun loadPhotos() = db.connection.use { c ->
        c.prepareStatement("SELECT convidado_id, base64, versao FROM lobby.foto").executeQuery().use { rs ->
            buildList { while (rs.next()) add(GuestPersistence.StoredPhoto(rs.getString(1), rs.getString(2), rs.getInt(3))) }
        }
    }

    override fun saveGuest(g: GuestPersistence.StoredGuest) = exec(
        "INSERT INTO lobby.convidado (token_hash, id, nome, ultimo_uso) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (token_hash) DO UPDATE SET nome = EXCLUDED.nome, ultimo_uso = EXCLUDED.ultimo_uso",
        g.tokenHash, g.id, g.name, g.lastUsedMs,
    )

    override fun touch(tokenHash: String, lastUsedMs: Long) =
        exec("UPDATE lobby.convidado SET ultimo_uso = GREATEST(ultimo_uso, ?) WHERE token_hash = ?", lastUsedMs, tokenHash)

    override fun deleteGuests(tokenHashes: Collection<String>) {
        if (tokenHashes.isNotEmpty()) execArray("DELETE FROM lobby.convidado WHERE token_hash = ANY (?)", tokenHashes)
    }

    override fun savePhoto(p: GuestPersistence.StoredPhoto) = exec(
        "INSERT INTO lobby.foto (convidado_id, base64, versao) VALUES (?, ?, ?) " +
            "ON CONFLICT (convidado_id) DO UPDATE SET base64 = EXCLUDED.base64, versao = EXCLUDED.versao",
        p.guestId, p.base64, p.version,
    )

    override fun deletePhotos(guestIds: Collection<String>) {
        if (guestIds.isNotEmpty()) execArray("DELETE FROM lobby.foto WHERE convidado_id = ANY (?)", guestIds)
    }

    private fun exec(sql: String, vararg args: Any) {
        db.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeUpdate()
            }
        }
    }

    private fun execArray(sql: String, values: Collection<String>) {
        db.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setArray(1, c.createArrayOf("text", values.toTypedArray()))
                st.executeUpdate()
            }
        }
    }
}

/** Onde as mesas sao gravadas (JSON por mesa). */
interface TablePersistence {
    fun loadAll(): List<Table>
    fun save(table: Table)
    fun delete(ids: Collection<String>)
}

/**
 * [TableStore] com cache em memoria e gravacao imediata a cada mudanca (write-through). Ao carregar, uma passagem ao
 * game-service em andamento quando o pod caiu e desfeita (como [LobbyService.abortStart]).
 */
class PersistentTableStore(private val persistence: TablePersistence) : TableStore {
    private val cache = InMemoryTableStore()

    init {
        persistence.loadAll().forEach { t ->
            val fixed = if (t.starting) {
                t.copy(seats = t.seats.mapIndexed { i, s -> if (i in t.robotFilled) Seat.Vago else s }, starting = false, robotFilled = emptySet())
                    .also(persistence::save)
            } else {
                t
            }
            cache.insert(fixed)
        }
    }

    @Synchronized
    override fun insert(table: Table): Boolean {
        if (cache.find(table.id)?.let { it.state != TableState.ENCERRADA } == true) return false
        persistence.save(table)
        return cache.insert(table)
    }

    @Synchronized
    override fun find(id: String): Table? = cache.find(id)

    @Synchronized
    override fun update(id: String, transform: (Table) -> Table): Table? {
        val current = cache.find(id) ?: return null
        val next = transform(current)
        if (next != current) persistence.save(next)
        return cache.update(id) { next }
    }

    @Synchronized
    override fun removeIf(predicate: (Table) -> Boolean): Int {
        val gone = cache.all().filter(predicate).map { it.id }.toSet()
        if (gone.isEmpty()) return 0
        persistence.delete(gone)
        return cache.removeIf { it.id in gone }
    }

    @Synchronized
    override fun count(): Int = cache.count()

    @Synchronized
    override fun all(): List<Table> = cache.all()
}

class PostgresTablePersistence(private val db: DataSource) : TablePersistence {
    override fun loadAll(): List<Table> = db.connection.use { c ->
        c.prepareStatement("SELECT dados FROM lobby.mesa").executeQuery().use { rs ->
            buildList { while (rs.next()) add(TableCodec.decode(rs.getString(1))) }
        }
    }

    override fun save(table: Table) {
        db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO lobby.mesa (id, dados, atualizada) VALUES (?, ?, now()) " +
                    "ON CONFLICT (id) DO UPDATE SET dados = EXCLUDED.dados, atualizada = now()",
            ).use { st ->
                st.setString(1, table.id)
                st.setString(2, TableCodec.encode(table))
                st.executeUpdate()
            }
        }
    }

    override fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.connection.use { c ->
            c.prepareStatement("DELETE FROM lobby.mesa WHERE id = ANY (?)").use { st ->
                st.setArray(1, c.createArrayOf("text", ids.toTypedArray()))
                st.executeUpdate()
            }
        }
    }
}

/** Conversao Mesa <-> JSON (formato so do banco; os contratos do app nao mudam). */
internal object TableCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class SeatRow(
        val kind: String,
        val guestId: String? = null,
        val name: String? = null,
        val profile: BotProfile? = null,
        val level: BotLevel? = null,
    )

    @Serializable
    private data class TableRow(
        val id: String,
        val hostId: String? = null,
        val hostName: String? = null,
        val rules: TableRules,
        val seats: List<SeatRow>,
        val state: TableState,
        val lastActivityMs: Long,
        val starting: Boolean = false,
        val createdMs: Long,
        val name: String = "",
        val entryOrder: List<String> = emptyList(),
        val robotFilled: Set<Int> = emptySet(),
        val connected: Set<String> = emptySet(),
        val abandoned: Set<String> = emptySet(),
    )

    fun encode(t: Table): String = json.encodeToString(
        TableRow.serializer(),
        TableRow(
            t.id, t.host?.id, t.host?.name, t.rules,
            t.seats.map {
                when (it) {
                    Seat.Vago -> SeatRow("VAGO")
                    is Seat.Humano -> SeatRow("HUMANO", guestId = it.guestId, name = it.name)
                    is Seat.Robo -> SeatRow("ROBO", profile = it.profile, level = it.level)
                }
            },
            t.state, t.lastActivityMs, t.starting, t.createdMs, t.name, t.entryOrder, t.robotFilled, t.connected, t.abandoned,
        ),
    )

    fun decode(text: String): Table {
        val r = json.decodeFromString(TableRow.serializer(), text)
        return Table(
            id = r.id,
            host = r.hostId?.let { Guest(it, r.hostName.orEmpty()) },
            rules = r.rules,
            seats = r.seats.map {
                when (it.kind) {
                    "HUMANO" -> Seat.Humano(it.guestId.orEmpty(), it.name.orEmpty())
                    "ROBO" -> Seat.Robo(it.profile ?: BotProfile.JOVEM, it.level ?: BotLevel.FACIL)
                    else -> Seat.Vago
                }
            },
            state = r.state,
            lastActivityMs = r.lastActivityMs,
            starting = r.starting,
            createdMs = r.createdMs,
            name = r.name,
            entryOrder = r.entryOrder,
            robotFilled = r.robotFilled,
            connected = r.connected,
            abandoned = r.abandoned,
        )
    }
}
