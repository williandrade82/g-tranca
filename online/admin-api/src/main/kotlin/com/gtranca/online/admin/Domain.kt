package com.gtranca.online.admin

import com.gtranca.online.contracts.ErrorCode
import kotlinx.serialization.Serializable

/**
 * Papeis da equipe do console (Sec18.7): LEITOR so consulta; OPERADOR faz as acoes do dia a dia (sempre com
 * motivo); ADMINISTRADOR faz tudo, inclusive gerenciar a equipe. A ordem de declaracao importa: [atLeast] compara
 * pelo `ordinal`, entao LEITOR < OPERADOR < ADMINISTRADOR tem que continuar nessa ordem.
 */
@Serializable
enum class Role { LEITOR, OPERADOR, ADMINISTRADOR }

/** true se este papel e pelo menos [min] (mesma ideia de "leitor+" nas telas). */
fun Role.atLeast(min: Role): Boolean = ordinal >= min.ordinal

/** Membro da equipe, sem a senha nem o hash (DTO publico e registro de auditoria usam o mesmo formato). */
@Serializable
data class TeamMember(val id: String, val email: String, val role: Role, val createdMs: Long)

/** Quem fez a acao (para a auditoria): copiado do membro autenticado, nunca de um parametro livre do pedido. */
data class Actor(val id: String, val email: String)

/** Recusa de uma operacao do admin-api, com o motivo em pt-BR e o [ErrorCode] compartilhado de `contracts`. */
class AdminException(val code: ErrorCode, override val message: String, val status: Int) : RuntimeException(message) {
    fun toApiError() = com.gtranca.online.contracts.ApiError(code, message)
}

fun adminError(code: ErrorCode, message: String, status: Int): Nothing = throw AdminException(code, message, status)

object AdminLimits {
    const val MAX_BODY_BYTES = 4 * 1024

    /** Motivo da acao (Sec18.7 "por que"; telas: "acao destrutiva pede confirmacao e motivo"). */
    const val MIN_REASON = 3
    const val MAX_REASON = 500

    const val MAX_OPERATION_ID = 100

    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100
}
