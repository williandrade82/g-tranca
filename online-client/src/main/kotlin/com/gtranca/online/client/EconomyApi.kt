package com.gtranca.online.client

import com.gtranca.online.contracts.EconomyStateDto

/**
 * Operações da economia online (carteira, loja e ganho diário, §18.3/§18.5/§17.10) atrás do mesmo servidor do
 * [LobbyApi] (`endpoint`, rotas `/economy/...`, sem o prefixo `/api/lobby`). Toda chamada que chega a sair para a
 * rede lança [OnlineException] ([OnlineException.kind]/[OnlineException.economyCode] distinguem recusa de negócio,
 * ex.: saldo insuficiente, de rede/servidor fora). Um [accountId] ou uma chave de compra fora do formato esperado
 * lançam `IllegalArgumentException` **antes** de qualquer pedido (erro de programação de quem chama, não recusa do
 * servidor).
 *
 * [accountId] é sempre o id de quem é dono do [token]: o [com.gtranca.online.contracts.GuestIdentity.id] do convidado
 * ou o [com.gtranca.online.contracts.AccountLogin.id] depois do login Google (o servidor confere e responde
 * `SEM_PERMISSAO` se não bater). Existe como interface para as telas e os ViewModels serem testados com um servidor
 * falso em memória, como o [LobbyApi].
 */
interface EconomyApi {
    /** Saldo e inventário de boosters da própria conta. */
    suspend fun economyState(token: String, accountId: String): EconomyStateDto

    /**
     * Ganho diário (§14.6): [today] é o dia de hoje no fuso do jogador, em dias desde a época (quem chama calcula,
     * ex.: `LocalDate.now(zone).toEpochDay()`, e passa o valor pronto — isso facilita o teste deste cliente, que não
     * lê o relógio). Repetir no mesmo dia devolve `JA_RESGATADO` ([OnlineException.economyCode]).
     */
    suspend fun claimDaily(token: String, accountId: String, today: Long): EconomyStateDto

    /**
     * Compra uma oferta da loja (§17.10) pelo índice [offerIndex]. [key] é escolhida por quem chama (uma por
     * intenção de compra) e deve ser repetida ao tentar de novo depois de um erro de rede/servidor — a mesma chave
     * nunca cobra duas vezes (livro-razão idempotente); uma chave nova cobraria de novo.
     */
    suspend fun buyOffer(token: String, accountId: String, offerIndex: Int, key: String): EconomyStateDto
}
