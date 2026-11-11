import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { apiFetch } from '../api/client'
import type { LoginRequest, LoginResponse } from '../api/types'
import { clearSession, loadSession, saveSession, type Session } from '../api/session'

interface SessionContextValue {
  session: Session | null
  login: (email: string, password: string) => Promise<void>
  logout: () => void
  /** Sessao caiu do lado do servidor (401 numa chamada autenticada): limpa so o estado local, sem chamar a API de novo. */
  forceLogout: () => void
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession())

  const login = useCallback(async (email: string, password: string) => {
    const response = await apiFetch<LoginResponse>('/admin/login', {
      method: 'POST',
      body: { email, password } satisfies LoginRequest,
    })
    const next: Session = { token: response.token, id: response.id, email: response.email, role: response.role }
    saveSession(next)
    setSession(next)
  }, [])

  const logout = useCallback(() => {
    const token = session?.token
    clearSession()
    setSession(null)
    if (token) {
      // Sec18.7: revoga a sessao no servidor tambem; se a chamada falhar (rede), o token so expira pela ociosidade.
      void apiFetch('/admin/logout', { method: 'POST', token }).catch(() => undefined)
    }
  }, [session])

  const forceLogout = useCallback(() => {
    clearSession()
    setSession(null)
  }, [])

  const value = useMemo(() => ({ session, login, logout, forceLogout }), [session, login, logout, forceLogout])

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession precisa estar dentro de <SessionProvider>')
  return ctx
}
