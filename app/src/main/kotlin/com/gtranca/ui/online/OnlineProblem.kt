package com.gtranca.ui.online

import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.ErrorCode

/**
 * O que a interface explica ao jogador quando algo falha no online (§18.8, docs/telas.md 3.21 a 3.24). Cada valor tem um
 * texto em pt-BR no `strings.xml` (ver [OnlineProblemTexts]).
 */
enum class OnlineProblem {
    /** Sem rede, servidor fora do ar ou endereço errado. */
    NETWORK,

    /** 426: "Atualize o aplicativo". */
    UPDATE_APP,

    /** 503: "Servidor em manutenção". */
    MAINTENANCE,
    UNAUTHENTICATED,
    TABLE_NOT_FOUND,
    TABLE_FULL,

    /** §18.2: outro jogador pediu o mesmo lugar antes. */
    SEAT_TAKEN,
    ALREADY_STARTED,
    NOT_HOST,
    EMPTY_SEATS,
    INVALID_SEAT,
    INVALID_REQUEST,
    RATE_LIMIT,
    GAME_UNAVAILABLE,
    INSUFFICIENT_BALANCE,

    /** §18.2: o servidor não reconheceu a assinatura do aplicativo. */
    APP_NOT_RECOGNIZED,

    /** O id digitado não tem 6 caracteres válidos (checado no aparelho, sem chamar o servidor). */
    INVALID_ID,

    /** A pontuação-alvo não é um inteiro positivo dentro do limite. */
    INVALID_TARGET,
    UNEXPECTED,
}

/** Traduz a falha do cliente para o problema que a tela mostra. */
fun OnlineException.toProblem(): OnlineProblem = when (kind) {
    OnlineErrorKind.NETWORK -> OnlineProblem.NETWORK
    OnlineErrorKind.UPDATE_APP -> OnlineProblem.UPDATE_APP
    OnlineErrorKind.MAINTENANCE -> OnlineProblem.MAINTENANCE
    OnlineErrorKind.UNAUTHENTICATED -> OnlineProblem.UNAUTHENTICATED
    OnlineErrorKind.UNEXPECTED -> OnlineProblem.UNEXPECTED
    OnlineErrorKind.API -> when (code) {
        ErrorCode.MESA_INEXISTENTE -> OnlineProblem.TABLE_NOT_FOUND
        ErrorCode.MESA_CHEIA -> OnlineProblem.TABLE_FULL
        ErrorCode.MESA_JA_INICIADA -> OnlineProblem.ALREADY_STARTED
        ErrorCode.SEM_PERMISSAO -> OnlineProblem.NOT_HOST
        ErrorCode.LUGARES_VAGOS -> OnlineProblem.EMPTY_SEATS
        ErrorCode.LUGAR_INVALIDO -> OnlineProblem.INVALID_SEAT
        ErrorCode.REQUISICAO_INVALIDA, ErrorCode.CORPO_GRANDE_DEMAIS, ErrorCode.MENSAGEM_INVALIDA -> OnlineProblem.INVALID_REQUEST
        ErrorCode.LIMITE_DE_TAXA -> OnlineProblem.RATE_LIMIT
        ErrorCode.PARTIDA_INDISPONIVEL -> OnlineProblem.GAME_UNAVAILABLE
        ErrorCode.SALDO_INSUFICIENTE -> OnlineProblem.INSUFFICIENT_BALANCE
        ErrorCode.NAO_AUTENTICADO -> OnlineProblem.UNAUTHENTICATED
        ErrorCode.VERSAO_INCOMPATIVEL -> OnlineProblem.UPDATE_APP
        ErrorCode.APP_NAO_RECONHECIDO -> OnlineProblem.APP_NOT_RECOGNIZED
        ErrorCode.LOGIN_INDISPONIVEL -> OnlineProblem.MAINTENANCE
        ErrorCode.ASSENTO_ABANDONADO, null -> OnlineProblem.UNEXPECTED
    }
}
