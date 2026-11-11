import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { apiFetch } from '../api/client'
import { ApiRequestError, type AccountKind, type AccountsPageResponse } from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'

const KIND_LABEL: Record<AccountKind, string> = {
  CONVIDADO: 'Convidado',
  GOOGLE: 'Google',
  EMAIL: 'E-mail e senha',
}

function formatDate(ms: number | null): string {
  if (ms == null) return '—'
  return new Date(ms).toLocaleString('pt-BR')
}

const LIMIT = 20

/** Tela Contas (docs/telas-admin-web.md): busca por nome ou e-mail; leitor so consulta nesta etapa. */
export function AccountsList() {
  const { session, forceLogout } = useSession()
  // "query" e o que o campo mostra (muda a cada tecla); "submittedQuery" e o que de fato dispara a busca
  // (so muda ao submeter o formulario) - sem isso, cada tecla digitada chamaria o admin-api de novo.
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [offset, setOffset] = useState(0)
  const [data, setData] = useState<AccountsPageResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const params = new URLSearchParams({ q: submittedQuery, limit: String(LIMIT), offset: String(offset) })
        const response = await apiFetch<AccountsPageResponse>(`/admin/accounts?${params}`, { token: session?.token })
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
  }, [session?.token, forceLogout, offset, submittedQuery])

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setOffset(0)
    setSubmittedQuery(query)
  }

  const hasPrev = offset > 0
  const hasNext = data != null && offset + LIMIT < data.total

  return (
    <main className="accounts-page">
      <AppHeader title="Contas" />

      <form className="accounts-busca" onSubmit={handleSearch} role="search">
        <label htmlFor="busca-contas">Buscar por nome ou e-mail</label>
        <input
          id="busca-contas"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="nome, e-mail ou id"
        />
        <button type="submit">Buscar</button>
      </form>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <section aria-label="Resultado da busca">
          {data.items.length === 0 ? (
            <p>Nenhuma conta encontrada.</p>
          ) : (
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>Nome</th>
                  <th>Método de login</th>
                  <th>E-mail</th>
                  <th>Cadastro</th>
                  <th>Último acesso</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((account) => (
                  <tr key={account.id}>
                    <td>{account.name || '(sem nome)'}</td>
                    <td>{KIND_LABEL[account.kind]}</td>
                    <td>{account.email ?? '—'}</td>
                    <td>{formatDate(account.createdMs)}</td>
                    <td>{formatDate(account.lastSeenMs)}</td>
                    <td>
                      <Link to={`/contas/${encodeURIComponent(account.id)}`}>Ver ficha</Link>
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
