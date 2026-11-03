import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TablesList } from './TablesList'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { TablesPageResponse, TableView } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderTablesList() {
  return render(
    <MemoryRouter initialEntries={['/mesas']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/mesas"
            element={
              <ProtectedRoute>
                <TablesList />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
          <Route path="/mesas/:id" element={<p>ficha-da-mesa</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const MESA: TableView = {
  id: 'T1',
  hostId: 'G_1',
  hostName: 'Ana',
  state: 'ABERTA',
  name: 'Mesa Dourada',
  rules: {
    mode: 'DUPLAS',
    targetScore: 3000,
    bet: 50,
    skillBoostersAllowed: true,
    tempoDeJogada: 'INDETERMINADO',
    punicao: 'SEM_PUNICAO',
    visibilidade: 'PRIVADA',
    robotLevel: 'FACIL',
  },
  seats: [{ index: 0, kind: 'HUMANO', guestId: 'G_1', name: 'Ana', photoUrl: null, profile: null, level: null }],
}

const PAGINA: TablesPageResponse = { total: 1, limit: 20, offset: 0, items: [MESA] }

describe('TablesList (Sec18.7, docs/telas-admin-web.md: Mesas)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lista as mesas com id, estado, modo, aposta e criterios - mesmo com nome preenchido', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderTablesList()

    await waitFor(() => expect(screen.getByText('Mesa Dourada')).toBeInTheDocument())
    // O id continua visivel mesmo quando a mesa tem nome sorteado (e por ele que o suporte acha a mesa).
    expect(screen.getByText('T1')).toBeInTheDocument()
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByText('Aberta')).toBeInTheDocument()
    expect(screen.getByText('Duplas')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
    expect(screen.getByText('Indeterminado · Sem punição')).toBeInTheDocument()
  })

  it('Abrir mesa pelo id navega direto pra ficha, sem precisar paginar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    const user = userEvent.setup()
    renderTablesList()

    await waitFor(() => expect(screen.getByText('Mesa Dourada')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Abrir mesa pelo id'), 'ZZ99XX')
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await waitFor(() => expect(screen.getByText('ficha-da-mesa')).toBeInTheDocument())
  })

  it('sem mesas mostra mensagem', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { total: 0, limit: 20, offset: 0, items: [] }))
    renderTablesList()

    await waitFor(() => expect(screen.getByText('Nenhuma mesa.')).toBeInTheDocument())
  })

  it('pagina: Próxima refaz a busca com o offset seguinte', async () => {
    const pagina2 = { ...PAGINA, offset: 20, total: 21, items: [{ ...MESA, id: 'T2', name: 'Mesa Azul' }] }
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 21 }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, pagina2))
    const user = userEvent.setup()
    renderTablesList()

    await waitFor(() => expect(screen.getByText('Mesa Dourada')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('Mesa Azul')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('Ver mesa leva pra ficha da mesa', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderTablesList()

    await waitFor(() => expect(screen.getByText('Mesa Dourada')).toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'Ver mesa' })).toHaveAttribute('href', '/mesas/T1')
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderTablesList()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
