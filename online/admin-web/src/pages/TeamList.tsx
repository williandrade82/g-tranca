import { useEffect, useState, type FormEvent } from 'react'
import { apiFetch } from '../api/client'
import {
  ApiRequestError,
  type CreateMemberRequest,
  type RemoveMemberRequest,
  type Role,
  type TeamListResponse,
  type TeamMember,
  type UpdateMemberRequest,
} from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'
import { newOperationId } from '../api/uuid'

function formatDate(ms: number): string {
  return new Date(ms).toLocaleString('pt-BR')
}

const ROLES: Role[] = ['LEITOR', 'OPERADOR', 'ADMINISTRADOR']
const LIMIT = 20

/**
 * Tela Equipe (docs/telas-admin-web.md: "lista de e-mails permitidos e papel de cada um; adicionar, mudar
 * papel, remover") - so administrador, inclusive a propria pagina (leitor/operador nem veem o link no menu,
 * ver AppHeader; aqui tambem confere o papel antes de chamar qualquer rota). Sem autocadastro: so quem ja e
 * administrador cadastra outro membro.
 */
export function TeamList() {
  const { session, forceLogout } = useSession()
  const isAdmin = session?.role === 'ADMINISTRADOR'

  const [offset, setOffset] = useState(0)
  const [data, setData] = useState<TeamListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [reloadToken, setReloadToken] = useState(0)

  const [adding, setAdding] = useState(false)
  const [addEmail, setAddEmail] = useState('')
  const [addPassword, setAddPassword] = useState('')
  const [addRole, setAddRole] = useState<Role>('LEITOR')
  const [addReason, setAddReason] = useState('')
  const [addOperationId, setAddOperationId] = useState('')
  const [addBusy, setAddBusy] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

  const [editing, setEditing] = useState<TeamMember | null>(null)
  const [editRole, setEditRole] = useState<Role>('LEITOR')
  const [editPassword, setEditPassword] = useState('')
  const [editReason, setEditReason] = useState('')
  const [editOperationId, setEditOperationId] = useState('')
  const [editBusy, setEditBusy] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)

  const [removing, setRemoving] = useState<TeamMember | null>(null)
  const [removeReason, setRemoveReason] = useState('')
  const [removeOperationId, setRemoveOperationId] = useState('')
  const [removeBusy, setRemoveBusy] = useState(false)
  const [removeError, setRemoveError] = useState<string | null>(null)

  useEffect(() => {
    if (!isAdmin) return
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const params = new URLSearchParams({ limit: String(LIMIT), offset: String(offset) })
        const response = await apiFetch<TeamListResponse>(`/admin/team?${params}`, { token: session?.token })
        if (!cancelled) setData(response)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          forceLogout()
          return
        }
        setError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [isAdmin, session?.token, forceLogout, offset, reloadToken])

  function openAdd() {
    setAdding(true)
    setAddEmail('')
    setAddPassword('')
    setAddRole('LEITOR')
    setAddReason('')
    setAddOperationId(newOperationId())
    setAddError(null)
  }

  async function confirmAdd(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setAddBusy(true)
    setAddError(null)
    try {
      await apiFetch('/admin/team', {
        method: 'POST',
        token: session?.token,
        body: {
          email: addEmail,
          password: addPassword,
          role: addRole,
          reason: addReason,
          operationId: addOperationId,
        } satisfies CreateMemberRequest,
      })
      setAdding(false)
      setOffset(0)
      setReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setAddError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setAddBusy(false)
    }
  }

  function openEdit(member: TeamMember) {
    setEditing(member)
    setEditRole(member.role)
    setEditPassword('')
    setEditReason('')
    setEditOperationId(newOperationId())
    setEditError(null)
  }

  async function confirmEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editing) return
    // So manda o que de fato mudou: mandar o papel igual ao atual geraria um registro de auditoria dizendo
    // "papel: ADMINISTRADOR -> ADMINISTRADOR" (ver TeamStore.update), que parece uma troca sem ter acontecido.
    const roleChanged = editRole !== editing.role
    const newPassword = editPassword.trim() === '' ? undefined : editPassword
    if (!roleChanged && newPassword === undefined) {
      setEditError('Troque o papel ou informe uma nova senha.')
      return
    }
    setEditBusy(true)
    setEditError(null)
    try {
      await apiFetch(`/admin/team/${encodeURIComponent(editing.id)}`, {
        method: 'PATCH',
        token: session?.token,
        body: {
          role: roleChanged ? editRole : undefined,
          password: newPassword,
          reason: editReason,
          operationId: editOperationId,
        } satisfies UpdateMemberRequest,
      })
      // Sec18.7: o papel na sessao do PROPRIO navegador fica guardado desde o login (SessionContext); se o
      // administrador logado acabou de trocar o seu proprio papel, essa copia local ficaria desatualizada
      // (continuaria mostrando o link e a tela Equipe) ate a sessao cair por outro motivo. Forca o login de novo.
      if (editing.id === session?.id && roleChanged) {
        forceLogout()
        return
      }
      setEditing(null)
      setReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setEditError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setEditBusy(false)
    }
  }

  function openRemove(member: TeamMember) {
    setRemoving(member)
    setRemoveReason('')
    setRemoveOperationId(newOperationId())
    setRemoveError(null)
  }

  async function confirmRemove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!removing) return
    setRemoveBusy(true)
    setRemoveError(null)
    try {
      await apiFetch(`/admin/team/${encodeURIComponent(removing.id)}`, {
        method: 'DELETE',
        token: session?.token,
        body: { reason: removeReason, operationId: removeOperationId } satisfies RemoveMemberRequest,
      })
      setRemoving(null)
      setReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setRemoveError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setRemoveBusy(false)
    }
  }

  if (!isAdmin) {
    return (
      <main className="accounts-page">
        <AppHeader title="Equipe" />
        <p role="alert">Acesso restrito a administradores.</p>
      </main>
    )
  }

  const hasPrev = offset > 0
  const hasNext = data != null && offset + LIMIT < data.total

  return (
    <main className="accounts-page">
      <AppHeader title="Equipe" />

      <div className="acoes-futuras" style={{ marginBottom: '1rem' }}>
        <button type="button" onClick={openAdd}>
          Adicionar membro
        </button>
      </div>

      {loading && <p>Carregando…</p>}
      {error && <p role="alert">{error}</p>}

      {data && (
        <section aria-label="Lista da equipe">
          {data.items.length === 0 ? (
            <p>Nenhum membro.</p>
          ) : (
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>E-mail</th>
                  <th>Papel</th>
                  <th>Cadastro</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((member) => (
                  <tr key={member.id}>
                    <td>{member.email}</td>
                    <td>{member.role}</td>
                    <td>{formatDate(member.createdMs)}</td>
                    <td>
                      <button type="button" onClick={() => openEdit(member)}>
                        Trocar papel/senha
                      </button>{' '}
                      <button type="button" onClick={() => openRemove(member)}>
                        Remover
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="paginacao">
            <button type="button" disabled={!hasPrev} onClick={() => setOffset((o) => Math.max(0, o - LIMIT))}>
              Anterior
            </button>
            <span>
              {data.total === 0 ? '0' : `${offset + 1}–${Math.min(offset + LIMIT, data.total)}`} de {data.total}
            </span>
            <button type="button" disabled={!hasNext} onClick={() => setOffset((o) => o + LIMIT)}>
              Próxima
            </button>
          </div>
        </section>
      )}

      {adding && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Adicionar membro"
          className="dialogo-confirmacao"
          onKeyDown={(e) => {
            if (e.key === 'Escape' && !addBusy) setAdding(false)
          }}
        >
          <form onSubmit={confirmAdd}>
            <h3>Adicionar membro</h3>
            <div className="campo">
              <label htmlFor="add-email">E-mail</label>
              <input id="add-email" type="email" autoComplete="off" value={addEmail} onChange={(e) => setAddEmail(e.target.value)} required autoFocus disabled={addBusy} />
            </div>
            <div className="campo">
              <label htmlFor="add-password">Senha inicial</label>
              <input
                id="add-password"
                type="password"
                autoComplete="new-password"
                value={addPassword}
                onChange={(e) => setAddPassword(e.target.value)}
                required
                minLength={6}
                disabled={addBusy}
              />
            </div>
            <div className="campo">
              <label htmlFor="add-role">Papel</label>
              <select id="add-role" value={addRole} onChange={(e) => setAddRole(e.target.value as Role)} disabled={addBusy}>
                {ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role}
                  </option>
                ))}
              </select>
            </div>
            <div className="campo">
              <label htmlFor="add-reason">Motivo</label>
              <textarea id="add-reason" value={addReason} onChange={(e) => setAddReason(e.target.value)} required minLength={3} maxLength={500} disabled={addBusy} />
            </div>
            {addError && <p role="alert">{addError}</p>}
            <div className="dialogo-botoes">
              <button type="submit" disabled={addBusy}>
                Confirmar
              </button>
              <button type="button" onClick={() => setAdding(false)} disabled={addBusy}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}

      {editing && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Trocar papel/senha de ${editing.email}`}
          className="dialogo-confirmacao"
          onKeyDown={(e) => {
            if (e.key === 'Escape' && !editBusy) setEditing(null)
          }}
        >
          <form onSubmit={confirmEdit}>
            <h3>Trocar papel/senha de {editing.email}</h3>
            <div className="campo">
              <label htmlFor="edit-role">Papel</label>
              <select id="edit-role" value={editRole} onChange={(e) => setEditRole(e.target.value as Role)} disabled={editBusy} autoFocus>
                {ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role}
                  </option>
                ))}
              </select>
            </div>
            <div className="campo">
              <label htmlFor="edit-password">Nova senha (deixe em branco para não trocar)</label>
              <input
                id="edit-password"
                type="password"
                autoComplete="new-password"
                value={editPassword}
                onChange={(e) => setEditPassword(e.target.value)}
                minLength={6}
                disabled={editBusy}
              />
            </div>
            <div className="campo">
              <label htmlFor="edit-reason">Motivo</label>
              <textarea id="edit-reason" value={editReason} onChange={(e) => setEditReason(e.target.value)} required minLength={3} maxLength={500} disabled={editBusy} />
            </div>
            {editError && <p role="alert">{editError}</p>}
            <div className="dialogo-botoes">
              <button type="submit" disabled={editBusy}>
                Confirmar
              </button>
              <button type="button" onClick={() => setEditing(null)} disabled={editBusy}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}

      {removing && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Remover ${removing.email}`}
          className="dialogo-confirmacao"
          onKeyDown={(e) => {
            if (e.key === 'Escape' && !removeBusy) setRemoving(null)
          }}
        >
          <form onSubmit={confirmRemove}>
            <h3>Remover {removing.email}</h3>
            <p>Essa pessoa perde o acesso ao console imediatamente. Continuar?</p>
            <div className="campo">
              <label htmlFor="remove-reason">Motivo</label>
              <textarea
                id="remove-reason"
                value={removeReason}
                onChange={(e) => setRemoveReason(e.target.value)}
                required
                minLength={3}
                maxLength={500}
                autoFocus
                disabled={removeBusy}
              />
            </div>
            {removeError && <p role="alert">{removeError}</p>}
            <div className="dialogo-botoes">
              <button type="submit" disabled={removeBusy}>
                Confirmar
              </button>
              <button type="button" onClick={() => setRemoving(null)} disabled={removeBusy}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  )
}
