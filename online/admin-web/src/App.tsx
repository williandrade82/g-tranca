import { Route, Routes } from 'react-router-dom'
import { SessionProvider } from './auth/SessionContext'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { AccountsList } from './pages/AccountsList'
import { AccountDetail } from './pages/AccountDetail'
import { TablesList } from './pages/TablesList'
import { TableDetail } from './pages/TableDetail'
import { MigrationsList } from './pages/MigrationsList'
import { TeamList } from './pages/TeamList'
import { AuditList } from './pages/AuditList'

export function App() {
  return (
    <SessionProvider>
      <Routes>
        <Route path="/entrar" element={<Login />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <Dashboard />
            </ProtectedRoute>
          }
        />
        <Route
          path="/contas"
          element={
            <ProtectedRoute>
              <AccountsList />
            </ProtectedRoute>
          }
        />
        <Route
          path="/contas/:id"
          element={
            <ProtectedRoute>
              <AccountDetail />
            </ProtectedRoute>
          }
        />
        <Route
          path="/mesas"
          element={
            <ProtectedRoute>
              <TablesList />
            </ProtectedRoute>
          }
        />
        <Route
          path="/mesas/:id"
          element={
            <ProtectedRoute>
              <TableDetail />
            </ProtectedRoute>
          }
        />
        <Route
          path="/migracoes"
          element={
            <ProtectedRoute>
              <MigrationsList />
            </ProtectedRoute>
          }
        />
        <Route
          path="/auditoria"
          element={
            <ProtectedRoute>
              <AuditList />
            </ProtectedRoute>
          }
        />
        <Route
          path="/equipe"
          element={
            <ProtectedRoute>
              <TeamList />
            </ProtectedRoute>
          }
        />
      </Routes>
    </SessionProvider>
  )
}
