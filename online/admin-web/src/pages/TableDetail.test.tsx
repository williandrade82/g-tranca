import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TableDetail } from './TableDetail'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { Role, TableView } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderDetail(id = 'T1', role: Role = 'LEITOR') {
  saveSession({ token: 'tok-123', id: '1', email: 'ana@exemplo.com', role })
  return render(
    <MemoryRouter initialEntries={[`/mesas/${id}`]}>
      <SessionProvider>
        <Routes>
          <Route
            path="/mesas/:id"
            element={
              <ProtectedRoute>
                <TableDetail />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
          <Route path="/mesas" element={<p>lista-de-mesas</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const MESA_ABERTA: TableView = {
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

describe('TableDetail (Sec18.7, docs/telas-admin-web.md: ficha da mesa)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('busca a ficha pelo id da rota e mostra id, regras e assentos', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA))
    renderDetail('T1', 'LEITOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Mesa Dourada' })).toBeInTheDocument())
    // O id continua visivel mesmo com o nome sorteado preenchido.
    expect(screen.getByText('T1')).toBeInTheDocument()
    expect(screen.getByText('Aberta')).toBeInTheDocument()
    expect(screen.getByText('Duplas')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
    expect(screen.getByText('Humano')).toBeInTheDocument()

    const [url] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toBe('/admin/tables/T1')
  })

  it('leitor nao ve o botao Encerrar mesa', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA))
    renderDetail('T1', 'LEITOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Mesa Dourada' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Encerrar mesa' })).not.toBeInTheDocument()
  })

  it('operador ve o aviso diferente para mesa ABERTA e para mesa EM_JOGO (Encerrar mesa busca a ficha de novo)', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA)) // refetch do openConfirm
    const user = userEvent.setup()
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Encerrar mesa' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar mesa' }))
    await waitFor(() => expect(screen.getByText('Isso vai encerrar esta mesa. Continuar?')).toBeInTheDocument())
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('mesa EM_JOGO mostra o aviso da devolucao da aposta', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'EM_JOGO' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'EM_JOGO' }))
    const user = userEvent.setup()
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Encerrar mesa' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar mesa' }))
    await waitFor(() =>
      expect(
        screen.getByText('Isso vai encerrar a partida em andamento e devolver a aposta a todos os jogadores. Ninguém será penalizado. Continuar?'),
      ).toBeInTheDocument(),
    )
  })

  it('a mesa virou EM_JOGO entre a pagina abrir e o operador clicar: Encerrar mesa busca de novo e mostra o aviso certo', async () => {
    // A pagina carregou com a mesa ABERTA; quando o operador clica em "Encerrar mesa", ela ja virou EM_JOGO.
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'EM_JOGO' }))
    const user = userEvent.setup()
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Encerrar mesa' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar mesa' }))

    await waitFor(() =>
      expect(
        screen.getByText('Isso vai encerrar a partida em andamento e devolver a aposta a todos os jogadores. Ninguém será penalizado. Continuar?'),
      ).toBeInTheDocument(),
    )
  })

  it('operador confirma com motivo, a mesa atualiza pra ENCERRADA e o botao some', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'EM_JOGO' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'EM_JOGO' })) // refetch do openConfirm
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'ENCERRADA' }))
    const user = userEvent.setup()
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Encerrar mesa' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar mesa' }))
    await waitFor(() => expect(screen.getByLabelText('Motivo')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Motivo'), 'mesa travada ha 2 horas')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('Mesa encerrada.')).toBeInTheDocument())
    expect(screen.getByText('Encerrada')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Encerrar mesa' })).not.toBeInTheDocument()

    const [url, options] = vi.mocked(fetch).mock.calls[2]
    expect(String(url)).toBe('/admin/tables/T1/close')
    expect(JSON.parse(String(options?.body))).toEqual({ reason: 'mesa travada ha 2 horas', expectedState: 'EM_JOGO' })
  })

  it('mesa ja ENCERRADA nao mostra o botao Encerrar mesa', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...MESA_ABERTA, state: 'ENCERRADA' }))
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Mesa Dourada' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Encerrar mesa' })).not.toBeInTheDocument()
  })

  it('Cancelar fecha o dialogo sem chamar o close', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, MESA_ABERTA)) // refetch do openConfirm
    const user = userEvent.setup()
    renderDetail('T1', 'OPERADOR')

    await waitFor(() => expect(screen.getByRole('button', { name: 'Encerrar mesa' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Encerrar mesa' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/close'))).toBe(false)
  })

  it('mesa nao encontrada (404) mostra a mensagem do servidor', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(404, { code: 'MESA_INEXISTENTE', message: 'Mesa não encontrada.' }))
    renderDetail('ninguem', 'LEITOR')

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Mesa não encontrada.')
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderDetail('T1', 'LEITOR')

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
