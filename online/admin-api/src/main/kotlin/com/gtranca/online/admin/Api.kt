package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountSummary
import com.gtranca.online.contracts.AdminWalletDto
import kotlinx.serialization.Serializable

/** POST /admin/login (Sec18.7, mesmo mecanismo de Sec18.1: e-mail e senha). */
@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val id: String, val email: String, val role: Role)

/** GET /admin/team: listagem paginada (toda listagem e paginada). */
@Serializable
data class TeamListResponse(val items: List<TeamMember>, val total: Int, val limit: Int, val offset: Int)

/** POST /admin/team (administrador; sem autocadastro). [reason] e obrigatorio; [operationId] e a chave de idempotencia. */
@Serializable
data class CreateMemberRequest(val email: String, val password: String, val role: Role, val reason: String, val operationId: String? = null)

/** PATCH /admin/team/{id} (administrador): troca o papel e/ou a senha; ao menos um dos dois precisa vir preenchido. */
@Serializable
data class UpdateMemberRequest(val role: Role? = null, val password: String? = null, val reason: String, val operationId: String? = null)

/** DELETE /admin/team/{id} (administrador): motivo no corpo JSON (ver Dockerfile/README do admin-api). */
@Serializable
data class RemoveMemberRequest(val reason: String, val operationId: String? = null)

/** Estado de um servico sondado pelo Painel (docs/telas-admin-web.md): verde/amarelo/vermelho. */
@Serializable
enum class ServiceStatusLevel { VERDE, AMARELO, VERMELHO }

@Serializable
data class ServiceHealthInfo(val name: String, val status: ServiceStatusLevel, val latencyMs: Long? = null)

/**
 * GET /admin/dashboard (leitor+): tela Painel (docs/telas-admin-web.md).
 *
 * [playersOnline], [tablesOpen], [tablesInGame] e [matchesEndedLastHour] vem de `GET /internal/admin/stats` do
 * lobby-service. Ficam nulos se `LOBBY_SERVICE_URL`/`ADMIN_INTERNAL_SECRET` nao estiverem configurados ou se o
 * lobby-service nao responder (ver `admin.gateway` no log); o admin-web mostra "indisponivel" nesse caso, nunca
 * um numero inventado.
 */
@Serializable
data class DashboardResponse(
    val services: List<ServiceHealthInfo>,
    val playersOnline: Int? = null,
    val tablesOpen: Int? = null,
    val tablesInGame: Int? = null,
    val matchesEndedLastHour: Int? = null,
    val grafanaUrl: String? = null,
    val lokiUrl: String? = null,
)

/**
 * GET /admin/accounts?q=&limit=&offset= (leitor+): tela Contas (docs/telas-admin-web.md), busca agregada do
 * lobby-service. Mesmo formato de paginacao de [TeamListResponse] ([items]/[total]/[limit]/[offset]); reusa
 * [AdminAccountSummary] de `online/contracts` (nao duplica o tipo).
 */
@Serializable
data class AccountsPageResponse(val items: List<AdminAccountSummary>, val total: Int, val limit: Int, val offset: Int)

/**
 * GET /admin/accounts/{id} (leitor+): ficha da conta (docs/telas-admin-web.md). [account.currentTables] sao as
 * mesas onde a conta esta sentada AGORA, nao historico (ver [AdminAccountDetail]).
 *
 * [wallet] e [walletUnavailable] juntos distinguem dois casos bem diferentes para a conta nao ter carteira:
 * - [wallet] `null` e [walletUnavailable] `false`: a conta genuinamente ainda nao tem carteira online (o
 *   economy-service respondeu 404) - a tela mostra "sem carteira online", nao e um problema.
 * - [wallet] `null` e [walletUnavailable] `true`: o economy-service nao respondeu, ou nao esta configurado - a
 *   tela mostra "indisponivel" (ver `admin.gateway` no log), nunca inventa saldo.
 */
@Serializable
data class AccountDetailResponse(val account: AdminAccountDetail, val wallet: AdminWalletDto? = null, val walletUnavailable: Boolean = false)

/**
 * POST /admin/accounts/{id}/block|unblock|revoke-sessions (operador+, docs/telas-admin-web.md "operador
 * bloqueia"). [reason] e OBRIGATORIO nas tres (convencao do projeto: toda acao destrutiva pede motivo), mesmo
 * em "revoke-sessions" e "unblock" (o lobby-service em si nao exige motivo nelas, ver `AdminBlockRequest`
 * no contrato - a exigencia e do admin-api, pela auditoria). [operationId] e a chave de idempotencia, como em
 * [CreateMemberRequest].
 */
@Serializable
data class AccountActionRequest(val reason: String, val operationId: String? = null)
