// Espelha os DTOs de `online/admin-api/src/main/kotlin/com/gtranca/online/admin/Api.kt` (Sec18.7).
// Nao duplicar regra aqui: qualquer validacao de negocio mora no admin-api; isto e so o formato da mensagem.

export type Role = 'LEITOR' | 'OPERADOR' | 'ADMINISTRADOR'

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  id: string
  email: string
  role: Role
}

/** Mesmo formato de `com.gtranca.online.contracts.ApiError`: codigo estavel + mensagem em pt-BR. */
export interface ApiError {
  code: string
  message: string
}

export class ApiRequestError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message)
  }
}
