import type { Role } from './types'

/**
 * Sessao do operador guardada so em memoria do processo + `sessionStorage` (nunca `localStorage` nem na URL):
 * fecha a aba, a sessao local some; o admin-api ainda tem o teto de ociosidade/absoluto por tras (Sec18.7).
 * Risco aceito, a registrar em docs/seguranca-pendencias.md pelo chamador: um XSS no admin-web leria o token
 * daqui enquanto a aba estiver aberta.
 */
const STORAGE_KEY = 'gtranca-admin-session'

export interface Session {
  token: string
  id: string
  email: string
  role: Role
}

export function loadSession(): Session | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as Session
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return null
  }
}

export function saveSession(session: Session): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  sessionStorage.removeItem(STORAGE_KEY)
}
