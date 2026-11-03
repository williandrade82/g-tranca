import { useEffect, useState } from 'react'
import { apiFetch } from '../api/client'
import { ApiRequestError, type DashboardResponse, type ServiceStatusLevel } from '../api/types'
import { useSession } from '../auth/SessionContext'

const STATUS_LABEL: Record<ServiceStatusLevel, string> = {
  VERDE: 'Operando normalmente',
  AMARELO: 'Lento ou instável',
  VERMELHO: 'Fora do ar',
}

const STATUS_CLASS: Record<ServiceStatusLevel, string> = {
  VERDE: 'servico-verde',
  AMARELO: 'servico-amarelo',
  VERMELHO: 'servico-vermelho',
}

function Contagem({ rotulo, valor }: { rotulo: string; valor: number | null }) {
  return (
    <div className="contagem">
      <span className="contagem-rotulo">{rotulo}</span>
      <strong>{valor === null ? 'indisponível' : valor}</strong>
    </div>
  )
}

/** Tela Painel (docs/telas-admin-web.md): jogadores online, mesas ativas, partidas por hora e a saude dos servicos. */
export function Dashboard() {
  const { session, logout, forceLogout } = useSession()
  const [data, setData] = useState<DashboardResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const response = await apiFetch<DashboardResponse>('/admin/dashboard', { token: session?.token })
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
  }, [session?.token, forceLogout])

  return (
    <main className="dashboard-page">
      <header className="dashboard-header">
        <h1>Painel</h1>
        <div>
          <span>
            {session?.email} ({session?.role})
          </span>
          <button type="button" onClick={logout}>
            Sair
          </button>
        </div>
      </header>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <>
          <section aria-label="Contagens">
            <Contagem rotulo="Jogadores online" valor={data.playersOnline} />
            <Contagem rotulo="Mesas ativas" valor={data.tablesOpen} />
            <Contagem rotulo="Em jogo" valor={data.tablesInGame} />
            <Contagem rotulo="Partidas na última hora" valor={data.matchesEndedLastHour} />
          </section>

          <section aria-label="Saúde dos serviços">
            <h2>Saúde dos serviços</h2>
            {data.services.length === 0 ? (
              <p>Nenhum serviço configurado para sondagem.</p>
            ) : (
              <ul>
                {data.services.map((service) => (
                  <li key={service.name} className={STATUS_CLASS[service.status]}>
                    <strong>{service.name}</strong>: {STATUS_LABEL[service.status]}
                    {service.latencyMs !== null && ` (${service.latencyMs} ms)`}
                  </li>
                ))}
              </ul>
            )}
          </section>

          {(data.grafanaUrl || data.lokiUrl) && (
            <section aria-label="Métricas e logs">
              <h2>Métricas e logs</h2>
              <ul>
                {data.grafanaUrl && (
                  <li>
                    <a href={data.grafanaUrl} target="_blank" rel="noreferrer">
                      Grafana
                    </a>
                  </li>
                )}
                {data.lokiUrl && (
                  <li>
                    <a href={data.lokiUrl} target="_blank" rel="noreferrer">
                      Loki
                    </a>
                  </li>
                )}
              </ul>
            </section>
          )}
        </>
      )}
    </main>
  )
}
