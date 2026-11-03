import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import {
  ApiRequestError,
  type AccountActionRequest,
  type AccountDetailResponse,
  type AccountKind,
  type RevokeSessionsResponse,
  type TableState,
} from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'

const KIND_LABEL: Record<AccountKind, string> = {
  CONVIDADO: 'Convidado',
  GOOGLE: 'Google',
  EMAIL: 'E-mail e senha',
}

const TABLE_STATE_LABEL: Record<TableState, string> = {
  ABERTA: 'Aberta',
  EM_JOGO: 'Em jogo',
  ENCERRADA: 'Encerrada',
}

function formatDate(ms: number | null): string {
  if (ms == null) return '—'
  return new Date(ms).toLocaleString('pt-BR')
}

type PendingAction = 'block' | 'unblock' | 'revoke'

const ACTION_TITLE: Record<PendingAction, string> = {
  block: 'Bloquear conta',
  unblock: 'Desbloquear conta',
  revoke: 'Encerrar sessões',
}

const ACTION_PATH: Record<PendingAction, string> = {
  block: 'block',
  unblock: 'unblock',
  revoke: 'revoke-sessions',
}

/**
 * Ficha da conta (docs/telas-admin-web.md, tela Contas): leitor so consulta; operador+ bloqueia/desbloqueia e
 * encerra sessoes, sempre com motivo (docs/telas-admin-web.md: "ação destrutiva pede confirmação e motivo").
 * Exportar/apagar dados (LGPD) ainda nao tem rota no admin-api - continuam desabilitados, "Em breve".
 */
export function AccountDetail() {
  const { id = '' } = useParams<{ id: string }>()
  const { session, forceLogout } = useSession()
  const [data, setData] = useState<AccountDetailResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [reason, setReason] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<string | null>(null)

  const canAct = session?.role === 'OPERADOR' || session?.role === 'ADMINISTRADOR'

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const response = await apiFetch<AccountDetailResponse>(`/admin/accounts/${encodeURIComponent(id)}`, {
          token: session?.token,
        })
        if (!cancelled) setData(response)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          forceLogout()
          return
        }
        setError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [id, session?.token, forceLogout])

  function openAction(action: PendingAction) {
    setPendingAction(action)
    setReason('')
    setActionError(null)
    setActionMessage(null)
  }

  async function confirmAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!pendingAction) return
    setActionBusy(true)
    setActionError(null)
    try {
      const path = `/admin/accounts/${encodeURIComponent(id)}/${ACTION_PATH[pendingAction]}`
      if (pendingAction === 'revoke') {
        const response = await apiFetch<RevokeSessionsResponse>(path, {
          method: 'POST',
          token: session?.token,
          body: { reason } satisfies AccountActionRequest,
        })
        setActionMessage(`Sessões encerradas: ${response.revoked}.`)
      } else {
        await apiFetch(path, { method: 'POST', token: session?.token, body: { reason } satisfies AccountActionRequest })
        setData((prev) => (prev ? { ...prev, account: { ...prev.account, blocked: pendingAction === 'block' } } : prev))
        setActionMessage(pendingAction === 'block' ? 'Conta bloqueada.' : 'Conta desbloqueada.')
      }
      setPendingAction(null)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setActionError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setActionBusy(false)
    }
  }

  return (
    <main className="account-detail-page">
      <AppHeader title="Ficha da conta" />
      <p>
        <Link to="/contas">← Voltar para Contas</Link>
      </p>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <>
          <section aria-label="Dados da conta">
            <h2>{data.account.name || '(sem nome)'}</h2>
            <dl>
              <dt>Método de login</dt>
              <dd>{KIND_LABEL[data.account.kind]}</dd>
              <dt>E-mail</dt>
              <dd>{data.account.email ?? '—'}</dd>
              <dt>Cadastro</dt>
              <dd>{formatDate(data.account.createdMs)}</dd>
              <dt>Último acesso</dt>
              <dd>{formatDate(data.account.lastSeenMs)}</dd>
              <dt>Bloqueada</dt>
              <dd>{data.account.blocked ? 'Sim' : 'Não'}</dd>
            </dl>
          </section>

          <section aria-label="Mesas atuais">
            <h2>Mesas atuais</h2>
            <p>Mesas onde esta conta está sentada agora — não é o histórico de partidas.</p>
            {data.account.currentTables.length === 0 ? (
              <p>Nenhuma mesa agora.</p>
            ) : (
              <ul>
                {data.account.currentTables.map((table) => (
                  <li key={table.id}>
                    {table.name} — {TABLE_STATE_LABEL[table.state]}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label="Carteira">
            <h2>Carteira</h2>
            {data.wallet == null ? (
              <p>{data.walletUnavailable ? 'indisponível' : 'Sem carteira online ainda.'}</p>
            ) : (
              <>
                <p>Saldo: {data.wallet.coins} moedas</p>
                <p>Carteira local migrada: {data.wallet.migratedLocal ? 'Sim' : 'Não'}</p>
                <h3>Inventário</h3>
                {Object.keys(data.wallet.inventory).length === 0 ? (
                  <p>Sem boosters.</p>
                ) : (
                  <ul>
                    {Object.entries(data.wallet.inventory).map(([booster, quantidade]) => (
                      <li key={booster}>
                        {booster}: {quantidade}
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </section>

          <section aria-label="Ações">
            <h2>Ações</h2>
            {actionMessage && <p role="status">{actionMessage}</p>}
            <div className="acoes-futuras">
              {canAct && (
                <>
                  <button type="button" onClick={() => openAction(data.account.blocked ? 'unblock' : 'block')}>
                    {data.account.blocked ? 'Desbloquear conta' : 'Bloquear conta'}
                  </button>
                  <button type="button" onClick={() => openAction('revoke')}>
                    Encerrar sessões
                  </button>
                </>
              )}
              <button type="button" disabled title="Em breve">
                Exportar dados (LGPD)
              </button>
              <button type="button" disabled title="Em breve">
                Apagar dados (LGPD)
              </button>
            </div>
          </section>

          {pendingAction && (
            <div
              role="dialog"
              aria-modal="true"
              aria-label={ACTION_TITLE[pendingAction]}
              className="dialogo-confirmacao"
              onKeyDown={(e) => {
                if (e.key === 'Escape' && !actionBusy) setPendingAction(null)
              }}
            >
              <form onSubmit={confirmAction}>
                <h3>{ACTION_TITLE[pendingAction]}</h3>
                <div className="campo">
                  <label htmlFor="motivo-acao">Motivo</label>
                  <textarea
                    id="motivo-acao"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    required
                    // Mesmos limites de AdminLimits.MIN_REASON/MAX_REASON (admin-api/.../Domain.kt): o admin-api
                    // recusa fora dessa faixa de qualquer jeito, isto so evita a viagem ao servidor pra descobrir.
                    minLength={3}
                    maxLength={500}
                    autoFocus
                    disabled={actionBusy}
                  />
                </div>
                {actionError && <p role="alert">{actionError}</p>}
                <div className="dialogo-botoes">
                  <button type="submit" disabled={actionBusy}>
                    Confirmar
                  </button>
                  <button type="button" onClick={() => setPendingAction(null)} disabled={actionBusy}>
                    Cancelar
                  </button>
                </div>
              </form>
            </div>
          )}
        </>
      )}
    </main>
  )
}
