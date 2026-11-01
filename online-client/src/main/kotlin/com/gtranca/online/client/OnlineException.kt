package com.gtranca.online.client

import com.gtranca.online.contracts.ApiError
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.EconomyError
import com.gtranca.online.contracts.EconomyErrorCode

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
    /** Recusa da economia (§18.5/§17.10): `null` quando [kind] não vem do economy-service. */
    val economyCode: EconomyErrorCode? = null,
) : Exception(message ?: kind.name, cause) {
    /** Vale tentar de novo sozinho (rede ou servidor instável); as demais não melhoram com a espera. */
    val retryable: Boolean get() = kind == OnlineErrorKind.NETWORK || kind == OnlineErrorKind.MAINTENANCE

    companion object {
        /** Traduz a recusa HTTP ([status], corpo já lido como [api] se for JSON do lobby). */
        fun fromResponse(status: Int, api: ApiError?): OnlineException = when {
            // 426 do canal da partida pedido sem upgrade ("use uma conexão WebSocket") não é versão antiga.
            status == 426 && api?.code == ErrorCode.REQUISICAO_INVALIDA -> OnlineException(OnlineErrorKind.API, api.code, status, api.message)
            status == 426 -> OnlineException(OnlineErrorKind.UPDATE_APP, ErrorCode.VERSAO_INCOMPATIVEL, status, api?.message)
            api?.code == ErrorCode.VERSAO_INCOMPATIVEL -> OnlineException(OnlineErrorKind.MAINTENANCE, api.code, status, api.message)
            // §18.2: app sem a chave certa. Nao e token vencido: recriar o convidado nao resolve.
            api?.code == ErrorCode.APP_NAO_RECONHECIDO -> OnlineException(OnlineErrorKind.API, api.code, status, api.message)
            status == 401 || api?.code == ErrorCode.NAO_AUTENTICADO && status != 503 ->
                OnlineException(OnlineErrorKind.UNAUTHENTICATED, api?.code, status, api?.message)
            api != null -> OnlineException(OnlineErrorKind.API, api.code, status, api.message)
            status in 502..504 -> OnlineException(OnlineErrorKind.MAINTENANCE, null, status)
            else -> OnlineException(OnlineErrorKind.UNEXPECTED, null, status)
        }

        /**
         * Traduz a recusa HTTP da economia (§18.5, §17.10): [error] é o corpo já decodificado como `EconomyError`,
         * se for JSON da economia. A UI distingue um erro de negócio ([economyCode], ex.: `SALDO_INSUFICIENTE`) de
         * rede/servidor fora ([OnlineErrorKind.NETWORK]/[OnlineErrorKind.MAINTENANCE]) por [OnlineException.kind].
         */
        fun fromEconomyResponse(status: Int, error: EconomyError?): OnlineException = when {
            // §18.2: app sem a chave certa. Nao e token vencido: recriar o convidado nao resolve.
            error?.code == EconomyErrorCode.APP_NAO_RECONHECIDO ->
                OnlineException(OnlineErrorKind.API, status = status, message = error.message, economyCode = error.code)
            status == 401 || error?.code == EconomyErrorCode.NAO_AUTENTICADO ->
                OnlineException(OnlineErrorKind.UNAUTHENTICATED, status = status, message = error?.message, economyCode = error?.code)
            error?.code == EconomyErrorCode.INDISPONIVEL ->
                OnlineException(OnlineErrorKind.MAINTENANCE, status = status, message = error.message, economyCode = error.code)
            error != null -> OnlineException(OnlineErrorKind.API, status = status, message = error.message, economyCode = error.code)
            status in 502..504 -> OnlineException(OnlineErrorKind.MAINTENANCE, status = status)
            else -> OnlineException(OnlineErrorKind.UNEXPECTED, status = status)
        }
    }
}
