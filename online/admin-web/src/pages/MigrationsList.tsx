import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { apiFetch } from '../api/client'
import { ApiRequestError, type MigrationsPageResponse } from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'

function formatDate(ms: number): string {
  return new Date(ms).toLocaleString('pt-BR')
}

const LIMIT = 20

/**
 * Migrações da carteira local (docs/telas-admin-web.md, tela Economia; definition.md Sec18.6): lista GLOBAL
 * (todas as contas que já migraram, não de uma conta só) - quem migrou, quanto e quando. Leitor vê; não há
 * ação de escrita aqui (migrar é escolha do jogador, o operador só revisa).
 */
export function MigrationsList() {
  const { session, forceLogout } = useSession()
  const [offset, setOffset] = useState(0)
  const [data, setData] = useState<MigrationsPageResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const params = new URLSearchParams({ limit: String(LIMIT), offset: String(offset) })
        const response = await apiFetch<MigrationsPageResponse>(`/admin/migrations?${params}`, { token: session?.token })
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
      <AppHeader title="Migrações" />

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <section aria-label="Lista de migrações">
          {data.items.length === 0 ? (
            <p>Nenhuma migração ainda.</p>
          ) : (
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>Conta</th>
                  <th>Moedas migradas</th>
                  <th>Quando</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((migration) => (
                  <tr key={migration.accountId}>
                    <td>{migration.accountId}</td>
                    <td>{migration.coins}</td>
                    <td>{formatDate(migration.createdMs)}</td>
                    <td>
                      <Link to={`/contas/${encodeURIComponent(migration.accountId)}`}>Ver conta</Link>
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
