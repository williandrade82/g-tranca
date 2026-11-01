package com.gtranca.online.lobby

import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Base64
import kotlin.random.Random

/** Plano 2.4 / §18.2: reiniciar o lobby nao apaga convidados, tokens, fotos nem mesas. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LobbyPersistenceTest {
    private val pg = EmbeddedPostgres.start()
    private val db = pg.postgresDatabase.also { LobbyDatabase.migrate(it) }
    private var now = 1_000_000L
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("TRUNCATE lobby.convidado, lobby.foto, lobby.mesa") }
    }

    @AfterAll
    fun stop() = pg.close()

    private fun registry() = GuestRegistry({ now }, persistence = PostgresGuestPersistence(db))
    private fun service() = LobbyService(PersistentTableStore(PostgresTablePersistence(db)), { now }, Random(1))

    @Test
    fun `migrar de novo nao faz nada e o historico fica no schema lobby`() {
        LobbyDatabase.migrate(db)
        db.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM lobby.flyway_lobby_history").use { it.next(); it.getInt(1) } shouldNotBe 0
        }
    }

    @Test
    fun `18_2 convidado, token e foto sobrevivem ao reinicio`() {
        val id = registry().let { r ->
            val g = r.create("Ana")
            r.setPhoto(g.id, jpeg)
            g
        }
        val after = registry()
        after.authenticate(id.token) shouldBe Guest(id.id, "Ana")
        after.photo(id.id) shouldBe jpeg
        after.photoVersion(id.id) shouldBe 1
        after.authenticate("token-falso") shouldBe null
    }

    @Test
    fun `18_2 o banco guarda so o hash do token`() {
        val g = registry().create("Beto")
        db.connection.use { c ->
            c.createStatement().executeQuery("SELECT token_hash FROM lobby.convidado").use { rs ->
                rs.next()
                rs.getString(1) shouldBe GuestRegistry.hash(g.token)
            }
        }
    }

    @Test
    fun `18_2 convidado expirado e apagado do banco`() {
        val g = registry().create("Caio")
        now += 25 * 3_600_000L
        registry().authenticate(g.token) shouldBe null
        registry().authenticate(g.token) shouldBe null
        db.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM lobby.convidado").use { it.next(); it.getInt(1) } shouldBe 0
        }
    }

    @Test
    fun `18_2 a mesa e o lugar ocupado sobrevivem ao reinicio`() {
        val ana = Guest("a", "Ana")
        val s1 = service()
        val id = s1.list(0).tables.first().id
        s1.join(ana, id, 2)
        val after = service().list(0).tables
        after.map { it.id } shouldContain id
        val t = after.first { it.id == id }
        t.state shouldBe TableState.ABERTA
        t.seats[2].kind shouldBe SeatKind.HUMANO
        t.seats[2].guestId shouldBe "a"
    }

    @Test
    fun `passagem ao game-service interrompida pelo reinicio volta a mesa ABERTA sem os robos postos`() {
        val ana = Guest("a", "Ana")
        val s1 = service()
        val id = s1.list(0).tables.first().id
        s1.join(ana, id, 0)
        s1.beginStart(ana, id)
        val t = service().get(ana, id)
        t.state shouldBe TableState.ABERTA
        t.seats.count { it.kind == SeatKind.ROBO } shouldBe 0
        t.seats[0].kind shouldBe SeatKind.HUMANO
    }

    @Test
    fun `18_1 conta Google com dois logins tem duas sessoes e a foto da conta sobrevive ao reinicio`() {
        val (a, b) = registry().let { r ->
            val a = r.createSession("G_abc", "Ana")
            val b = r.createSession("G_abc", "Ana")
            r.setPhoto("G_abc", jpeg)
            a to b
        }
        now += 25 * 3_600_000L // sessoes expiram; a foto da conta fica
        val r2 = registry()
        r2.create("Beto") // dispara a limpeza
        r2.photo("G_abc") shouldBe jpeg
        val r3 = GuestRegistry({ 1_000_000L }, persistence = PostgresGuestPersistence(db))
        (a.token != b.token) shouldBe true
        r3.photo("G_abc") shouldBe jpeg
    }

    @Test
    fun `18_1 os dois tokens da mesma conta valem depois do reinicio`() {
        val (a, b) = registry().let { r -> r.createSession("G_x", "Ana") to r.createSession("G_x", "Ana") }
        val r2 = registry()
        r2.authenticate(a.token)?.id shouldBe "G_x"
        r2.authenticate(b.token)?.id shouldBe "G_x"
    }
}
