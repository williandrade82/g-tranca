package com.gtranca.ui.game

import kotlin.math.abs

/**
 * §17.11.1 escala da barra de progressão do b4 (lógica pura, testável na JVM). A escala é a mesma para os dois lados,
 * começa em 500 a cada partida e nunca diminui: chegar a 500 leva o teto a 1000, chegar a 1000 leva a 1200 e passar de
 * 1200 faz o teto acompanhar o maior total já visto. Cada marco alcançado ganha uma estrela.
 *
 * @property level marcos alcançados: 0 (nenhum), 1 (500), 2 (1000), 3 (passou de 1200).
 * @property peak maior total positivo visto na partida (de qualquer lado).
 */
data class ScoreBarScale(val level: Int = 0, val peak: Int = 0) {

    /** Teto de cada metade da barra. */
    val max: Int get() = when (level) {
        0 -> MARKS[0]
        1 -> MARKS[1]
        2 -> MARKS[2]
        else -> maxOf(peak, MARKS[2])
    }

    /** Pontos que já ganharam estrela (500, 1000, 1200), na ordem. */
    val stars: List<Int> get() = MARKS.take(level)

    /** A escala depois de ver os [totals] dos lados: só sobe. */
    fun advance(totals: List<Int>): ScoreBarScale {
        val top = maxOf(peak, totals.maxOrNull() ?: 0)
        return ScoreBarScale(maxOf(level, levelFor(top)), top)
    }

    /** Fração (0 a 1) da metade que o [value] ocupa (negativo ocupa pelo valor absoluto, em vermelho). */
    fun fraction(value: Int): Float = (abs(value).toFloat() / max).coerceIn(0f, 1f)

    /** Posição (0 a 1) da estrela do [mark] na metade. */
    fun starFraction(mark: Int): Float = (mark.toFloat() / max).coerceIn(0f, 1f)

    companion object {
        /** Marcos da escala: 500, 1000 e 1200. */
        val MARKS: List<Int> = listOf(500, 1000, 1200)

        /** Nível de um total: 500 e 1000 contam ao chegar; 1200 só ao passar. */
        fun levelFor(points: Int): Int = when {
            points > MARKS[2] -> 3
            points >= MARKS[1] -> 2
            points >= MARKS[0] -> 1
            else -> 0
        }
    }
}
