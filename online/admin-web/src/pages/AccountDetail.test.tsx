import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountDetail } from './AccountDetail'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AccountDetailResponse, Role } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderDetail(id = 'G_1', role: Role = 'LEITOR') {
  saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role })
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

  it('leitor nao ve Bloquear/Encerrar sessões (so operador+ aciona); LGPD continua desabilitado pra todos', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    renderDetail('G_1', 'LEITOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Bloquear conta' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Encerrar sessões' })).not.toBeInTheDocument()
    for (const nome of ['Exportar dados (LGPD)', 'Apagar dados (LGPD)']) {
      const botao = screen.getByRole('button', { name: nome })
      expect(botao).toBeDisabled()
      expect(botao).toHaveAttribute('title', 'Em breve')
    }
  })

  it('operador bloqueia a conta: motivo obrigatorio, confirma, o botao vira Desbloquear', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { status: 'UP' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))

    const dialogo = screen.getByRole('dialog', { name: 'Bloquear conta' })
    expect(dialogo).toBeInTheDocument()
    await user.type(screen.getByLabelText('Motivo'), 'Comportamento abusivo')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Conta bloqueada.')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Desbloquear conta' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const [url, options] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/accounts/G_1/block')
    expect(JSON.parse(String(options?.body))).toEqual({ reason: 'Comportamento abusivo' })
  })

  it('operador desbloqueia uma conta ja bloqueada: POST em /unblock, rotulo volta a Bloquear', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, account: { ...FICHA.account, blocked: true } }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { status: 'UP' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Desbloquear conta' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Desbloquear conta' }))
    await user.type(screen.getByLabelText('Motivo'), 'Revisão concluída')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Conta desbloqueada.')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Bloquear conta' })).toBeInTheDocument()
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/accounts/G_1/unblock')
  })

  it('Cancelar fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('Esc fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('operador encerra sessões e ve quantas foram encerradas', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { revoked: 2 }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar sessões' }))
    await user.type(screen.getByLabelText('Motivo'), 'Suspeita de conta comprometida')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Sessões encerradas: 2.')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/accounts/G_1/revoke-sessions')
  })

  it('erro ao confirmar mostra a mensagem dentro do dialogo, sem fechar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Contas indisponíveis agora. Tente de novo.' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    await user.type(screen.getByLabelText('Motivo'), 'Motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Contas indisponíveis agora. Tente de novo.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
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
