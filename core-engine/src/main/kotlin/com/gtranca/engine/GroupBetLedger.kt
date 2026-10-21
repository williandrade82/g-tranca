package com.gtranca.engine

import kotlinx.serialization.Serializable

/**
 * Aposta de um assento na mesa multijogador (§16.13). [holder] é o id público do humano dono da aposta (`null` = robô de
 * origem, aposta virtual). [forfeited] = o humano desistiu ou foi substituído (§16.8, §16.9): a aposta fica **retida no
 * assento** e o que couber a ele vai ao parceiro humano ou ao anfitrião.
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

    /**
     * §14.6, §16.9, §16.13 quanto cada humano recebe ao fim do jogo (por id; ausente = 0). [winners] diz, por assento, se
     * está no lado vencedor; [hostId] é o anfitrião atual (dono da mesa). O assento de robô de origem não paga ninguém; o
     * de quem desistiu paga ao outro humano da dupla que não desistiu ou, na falta, ao anfitrião.
     */
    fun payouts(winners: List<Boolean>, hostId: Int): Map<Int, Int> {
        require(winners.size == seats.size) { "Um resultado por assento" }
        val base = Coins.payouts(List(seats.size) { stake }, winners)
        val result = mutableMapOf<Int, Int>()
        // §16.9 mesa só com o anfitrião: se ele perde, perde só a aposta dele (nada dos assentos que desistiram).
        val onlyHost = seats.none { it.holder != null && !it.forfeited && it.holder != hostId }
        val hostLost = seats.indices.none { seats[it].holder == hostId && !seats[it].forfeited && winners[it] }
        if (onlyHost && hostLost) return emptyMap()
        seats.forEachIndexed { i, s ->
            val holder = s.holder ?: return@forEachIndexed
            val amount = base[i]
            if (amount == 0) return@forEachIndexed
            val to = if (!s.forfeited) holder else {
                if (amount == 0) return@forEachIndexed
                val partner = seats.indices.firstOrNull { j ->
                    j != i && j % 2 == i % 2 && seats[j].holder != null && !seats[j].forfeited
                }
                partner?.let { seats[it].holder } ?: hostId
            }
            result[to] = (result[to] ?: 0) + amount
        }
        return result
    }

    companion object {
        /** No início do jogo: [humans] = id do humano por assento; os demais assentos são robôs de origem. */
        fun start(stake: Int, seatCount: Int, humans: Map<Int, Int>): GroupBetLedger =
            GroupBetLedger(stake, List(seatCount) { SeatStake(holder = humans[it]) })
    }
}
