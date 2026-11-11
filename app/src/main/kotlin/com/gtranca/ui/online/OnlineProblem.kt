package com.gtranca.ui.online

import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.EconomyErrorCode
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

    /** §18.5/§17.10: a carteira do pedido não é a de quem está autenticado (nunca confundir com [NOT_HOST], que é o
     * mesmo nome de código no lobby mas com outro significado). */
    WALLET_NOT_YOURS,

    /** §17.10: a loja recusou a oferta ou o pedido de compra (índice inexistente, chave fora do formato). */
    SHOP_INVALID_REQUEST,

    /** §18.2: o servidor não reconheceu a assinatura do aplicativo. */
    APP_NOT_RECOGNIZED,

    /** O id digitado não tem 6 caracteres válidos (checado no aparelho, sem chamar o servidor). */
    INVALID_ID,

    /** A pontuação-alvo não é um inteiro positivo dentro do limite. */
    INVALID_TARGET,

    /** §18.1 (mudança de 02/11/2026): esse e-mail já tem uma conta (cadastro por e-mail e senha). */
    EMAIL_ALREADY_REGISTERED,

    /** §18.1: muitas tentativas erradas de login com o mesmo e-mail; aguarde antes de tentar de novo. */
    LOGIN_LOCKED,

    /** §18.7: o operador do console bloqueou esta conta. Só impede login/entrar numa mesa nova; nunca aparece
     * durante uma partida em andamento (bloquear não ejeta ninguém). */
    ACCOUNT_BLOCKED,
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
        ErrorCode.EMAIL_JA_CADASTRADO -> OnlineProblem.EMAIL_ALREADY_REGISTERED
        ErrorCode.LOGIN_BLOQUEADO -> OnlineProblem.LOGIN_LOCKED
        ErrorCode.CONTA_BLOQUEADA -> OnlineProblem.ACCOUNT_BLOCKED
        // §18.7: código genérico de 503 do lobby/economy; reaproveita o texto de manutenção (já é "serviço fora
        // do ar, tente depois"), sem precisar de string nova.
        ErrorCode.SERVICO_INDISPONIVEL -> OnlineProblem.MAINTENANCE
        ErrorCode.ASSENTO_ABANDONADO, null -> OnlineProblem.UNEXPECTED
    }
}

/**
 * O que a tela de Conta online (3.22, §18.1) explica, separado de [OnlineProblem] porque o mesmo código do servidor
 * tem texto diferente aqui: [ErrorCode.NAO_AUTENTICADO] no login é "e-mail ou senha incorretos" (nunca "sessão
 * expirou", que é o que [OnlineProblem.UNAUTHENTICATED] diz nas outras telas), e [ErrorCode.LOGIN_INDISPONIVEL] no
 * cadastro quer dizer que a conta já foi criada (a carteira é que não migrou ainda).
 */
enum class OnlineAccountProblem {
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_REGISTERED,

    /** §18.5: cadastro feito, mas a carteira não migrou agora (economia fora do ar) — a tela orienta a tentar Entrar. */
    WALLET_PENDING,
    LOGIN_LOCKED,

    /** §18.7: o operador do console bloqueou esta conta — diferente de [INVALID_CREDENTIALS] (senha errada). */
    ACCOUNT_BLOCKED,
    INVALID_REQUEST,
    NETWORK,
    MAINTENANCE,
    UNEXPECTED,
}

/** Traduz a falha de entrar ou criar a conta por e-mail e senha (3.22) num problema que a tela explica. */
fun OnlineException.toAccountProblem(signup: Boolean): OnlineAccountProblem = when {
    code == ErrorCode.NAO_AUTENTICADO -> OnlineAccountProblem.INVALID_CREDENTIALS
    code == ErrorCode.EMAIL_JA_CADASTRADO -> OnlineAccountProblem.EMAIL_ALREADY_REGISTERED
    code == ErrorCode.LOGIN_BLOQUEADO -> OnlineAccountProblem.LOGIN_LOCKED
    code == ErrorCode.CONTA_BLOQUEADA -> OnlineAccountProblem.ACCOUNT_BLOCKED
    code == ErrorCode.LOGIN_INDISPONIVEL && signup -> OnlineAccountProblem.WALLET_PENDING
    code == ErrorCode.REQUISICAO_INVALIDA -> OnlineAccountProblem.INVALID_REQUEST
    kind == OnlineErrorKind.NETWORK -> OnlineAccountProblem.NETWORK
    kind == OnlineErrorKind.MAINTENANCE || code == ErrorCode.LOGIN_INDISPONIVEL -> OnlineAccountProblem.MAINTENANCE
    else -> OnlineAccountProblem.UNEXPECTED
}

/**
 * Traduz a falha da economia (§18.5/§17.10, Loja online 3.20a e o ganho diário de 3.21) para o problema que a tela
 * mostra. Não reaproveita [toProblem]: o economy-service usa [EconomyErrorCode], não [ErrorCode] do lobby, e alguns
 * nomes se repetem com outro sentido — `SEM_PERMISSAO` aqui é "esta carteira não é sua" ([WALLET_NOT_YOURS]), nunca
 * "você não é mais o anfitrião" ([NOT_HOST], que é o `SEM_PERMISSAO` do lobby).
 */
fun OnlineException.toEconomyProblem(): OnlineProblem = when (kind) {
    OnlineErrorKind.NETWORK -> OnlineProblem.NETWORK
    OnlineErrorKind.UPDATE_APP -> OnlineProblem.UPDATE_APP
    OnlineErrorKind.MAINTENANCE -> OnlineProblem.MAINTENANCE
    OnlineErrorKind.UNAUTHENTICATED -> OnlineProblem.UNAUTHENTICATED
    OnlineErrorKind.UNEXPECTED -> OnlineProblem.UNEXPECTED
    OnlineErrorKind.API -> when (economyCode) {
        EconomyErrorCode.SALDO_INSUFICIENTE -> OnlineProblem.INSUFFICIENT_BALANCE
        EconomyErrorCode.OFERTA_INEXISTENTE, EconomyErrorCode.REQUISICAO_INVALIDA -> OnlineProblem.SHOP_INVALID_REQUEST
        EconomyErrorCode.APP_NAO_RECONHECIDO -> OnlineProblem.APP_NOT_RECOGNIZED
        EconomyErrorCode.NAO_AUTENTICADO -> OnlineProblem.UNAUTHENTICATED
        EconomyErrorCode.SEM_PERMISSAO -> OnlineProblem.WALLET_NOT_YOURS
        EconomyErrorCode.INDISPONIVEL -> OnlineProblem.MAINTENANCE
        // JA_RESGATADO é tratado antes de chegar aqui (concilia relendo a carteira, nunca mostra erro); as demais
        // não deviam acontecer nesta tela (conta sempre existe antes de comprar ou liquidar, §18.5).
        // CONTA_NAO_ELEGIVEL (§18.6) também não devia acontecer aqui: a tela só mostra o botão de migrar para conta
        // (nunca para convidado), então o servidor recusando por esse motivo é só defesa, nunca um caminho normal.
        EconomyErrorCode.JA_RESGATADO, EconomyErrorCode.CONTA_INEXISTENTE, EconomyErrorCode.APOSTA_INEXISTENTE,
        EconomyErrorCode.CONTA_NAO_ELEGIVEL, null,
        -> OnlineProblem.UNEXPECTED
    }
}

/**
 * Traduz a falha de "Trazer minha carteira" (§18.6, modal "Trazer carteira local") para o problema que o modal
 * mostra. Separado de [toEconomyProblem] porque ele reaproveita códigos com texto de outra tela: `REQUISICAO_INVALIDA`
 * ali vira "Não foi possível completar esta compra" (loja), que não faz sentido aqui.
 *
 * O app nunca filtra o inventário local antes de montar o pedido (de propósito, ver `onMigrateTap`): quem recusa um
 * booster que o servidor ainda não conhece (descompasso de versão) é o `economy-service`, rejeitando a requisição
 * INTEIRA com `REQUISICAO_INVALIDA` (`EconomyStore.migrateLocalWallet`, commit d67ce11) — nada migra, o jogador só
 * consegue de novo depois de o servidor (ou o app) atualizar. `CONTA_NAO_ELEGIVEL` também não deveria ocorrer aqui
 * (o botão só aparece para conta). Os dois caem no mesmo [OnlineProblem.UNEXPECTED] genérico, honesto sobre não saber
 * o motivo — "Tentar de novo" continua certo, só que só funciona depois daquela atualização.
 */
fun OnlineException.toMigrateProblem(): OnlineProblem = when (kind) {
    OnlineErrorKind.NETWORK -> OnlineProblem.NETWORK
    OnlineErrorKind.UPDATE_APP -> OnlineProblem.UPDATE_APP
    OnlineErrorKind.MAINTENANCE -> OnlineProblem.MAINTENANCE
    OnlineErrorKind.UNAUTHENTICATED -> OnlineProblem.UNAUTHENTICATED
    OnlineErrorKind.UNEXPECTED -> OnlineProblem.UNEXPECTED
    OnlineErrorKind.API -> when (economyCode) {
        EconomyErrorCode.INDISPONIVEL -> OnlineProblem.MAINTENANCE
        else -> OnlineProblem.UNEXPECTED
    }
}
