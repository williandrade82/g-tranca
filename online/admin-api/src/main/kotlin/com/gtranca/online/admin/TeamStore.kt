package com.gtranca.online.admin

import com.gtranca.online.contracts.ErrorCode
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/** Resultado do login (Sec18.7): id, e-mail e o papel atual do membro. */
data class TeamLogin(val id: String, val email: String, val role: Role)

/**
 * Equipe do console (Sec18.7) e a auditoria das acoes sobre ela, no Postgres (schema `admin`). Cada mudanca de
 * equipe (criar, alterar, remover) e uma transacao so, que grava o lancamento de auditoria **na mesma transacao**
 * (nunca uma sem a outra). [operationId], quando informado, e a chave de idempotencia (UNIQUE em `auditoria`):
 * repetir a mesma operacao devolve o resultado gravado da primeira vez, sem duplicar o membro nem o registro.
 */
class TeamStore(
    private val db: DataSource,
    private val passwordCost: Int = 12,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val secure = SecureRandom()

    // Sec18.1 (mesma tecnica, espelhada): gasta o mesmo tempo de bcrypt para um e-mail inexistente, para o tempo de
    // resposta nao revelar se a conta existe.
    private val dummyHash by lazy { PasswordHashing.hash("equipe-inexistente-nao-usar", passwordCost) }

    private data class MemberRow(val id: String, val email: String, val passwordHash: String, val role: Role, val createdMs: Long)

    /** Sec18.7 login: credenciais erradas e e-mail inexistente dao o mesmo erro, com o mesmo tempo de resposta. */
    fun login(rawEmail: String, rawPassword: String): TeamLogin = tx { c ->
        val email = normalizeEmail(rawEmail)
        val row = email?.let { findByEmail(c, it) }
        val ok = PasswordHashing.matches(rawPassword, row?.passwordHash ?: dummyHash)
        if (row == null || !ok) adminError(ErrorCode.NAO_AUTENTICADO, "E-mail ou senha incorretos.", 401)
        TeamLogin(row.id, row.email, row.role)
    }

    /** Lista paginada (toda listagem e paginada), ordenada por data de criacao. @return (itens, total). */
    fun list(limit: Int, offset: Int): Pair<List<TeamMember>, Int> = tx { c ->
        val total = c.prepareStatement("SELECT count(*) FROM admin.membro").use { it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        val items = c.prepareStatement("SELECT id, email, papel, criado_ms FROM admin.membro ORDER BY criado_ms, id LIMIT ? OFFSET ?").use { st ->
            st.setInt(1, limit)
            st.setInt(2, offset)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(memberFromRow(rs)) } }
        }
        items to total
    }

    /** Sec18.7 "nao ha autocadastro": so quem ja e administrador chega aqui (conferido na rota). */
    fun create(rawEmail: String, rawPassword: String, role: Role, actor: Actor, reason: String, operationId: String?): TeamMember =
        withIdempotentRetry(operationId, ::findAuditResult) {
            tx { c ->
                operationId?.let { findAuditResult(c, it)?.let { cached -> return@tx cached } }
                val email = normalizeEmail(rawEmail) ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Informe um e-mail válido.", 400)
                validatePassword(rawPassword)
                if (findByEmail(c, email) != null) adminError(ErrorCode.EMAIL_JA_CADASTRADO, "Esse e-mail já está cadastrado.", 409)
                val id = randomId()
                val now = clock()
                val hash = PasswordHashing.hash(rawPassword, passwordCost)
                c.prepareStatement("INSERT INTO admin.membro (id, email, senha_hash, papel, criado_ms) VALUES (?, ?, ?, ?, ?)").use { st ->
                    st.setString(1, id)
                    st.setString(2, email)
                    st.setString(3, hash)
                    st.setString(4, role.name)
                    st.setLong(5, now)
                    st.executeUpdate()
                }
                val member = TeamMember(id, email, role, now)
                insertAudit(c, operationId, actor, "CRIAR_MEMBRO", id, email, reason, member, null)
                member
            }
        }

    /** Troca o papel e/ou a senha. Trocar a senha apaga as sessoes do membro (na mesma transacao). */
    fun update(id: String, newRole: Role?, newPassword: String?, actor: Actor, reason: String, operationId: String?): TeamMember =
        withIdempotentRetry(operationId, ::findAuditResult) {
            tx { c ->
                operationId?.let { findAuditResult(c, it)?.let { cached -> return@tx cached } }
                // Ordem fixa (id crescente) em TODAS as mutacoes que tocam administradores: evita deadlock entre
                // chamadas concorrentes que, de outro jeito, travariam linhas em ordens diferentes.
                val adminIds = lockAdministratorIds(c)
                val current = lockMember(c, id) ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Membro não encontrado.", 404)
                newPassword?.let(::validatePassword)
                val finalRole = newRole ?: current.role
                if (current.role == Role.ADMINISTRADOR && finalRole != Role.ADMINISTRADOR && adminIds.size <= 1) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Não é possível rebaixar o último administrador.", 409)
                }
                if (newRole != null) {
                    c.prepareStatement("UPDATE admin.membro SET papel = ? WHERE id = ?").use { st ->
                        st.setString(1, finalRole.name)
                        st.setString(2, id)
                        st.executeUpdate()
                    }
                }
                if (newPassword != null) {
                    val hash = PasswordHashing.hash(newPassword, passwordCost)
                    c.prepareStatement("UPDATE admin.membro SET senha_hash = ? WHERE id = ?").use { st ->
                        st.setString(1, hash)
                        st.setString(2, id)
                        st.executeUpdate()
                    }
                    // Sessao curta de proposito; trocar a senha invalida as sessoes abertas deste membro na hora.
                    c.prepareStatement("DELETE FROM admin.sessao WHERE membro_id = ?").use { st -> st.setString(1, id); st.executeUpdate() }
                }
                val member = TeamMember(id, current.email, finalRole, current.createdMs)
                val detail = buildList {
                    if (newRole != null) add("papel: ${current.role} -> $finalRole")
                    if (newPassword != null) add("senha redefinida")
                }.joinToString("; ").ifBlank { null }
                insertAudit(c, operationId, actor, "ALTERAR_MEMBRO", id, current.email, reason, member, detail)
                member
            }
        }

    /** Remove o membro (a sessao dele cai pela FK `ON DELETE CASCADE`, na mesma transacao). */
    fun remove(id: String, actor: Actor, reason: String, operationId: String?) {
        withIdempotentRetryUnit(operationId) {
            tx { c ->
                if (operationId != null && hasAuditOperation(c, operationId)) return@tx
                val adminIds = lockAdministratorIds(c)
                val current = lockMember(c, id) ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "Membro não encontrado.", 404)
                if (current.role == Role.ADMINISTRADOR && adminIds.size <= 1) {
                    adminError(ErrorCode.REQUISICAO_INVALIDA, "Não é possível remover o último administrador.", 409)
                }
                c.prepareStatement("DELETE FROM admin.membro WHERE id = ?").use { st -> st.setString(1, id); st.executeUpdate() }
                insertAudit(c, operationId, actor, "REMOVER_MEMBRO", id, current.email, reason, null, null)
            }
        }
    }

    /**
     * Semeia o primeiro administrador (variaveis de ambiente, so no arranque): nao faz nada se a equipe ja tem
     * alguem. O `pg_advisory_xact_lock` impede que duas replicas subindo juntas semeiem dois administradores.
     * @return true se semeou agora.
     */
    fun seedFirstAdmin(rawEmail: String, rawPassword: String): Boolean = tx { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { st -> st.setLong(1, SEED_LOCK_KEY); st.execute() }
        val already = c.prepareStatement("SELECT 1 FROM admin.membro LIMIT 1").use { it.executeQuery().use { rs -> rs.next() } }
        if (already) return@tx false
        val email = normalizeEmail(rawEmail) ?: adminError(ErrorCode.REQUISICAO_INVALIDA, "ADMIN_SEED_EMAIL inválido.", 400)
        validatePassword(rawPassword)
        val id = randomId()
        val now = clock()
        val hash = PasswordHashing.hash(rawPassword, passwordCost)
        c.prepareStatement("INSERT INTO admin.membro (id, email, senha_hash, papel, criado_ms) VALUES (?, ?, ?, 'ADMINISTRADOR', ?)").use { st ->
            st.setString(1, id)
            st.setString(2, email)
            st.setString(3, hash)
            st.setLong(4, now)
            st.executeUpdate()
        }
        val member = TeamMember(id, email, Role.ADMINISTRADOR, now)
        insertAudit(
            c, null, Actor("sistema", "sistema"), "SEMEAR_ADMINISTRADOR", id, email,
            "Primeiro administrador semeado no arranque (ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD).", member, null,
        )
        true
    }

    // ---------------------------------------------------------------------------------------------- idempotencia

    /**
     * Corrida rara: duas chamadas com o MESMO [operationId] passam ambas pela checagem "ja existe?" antes de
     * qualquer uma comitar. A segunda a inserir estoura a UNIQUE de `operacao_id`; a transacao dela roda de volta
     * (inclusive o membro que ela tentou criar/alterar) e so entao ela consulta de novo e devolve o resultado da
     * que ganhou a corrida, em vez de propagar o erro de banco.
     */
    private fun <T> withIdempotentRetry(operationId: String?, replay: (Connection, String) -> T?, block: () -> T): T {
        if (operationId == null) return block()
        return try {
            block()
        } catch (e: SQLException) {
            if (isUniqueViolation(e)) {
                db.connection.use { c -> replay(c, operationId) } ?: throw e
            } else {
                throw e
            }
        }
    }

    private fun withIdempotentRetryUnit(operationId: String?, block: () -> Unit) {
        try {
            block()
        } catch (e: SQLException) {
            if (operationId == null || !isUniqueViolation(e)) throw e
            // A outra chamada ja removeu (ou nao: REMOVER_MEMBRO nao grava `resultado`); so confere que a
            // operacao existe para nao propagar um erro de banco por uma repeticao legitima.
            val exists = db.connection.use { c -> hasAuditOperation(c, operationId) }
            if (!exists) throw e
        }
    }

    private fun isUniqueViolation(e: SQLException): Boolean = e.sqlState == "23505"

    // ------------------------------------------------------------------------------------------------- consultas

    private fun lockAdministratorIds(c: Connection): List<String> =
        c.prepareStatement("SELECT id FROM admin.membro WHERE papel = 'ADMINISTRADOR' ORDER BY id FOR UPDATE").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun lockMember(c: Connection, id: String): MemberRow? =
        c.prepareStatement("SELECT id, email, senha_hash, papel, criado_ms FROM admin.membro WHERE id = ? FOR UPDATE").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rowToMemberRow(rs) else null }
        }

    private fun findByEmail(c: Connection, email: String): MemberRow? =
        c.prepareStatement("SELECT id, email, senha_hash, papel, criado_ms FROM admin.membro WHERE email = ?").use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs -> if (rs.next()) rowToMemberRow(rs) else null }
        }

    private fun rowToMemberRow(rs: ResultSet) = MemberRow(rs.getString(1), rs.getString(2), rs.getString(3), Role.valueOf(rs.getString(4)), rs.getLong(5))

    private fun memberFromRow(rs: ResultSet) = TeamMember(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getLong(4))

    private fun findAuditResult(c: Connection, operationId: String): TeamMember? =
        c.prepareStatement("SELECT resultado FROM admin.auditoria WHERE operacao_id = ?").use { st ->
            st.setString(1, operationId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.let { json.decodeFromString(TeamMember.serializer(), it) } else null }
        }

    private fun hasAuditOperation(c: Connection, operationId: String): Boolean =
        c.prepareStatement("SELECT 1 FROM admin.auditoria WHERE operacao_id = ?").use { st ->
            st.setString(1, operationId)
            st.executeQuery().use { it.next() }
        }

    private fun insertAudit(
        c: Connection, operationId: String?, actor: Actor, action: String, targetId: String?, targetEmail: String?,
        reason: String, member: TeamMember?, detail: String?,
    ) {
        val resultado = member?.let { json.encodeToString(TeamMember.serializer(), it) }
        c.prepareStatement(
            "INSERT INTO admin.auditoria (operacao_id, quando_ms, ator_id, ator_email, acao, alvo_id, alvo_email, motivo, detalhe, resultado) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, operationId)
            st.setLong(2, clock())
            st.setString(3, actor.id)
            st.setString(4, actor.email)
            st.setString(5, action)
            st.setString(6, targetId)
            st.setString(7, targetEmail)
            st.setString(8, reason)
            st.setString(9, detail)
            st.setString(10, resultado)
            st.executeUpdate()
        }
    }

    private fun randomId(): String {
        val bytes = ByteArray(16)
        secure.nextBytes(bytes)
        return "M_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun <T> tx(block: (Connection) -> T): T = db.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    companion object {
        // Chave arbitraria e fixa do advisory lock do seed do primeiro administrador (Sec18.7).
        private const val SEED_LOCK_KEY = 727_180_107L
    }
}
