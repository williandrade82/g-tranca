import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSession } from '../auth/SessionContext'
import { ApiRequestError } from '../api/types'

/** Tela Entrar (docs/telas-admin-web.md): e-mail e senha; erro sem detalhar se o e-mail existe (Sec18.7). */
export function Login() {
  const { login } = useSession()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(email, password)
      navigate('/', { replace: true })
    } catch (e) {
      // O admin-api ja devolve uma mensagem generica para credenciais erradas, bloqueio por tentativas e sessao
      // ociosa (Sec18.7); aqui so repassamos, sem adivinhar nada a mais sobre a conta.
      setError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <form onSubmit={handleSubmit} aria-label="Entrar no console">
        <h1>Console G-Tranca</h1>
        <div className="campo">
          <label htmlFor="email">E-mail</label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
          />
        </div>
        <div className="campo">
          <label htmlFor="password">Senha</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={submitting}
          />
        </div>
        {error && (
          <p role="alert" className="erro">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </main>
  )
}
