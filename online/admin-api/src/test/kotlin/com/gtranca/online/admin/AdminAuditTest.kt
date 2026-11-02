package com.gtranca.online.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Sec18.7 "o registro nao se altera": a tabela `auditoria` so aceita INSERT; UPDATE/DELETE/TRUNCATE sao recusados
 * pelo banco (trigger), mesmo pelo usuario dono da tabela. Nunca a senha nem o hash entram na auditoria.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuditTest {
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
    fun `18_7 cadastrar um membro grava quem, quando, o que e por que na auditoria`() {
        store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        val admin = store.list(10, 0).first.single()
        store.create("leitor@exemplo.com", "segredo123", Role.LEITOR, Actor(admin.id, admin.email), "cadastro inicial da equipe", null)

        val row = db.connection.use { c ->
            c.prepareStatement("SELECT ator_email, acao, alvo_email, motivo FROM admin.auditoria WHERE acao = 'CRIAR_MEMBRO'").use { st ->
                st.executeQuery().use { rs -> rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
            }
        }
        row shouldBe listOf("admin@exemplo.com", "CRIAR_MEMBRO", "leitor@exemplo.com", "cadastro inicial da equipe")
    }

    @Test
    fun `18_7 nenhuma linha da auditoria contem a senha ou o hash de senha`() {
        store.seedFirstAdmin("admin@exemplo.com", "s3gredo-bem-dificil")
        val admin = store.list(10, 0).first.single()
        store.create("leitor@exemplo.com", "outra-senha-secreta", Role.LEITOR, Actor(admin.id, admin.email), "cadastro", null)
        store.update(admin.id, null, "senha-nova-trocada", Actor(admin.id, admin.email), "troca de senha", null)

        val allText = db.connection.use { c ->
            c.prepareStatement("SELECT ator_id, ator_email, acao, alvo_id, alvo_email, motivo, detalhe, resultado FROM admin.auditoria").use { st ->
                st.executeQuery().use { rs ->
                    buildString {
                        while (rs.next()) for (i in 1..8) append(rs.getString(i)).append('|')
                    }
                }
            }
        }
        allText shouldNotContain "s3gredo-bem-dificil"
        allText shouldNotContain "outra-senha-secreta"
        allText shouldNotContain "senha-nova-trocada"
        allText shouldNotContain "\$2a$" // nunca o hash bcrypt tambem
    }

    @Test
    fun `18_7 UPDATE e DELETE na auditoria sao recusados pelo banco`() {
        store.seedFirstAdmin("admin@exemplo.com", "segredo123")
        shouldThrow<SQLException> { db.connection.use { it.createStatement().execute("UPDATE admin.auditoria SET motivo = 'alterado' WHERE id = 1") } }
        shouldThrow<SQLException> { db.connection.use { it.createStatement().execute("DELETE FROM admin.auditoria WHERE id = 1") } }
        shouldThrow<SQLException> { db.connection.use { it.createStatement().execute("TRUNCATE admin.auditoria") } }
    }
}
