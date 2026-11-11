import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import {
  ApiRequestError,
  type AccountActionRequest,
  type AccountDetailResponse,
  type AccountKind,
  type AdminAdjustRequest,
  type AdminWalletDto,
  type LedgerPageResponse,
  type RevokeSessionsResponse,
  type TableState,
} from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'
import { newOperationId } from '../api/uuid'

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

const LEDGER_LIMIT = 10

/**
 * Ficha da conta (docs/telas-admin-web.md, tela Contas): leitor so consulta; operador+ bloqueia/desbloqueia,
 * encerra sessoes e ajusta a carteira, sempre com motivo (docs/telas-admin-web.md: "ação destrutiva pede
 * confirmação e motivo"). Exportar/apagar dados (LGPD) ainda nao tem rota no admin-api - continuam
 * desabilitados, "Em breve".
 */
export function AccountDetail() {
  const { id = '' } = useParams<{ id: string }>()
  const { session, forceLogout } = useSession()
  const [data, setData] = useState<AccountDetailResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [reason, setReason] = useState('')
  // Gerado UMA vez ao abrir o dialogo (newOperationId), nao a cada "Confirmar": uma nova tentativa depois de
  // um erro de rede repete a MESMA chave, nunca uma nova (ver AuditLog.record/EconomyStore.adjustWallet - os
  // dois dependem da chave ser estavel pra nao repetir a acao numa segunda tentativa).
  const [actionOperationId, setActionOperationId] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<string | null>(null)

  const [ledger, setLedger] = useState<LedgerPageResponse | null>(null)
  const [ledgerOffset, setLedgerOffset] = useState(0)
  const [ledgerError, setLedgerError] = useState<string | null>(null)
  // 404 do livro-razao quer dizer "esta conta ainda nao tem carteira online" (o economy-service nunca cria
  // uma so por consultar) - nao e um erro de verdade, por isso fica separado de ledgerError (sem role=alert).
  const [ledgerNoWallet, setLedgerNoWallet] = useState(false)
  // Incrementado depois de um ajuste bem sucedido pra forcar a releitura mesmo quando ledgerOffset ja era 0
  // (setLedgerOffset(0) nao dispara o efeito de novo se o valor nao mudou).
  const [ledgerReloadToken, setLedgerReloadToken] = useState(0)

  const [adjusting, setAdjusting] = useState(false)
  const [adjustReason, setAdjustReason] = useState('')
  const [adjustOperationId, setAdjustOperationId] = useState('')
  const [coinsDeltaInput, setCoinsDeltaInput] = useState('')
  const [boosterIdInput, setBoosterIdInput] = useState('')
  const [quantityDeltaInput, setQuantityDeltaInput] = useState('')
  const [adjustBusy, setAdjustBusy] = useState(false)
  const [adjustError, setAdjustError] = useState<string | null>(null)
  const [adjustMessage, setAdjustMessage] = useState<string | null>(null)

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

  useEffect(() => {
    let cancelled = false
    async function loadLedger() {
      setLedgerError(null)
      setLedgerNoWallet(false)
      try {
        const params = new URLSearchParams({ limit: String(LEDGER_LIMIT), offset: String(ledgerOffset) })
        const response = await apiFetch<LedgerPageResponse>(`/admin/accounts/${encodeURIComponent(id)}/ledger?${params}`, {
          token: session?.token,
        })
        if (!cancelled) setLedger(response)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          forceLogout()
          return
        }
        // 404 aqui e sempre "esta conta ainda nao tem carteira online" (a conta em si ja existe, estamos na
        // ficha dela) - nunca um erro de verdade, por isso nao usa role=alert.
        if (e instanceof ApiRequestError && e.status === 404) {
          setLedger(null)
          setLedgerNoWallet(true)
          return
        }
        setLedgerError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
      }
    }
    void loadLedger()
    return () => {
      cancelled = true
    }
  }, [id, session?.token, forceLogout, ledgerOffset, ledgerReloadToken])

  function openAction(action: PendingAction) {
    setPendingAction(action)
    setReason('')
    setActionOperationId(newOperationId())
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
          body: { reason, operationId: actionOperationId } satisfies AccountActionRequest,
        })
        setActionMessage(`Sessões encerradas: ${response.revoked}.`)
      } else {
        await apiFetch(path, {
          method: 'POST',
          token: session?.token,
          body: { reason, operationId: actionOperationId } satisfies AccountActionRequest,
        })
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

  function openAdjust() {
    setAdjusting(true)
    setAdjustReason('')
    setAdjustOperationId(newOperationId())
    setCoinsDeltaInput('')
    setBoosterIdInput('')
    setQuantityDeltaInput('')
    setAdjustError(null)
    setAdjustMessage(null)
  }

  async function confirmAdjust(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setAdjustError(null)
    // Validacao de FORMATO (nunca de negocio: saldo/quantidade negativos so o economy-service sabe recusar).
    if (coinsDeltaInput.trim() !== '' && !Number.isInteger(Number(coinsDeltaInput))) {
      setAdjustError('O ajuste de moedas precisa ser um número inteiro.')
      return
    }
    if (quantityDeltaInput.trim() !== '' && !Number.isInteger(Number(quantityDeltaInput))) {
      setAdjustError('O ajuste de quantidade precisa ser um número inteiro.')
      return
    }
    setAdjustBusy(true)
    try {
      // operationId gerado ao ABRIR o dialogo (openAdjust), nao aqui: uma nova tentativa depois de um erro
      // de rede repete a MESMA chave (ver EconomyStore.adjustWallet, idempotente por operationId).
      const body: AdminAdjustRequest = { reason: adjustReason, operationId: adjustOperationId }
      if (coinsDeltaInput.trim() !== '') body.coinsDelta = Number(coinsDeltaInput)
      if (boosterIdInput.trim() !== '') body.boosterId = boosterIdInput.trim()
      if (quantityDeltaInput.trim() !== '') body.quantityDelta = Number(quantityDeltaInput)
      const wallet = await apiFetch<AdminWalletDto>(`/admin/accounts/${encodeURIComponent(id)}/adjust`, {
        method: 'POST',
        token: session?.token,
        body,
      })
      setData((prev) => (prev ? { ...prev, wallet, walletUnavailable: false } : prev))
      setAdjustMessage('Carteira ajustada.')
      setAdjusting(false)
      // A entrada nova e a mais recente (lista ordenada do mais novo pro mais velho): volta pra 1a pagina e
      // forca a releitura (ledgerReloadToken) mesmo se o offset ja era 0.
      setLedgerOffset(0)
      setLedgerReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setAdjustError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setAdjustBusy(false)
    }
  }

  const ledgerHasPrev = ledgerOffset > 0
  const ledgerHasNext = ledger != null && ledgerOffset + LEDGER_LIMIT < ledger.total

  // Resumo ao vivo no dialogo de ajuste: o sinal errado (+ vs -) e o erro mais provavel aqui, mais facil de
  // apanhar numa frase do que olhando so o numero no campo.
  const coinsDeltaPreview = coinsDeltaInput.trim() !== '' ? Number(coinsDeltaInput) : null
  const quantityDeltaPreview = quantityDeltaInput.trim() !== '' ? Number(quantityDeltaInput) : null
  const ajusteResumo = [
    coinsDeltaPreview != null && !Number.isNaN(coinsDeltaPreview)
      ? `Vai ${coinsDeltaPreview >= 0 ? 'creditar' : 'debitar'} ${Math.abs(coinsDeltaPreview)} moeda${Math.abs(coinsDeltaPreview) === 1 ? '' : 's'}`
      : null,
    boosterIdInput.trim() !== '' && quantityDeltaPreview != null && !Number.isNaN(quantityDeltaPreview)
      ? `${boosterIdInput.trim()}: ${quantityDeltaPreview >= 0 ? '+' : ''}${quantityDeltaPreview}`
      : null,
  ]
    .filter((parte): parte is string => parte != null)
    .join(' · ')

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
            {adjustMessage && <p role="status">{adjustMessage}</p>}
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
            {/* So com carteira de verdade: ajustar numa conta sem carteira a CRIARIA (EconomyStore.adjustWallet
                chama ensureAccount antes de tudo) - efeito indesejado que as rotas de leitura evitam de
                proposito (ver relatorio da etapa, pendencia pro online-backend-dev). */}
            {canAct && data.wallet != null && (
              <button type="button" onClick={openAdjust}>
                Ajustar saldo ou booster
              </button>
            )}
          </section>

          <section aria-label="Livro-razão">
            <h2>Livro-razão</h2>
            {ledgerError && <p role="alert">{ledgerError}</p>}
            {ledgerNoWallet && <p>Sem lançamentos (conta sem carteira online).</p>}
            {ledger && (
              <>
                {ledger.items.length === 0 ? (
                  <p>Nenhum lançamento.</p>
                ) : (
                  <table className="accounts-tabela">
                    <thead>
                      <tr>
                        <th>Quando</th>
                        <th>Motivo</th>
                        <th>Moedas</th>
                        <th>Booster</th>
                        <th>Detalhe</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ledger.items.map((entry) => (
                        <tr key={entry.key}>
                          <td>{formatDate(entry.createdMs)}</td>
                          <td>{entry.reason}</td>
                          <td>{entry.coins !== 0 ? entry.coins : '—'}</td>
                          <td>{entry.boosterId ? `${entry.boosterId}: ${entry.quantity}` : '—'}</td>
                          <td>{entry.detail ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <div className="paginacao">
                  <button type="button" disabled={!ledgerHasPrev} onClick={() => setLedgerOffset((o) => Math.max(0, o - LEDGER_LIMIT))}>
                    Anterior
                  </button>
                  <span>
                    {ledger.total === 0 ? '0' : `${ledgerOffset + 1}–${Math.min(ledgerOffset + LEDGER_LIMIT, ledger.total)}`} de {ledger.total}
                  </span>
                  <button type="button" disabled={!ledgerHasNext} onClick={() => setLedgerOffset((o) => o + LEDGER_LIMIT)}>
                    Próxima
                  </button>
                </div>
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

          {adjusting && (
            <div
              role="dialog"
              aria-modal="true"
              aria-label="Ajustar saldo ou booster"
              className="dialogo-confirmacao"
              onKeyDown={(e) => {
                if (e.key === 'Escape' && !adjustBusy) setAdjusting(false)
              }}
            >
              <form onSubmit={confirmAdjust}>
                <h3>Ajustar saldo ou booster</h3>
                <p>Informe ao menos um ajuste: moedas, booster, ou os dois. O saldo e a quantidade nunca ficam negativos.</p>
                <div className="campo">
                  <label htmlFor="ajuste-moedas">Ajuste de moedas (ex.: 500 ou -200)</label>
                  <input
                    id="ajuste-moedas"
                    type="number"
                    step="1"
                    value={coinsDeltaInput}
                    onChange={(e) => setCoinsDeltaInput(e.target.value)}
                    autoFocus
                    disabled={adjustBusy}
                  />
                </div>
                <div className="campo">
                  <label htmlFor="ajuste-booster-id">Booster (id)</label>
                  <input
                    id="ajuste-booster-id"
                    type="text"
                    value={boosterIdInput}
                    onChange={(e) => setBoosterIdInput(e.target.value)}
                    disabled={adjustBusy}
                  />
                </div>
                <div className="campo">
                  <label htmlFor="ajuste-booster-qtd">Ajuste de quantidade do booster</label>
                  <input
                    id="ajuste-booster-qtd"
                    type="number"
                    step="1"
                    value={quantityDeltaInput}
                    onChange={(e) => setQuantityDeltaInput(e.target.value)}
                    disabled={adjustBusy}
                  />
                </div>
                {ajusteResumo && <p role="status">{ajusteResumo}</p>}
                <div className="campo">
                  <label htmlFor="motivo-ajuste">Motivo</label>
                  <textarea
                    id="motivo-ajuste"
                    value={adjustReason}
                    onChange={(e) => setAdjustReason(e.target.value)}
                    required
                    minLength={3}
                    maxLength={500}
                    disabled={adjustBusy}
                  />
                </div>
                {adjustError && <p role="alert">{adjustError}</p>}
                <div className="dialogo-botoes">
                  <button type="submit" disabled={adjustBusy}>
                    Confirmar
                  </button>
                  <button type="button" onClick={() => setAdjusting(false)} disabled={adjustBusy}>
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
