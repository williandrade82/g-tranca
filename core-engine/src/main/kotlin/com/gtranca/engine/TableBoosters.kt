package com.gtranca.engine

import kotlinx.serialization.Serializable

/**
 * §17.4 o que o **assento** tem de boosters na partida em andamento: os escolhidos (já debitados), as reações já usadas e a
 * partida a que isso vale. É do assento, não de quem senta nele.
 */
@Serializable
data class SeatBoosters(
    val boosters: List<String> = emptyList(),
    /** §17.8 reações já enviadas nesta partida. */
    val reactionsUsed: Int = 0,
    /**
     * §17.3 a partida (1, 2, ...) em que o seletor foi confirmado; 0 = nunca. O assento só vale na partida dele: na partida
     * seguinte ninguém confirmou ainda, e todos escolhem de novo.
     */
    val round: Int = 0,
) {
    val reactionsLeft: Int get() = if (BoosterCatalog.REACTIONS in boosters) Reactions.remaining(reactionsUsed) else 0

    /** O seletor da partida [roundNumber] já foi confirmado neste assento. */
    fun confirmedFor(roundNumber: Int): Boolean = round != 0 && round == roundNumber
}

/**
 * §17.4 os boosters de cada assento da mesa na partida em andamento. Fica **na mesa**: é o que o jogo salvo ("Continuar") e o
 * anfitrião guardam por assento, para que quem entrar depois num assento siga com os boosters que ele realmente tem e com os
 * usos de reação que sobraram, sem descontar do inventário. Imutável.
 */
@Serializable
data class TableBoosters(val bySeat: Map<Int, SeatBoosters> = emptyMap()) {
    fun of(seat: Int): SeatBoosters = bySeat[seat] ?: SeatBoosters()

    /** O estado do [seat] **na partida [roundNumber]**: o de partida anterior não vale (vazio, ainda sem escolha). */
    fun forRound(seat: Int, roundNumber: Int): SeatBoosters = of(seat).takeIf { it.confirmedFor(roundNumber) } ?: SeatBoosters()

    /** §17.3 o ocupante do [seat] confirmou o seletor da partida [round]: valem [boosters] (já debitados) e os usos de reação recomeçam. */
    fun confirm(seat: Int, boosters: List<String>, round: Int): TableBoosters =
        copy(bySeat = bySeat + (seat to SeatBoosters(boosters.distinct().take(Loadout.MAX), 0, round)))

    /** §17.8 o [seat] enviou uma reação (na partida [round], se informada); `null` se não pode (sem o b3, sem usos ou outra partida). */
    fun useReaction(seat: Int, round: Int? = null): TableBoosters? {
        val current = of(seat)
        if (round != null && !current.confirmedFor(round)) return null
        if (!Reactions.canUse(current.boosters, current.reactionsUsed)) return null
        return copy(bySeat = bySeat + (seat to current.copy(reactionsUsed = current.reactionsUsed + 1)))
    }

    /** Põe o estado já pronto de um assento (ex.: o recebido do anfitrião). */
    fun with(seat: Int, state: SeatBoosters): TableBoosters = copy(bySeat = bySeat + (seat to state))

    /** §17.3 começa uma partida nova: os boosters da partida anterior não valem mais e todos os assentos voltam a escolher. */
    fun newRound(): TableBoosters = TableBoosters()

    companion object {
        /** Mesa em que só o [seat] tem boosters, já confirmados na partida [round] (jogo contra robôs). */
        fun single(seat: Int, boosters: List<String>, round: Int = 1): TableBoosters = TableBoosters().confirm(seat, boosters, round)
    }
}
