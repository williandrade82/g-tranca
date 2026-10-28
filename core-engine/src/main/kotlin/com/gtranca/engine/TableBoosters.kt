package com.gtranca.engine

import kotlinx.serialization.Serializable

/**
 * §17.4 o que o **assento** tem de boosters na partida em andamento: os escolhidos (já debitados), as reações já usadas e se
 * o seletor foi confirmado. É do assento, não de quem senta nele.
 */
@Serializable
data class SeatBoosters(
    val boosters: List<String> = emptyList(),
    /** §17.8 reações já enviadas nesta partida. */
    val reactionsUsed: Int = 0,
    /** §17.3 o seletor desta partida já foi confirmado (falso: o ocupante ainda vai escolher). */
    val confirmed: Boolean = false,
) {
    val reactionsLeft: Int get() = if (BoosterCatalog.REACTIONS in boosters) Reactions.remaining(reactionsUsed) else 0
}

/**
 * §17.4 os boosters de cada assento da mesa na partida em andamento. Fica **na mesa**: é o que o jogo salvo ("Continuar") e o
 * anfitrião guardam por assento, para que quem entrar depois num assento siga com os boosters que ele realmente tem e com os
 * usos de reação que sobraram, sem descontar do inventário. Imutável.
 */
@Serializable
data class TableBoosters(val bySeat: Map<Int, SeatBoosters> = emptyMap()) {
    fun of(seat: Int): SeatBoosters = bySeat[seat] ?: SeatBoosters()

    /** §17.3 o ocupante do [seat] confirmou o seletor: valem [boosters] (já debitados) e os usos de reação recomeçam. */
    fun confirm(seat: Int, boosters: List<String>): TableBoosters =
        copy(bySeat = bySeat + (seat to SeatBoosters(boosters.distinct().take(Loadout.MAX), 0, confirmed = true)))

    /** §17.8 o [seat] enviou uma reação; `null` se não pode (sem o b3 ou sem usos). */
    fun useReaction(seat: Int): TableBoosters? {
        val current = of(seat)
        if (!Reactions.canUse(current.boosters, current.reactionsUsed)) return null
        return copy(bySeat = bySeat + (seat to current.copy(reactionsUsed = current.reactionsUsed + 1)))
    }

    /** §17.3 começa uma partida nova: os boosters da partida anterior não valem mais e todos os assentos voltam a escolher. */
    fun newRound(): TableBoosters = TableBoosters()

    companion object {
        /** Mesa em que só o [seat] tem boosters, já confirmados (jogo contra robôs). */
        fun single(seat: Int, boosters: List<String>): TableBoosters = TableBoosters().confirm(seat, boosters)
    }
}
