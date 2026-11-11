import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuditList } from './AuditList'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AuditPageResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderAudit() {
  return render(
    <MemoryRouter initialEntries={['/auditoria']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/auditoria"
            element={
              <ProtectedRoute>
                <AuditList />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const PAGINA: AuditPageResponse = {
  total: 1,
  limit: 20,
  offset: 0,
  items: [
    {
      id: 1,
      operationId: 'op-1',
      whenMs: 1_700_000_000_000,
      actorEmail: 'admin@exemplo.com',
      action: 'BLOQUEAR_CONTA',
      targetId: 'G_1',
      targetEmail: 'ana@exemplo.com',
      reason: 'Comportamento abusivo',
      detail: null,
    },
  ],
}

describe('AuditList (Sec18.7, docs/telas-admin-web.md: auditoria - quem, quando, o que, por que)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('leitor (o menor papel) ve a lista: quem, o que, alvo e motivo, mas nao o link Equipe', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderAudit()

    await waitFor(() => expect(screen.getByText('BLOQUEAR_CONTA')).toBeInTheDocument())
    expect(screen.getByText('admin@exemplo.com')).toBeInTheDocument()
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument()
    expect(screen.getByText('Comportamento abusivo')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Equipe' })).not.toBeInTheDocument()

    const [url] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toMatch(/^\/admin\/audit\?/)
  })

  it('sem acoes ainda mostra mensagem', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { total: 0, limit: 20, offset: 0, items: [] }))
    renderAudit()

    await waitFor(() => expect(screen.getByText('Nenhuma ação registrada ainda.')).toBeInTheDocument())
  })

  it('pagina: Próxima refaz a busca com o offset seguinte', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 21 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, {
        total: 21,
        limit: 20,
        offset: 20,
        items: [{ ...PAGINA.items[0], id: 2, action: 'DESBLOQUEAR_CONTA' }],
      }),
    )
    const user = userEvent.setup()
    renderAudit()

    await waitFor(() => expect(screen.getByText('BLOQUEAR_CONTA')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('DESBLOQUEAR_CONTA')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderAudit()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
