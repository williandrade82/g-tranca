package com.gtranca.online.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.sql.DataSource

/** Quem o token identifica, com o papel ja lido do banco nesta chamada (nunca guardado no proprio token). */
data class SessionMember(val id: String, val email: String, val role: Role)

/**
 * Sessao da equipe do console (Sec18.7): token opaco (32 bytes aleatorios, Base64 URL), so o hash SHA-256 gravado
 * em `admin.sessao` (mesma tecnica do `GuestRegistry` do lobby-service, implementada aqui sem depender daquele
 * modulo: nem um despejo de banco revela um token valido).
 *
 * Expira por ociosidade ([idleTtlMs]) OU por um teto absoluto desde a criacao ([absoluteTtlMs]), o que vier
 * primeiro: sessao **curta** de proposito (console administrativo, nao o jogo). O papel e sempre relido do banco
 * (JOIN com `membro`) a cada chamada: remover ou rebaixar alguem vale na hora, sem esperar o token expirar.
 */
class SessionStore(
    private val db: DataSource,
    private val idleTtlMs: Long = 30 * 60_000L,
    private val absoluteTtlMs: Long = 8 * 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val secure = SecureRandom()

    fun create(memberId: String): String {
        val bytes = ByteArray(32).also(secure::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        db.connection.use { c ->
            c.prepareStatement("INSERT INTO admin.sessao (token_hash, membro_id, criada_ms, ultimo_uso_ms) VALUES (?, ?, ?, ?)").use { st ->
                st.setString(1, hash(token))
                st.setString(2, memberId)
                st.setLong(3, now)
                st.setLong(4, now)
                st.executeUpdate()
            }
        }
        return token
    }

    /** `null` = sem token valido (inexistente, ocioso demais ou passou do teto absoluto). */
    fun authenticate(token: String): SessionMember? {
        val now = clock()
        val h = hash(token)
        return db.connection.use { c ->
            data class Row(val createdMs: Long, val lastUsedMs: Long, val id: String, val email: String, val role: String)
            val row = c.prepareStatement(
                "SELECT s.criada_ms, s.ultimo_uso_ms, m.id, m.email, m.papel " +
                    "FROM admin.sessao s JOIN admin.membro m ON m.id = s.membro_id WHERE s.token_hash = ?",
            ).use { st ->
                st.setString(1, h)
                st.executeQuery().use { rs -> if (rs.next()) Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)) else null }
            } ?: return@use null
            if (now - row.createdMs > absoluteTtlMs || now - row.lastUsedMs > idleTtlMs) {
                c.prepareStatement("DELETE FROM admin.sessao WHERE token_hash = ?").use { it.setString(1, h); it.executeUpdate() }
                return@use null
            }
            c.prepareStatement("UPDATE admin.sessao SET ultimo_uso_ms = ? WHERE token_hash = ?").use {
                it.setLong(1, now)
                it.setString(2, h)
                it.executeUpdate()
            }
            SessionMember(row.id, row.email, Role.valueOf(row.role))
        }
    }

    /** Sec18.7 "Sair": revoga a sessao no servidor. Token inexistente ou ja expirado: nao faz nada (idempotente). */
    fun revoke(token: String) {
        db.connection.use { c ->
            c.prepareStatement("DELETE FROM admin.sessao WHERE token_hash = ?").use {
                it.setString(1, hash(token))
                it.executeUpdate()
            }
        }
    }

    companion object {
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
