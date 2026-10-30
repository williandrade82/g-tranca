package com.gtranca.online.client

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode

/** O que deu errado, em grupos que a interface traduz para pt-BR (os textos ficam no `strings.xml` do app). */
enum class OnlineErrorKind {
    /** Sem rede, servidor fora do ar ou resposta que não é do jogo. */
    NETWORK,

    /** 426: o app é mais antigo que o servidor (§18.8): "Atualize o aplicativo". */
    UPDATE_APP,

    /** 503 de versão (app mais novo) ou servidor indisponível: "Servidor em manutenção". */
    MAINTENANCE,

    /** 401: o acesso (token) não vale mais. */
    UNAUTHENTICATED,

    /** Recusa do lobby com código ([OnlineException.code]). */
    API,

    UNEXPECTED,
}

/**
 * Falha de uma chamada online. [message] é o texto do servidor (já em pt-BR) quando houver; a interface deve preferir os
 * textos próprios por [kind] e [code].
 */
class OnlineException(
    val kind: OnlineErrorKind,
    val code: ErrorCode? = null,
    val status: Int? = null,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: kind.name, cause) {
    /** Vale tentar de novo sozinho (rede ou servidor instável); as demais não melhoram com a espera. */
    val retryable: Boolean get() = kind == OnlineErrorKind.NETWORK || kind == OnlineErrorKind.MAINTENANCE

    companion object {
        /** Traduz a recusa HTTP ([status], corpo já lido como [api] se for JSON do lobby). */
        fun fromResponse(status: Int, api: ApiError?): OnlineException = when {
            status == 426 -> OnlineException(OnlineErrorKind.UPDATE_APP, ErrorCode.VERSAO_INCOMPATIVEL, status, api?.message)
            api?.code == ErrorCode.VERSAO_INCOMPATIVEL -> OnlineException(OnlineErrorKind.MAINTENANCE, api.code, status, api.message)
            status == 401 || api?.code == ErrorCode.NAO_AUTENTICADO && status != 503 ->
                OnlineException(OnlineErrorKind.UNAUTHENTICATED, api?.code, status, api?.message)
            api != null -> OnlineException(OnlineErrorKind.API, api.code, status, api.message)
            status in 502..504 -> OnlineException(OnlineErrorKind.MAINTENANCE, null, status)
            else -> OnlineException(OnlineErrorKind.UNEXPECTED, null, status)
        }
    }
}
