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
