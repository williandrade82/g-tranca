import { useEffect, useState } from 'react'
import { apiFetch } from '../api/client'
import { ApiRequestError, type AuditPageResponse } from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'

function formatDate(ms: number): string {
  return new Date(ms).toLocaleString('pt-BR')
}

const LIMIT = 20

/**
 * Tela Auditoria (docs/telas-admin-web.md: "quem, quando, o que, por que"; leitor+, so leitura): o rastro de
 * toda acao de escrita feita pelo console (Equipe, Contas, Mesas, Economia) - registro append-only, nunca se
 * altera. Sem filtros nesta etapa, so a lista legível, mais recente primeiro.
 */
export function AuditList() {
  const { session, forceLogout } = useSession()
  const [offset, setOffset] = useState(0)
  const [data, setData] = useState<AuditPageResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const params = new URLSearchParams({ limit: String(LIMIT), offset: String(offset) })
        const response = await apiFetch<AuditPageResponse>(`/admin/audit?${params}`, { token: session?.token })
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

  const hasPrev = offset > 0
  const hasNext = data != null && offset + LIMIT < data.total

  return (
    <main className="accounts-page">
      <AppHeader title="Auditoria" />

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <section aria-label="Lista de auditoria">
          {data.items.length === 0 ? (
            <p>Nenhuma ação registrada ainda.</p>
          ) : (
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>Quando</th>
                  <th>Quem</th>
                  <th>O quê</th>
                  <th>Alvo</th>
                  <th>Motivo</th>
                  <th>Detalhe</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((entry) => (
                  <tr key={entry.id}>
                    <td>{formatDate(entry.whenMs)}</td>
                    <td>{entry.actorEmail}</td>
                    <td>{entry.action}</td>
                    <td>{entry.targetEmail ?? entry.targetId ?? '—'}</td>
                    <td>{entry.reason}</td>
                    <td>{entry.detail ?? '—'}</td>
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
