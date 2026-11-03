import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Login } from './Login'
import { SessionProvider } from '../auth/SessionContext'

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/entrar']}>
      <SessionProvider>
        <Routes>
          <Route path="/entrar" element={<Login />} />
          <Route path="/" element={<p>painel-destino</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('Login (Sec18.7, docs/telas-admin-web.md: Entrar)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('tem os campos de e-mail e senha com rotulo, tipo e autocomplete corretos', () => {
    renderLogin()
    const email = screen.getByLabelText('E-mail')
    const password = screen.getByLabelText('Senha')
    expect(email).toHaveAttribute('type', 'email')
    expect(email).toHaveAttribute('autocomplete', 'username')
    expect(password).toHaveAttribute('type', 'password')
    expect(password).toHaveAttribute('autocomplete', 'current-password')
  })

  it('login certo leva ao painel', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { token: 'abc', id: '1', email: 'ana@exemplo.com', role: 'ADMINISTRADOR' }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'segredo123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    await waitFor(() => expect(screen.getByText('painel-destino')).toBeInTheDocument())
    expect(fetch).toHaveBeenCalledWith(
      '/admin/login',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('Enter no campo de senha envia o formulario', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(200, { token: 'abc', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'segredo123{Enter}')

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  })

  it('credenciais erradas mostram a mensagem generica do servidor, sem revelar se o e-mail existe', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(401, { code: 'NAO_AUTENTICADO', message: 'E-mail ou senha incorretos.' }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('E-mail'), 'ninguem@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'qualquer')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('E-mail ou senha incorretos.')
    expect(screen.queryByText('painel-destino')).not.toBeInTheDocument()
  })

  it('desabilita o botao enquanto o pedido esta em andamento', async () => {
    let resolveFetch: (value: Response) => void = () => undefined
    vi.mocked(fetch).mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        resolveFetch = resolve
      }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'segredo123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(screen.getByRole('button', { name: 'Entrando…' })).toBeDisabled()

    resolveFetch(jsonResponse(200, { token: 'abc', id: '1', email: 'ana@exemplo.com', role: 'LEITOR' }))
    await waitFor(() => expect(screen.getByText('painel-destino')).toBeInTheDocument())
  })
})
