import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ShopConfig } from './ShopConfig'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { AdminShopConfigDto, AdminShopConfigHistoryPage, Role } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderShop(role: Role = 'ADMINISTRADOR') {
  saveSession({ token: 'tok-123', id: '1', email: 'admin@exemplo.com', role })
  return render(
    <MemoryRouter initialEntries={['/loja']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/loja"
            element={
              <ProtectedRoute>
                <ShopConfig />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const CONFIG: AdminShopConfigDto = {
  offers: [
    { index: 0, boosterId: 'ESPIAR', price: { value: 100, source: 'DEFAULT', sinceMs: null }, quantity: { value: 1, source: 'DEFAULT', sinceMs: null } },
    { index: 1, boosterId: 'TROCAR', price: { value: 200, source: 'OVERRIDE', sinceMs: 1_700_000_000_000 }, quantity: { value: 2, source: 'DEFAULT', sinceMs: null } },
  ],
  dailyGain: { value: 50, source: 'DEFAULT', sinceMs: null },
}

const HISTORICO_VAZIO: AdminShopConfigHistoryPage = { total: 0, entries: [] }

describe('ShopConfig (Sec18.7, docs/telas-admin-web.md: Loja e configuração)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([['LEITOR'], ['OPERADOR']] as [Role][])('%s ve as ofertas e o ganho diario, mas sem nenhum botao Editar', async (role) => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    renderShop(role)

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    expect(screen.getByText('TROCAR')).toBeInTheDocument()
    expect(screen.getByText(/200 moedas/)).toBeInTheDocument()
    expect(screen.getByText(/ajustado desde/)).toBeInTheDocument()
    expect(screen.getByText(/50 moedas/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Editar/ })).not.toBeInTheDocument()
  })

  it('administrador ve um botao Editar distinto em cada oferta e no ganho diario', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Editar oferta 1 (TROCAR)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Editar ganho diário' })).toBeInTheDocument()
  })

  it('administrador muda so o preco de uma oferta: o corpo nao manda a quantidade (nao mudou)', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const ajustada = { ...CONFIG.offers[0], price: { value: 150, source: 'OVERRIDE', sinceMs: 1 } }
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, ajustada))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))

    const dialogo = screen.getByRole('dialog', { name: 'Editar oferta de ESPIAR' })
    expect(dialogo).toBeInTheDocument()
    expect(dialogo).toHaveTextContent('o app ainda mostra o preço padrão do jogo')
    const campoPreco = screen.getByLabelText('Preço (moedas)')
    expect(campoPreco).toHaveValue(100)
    await user.clear(campoPreco)
    await user.type(campoPreco, '150')
    await user.type(screen.getByLabelText('Motivo'), 'ajuste de balanceamento')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [url, options] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/shop-config/offer/0')
    const body = JSON.parse(String(options?.body))
    expect(body.price).toBe(150)
    expect(body.quantity).toBeUndefined()
    expect(body.reason).toBe('ajuste de balanceamento')
    expect(typeof body.operationId).toBe('string')
    expect(body.operationId.length).toBeGreaterThan(0)
  })

  it('preco ou quantidade zero/negativo e recusado no cliente, sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    await user.clear(screen.getByLabelText('Preço (moedas)'))
    await user.type(screen.getByLabelText('Preço (moedas)'), '0')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Preço e quantidade devem ser maiores que zero.')
    expect(fetch).toHaveBeenCalledTimes(2)
    // O erro de validacao local NAO trava os campos (nao houve tentativa real ao admin-api ainda).
    expect(screen.getByLabelText('Preço (moedas)')).not.toBeDisabled()
  })

  it('editar oferta sem mudar nada e recusado no cliente, sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Mude o preço ou a quantidade.')
    expect(fetch).toHaveBeenCalledTimes(2) // so a configuracao e o historico iniciais
  })

  it('economy-service recusa oferta inexistente: mensagem clara no dialogo, sem fechar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(400, { code: 'REQUISICAO_INVALIDA', message: 'Oferta inexistente.' }))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    const campoPreco = screen.getByLabelText('Preço (moedas)')
    await user.clear(campoPreco)
    await user.type(campoPreco, '150')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Oferta inexistente.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('depois de uma tentativa, os campos ficam travados: so Cancelar (e reabrir) libera editar de novo', async () => {
    // Por que: o economy-service deduplica preco/quantidade por (campo, operationId) SEPARADAMENTE - se o 1o
    // envio so mandou preco e "deu erro" (mas pode ter aplicado de verdade do lado do servidor, so a resposta
    // se perdeu), um 2o envio com a MESMA chave que agora tambem manda quantidade aplicaria essa mudanca sem
    // conseguir registrar na auditoria (que deduplica por operationId inteiro). Por isso trava os campos.
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Indisponível agora. Tente de novo.' }))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    const campoPreco = screen.getByLabelText('Preço (moedas)')
    await user.clear(campoPreco)
    await user.type(campoPreco, '150')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')

    expect(screen.getByLabelText('Preço (moedas)')).toBeDisabled()
    expect(screen.getByLabelText('Quantidade')).toBeDisabled()
    expect(screen.getByLabelText('Motivo')).toBeDisabled()
  })

  it('retry depois de erro reusa a MESMA chave de idempotencia e o MESMO corpo (campos travados)', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Indisponível agora. Tente de novo.' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...CONFIG.offers[0], price: { value: 150, source: 'OVERRIDE', sinceMs: 1 } }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    await user.clear(screen.getByLabelText('Preço (moedas)'))
    await user.type(screen.getByLabelText('Preço (moedas)'), '150')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const primeiraChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[2][1]?.body))
    const segundaChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[3][1]?.body))
    expect(segundaChamada).toEqual(primeiraChamada)
  })

  it('Cancelar fecha o dialogo de oferta sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar oferta 0 (ESPIAR)' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('administrador muda o ganho diario', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { value: 80, source: 'OVERRIDE', sinceMs: 1 }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar ganho diário' }))

    const dialogo = screen.getByRole('dialog', { name: 'Editar ganho diário' })
    expect(dialogo).toBeInTheDocument()
    expect(dialogo).toHaveTextContent('o app ainda mostra o ganho diário padrão do jogo')
    const campo = screen.getByLabelText('Ganho diário (moedas)')
    expect(campo).toHaveValue(50)
    await user.clear(campo)
    await user.type(campo, '80')
    await user.type(screen.getByLabelText('Motivo'), 'ajuste de economia do jogo')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [url, options] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/shop-config/daily-gain')
    const body = JSON.parse(String(options?.body))
    expect(body.amount).toBe(80)
    expect(body.reason).toBe('ajuste de economia do jogo')
  })

  it('ganho diario zero ou negativo e recusado no cliente, sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar ganho diário' }))
    await user.clear(screen.getByLabelText('Ganho diário (moedas)'))
    await user.type(screen.getByLabelText('Ganho diário (moedas)'), '0')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('O ganho diário deve ser maior que zero.')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('editar o ganho diario com o mesmo valor e recusado no cliente', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    const user = userEvent.setup()
    renderShop()

    await waitFor(() => expect(screen.getByText('ESPIAR')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Editar ganho diário' }))
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Informe um valor diferente do atual.')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('historico vazio mostra mensagem', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, HISTORICO_VAZIO))
    renderShop()

    await waitFor(() => expect(screen.getByText('Nenhuma mudança registrada ainda (tudo no valor padrão do jogo).')).toBeInTheDocument())
  })

  it('historico mostra um rotulo legivel do campo (oferta+booster, ou "Ganho diario"), nunca a chave crua', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, {
        total: 2,
        entries: [
          { field: 'oferta-0-preco', value: 150, previousValue: 100, reason: 'r', operationId: 'op-1', createdMs: 2 },
          { field: 'ganho-diario', value: 80, previousValue: 50, reason: 'r2', operationId: 'op-2', createdMs: 1 },
        ],
      }),
    )
    renderShop()

    const historico = screen.getByRole('region', { name: 'Histórico de mudanças' })
    await waitFor(() => expect(within(historico).getByText('Oferta 0 (ESPIAR) · preço')).toBeInTheDocument())
    expect(within(historico).getByText('Ganho diário')).toBeInTheDocument()
    expect(screen.queryByText('oferta-0-preco')).not.toBeInTheDocument()
    expect(screen.queryByText('ganho-diario')).not.toBeInTheDocument()
  })

  it('historico pagina: Proxima refaz a busca com o offset seguinte', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, CONFIG))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { total: 21, entries: [{ field: 'oferta-0-preco', value: 150, previousValue: 100, reason: 'r', operationId: 'op-1', createdMs: 1 }] }),
    )
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { total: 21, entries: [{ field: 'ganho-diario', value: 80, previousValue: 50, reason: 'r2', operationId: 'op-2', createdMs: 2 }] }),
    )
    const user = userEvent.setup()
    renderShop()

    const historico = screen.getByRole('region', { name: 'Histórico de mudanças' })
    await waitFor(() => expect(within(historico).getByText('Oferta 0 (ESPIAR) · preço')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(within(historico).getByText('Ganho diário')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderShop()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
