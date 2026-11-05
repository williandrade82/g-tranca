package com.gtranca.online.admin

import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.ErrorCode
import java.util.Locale

/** E-mail normalizado (minusculas, sem espacos nas pontas), ou `null` se vazio, grande demais ou sem forma de e-mail. */
fun normalizeEmail(raw: String): String? {
    val email = raw.trim().lowercase(Locale.ROOT)
    if (email.isEmpty() || email.length > AccountLimits.MAX_EMAIL || email.any { it.isWhitespace() || it.isISOControl() }) return null
    val at = email.indexOf('@')
    if (at <= 0 || at != email.lastIndexOf('@') || at == email.length - 1 || !email.substringAfter('@').contains('.')) return null
    return email
}

/** Sec18.7 espelha Sec18.1: minimo de [AccountLimits.MIN_PASSWORD] caracteres, sem outra exigencia. */
fun validatePassword(password: String) {
    if (password.length < AccountLimits.MIN_PASSWORD) {
        adminError(ErrorCode.REQUISICAO_INVALIDA, "A senha deve ter ao menos ${AccountLimits.MIN_PASSWORD} caracteres.", 400)
    }
    if (password.length > AccountLimits.MAX_PASSWORD) {
        adminError(ErrorCode.REQUISICAO_INVALIDA, "Senha grande demais.", 400)
    }
}

/** Toda acao de equipe exige o motivo (Sec18.7 "por que"; telas: acao destrutiva pede confirmacao e motivo). */
fun validateReason(reason: String) {
    val trimmed = reason.trim()
    if (trimmed.length < AdminLimits.MIN_REASON || trimmed.length > AdminLimits.MAX_REASON) {
        adminError(
            ErrorCode.REQUISICAO_INVALIDA,
            "Informe o motivo da ação (${AdminLimits.MIN_REASON} a ${AdminLimits.MAX_REASON} caracteres).",
            400,
        )
    }
}

fun validateOperationId(operationId: String?) {
    if (operationId != null && (operationId.isBlank() || operationId.length > AdminLimits.MAX_OPERATION_ID)) {
        adminError(ErrorCode.REQUISICAO_INVALIDA, "Identificador de operação inválido.", 400)
    }
}
