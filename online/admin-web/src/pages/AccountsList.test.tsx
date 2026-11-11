import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountsList } from './AccountsList'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AccountsPageResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderAccountsList() {
  return render(
    <MemoryRouter initialEntries={['/contas']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/contas"
            element={
              <ProtectedRoute>
                <AccountsList />
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

const PAGINA: AccountsPageResponse = {
  total: 1,
  limit: 20,
  offset: 0,
  items: [
    {
      id: 'G_1',
      kind: 'CONVIDADO',
      name: 'Ana',
      email: null,
      createdMs: null,
      lastSeenMs: 1_700_000_000_000,
      blocked: false,
    },
  ],
}

describe('AccountsList (Sec18.7, docs/telas-admin-web.md: Contas)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('busca ao carregar (q vazio) e lista o resultado', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderAccountsList()

    await waitFor(() => expect(screen.getByText('Ana')).toBeInTheDocument())
    expect(screen.getByText('Convidado')).toBeInTheDocument()
    const [url] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toMatch(/\/admin\/accounts\?q=&limit=20&offset=0/)
  })

  it('digitar e buscar manda a query pro admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { total: 0, limit: 20, offset: 0, items: [] }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    const user = userEvent.setup()
    renderAccountsList()

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    await user.type(screen.getByLabelText('Buscar por nome ou e-mail'), 'ana')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/q=ana/)
  })

  it('sem resultado mostra mensagem, sem tabela', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { total: 0, limit: 20, offset: 0, items: [] }))
    renderAccountsList()

    await waitFor(() => expect(screen.getByText('Nenhuma conta encontrada.')).toBeInTheDocument())
  })

  it('pagina: Próxima refaz a busca com o offset seguinte, Anterior some na primeira pagina', async () => {
    const pagina2 = { ...PAGINA, offset: 20, items: [{ ...PAGINA.items[0], id: 'G_2', name: 'Beto' }] }
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 21 }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...pagina2, total: 21 }))
    const user = userEvent.setup()
    renderAccountsList()

    await waitFor(() => expect(screen.getByText('Ana')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('Beto')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('Ver ficha leva pra tela da conta', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderAccountsList()

    await waitFor(() => expect(screen.getByText('Ana')).toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'Ver ficha' })).toHaveAttribute('href', '/contas/G_1')
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderAccountsList()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
