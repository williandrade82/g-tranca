package com.gtranca.online.lobby

import javax.sql.DataSource

/**
 * §18.7 ("Bloquear conta", `definition.md`, decisao de 03/11/2026): impede a conta de LOGAR de novo e de ENTRAR
 * em mesa nova; nao ejeta de uma partida em andamento (quem ja esta jogando segue, mesmo bloqueado durante o
 * jogo) e nao invalida tokens ja emitidos (isso e "Encerrar sessoes", [GuestRegistry.revokeSessions], acao
 * INDEPENDENTE). Desbloquear reverte, sem efeito retroativo.
 *
 * Funciona por id de conta, independente do tipo (CONVIDADO/EMAIL/GOOGLE): a linha existir no banco e que
 * significa "bloqueada", nao uma coluna por tabela de conta (EMAIL e GOOGLE nem tem a mesma tabela hoje, ver
 * pendencia sobre `conta_google` no relatorio da etapa anterior). Convidado TAMBEM pode ser bloqueado aqui:
 * decidimos nao excluir esse caso porque o mecanismo e o mesmo e nao custa nada extra, mas e uma protecao fraca
 * (o id do convidado nao e duradouro — reinstalar o app ou limpar dados cria um convidado novo, nao bloqueado).
 */
interface AccountBlockPersistence {
    class StoredBlock(val accountId: String, val reason: String?, val blockedAtMs: Long)

    fun loadAll(): List<StoredBlock>
    fun block(block: StoredBlock)
    fun unblock(accountId: String)
}

class PostgresAccountBlockPersistence(private val db: DataSource) : AccountBlockPersistence {
    override fun loadAll(): List<AccountBlockPersistence.StoredBlock> = db.connection.use { c ->
        c.prepareStatement("SELECT conta_id, motivo, bloqueada_em FROM lobby.bloqueio_conta").executeQuery().use { rs ->
            buildList { while (rs.next()) add(AccountBlockPersistence.StoredBlock(rs.getString(1), rs.getString(2), rs.getLong(3))) }
        }
    }

    override fun block(block: AccountBlockPersistence.StoredBlock) {
        db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO lobby.bloqueio_conta (conta_id, motivo, bloqueada_em) VALUES (?, ?, ?) " +
                    "ON CONFLICT (conta_id) DO UPDATE SET motivo = EXCLUDED.motivo, bloqueada_em = EXCLUDED.bloqueada_em",
            ).use { st ->
                st.setString(1, block.accountId)
                st.setString(2, block.reason)
                st.setLong(3, block.blockedAtMs)
                st.executeUpdate()
            }
        }
    }

    override fun unblock(accountId: String) {
        db.connection.use { c ->
            c.prepareStatement("DELETE FROM lobby.bloqueio_conta WHERE conta_id = ?").use { st ->
                st.setString(1, accountId)
                st.executeUpdate()
            }
        }
    }
}

/**
 * Cache em memoria do bloqueio, com copia duravel quando ha [persistence] (sem ela, so memoria: desenvolvimento e
 * testes). Idempotente: bloquear quem ja esta bloqueado (ou desbloquear quem nao esta) nao e erro.
 */
class AccountBlockRegistry(
    private val persistence: AccountBlockPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val blocked = HashMap<String, Long>() // accountId -> bloqueada_em

    init {
        persistence?.loadAll()?.forEach { blocked[it.accountId] = it.blockedAtMs }
    }

    @Synchronized
    fun isBlocked(accountId: String): Boolean = accountId in blocked

    @Synchronized
    fun block(accountId: String, reason: String?) {
        val now = clock()
        persistence?.block(AccountBlockPersistence.StoredBlock(accountId, reason, now))
        blocked[accountId] = now
    }

    @Synchronized
    fun unblock(accountId: String) {
        persistence?.unblock(accountId)
        blocked.remove(accountId)
    }
}
