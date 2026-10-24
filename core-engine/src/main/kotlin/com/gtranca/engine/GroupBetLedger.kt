package com.gtranca.engine

import kotlinx.serialization.Serializable

/**
 * Aposta de um assento na mesa multijogador (§16.13). [holder] é o id público do humano dono da aposta (`null` = robô de
 * origem, aposta virtual). [forfeited] = o humano desistiu ou foi substituído (§16.8, §16.9): a aposta fica **retida no
 * assento** e a parte dele no prêmio se perde (salvo se ele voltar pelo token, [GroupBetLedger.restore]).
 */
@Serializable
data class SeatStake(val holder: Int? = null, val forfeited: Boolean = false)

/**
 * Apostas em grupo (§14.6, §16.9, §16.13), isoladas para endurecer depois. Puro: o saldo de cada celular é local; aqui só
 * se decide quem apostou, quem herda e quem recebe. Todos os assentos apostam [stake] (os robôs, virtualmente).
 */
@Serializable
data class GroupBetLedger(val stake: Int, val seats: List<SeatStake>) {

    /** §16.9, §16.8 opção 2: o humano do [seat] desistiu ou foi substituído; a aposta fica retida no assento. */
    fun forfeit(seat: Int): GroupBetLedger {
        val current = seats[seat]
        if (current.holder == null || current.forfeited) return this
        return copy(seats = seats.toMutableList().also { it[seat] = current.copy(forfeited = true) })
    }

    /**
     * §16.7 o humano [playerId] passa a ocupar o [seat] no meio do jogo. Devolve o novo razão e quanto ele debita: no lugar
     * de **quem saiu** herda a aposta retida (0); no lugar de um **robô de origem** debita a aposta da mesa.
     */
    fun occupy(seat: Int, playerId: Int): Pair<GroupBetLedger, Int> {
        val current = seats[seat]
        require(current.holder == null || current.forfeited) { "Assento com dono" }
        val debit = if (current.holder == null) stake else 0
        return copy(seats = seats.toMutableList().also { it[seat] = SeatStake(holder = playerId) }) to debit
    }

    /** §16.9 quem desistiu voltou pelo token (o anfitrião aguardou): recupera a aposta. */
    fun restore(seat: Int, playerId: Int): GroupBetLedger {
        val current = seats[seat]
        if (current.holder != playerId || !current.forfeited) return this
        return copy(seats = seats.toMutableList().also { it[seat] = current.copy(forfeited = false) })
    }

    /**
     * §16.13 quanto cada humano recebe ao fim do jogo (por id; ausente = 0): o prêmio (soma das apostas de todos os
     * assentos) é dividido **igualmente entre os assentos vencedores**; cada humano vencedor recebe a parte do seu assento.
     * A parte de assento com robô (de origem ou substituto) ou de quem desistiu sem voltar **se perde** ([lost]).
     */
    fun payouts(winners: List<Boolean>): Map<Int, Int> {
        val shares = shares(winners)
        val result = mutableMapOf<Int, Int>()
        seats.forEachIndexed { i, s ->
            val holder = s.holder ?: return@forEachIndexed
            if (s.forfeited || shares[i] == 0) return@forEachIndexed
            result[holder] = (result[holder] ?: 0) + shares[i]
        }
        return result
    }

    /** §16.13 a parte dos assentos vencedores sem humano que receba (robô, VAGO, desistente): não vai a ninguém. */
    fun lost(winners: List<Boolean>): Int = shares(winners).sum() - payouts(winners).values.sum()

    private fun shares(winners: List<Boolean>): List<Int> {
        require(winners.size == seats.size) { "Um resultado por assento" }
        return Coins.payouts(List(seats.size) { stake }, winners)
    }

    companion object {
        /** No início do jogo: [humans] = id do humano por assento; os demais assentos são robôs de origem. */
        fun start(stake: Int, seatCount: Int, humans: Map<Int, Int>): GroupBetLedger =
            GroupBetLedger(stake, List(seatCount) { SeatStake(holder = humans[it]) })
    }
}
