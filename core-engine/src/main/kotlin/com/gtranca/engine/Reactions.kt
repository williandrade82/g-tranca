package com.gtranca.engine

/** §17.8 as reações do booster b3. [code] é o identificador estável (rede, imagens). */
enum class Reaction(val code: String) {
    APPLAUSE("clap"),
    LOVE("love"),
    THINKING("tired"),
    ANGRY("angry"),
    CHICKEN("chicken"),
    ;

    companion object {
        fun ofCode(code: String): Reaction? = entries.firstOrNull { it.code == code }
    }
}

/** §17.8 quantas reações o jogador ainda pode enviar na partida. */
object Reactions {
    /** Cada partida com o b3 dá 3 reações, de qualquer tipo. */
    const val USES_PER_ROUND = 3

    /** Quantas restam depois de [used] envios na partida. */
    fun remaining(used: Int): Int = (USES_PER_ROUND - used).coerceIn(0, USES_PER_ROUND)

    /** Pode enviar: o b3 está na partida e ainda há usos. */
    fun canUse(boosters: List<String>, used: Int): Boolean =
        BoosterCatalog.REACTIONS in boosters && remaining(used) > 0
}
