package com.gtranca.online.admin

import java.sql.Connection
import javax.sql.DataSource

/**
 * Auditoria append-only de toda acao do operador (Sec18.7: "quem, quando, o que, por que", imutavel). Nao
 * conhece tipo nenhum de dominio ([resultado] ja vem serializado por quem chama) - por isso serve tanto para a
 * Equipe ([TeamStore], que grava na MESMA transacao da mudanca) quanto para acoes sobre conta de jogador
 * (bloquear, desbloquear, encerrar sessoes: a mutacao de verdade e remota, no lobby-service, entao o [record]
 * so grava depois que a chamada remota confirmou - nao ha "mesma transacao" possivel com outro servico).
 */
class AuditLog(private val db: DataSource, private val clock: () -> Long = System::currentTimeMillis) {
    /** Insere dentro de uma transacao [c] ja aberta por quem chama (ex.: [TeamStore], junto com a mutacao). */
    fun insert(
        c: Connection, operationId: String?, actor: Actor, action: String,
        targetId: String?, targetEmail: String?, reason: String, resultado: String?, detail: String?,
    ) {
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

    fun hasOperation(c: Connection, operationId: String): Boolean =
        c.prepareStatement("SELECT 1 FROM admin.auditoria WHERE operacao_id = ?").use { st ->
            st.setString(1, operationId)
            st.executeQuery().use { it.next() }
        }

    /** Mesma consulta, abrindo a propria conexao (para quem quer conferir ANTES de uma mutacao remota, como
     * as rotas de bloquear/desbloquear/encerrar sessoes: evita repetir a chamada ao lobby-service). */
    fun hasOperation(operationId: String): Boolean = db.connection.use { c -> hasOperation(c, operationId) }

    /** O `resultado` gravado para [operationId], se houver (idempotencia com resposta: ver [TeamStore.findAuditResult]). */
    fun findResult(operationId: String): String? = db.connection.use { c ->
        c.prepareStatement("SELECT resultado FROM admin.auditoria WHERE operacao_id = ?").use { st ->
            st.setString(1, operationId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Abre a propria transacao e grava (para quem NAO tem uma mutacao local para acompanhar, ver KDoc da classe).
     * @return `true` se gravou agora; `false` se [operationId] ja tinha uma gravacao (idempotencia: nao duplica).
     */
    fun record(operationId: String?, actor: Actor, action: String, targetId: String?, targetEmail: String?, reason: String, resultado: String? = null, detail: String? = null): Boolean =
        db.connection.use { c ->
            c.autoCommit = false
            try {
                if (operationId != null && hasOperation(c, operationId)) {
                    c.commit()
                    return@use false
                }
                insert(c, operationId, actor, action, targetId, targetEmail, reason, resultado, detail)
                c.commit()
                true
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }

    /** Uma linha da auditoria para a tela Auditoria (Sec18.7): nunca inclui [resultado] (pode ser um JSON de
     * dominio grande, ex. carteira inteira - a lista so mostra quem/quando/o que/alvo/por que/detalhe). */
    data class Entry(
        val id: Long, val operationId: String?, val whenMs: Long, val actorEmail: String, val action: String,
        val targetId: String?, val targetEmail: String?, val reason: String, val detail: String?,
    )

    /** Lista paginada (toda listagem e paginada), mais recente primeiro. @return (total sem paginar, a pagina). */
    fun list(limit: Int, offset: Int): Pair<Int, List<Entry>> = db.connection.use { c ->
        val total = c.prepareStatement("SELECT count(*) FROM admin.auditoria").use { it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        val items = c.prepareStatement(
            "SELECT id, operacao_id, quando_ms, ator_email, acao, alvo_id, alvo_email, motivo, detalhe FROM admin.auditoria " +
                "ORDER BY quando_ms DESC, id DESC LIMIT ? OFFSET ?",
        ).use { st ->
            st.setInt(1, limit)
            st.setInt(2, offset)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Entry(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))
                    }
                }
            }
        }
        total to items
    }
}
