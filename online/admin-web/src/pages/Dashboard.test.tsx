import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Dashboard } from './Dashboard'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { DashboardResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <Dashboard />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const RESPOSTA_BASE: DashboardResponse = {
  services: [
    { name: 'lobby-service', status: 'VERDE', latencyMs: 12 },
    { name: 'game-service', status: 'VERMELHO', latencyMs: null },
  ],
  playersOnline: null,
  tablesOpen: null,
  tablesInGame: null,
  matchesEndedLastHour: null,
  grafanaUrl: 'https://grafana.local',
  lokiUrl: null,
}

describe('Dashboard (Sec18.7, docs/telas-admin-web.md: Painel)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('mostra a saude de cada servico e "indisponível" nas contagens que o admin-api ainda nao tem', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, RESPOSTA_BASE))
    renderDashboard()

    await waitFor(() => expect(screen.getByText(/lobby-service/)).toBeInTheDocument())
    expect(screen.getByText(/Operando normalmente/)).toBeInTheDocument()
    expect(screen.getByText(/Fora do ar/)).toBeInTheDocument()
    expect(screen.getAllByText('indisponível')).toHaveLength(4)
    expect(screen.getByRole('link', { name: 'Grafana' })).toHaveAttribute('href', 'https://grafana.local')
    expect(screen.queryByRole('link', { name: 'Loki' })).not.toBeInTheDocument()

    expect(fetch).toHaveBeenCalledWith('/admin/dashboard', expect.objectContaining({ method: 'GET' }))
    const [, options] = vi.mocked(fetch).mock.calls[0]
    expect((options?.headers as Record<string, string>)?.Authorization).toBe('Bearer tok-123')
  })

  it('mostra numeros quando o admin-api ja os tiver', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { ...RESPOSTA_BASE, playersOnline: 7, tablesOpen: 2 }),
    )
    renderDashboard()

    await waitFor(() => expect(screen.getByText('7')).toBeInTheDocument())
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderDashboard()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })

  it('erro de rede mostra mensagem e nao quebra a tela', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('falhou'))
    renderDashboard()

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não foi possível falar com o servidor')
  })
})
