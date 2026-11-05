package com.gtranca.online.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Sec18.7: semear o primeiro administrador no arranque (ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD), uma unica vez. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminSeedTest {
    private val pg = EmbeddedPostgres.start()
    private val db: DataSource = pg.postgresDatabase.also { Database.migrate(it) }
    private val store = TeamStore(db, passwordCost = 4)

    @BeforeEach
    fun clean() {
        db.connection.use { it.createStatement().execute("DROP SCHEMA IF EXISTS admin CASCADE") }
        Database.migrate(db)
    }

    @AfterAll
    fun stop() = pg.close()

    @Test
    fun `18_7 semeia o primeiro administrador quando a equipe esta vazia e audita como sistema`() {
        store.seedFirstAdmin("ana@exemplo.com", "segredo123") shouldBe true
        val (items, total) = store.list(10, 0)
        total shouldBe 1
        items.single().email shouldBe "ana@exemplo.com"
        items.single().role shouldBe Role.ADMINISTRADOR

        val audit = db.connection.use { c ->
            c.prepareStatement("SELECT ator_id, ator_email, acao, motivo FROM admin.auditoria WHERE acao = 'SEMEAR_ADMINISTRADOR'").use { st ->
                st.executeQuery().use { rs -> rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
            }
        }
        audit[0] shouldBe "sistema"
        audit[1] shouldBe "sistema"
    }

    @Test
    fun `18_7 nao semeia de novo (nem sobrescreve) se a equipe ja tem gente`() {
        store.seedFirstAdmin("ana@exemplo.com", "segredo123") shouldBe true
        store.seedFirstAdmin("outra@exemplo.com", "outrasenha") shouldBe false

        val (items, total) = store.list(10, 0)
        total shouldBe 1
        items.single().email shouldBe "ana@exemplo.com"
    }

    @Test
    fun `18_7 seed com senha curta ou e-mail invalido falha sem criar nada`() {
        shouldThrow<AdminException> { store.seedFirstAdmin("ana@exemplo.com", "123") }
        store.list(10, 0).second shouldBe 0

        shouldThrow<AdminException> { store.seedFirstAdmin("nao-e-email", "segredo123") }
        store.list(10, 0).second shouldBe 0
    }
}
