import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountDetail } from './AccountDetail'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AccountDetailResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderDetail(id = 'G_1') {
  return render(
    <MemoryRouter initialEntries={[`/contas/${id}`]}>
      <SessionProvider>
        <Routes>
          <Route
            path="/contas/:id"
            element={
              <ProtectedRoute>
                <AccountDetail />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
          <Route path="/contas" element={<p>lista-de-contas</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const FICHA: AccountDetailResponse = {
  account: {
    id: 'G_1',
    kind: 'EMAIL',
    name: 'Ana',
    email: 'ana@exemplo.com',
    createdMs: 1_700_000_000_000,
    lastSeenMs: 1_700_000_500_000,
    blocked: false,
    currentTables: [{ id: 'T1', name: 'Mesa 1', state: 'ABERTA', updatedMs: 1_700_000_600_000 }],
  },
  wallet: { coins: 1_000, inventory: { ESPIAR: 2 }, migratedLocal: false },
  walletUnavailable: false,
}

describe('AccountDetail (Sec18.7, docs/telas-admin-web.md: ficha da conta)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
    saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('busca a ficha pelo id da rota e mostra os dados, mesas atuais e carteira', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument()
    expect(screen.getByText('E-mail e senha')).toBeInTheDocument()
    expect(screen.getByText(/Mesa 1/)).toBeInTheDocument()
    expect(screen.getByText(/não é o histórico de partidas/)).toBeInTheDocument()
    expect(screen.getByText('Saldo: 1000 moedas')).toBeInTheDocument()
    expect(screen.getByText('ESPIAR: 2')).toBeInTheDocument()

    const [url] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toBe('/admin/accounts/G_1')
  })

  it('economy-service fora do ar mostra "indisponível", sem quebrar a ficha', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: true }))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('indisponível')).toBeInTheDocument()
  })

  it('conta sem carteira online ainda mostra mensagem diferente de "indisponível"', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: false }))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('Sem carteira online ainda.')).toBeInTheDocument()
    expect(screen.queryByText('indisponível')).not.toBeInTheDocument()
  })

  it('as acoes de escrita aparecem desabilitadas, com "Em breve"', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    for (const nome of ['Bloquear conta', 'Encerrar sessões', 'Exportar dados (LGPD)', 'Apagar dados (LGPD)']) {
      const botao = screen.getByRole('button', { name: nome })
      expect(botao).toBeDisabled()
      expect(botao).toHaveAttribute('title', 'Em breve')
    }
  })

  it('conta nao encontrada (404) mostra a mensagem do servidor', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(404, { code: 'REQUISICAO_INVALIDA', message: 'Conta não encontrada.' }))
    renderDetail('ninguem')

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Conta não encontrada.')
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
