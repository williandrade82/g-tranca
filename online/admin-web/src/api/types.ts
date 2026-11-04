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
 * GET /admin/dashboard (tela Painel, docs/telas-admin-web.md). Os campos de contagem vem do lobby-service;
 * ficam `null` se o admin-api nao estiver configurado pra chamar ou se o lobby-service nao responder: mostrar
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

export type AccountKind = 'CONVIDADO' | 'GOOGLE' | 'EMAIL'

/**
 * Espelha `com.gtranca.online.contracts.AdminAccountSummary`. `email`/`createdMs`: so conta EMAIL tem os dois
 * de verdade; GOOGLE e CONVIDADO ficam `null` (ver comentario do contrato). `blocked` e sempre `false` nesta
 * etapa: bloquear conta ainda nao tem rota (so leitura).
 */
export interface AdminAccountSummary {
  id: string
  kind: AccountKind
  name: string
  email: string | null
  createdMs: number | null
  lastSeenMs: number | null
  blocked: boolean
}

/** GET /admin/accounts: mesmo formato de paginacao das outras listagens (items/total/limit/offset). */
export interface AccountsPageResponse {
  items: AdminAccountSummary[]
  total: number
  limit: number
  offset: number
}

export type TableState = 'ABERTA' | 'EM_JOGO' | 'ENCERRADA'

/** Mesa onde a conta esta sentada AGORA (nao e historico de partidas, ver contrato). */
export interface AdminTableSummary {
  id: string
  name: string
  state: TableState
  updatedMs: number
}

export interface AdminAccountDetail {
  id: string
  kind: AccountKind
  name: string
  email: string | null
  createdMs: number | null
  lastSeenMs: number | null
  blocked: boolean
  currentTables: AdminTableSummary[]
}

export interface AdminWalletDto {
  coins: number
  inventory: Record<string, number>
  migratedLocal: boolean
}

/**
 * GET /admin/accounts/{id}: ficha (lobby) + carteira (economy). `wallet` e `walletUnavailable` juntos
 * distinguem dois casos: `wallet` nulo com `walletUnavailable` falso = a conta genuinamente ainda nao tem
 * carteira online (mostrar "sem carteira online"); `wallet` nulo com `walletUnavailable` true = o
 * economy-service nao respondeu ou nao esta configurado (mostrar "indisponivel", nunca inventar saldo).
 */
export interface AccountDetailResponse {
  account: AdminAccountDetail
  wallet: AdminWalletDto | null
  walletUnavailable: boolean
}

/** POST /admin/accounts/{id}/block|unblock|revoke-sessions (operador+): motivo sempre obrigatorio. */
export interface AccountActionRequest {
  reason: string
  operationId?: string
}

export interface RevokeSessionsResponse {
  revoked: number
}

export type TableMode = 'INDIVIDUAL' | 'DUPLAS'
export type TempoDeJogada = 'INDETERMINADO' | 'UM_MINUTO' | 'CINCO_MINUTOS' | 'QUINZE_MINUTOS'
export type Punicao = 'SEM_PUNICAO' | 'PERDE_APOSTA'
export type Visibilidade = 'PRIVADA' | 'PUBLICA'
export type BotLevel = 'FACIL' | 'MEDIO' | 'DIFICIL'
export type BotProfile = 'JOVEM' | 'SOBRIO' | 'ALTERNATIVO' | 'ATLETA'
export type SeatKind = 'VAGO' | 'HUMANO' | 'ROBO'

/** Espelha `com.gtranca.online.contracts.TableRules`: regras e critérios da mesa, imutáveis depois de iniciar. */
export interface TableRules {
  mode: TableMode
  targetScore: number
  bet: number
  skillBoostersAllowed: boolean
  tempoDeJogada: TempoDeJogada
  punicao: Punicao
  visibilidade: Visibilidade
  robotLevel: BotLevel
}

export interface SeatView {
  index: number
  kind: SeatKind
  guestId: string | null
  name: string | null
  photoUrl: string | null
  profile: BotProfile | null
  level: BotLevel | null
}

/** Espelha `com.gtranca.online.contracts.TableView` (tela Mesas, docs/telas-admin-web.md): mesma forma pra
 * lista e pra ficha - id, anfitrião, estado, regras, assentos. */
export interface TableView {
  id: string
  hostId: string
  hostName: string
  state: TableState
  rules: TableRules
  seats: SeatView[]
  name: string
}

/** GET /admin/tables: TODO estado (ao contrário da lista pública do app, que nunca mostra ENCERRADA). */
export interface TablesPageResponse {
  items: TableView[]
  total: number
  limit: number
  offset: number
}

/** POST /admin/tables/{id}/close (operador+): motivo sempre obrigatório. Idempotente em qualquer estado;
 * mesa EM_JOGO devolve a aposta a todos os jogadores (ninguém é punido). */
export interface CloseTableRequest {
  reason: string
  operationId?: string
  /** O estado que o operador viu ao confirmar o aviso (ver `TableDetail.openConfirm`): se a mesa tiver virado
   * EM_JOGO entre a ficha abrir e o clique, e este campo nao for EM_JOGO, o admin-api recusa com 409. */
  expectedState?: TableState
}

/** Espelha `com.gtranca.online.contracts.AdminLedgerEntryDto` (livro-razao, tela Economia). */
export interface AdminLedgerEntryDto {
  reason: string
  coins: number
  boosterId: string | null
  quantity: number
  reference: string | null
  detail: string | null
  key: string
  createdMs: number
}

/** GET /admin/accounts/{id}/ledger. */
export interface LedgerPageResponse {
  items: AdminLedgerEntryDto[]
  total: number
  limit: number
  offset: number
}

/**
 * POST /admin/accounts/{id}/adjust (operador+): motivo e chave de idempotencia SEMPRE obrigatorios (gere um
 * uuid no front antes de confirmar). Pelo menos um ajuste real: `coinsDelta` (pode ser negativo) OU o par
 * `boosterId`/`quantityDelta` (tambem pode ser negativo) - os dois juntos tambem valem. O economy-service
 * nunca deixa saldo nem quantidade negativos (409); nao precisa validar isso de novo aqui, so tratar o erro.
 */
export interface AdminAdjustRequest {
  reason: string
  operationId: string
  coinsDelta?: number
  boosterId?: string
  quantityDelta?: number
}

/** Espelha `com.gtranca.online.contracts.AdminMigrationDto` (Sec18.6): uma migracao de carteira local JA feita. */
export interface AdminMigrationDto {
  accountId: string
  coins: number
  createdMs: number
}

/** GET /admin/migrations: lista GLOBAL (todas as contas), nao de uma conta so. */
export interface MigrationsPageResponse {
  items: AdminMigrationDto[]
  total: number
  limit: number
  offset: number
}
