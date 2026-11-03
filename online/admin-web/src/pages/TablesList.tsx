import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiFetch } from '../api/client'
import { ApiRequestError, type TablesPageResponse } from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'
import { PUNICAO_LABEL, TABLE_MODE_LABEL, TABLE_STATE_LABEL, TEMPO_DE_JOGADA_LABEL } from './tableLabels'

const LIMIT = 20

/**
 * Tela Mesas (docs/telas-admin-web.md): lista em QUALQUER estado (ao contrário da lista do app, que nunca
 * mostra ENCERRADA). Sem busca por texto: o lobby-service ainda não tem essa agregação (ver relatório da
 * etapa); so paginação e, como atalho, abrir uma mesa direto pelo id (o suporte normalmente já tem o id de 6
 * caracteres que o jogador relatou).
 */
export function TablesList() {
  const { session, forceLogout } = useSession()
  const navigate = useNavigate()
  const [idBusca, setIdBusca] = useState('')
  const [offset, setOffset] = useState(0)
  const [data, setData] = useState<TablesPageResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const params = new URLSearchParams({ limit: String(LIMIT), offset: String(offset) })
        const response = await apiFetch<TablesPageResponse>(`/admin/tables?${params}`, { token: session?.token })
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
  }, [session?.token, forceLogout, offset])

  function handleAbrirPeloId(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (idBusca.trim()) navigate(`/mesas/${encodeURIComponent(idBusca.trim())}`)
  }

  const hasPrev = offset > 0
  const hasNext = data != null && offset + LIMIT < data.total

  return (
    <main className="accounts-page">
      <AppHeader title="Mesas" />

      <form className="accounts-busca" onSubmit={handleAbrirPeloId} role="search">
        <label htmlFor="busca-mesa-id">Abrir mesa pelo id</label>
        <input id="busca-mesa-id" type="text" value={idBusca} onChange={(e) => setIdBusca(e.target.value)} placeholder="ex.: AB12CD" />
        <button type="submit">Abrir</button>
      </form>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <section aria-label="Lista de mesas">
          {data.items.length === 0 ? (
            <p>Nenhuma mesa.</p>
          ) : (
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>Id</th>
                  <th>Mesa</th>
                  <th>Anfitrião</th>
                  <th>Estado</th>
                  <th>Modo</th>
                  <th>Aposta</th>
                  <th>Critérios</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((table) => (
                  <tr key={table.id}>
                    <td>{table.id}</td>
                    <td>{table.name || '—'}</td>
                    <td>{table.hostName}</td>
                    <td className={`estado-mesa-${table.state}`}>{TABLE_STATE_LABEL[table.state]}</td>
                    <td>{TABLE_MODE_LABEL[table.rules.mode]}</td>
                    <td>{table.rules.bet}</td>
                    <td>
                      {TEMPO_DE_JOGADA_LABEL[table.rules.tempoDeJogada]} · {PUNICAO_LABEL[table.rules.punicao]}
                    </td>
                    <td>
                      <Link to={`/mesas/${encodeURIComponent(table.id)}`}>Ver mesa</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="paginacao">
            <button type="button" disabled={!hasPrev} onClick={() => setOffset((o) => Math.max(0, o - LIMIT))}>
              Anterior
            </button>
            <span>
              {data.total === 0 ? '0' : `${offset + 1}–${Math.min(offset + LIMIT, data.total)}`} de {data.total}
            </span>
            <button type="button" disabled={!hasNext} onClick={() => setOffset((o) => o + LIMIT)}>
              Próxima
            </button>
          </div>
        </section>
      )}
    </main>
  )
}
