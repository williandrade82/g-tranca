package com.gtranca.online.admin

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
 * [playersOnline], [tablesOpen], [tablesInGame] e [matchesEndedLastHour] sao nulos porque **nenhum servico dono do
 * dado expoe essa agregacao hoje** (ver online-backend-dev): o admin-api nao le banco de outro servico, so API.
 * O admin-web mostra "indisponivel" nesse caso, nunca um numero inventado.
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
