import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import { ApiRequestError, type CloseTableRequest, type TableState, type TableView } from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'
import {
  BOT_LEVEL_LABEL,
  PUNICAO_LABEL,
  TABLE_MODE_LABEL,
  TABLE_STATE_LABEL,
  TEMPO_DE_JOGADA_LABEL,
  VISIBILIDADE_LABEL,
} from './tableLabels'

const SEAT_KIND_LABEL: Record<string, string> = { VAGO: 'Vago', HUMANO: 'Humano', ROBO: 'Robô' }

/**
 * Ficha da mesa (docs/telas-admin-web.md, tela Mesas): leitor consulta; operador+ encerra mesas travadas, com
 * motivo. O aviso de confirmação muda conforme o estado (definition.md Sec18.4, decisão de 03/11/2026):
 * encerrar uma mesa EM_JOGO devolve a aposta a todos os jogadores (ninguém é punido); ABERTA é trivial.
 *
 * O estado usado no aviso e enviado como `expectedState` NÃO é o da página (que pode estar desatualizado
 * desde que a ficha abriu) - "Encerrar mesa" busca a ficha de novo antes de montar o diálogo, e o admin-api
 * recusa com 409 se a mesa tiver virado EM_JOGO nesse intervalo e o aviso mostrado não for o de EM_JOGO.
 */
export function TableDetail() {
  const { id = '' } = useParams<{ id: string }>()
  const { session, forceLogout } = useSession()
  const [table, setTable] = useState<TableView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [confirmState, setConfirmState] = useState<TableState | null>(null)
  const [openingConfirm, setOpeningConfirm] = useState(false)
  const [reason, setReason] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<string | null>(null)

  const canAct = session?.role === 'OPERADOR' || session?.role === 'ADMINISTRADOR'

  async function fetchTable(): Promise<TableView> {
    return apiFetch<TableView>(`/admin/tables/${encodeURIComponent(id)}`, { token: session?.token })
  }

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const response = await fetchTable()
        if (!cancelled) setTable(response)
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, session?.token, forceLogout])

  async function openConfirm() {
    setOpeningConfirm(true)
    setError(null)
    try {
      // Busca de novo: a pagina pode estar aberta ha um tempo e a mesa pode ter virado EM_JOGO desde entao - o
      // aviso certo (e o expectedState enviado no close) tem que refletir o estado AGORA, nao o da pagina.
      const fresh = await fetchTable()
      setTable(fresh)
      setConfirmState(fresh.state)
      setReason('')
      setActionError(null)
      setActionMessage(null)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setOpeningConfirm(false)
    }
  }

  async function confirmClose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setActionBusy(true)
    setActionError(null)
    try {
      const response = await apiFetch<TableView>(`/admin/tables/${encodeURIComponent(id)}/close`, {
        method: 'POST',
        token: session?.token,
        body: { reason, expectedState: confirmState ?? undefined } satisfies CloseTableRequest,
      })
      setTable(response)
      setActionMessage('Mesa encerrada.')
      setConfirmState(null)
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

  const avisoConfirmacao =
    confirmState === 'EM_JOGO'
      ? 'Isso vai encerrar a partida em andamento e devolver a aposta a todos os jogadores. Ninguém será penalizado. Continuar?'
      : 'Isso vai encerrar esta mesa. Continuar?'

  return (
    <main className="account-detail-page">
      <AppHeader title="Ficha da mesa" />
      <p>
        <Link to="/mesas">← Voltar para Mesas</Link>
      </p>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {table && (
        <>
          <section aria-label="Dados da mesa">
            <h2>{table.name || table.id}</h2>
            <dl>
              <dt>Id</dt>
              <dd>{table.id}</dd>
              <dt>Anfitrião</dt>
              <dd>{table.hostName}</dd>
              <dt>Estado</dt>
              <dd className={`estado-mesa-${table.state}`}>{TABLE_STATE_LABEL[table.state]}</dd>
              <dt>Modo</dt>
              <dd>{TABLE_MODE_LABEL[table.rules.mode]}</dd>
              <dt>Aposta</dt>
              <dd>{table.rules.bet}</dd>
              <dt>Pontuação-alvo</dt>
              <dd>{table.rules.targetScore}</dd>
              <dt>Tempo de jogada</dt>
              <dd>{TEMPO_DE_JOGADA_LABEL[table.rules.tempoDeJogada]}</dd>
              <dt>Punição de abandono</dt>
              <dd>{PUNICAO_LABEL[table.rules.punicao]}</dd>
              <dt>Visibilidade</dt>
              <dd>{VISIBILIDADE_LABEL[table.rules.visibilidade]}</dd>
              <dt>Nível dos robôs</dt>
              <dd>{BOT_LEVEL_LABEL[table.rules.robotLevel]}</dd>
              <dt>Boosters de perícia</dt>
              <dd>{table.rules.skillBoostersAllowed ? 'Permitidos' : 'Não permitidos'}</dd>
            </dl>
          </section>

          <section aria-label="Assentos">
            <h2>Assentos</h2>
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Quem</th>
                  <th>Nome</th>
                </tr>
              </thead>
              <tbody>
                {table.seats.map((seat) => (
                  <tr key={seat.index}>
                    <td>{seat.index + 1}</td>
                    <td>{SEAT_KIND_LABEL[seat.kind]}</td>
                    <td>{seat.name ?? (seat.level ? `Robô ${BOT_LEVEL_LABEL[seat.level]}` : '—')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {canAct && (
            <section aria-label="Ações">
              <h2>Ações</h2>
              {actionMessage && <p role="status">{actionMessage}</p>}
              {table.state !== 'ENCERRADA' && (
                <div className="acoes-futuras">
                  <button type="button" onClick={openConfirm} disabled={openingConfirm}>
                    Encerrar mesa
                  </button>
                </div>
              )}
            </section>
          )}

          {confirmState && (
            <div
              role="dialog"
              aria-modal="true"
              aria-label="Encerrar mesa"
              className="dialogo-confirmacao"
              onKeyDown={(e) => {
                if (e.key === 'Escape' && !actionBusy) setConfirmState(null)
              }}
            >
              <form onSubmit={confirmClose}>
                <h3>Encerrar mesa</h3>
                <p>{avisoConfirmacao}</p>
                <div className="campo">
                  <label htmlFor="motivo-encerrar">Motivo</label>
                  <textarea
                    id="motivo-encerrar"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    required
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
                  <button type="button" onClick={() => setConfirmState(null)} disabled={actionBusy}>
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
