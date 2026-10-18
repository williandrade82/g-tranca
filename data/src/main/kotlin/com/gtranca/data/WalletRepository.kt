package com.gtranca.data

import com.gtranca.engine.Wallet
import kotlinx.coroutines.flow.Flow

/** Carteira local do jogador (§14.6): saldo, ganho diário e resultado total em moedas. */
interface WalletRepository {
    /** Saldo e último dia do ganho diário; sem nada gravado, o saldo inicial. */
    val wallet: Flow<Wallet>

    /** Soma do resultado (recebido menos apostado) de todos os jogos apostados. */
    val lifetimeNet: Flow<Int>

    /** Debita a aposta [stake] se o saldo cobre; devolve se debitou. */
    suspend fun debit(stake: Int): Boolean

    /** Resgata o ganho diário de [today] (dias desde 1970) se ainda não foi resgatado; devolve se resgatou. */
    suspend fun claimDaily(today: Long): Boolean
}
