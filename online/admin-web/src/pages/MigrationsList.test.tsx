import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MigrationsList } from './MigrationsList'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { MigrationsPageResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderMigrations() {
  return render(
    <MemoryRouter initialEntries={['/migracoes']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/migracoes"
            element={
              <ProtectedRoute>
                <MigrationsList />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
          <Route path="/contas/:id" element={<p>ficha-da-conta</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const PAGINA: MigrationsPageResponse = {
  total: 1,
  limit: 20,
  offset: 0,
  items: [{ accountId: 'E_1', coins: 300, createdMs: 1_700_000_000_000 }],
}

describe('MigrationsList (Sec18.7, docs/telas-admin-web.md: migrações da carteira local)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lista as migracoes com conta, moedas e data', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderMigrations()

    await waitFor(() => expect(screen.getByText('E_1')).toBeInTheDocument())
    expect(screen.getByText('300')).toBeInTheDocument()
  })

  it('sem migracoes mostra mensagem', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { total: 0, limit: 20, offset: 0, items: [] }))
    renderMigrations()

    await waitFor(() => expect(screen.getByText('Nenhuma migração ainda.')).toBeInTheDocument())
  })

  it('Ver conta leva pra ficha da conta que migrou', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderMigrations()

    await waitFor(() => expect(screen.getByText('E_1')).toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'Ver conta' })).toHaveAttribute('href', '/contas/E_1')
  })

  it('pagina: Próxima refaz a busca com o offset seguinte', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 21 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { total: 21, limit: 20, offset: 20, items: [{ accountId: 'E_2', coins: 700, createdMs: 1 }] }),
    )
    const user = userEvent.setup()
    renderMigrations()

    await waitFor(() => expect(screen.getByText('E_1')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('E_2')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderMigrations()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
