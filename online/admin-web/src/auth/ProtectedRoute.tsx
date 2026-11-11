import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useSession } from './SessionContext'

/** Sem sessao, volta para Entrar. As telas decidem sozinhas o papel minimo que exigem (Sec18.7). */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { session } = useSession()
  if (!session) return <Navigate to="/entrar" replace />
  return <>{children}</>
}
