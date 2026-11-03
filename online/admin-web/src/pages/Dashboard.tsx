import { useSession } from '../auth/SessionContext'

/** Placeholder: a tela Painel (docs/telas-admin-web.md) entra numa etapa seguinte. */
export function Dashboard() {
  const { session, logout } = useSession()
  return (
    <main>
      <h1>Painel</h1>
      <p>Sessão de {session?.email} ({session?.role}).</p>
      <button type="button" onClick={logout}>
        Sair
      </button>
    </main>
  )
}
