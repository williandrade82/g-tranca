import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountDetail } from './AccountDetail'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AccountDetailResponse, AdminWalletDto, LedgerPageResponse, Role } from '../api/types'

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

const LEDGER_VAZIO: LedgerPageResponse = { items: [], total: 0, limit: 10, offset: 0 }

const LEDGER_COM_ENTRADA: LedgerPageResponse = {
  items: [{ reason: 'SALDO_INICIAL', coins: 1_000, boosterId: null, quantity: 0, reference: null, detail: null, key: 'saldo-inicial', createdMs: 1_700_000_000_000 }],
  total: 1,
  limit: 10,
  offset: 0,
}

describe('AccountDetail (Sec18.7, docs/telas-admin-web.md: ficha da conta)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('busca a ficha e o livro-razao, mostra os dados, mesas, carteira e lancamentos', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_COM_ENTRADA))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument()
    expect(screen.getByText('E-mail e senha')).toBeInTheDocument()
    expect(screen.getByText(/Mesa 1/)).toBeInTheDocument()
    expect(screen.getByText(/não é o histórico de partidas/)).toBeInTheDocument()
    expect(screen.getByText('Saldo: 1000 moedas')).toBeInTheDocument()
    expect(screen.getByText('ESPIAR: 2')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('SALDO_INICIAL')).toBeInTheDocument())

    const [urlFicha] = vi.mocked(fetch).mock.calls[0]
    expect(String(urlFicha)).toBe('/admin/accounts/G_1')
    const [urlLedger] = vi.mocked(fetch).mock.calls[1]
    expect(String(urlLedger)).toMatch(/^\/admin\/accounts\/G_1\/ledger\?/)
  })

  it('economy-service fora do ar mostra "indisponível" na carteira, sem quebrar a ficha', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: true }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('indisponível')).toBeInTheDocument()
  })

  it('conta sem carteira online ainda mostra mensagem diferente de "indisponível"', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: false }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.getByText('Sem carteira online ainda.')).toBeInTheDocument()
    expect(screen.queryByText('indisponível')).not.toBeInTheDocument()
  })

  it('livro-razao vazio mostra mensagem, erro no livro-razao nao quebra o resto da ficha', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Economia indisponível agora. Tente de novo.' }))
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Economia indisponível agora. Tente de novo.')
    // O resto da ficha segue normal mesmo com o livro-razao falhando.
    expect(screen.getByText('Saldo: 1000 moedas')).toBeInTheDocument()
  })

  it('pagina do livro-razao: Próxima refaz a busca com o offset seguinte', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...LEDGER_COM_ENTRADA, total: 11 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { items: [{ ...LEDGER_COM_ENTRADA.items[0], key: 'outra-chave', reason: 'AJUSTE' }], total: 11, limit: 10, offset: 10 }),
    )
    const user = userEvent.setup()
    renderDetail('G_1')

    await waitFor(() => expect(screen.getByText('SALDO_INICIAL')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('AJUSTE')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toMatch(/offset=10/)
  })

  it('leitor nao ve Bloquear/Encerrar sessões nem Ajustar; LGPD continua desabilitado pra todos', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    renderDetail('G_1', 'LEITOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Bloquear conta' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Encerrar sessões' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ajustar saldo ou booster' })).not.toBeInTheDocument()
    for (const nome of ['Exportar dados (LGPD)', 'Apagar dados (LGPD)']) {
      const botao = screen.getByRole('button', { name: nome })
      expect(botao).toBeDisabled()
      expect(botao).toHaveAttribute('title', 'Em breve')
    }
  })

  it('operador bloqueia a conta: motivo obrigatorio, confirma, o botao vira Desbloquear', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
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

    const [url, options] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/accounts/G_1/block')
    const body = JSON.parse(String(options?.body))
    expect(body.reason).toBe('Comportamento abusivo')
    expect(typeof body.operationId).toBe('string')
    expect(body.operationId.length).toBeGreaterThan(0)
  })

  it('uma 2a tentativa (depois de um erro) reusa a MESMA chave de idempotencia, nunca uma nova', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Contas indisponíveis agora. Tente de novo.' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { status: 'UP' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(screen.getByText('Conta bloqueada.')).toBeInTheDocument())

    const primeiraChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[2][1]?.body))
    const segundaChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[3][1]?.body))
    expect(segundaChamada.operationId).toBe(primeiraChamada.operationId)
  })

  it('operador desbloqueia uma conta ja bloqueada: POST em /unblock, rotulo volta a Bloquear', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, account: { ...FICHA.account, blocked: true } }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { status: 'UP' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Desbloquear conta' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Desbloquear conta' }))
    await user.type(screen.getByLabelText('Motivo'), 'Revisão concluída')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Conta desbloqueada.')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Bloquear conta' })).toBeInTheDocument()
    const [url] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/accounts/G_1/unblock')
  })

  it('Cancelar fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('Esc fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Bloquear conta' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('operador encerra sessões e ve quantas foram encerradas', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { revoked: 2 }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar sessões' }))
    await user.type(screen.getByLabelText('Motivo'), 'Suspeita de conta comprometida')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Sessões encerradas: 2.')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/accounts/G_1/revoke-sessions')
  })

  it('erro ao confirmar bloqueio mostra a mensagem dentro do dialogo, sem fechar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
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

  // ---------------------------------------------------------------------------------------------------- ajustar

  it('operador ajusta moedas: gera um operationId, atualiza o saldo mostrado e recarrega o livro-razao', async () => {
    const novaCarteira: AdminWalletDto = { coins: 1_500, inventory: { ESPIAR: 2 }, migratedLocal: false }
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_COM_ENTRADA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, novaCarteira))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { items: [{ ...LEDGER_COM_ENTRADA.items[0], key: 'ajuste-x', reason: 'AJUSTE' }, ...LEDGER_COM_ENTRADA.items], total: 2, limit: 10, offset: 0 }),
    )
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Ajuste de moedas (ex.: 500 ou -200)'), '500')
    await user.type(screen.getByLabelText('Motivo'), 'corrigindo um bug')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Carteira ajustada.')).toBeInTheDocument())
    expect(screen.getByText('Saldo: 1500 moedas')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const [url, options] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/accounts/G_1/adjust')
    const body = JSON.parse(String(options?.body))
    expect(body.reason).toBe('corrigindo um bug')
    expect(body.coinsDelta).toBe(500)
    expect(typeof body.operationId).toBe('string')
    expect(body.operationId.length).toBeGreaterThan(0)

    // O livro-razao recarrega depois do ajuste (4a chamada).
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(4))
    const [urlLedgerDeNovo] = vi.mocked(fetch).mock.calls[3]
    expect(String(urlLedgerDeNovo)).toMatch(/^\/admin\/accounts\/G_1\/ledger\?/)
  })

  it('operador ajusta um booster: id e quantidade vao no corpo', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { coins: 1_000, inventory: { ESPIAR: 5 }, migratedLocal: false }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Booster (id)'), 'ESPIAR')
    await user.type(screen.getByLabelText('Ajuste de quantidade do booster'), '3')
    await user.type(screen.getByLabelText('Motivo'), 'deu premio')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('ESPIAR: 5')).toBeInTheDocument())
    const [, options] = vi.mocked(fetch).mock.calls[2]
    const body = JSON.parse(String(options?.body))
    expect(body.boosterId).toBe('ESPIAR')
    expect(body.quantityDelta).toBe(3)
  })

  it('economy-service recusa por saldo insuficiente: a mensagem aparece no dialogo, sem fechar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(409, { code: 'SALDO_INSUFICIENTE', message: 'O ajuste deixaria o saldo negativo.' }))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Ajuste de moedas (ex.: 500 ou -200)'), '-99999')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('O ajuste deixaria o saldo negativo.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    // O saldo mostrado nao mudou (o ajuste nao aconteceu).
    expect(screen.getByText('Saldo: 1000 moedas')).toBeInTheDocument()
  })

  it('Cancelar no ajuste fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('uma 2a tentativa de ajuste (depois de um erro) reusa a MESMA chave de idempotencia', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Economia indisponível agora. Tente de novo.' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { coins: 1_500, inventory: {}, migratedLocal: false }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Ajuste de moedas (ex.: 500 ou -200)'), '500')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(screen.getByText('Carteira ajustada.')).toBeInTheDocument())

    const primeiraChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[2][1]?.body))
    const segundaChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[3][1]?.body))
    expect(segundaChamada.operationId).toBe(primeiraChamada.operationId)
  })

  it('ajuste de moedas nao inteiro (ex.: 1.5) e recusado no cliente, sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Ajuste de moedas (ex.: 500 ou -200)'), '1.5')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('número inteiro')
    expect(fetch).toHaveBeenCalledTimes(2) // so a ficha e o livro-razao iniciais, nunca o adjust
  })

  it('o resumo ao vivo mostra credito/debito de moedas e o ajuste do booster', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, FICHA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, LEDGER_VAZIO))
    const user = userEvent.setup()
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajustar saldo ou booster' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajustar saldo ou booster' }))
    await user.type(screen.getByLabelText('Ajuste de moedas (ex.: 500 ou -200)'), '-200')
    await user.type(screen.getByLabelText('Booster (id)'), 'ESPIAR')
    await user.type(screen.getByLabelText('Ajuste de quantidade do booster'), '3')

    await waitFor(() => expect(screen.getByText('Vai debitar 200 moedas · ESPIAR: +3')).toBeInTheDocument())
  })

  it('conta sem carteira (wallet nulo) nao mostra o botao Ajustar, pra nao criar a carteira so por engano', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: false }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(404, { code: 'REQUISICAO_INVALIDA', message: 'Conta não encontrada.' }))
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Ajustar saldo ou booster' })).not.toBeInTheDocument()
    // 404 do livro-razao = "sem carteira ainda", nunca um alerta vermelho.
    expect(screen.getByText('Sem lançamentos (conta sem carteira online).')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('carteira indisponivel (economy fora do ar) tambem esconde o botao Ajustar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...FICHA, wallet: null, walletUnavailable: true }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Economia indisponível agora. Tente de novo.' }))
    renderDetail('G_1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ana' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Ajustar saldo ou booster' })).not.toBeInTheDocument()
  })
})
