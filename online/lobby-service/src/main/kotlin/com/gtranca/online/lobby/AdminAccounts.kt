package com.gtranca.online.lobby

import com.gtranca.online.contracts.AccountKind
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.AdminAccountSummary
import java.util.Locale

/**
 * §18.7: visao de LEITURA das contas para o console de gerenciamento, juntando as sessoes vivas ([GuestRegistry],
 * convidados e contas Google) com as contas de e-mail ([EmailAccountRegistry], que tem e-mail e data de criacao
 * reais). Nunca expoe o token da sessao nem o hash da senha.
 *
 * Contas GOOGLE nao tem registro proprio nesta fase (§18.1 deixou o login Google no backlog): o lobby so sabe
 * delas enquanto uma sessao estiver viva (24 h de inatividade, ver [GuestRegistry]); por isso [AdminAccountSummary.createdMs]
 * fica `null` para elas (e para convidados) e uma conta Google sem sessao viva simplesmente nao aparece na busca.
 * Isso e uma lacuna conhecida, registrada no relatorio desta etapa (recomendacao: uma tabela `conta_google`
 * durável, como a `conta_email` da §18.1).
 */
class AdminAccountDirectory(
    private val guests: GuestRegistry,
    private val emailAccounts: EmailAccountRegistry,
) {
    private fun merged(): List<AdminAccountSummary> {
        val sessionsById = guests.snapshot().groupBy { it.id }
        val emailById = emailAccounts.snapshot().associateBy { it.id }
        return (sessionsById.keys + emailById.keys).map { id ->
            val sessions = sessionsById[id].orEmpty()
            val latestSession = sessions.maxByOrNull { it.lastUsedMs }
            val lastSeenMs = sessions.maxOfOrNull { it.lastUsedMs }
            val email = emailById[id]
            when {
                email != null -> AdminAccountSummary(
                    id = id, kind = AccountKind.EMAIL, name = latestSession?.name ?: email.name,
                    email = email.email, createdMs = email.createdMs, lastSeenMs = lastSeenMs,
                )
                AccountLimits.isAccountId(id) -> AdminAccountSummary(
                    id = id, kind = AccountKind.GOOGLE, name = latestSession?.name.orEmpty(), lastSeenMs = lastSeenMs,
                )
                else -> AdminAccountSummary(
                    id = id, kind = AccountKind.CONVIDADO, name = latestSession?.name.orEmpty(), lastSeenMs = lastSeenMs,
                )
            }
        }
    }

    /**
     * Busca por nome ou e-mail (substring, sem distinguir maiuscula/minuscula) ou pelo id exato; `q` vazio lista
     * tudo. Mais recente primeiro (data de criacao quando existe, senao o ultimo uso); `id` como criterio final,
     * para a paginacao ser estavel entre chamadas. @return (total sem paginar, a pagina pedida).
     */
    fun search(query: String, limit: Int, offset: Int): Pair<Int, List<AdminAccountSummary>> {
        val q = query.trim().lowercase(Locale.ROOT)
        val all = merged()
            .filter {
                q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) ||
                    it.email?.lowercase(Locale.ROOT)?.contains(q) == true || it.id.lowercase(Locale.ROOT) == q
            }
            .sortedWith(compareByDescending<AdminAccountSummary> { it.createdMs ?: it.lastSeenMs ?: 0L }.thenBy { it.id })
        val page = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_LIMIT))
        return all.size to page
    }

    /** A ficha de uma conta, ou `null` se nao existe (nem sessao viva, nem conta de e-mail) (§18.7). */
    fun detail(id: String): AdminAccountSummary? = merged().firstOrNull { it.id == id }

    companion object {
        const val MAX_LIMIT = 100
    }
}
