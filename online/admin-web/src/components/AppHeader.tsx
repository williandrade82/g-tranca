import { NavLink } from 'react-router-dom'
import { useSession } from '../auth/SessionContext'

/** Cabecalho comum das telas autenticadas (Sec18.7): navegacao entre Painel/Contas/Mesas, sessao e Sair. */
export function AppHeader({ title }: { title: string }) {
  const { session, logout } = useSession()
  return (
    <header className="app-header">
      <div className="app-header-top">
        <h1>{title}</h1>
        <div className="app-header-session">
          <span>
            {session?.email} ({session?.role})
          </span>
          <button type="button" onClick={logout}>
            Sair
          </button>
        </div>
      </div>
      <nav aria-label="Navegação principal">
        <NavLink to="/" end>
          Painel
        </NavLink>
        <NavLink to="/contas">Contas</NavLink>
        <NavLink to="/mesas">Mesas</NavLink>
      </nav>
    </header>
  )
}
