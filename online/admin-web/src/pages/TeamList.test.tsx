import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TeamList } from './TeamList'
import { SessionProvider } from '../auth/SessionContext'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { saveSession } from '../api/session'
import type { Role, TeamListResponse } from '../api/types'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderTeam(role: Role = 'ADMINISTRADOR') {
  saveSession({ token: 'tok-123', id: '1', email: 'admin@exemplo.com', role })
  return render(
    <MemoryRouter initialEntries={['/equipe']}>
      <SessionProvider>
        <Routes>
          <Route
            path="/equipe"
            element={
              <ProtectedRoute>
                <TeamList />
              </ProtectedRoute>
            }
          />
          <Route path="/entrar" element={<p>tela-entrar</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const PAGINA: TeamListResponse = {
  total: 2,
  limit: 20,
  offset: 0,
  items: [
    { id: '1', email: 'admin@exemplo.com', role: 'ADMINISTRADOR', createdMs: 1_700_000_000_000 },
    { id: '2', email: 'leitor@exemplo.com', role: 'LEITOR', createdMs: 1_700_000_100_000 },
  ],
}

describe('TeamList (Sec18.7, docs/telas-admin-web.md: equipe do console, so administrador)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('leitor/operador nao vem a tela nem o link Equipe no menu, acesso restrito sem chamar o admin-api', async () => {
    renderTeam('LEITOR')

    expect(await screen.findByText('Acesso restrito a administradores.')).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.queryByRole('link', { name: 'Equipe' })).not.toBeInTheDocument()
  })

  it('administrador ve a lista da equipe com e-mail e papel, e o link Equipe no menu', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    expect(screen.getByText('admin@exemplo.com')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Equipe' })).toBeInTheDocument()
    const [url] = vi.mocked(fetch).mock.calls[0]
    expect(String(url)).toMatch(/^\/admin\/team\?/)
  })

  it('adiciona um membro: email, senha, papel e motivo vao no corpo, a lista recarrega', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { id: '3', email: 'novo@exemplo.com', role: 'OPERADOR', createdMs: 1 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { ...PAGINA, total: 3, items: [...PAGINA.items, { id: '3', email: 'novo@exemplo.com', role: 'OPERADOR', createdMs: 1 }] }),
    )
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Adicionar membro' }))

    const dialogo = screen.getByRole('dialog', { name: 'Adicionar membro' })
    expect(dialogo).toBeInTheDocument()
    await user.type(screen.getByLabelText('E-mail'), 'novo@exemplo.com')
    await user.type(screen.getByLabelText('Senha inicial'), 'segredo123')
    await user.selectOptions(screen.getByLabelText('Papel'), 'OPERADOR')
    await user.type(screen.getByLabelText('Motivo'), 'novo operador da equipe')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('novo@exemplo.com')).toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const [url, options] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/team')
    const body = JSON.parse(String(options?.body))
    expect(body.email).toBe('novo@exemplo.com')
    expect(body.password).toBe('segredo123')
    expect(body.role).toBe('OPERADOR')
    expect(body.reason).toBe('novo operador da equipe')
    expect(typeof body.operationId).toBe('string')
    expect(body.operationId.length).toBeGreaterThan(0)
  })

  it('e-mail ja cadastrado: a mensagem aparece no dialogo, sem fechar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(409, { code: 'EMAIL_JA_CADASTRADO', message: 'Esse e-mail já está cadastrado.' }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Adicionar membro' }))
    await user.type(screen.getByLabelText('E-mail'), 'leitor@exemplo.com')
    await user.type(screen.getByLabelText('Senha inicial'), 'segredo123')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Esse e-mail já está cadastrado.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('uma 2a tentativa de adicionar (depois de um erro) reusa a MESMA chave de idempotencia', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Indisponível agora. Tente de novo.' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { id: '3', email: 'novo@exemplo.com', role: 'LEITOR', createdMs: 1 }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 3, items: [...PAGINA.items, { id: '3', email: 'novo@exemplo.com', role: 'LEITOR', createdMs: 1 }] }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Adicionar membro' }))
    await user.type(screen.getByLabelText('E-mail'), 'novo@exemplo.com')
    await user.type(screen.getByLabelText('Senha inicial'), 'segredo123')
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(screen.getByText('novo@exemplo.com')).toBeInTheDocument())

    const primeiraChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[1][1]?.body))
    const segundaChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[2][1]?.body))
    expect(segundaChamada.operationId).toBe(primeiraChamada.operationId)
  })

  it('Cancelar em Adicionar fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Adicionar membro' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('troca o papel de um membro: PATCH com o papel escolhido e motivo, sem senha quando em branco', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { id: '2', email: 'leitor@exemplo.com', role: 'OPERADOR', createdMs: 1 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { ...PAGINA, items: [PAGINA.items[0], { ...PAGINA.items[1], role: 'OPERADOR' }] }),
    )
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Trocar papel/senha' })[1])
    await user.selectOptions(screen.getByLabelText('Papel'), 'OPERADOR')
    await user.type(screen.getByLabelText('Motivo'), 'promovido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [url, options] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/team/2')
    expect(options?.method).toBe('PATCH')
    const body = JSON.parse(String(options?.body))
    expect(body.role).toBe('OPERADOR')
    expect(body.password).toBeUndefined()
    expect(body.reason).toBe('promovido')
  })

  it('nao deixa rebaixar o ultimo administrador: mensagem clara no dialogo, nao e tratado como bug', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(409, { code: 'REQUISICAO_INVALIDA', message: 'Não é possível rebaixar o último administrador.' }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('admin@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Trocar papel/senha' })[0])
    await user.selectOptions(screen.getByLabelText('Papel'), 'LEITOR')
    await user.type(screen.getByLabelText('Motivo'), 'tentando rebaixar')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não é possível rebaixar o último administrador.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('trocar papel/senha sem mudar nada e recusado no cliente, sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Trocar papel/senha' })[1])
    // Papel ja comeca como LEITOR (o atual) e a senha fica em branco: nada mudou.
    await user.type(screen.getByLabelText('Motivo'), 'motivo valido')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Troque o papel ou informe uma nova senha.')
    expect(fetch).toHaveBeenCalledTimes(1) // so a lista inicial, nunca o PATCH
  })

  it('remove um membro: motivo obrigatorio, DELETE no corpo, a lista recarrega', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, {}))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 1, items: [PAGINA.items[0]] }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Remover' })[1])

    const dialogo = screen.getByRole('dialog', { name: 'Remover leitor@exemplo.com' })
    expect(dialogo).toBeInTheDocument()
    await user.type(screen.getByLabelText('Motivo'), 'saiu da equipe')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.queryByText('leitor@exemplo.com')).not.toBeInTheDocument())
    const [url, options] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toBe('/admin/team/2')
    expect(options?.method).toBe('DELETE')
    const body = JSON.parse(String(options?.body))
    expect(body.reason).toBe('saiu da equipe')
  })

  it('administrador troca o PROPRIO papel: a sessao local cai na hora, volta pra tela Entrar', async () => {
    // Dois administradores na equipe, pra trocar o papel do que esta logado ser uma operacao valida (nao e o
    // ultimo administrador).
    const paginaComDoisAdmins: TeamListResponse = {
      ...PAGINA,
      total: 2,
      items: [PAGINA.items[0], { id: '2', email: 'outro-admin@exemplo.com', role: 'ADMINISTRADOR', createdMs: 2 }],
    }
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, paginaComDoisAdmins))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { id: '1', email: 'admin@exemplo.com', role: 'LEITOR', createdMs: 1 }))
    const user = userEvent.setup()
    renderTeam() // sessao logada e admin@exemplo.com (id '1'), o primeiro item da pagina

    await waitFor(() => expect(screen.getByText('admin@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Trocar papel/senha' })[0])
    await user.selectOptions(screen.getByLabelText('Papel'), 'LEITOR')
    await user.type(screen.getByLabelText('Motivo'), 'testando autorrebaixamento')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })

  it('nao deixa remover o ultimo administrador: mensagem clara no dialogo, nao e tratado como bug', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(409, { code: 'REQUISICAO_INVALIDA', message: 'Não é possível remover o último administrador.' }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('admin@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Remover' })[0])
    await user.type(screen.getByLabelText('Motivo'), 'tentando remover')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não é possível remover o último administrador.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('uma 2a tentativa de remover (depois de um erro) reusa a MESMA chave de idempotencia', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(503, { code: 'SERVICO_INDISPONIVEL', message: 'Indisponível agora. Tente de novo.' }))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, {}))
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 1, items: [PAGINA.items[0]] }))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Remover' })[1])
    await user.type(screen.getByLabelText('Motivo'), 'saiu da equipe')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(screen.queryByText('leitor@exemplo.com')).not.toBeInTheDocument())

    const primeiraChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[1][1]?.body))
    const segundaChamada = JSON.parse(String(vi.mocked(fetch).mock.calls[2][1]?.body))
    expect(segundaChamada.operationId).toBe(primeiraChamada.operationId)
  })

  it('Cancelar em Remover fecha o dialogo sem chamar o admin-api', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, PAGINA))
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Remover' })[1])
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('pagina: Próxima refaz a busca com o offset seguinte', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { ...PAGINA, total: 21 }))
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { total: 21, limit: 20, offset: 20, items: [{ id: '99', email: 'extra@exemplo.com', role: 'LEITOR', createdMs: 1 }] }),
    )
    const user = userEvent.setup()
    renderTeam()

    await waitFor(() => expect(screen.getByText('leitor@exemplo.com')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    await waitFor(() => expect(screen.getByText('extra@exemplo.com')).toBeInTheDocument())
    const [url] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/offset=20/)
  })

  it('sessao expirada (401) volta para a tela Entrar', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'Entre novamente.' }))
    renderTeam()

    await waitFor(() => expect(screen.getByText('tela-entrar')).toBeInTheDocument())
  })
})
