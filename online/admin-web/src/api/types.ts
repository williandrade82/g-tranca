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

export type ServiceStatusLevel = 'VERDE' | 'AMARELO' | 'VERMELHO'

export interface ServiceHealthInfo {
  name: string
  status: ServiceStatusLevel
  latencyMs: number | null
}

/**
 * GET /admin/dashboard (tela Painel, docs/telas-admin-web.md). Os campos de contagem sao `null` quando o
 * admin-api ainda nao tem de onde buscar o dado (nenhum servico dono expoe essa agregacao): mostrar
 * "indisponivel" na tela, nunca inventar um numero.
 */
export interface DashboardResponse {
  services: ServiceHealthInfo[]
  playersOnline: number | null
  tablesOpen: number | null
  tablesInGame: number | null
  matchesEndedLastHour: number | null
  grafanaUrl: string | null
  lokiUrl: string | null
}
