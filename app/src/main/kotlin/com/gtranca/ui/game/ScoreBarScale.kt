package com.gtranca.ui.game

import kotlin.math.abs

/**
 * §17.11.1 escala da barra de progressão do b4 (lógica pura, testável na JVM). A escala é a mesma para os dois lados,
 * começa em 500 a cada partida e nunca diminui: chegar a 500 leva o teto a 750, chegar a 750 leva a 1000 e passar de
 * 1000 faz o teto acompanhar o maior total já visto. Cada marco alcançado é marcado nas duas metades: estrela no lado
 * que já chegou lá, linha fina no outro.
 *
 * @property level marcos alcançados por algum lado: 0 (nenhum), 1 (500), 2 (750), 3 (passou de 1000).
 * @property peak maior total positivo visto na partida (de qualquer lado).
 * @property sideLevels marcos que cada lado (por `Side.index`) já alcançou na partida.
 */
data class ScoreBarScale(val level: Int = 0, val peak: Int = 0, val sideLevels: List<Int> = emptyList()) {

    /** Teto de cada metade da barra. */
    val max: Int get() = when (level) {
        0 -> MARKS[0]
        1 -> MARKS[1]
        2 -> MARKS[2]
        else -> maxOf(peak, MARKS[2])
    }

    /** Marcos já alcançados por algum lado (aparecem nas duas metades), na ordem. */
    val marks: List<Int> get() = MARKS.take(level)

    /** O lado [side] já alcançou o [mark] (estrela); senão, só a linha fina. */
    fun hasStar(side: Int, mark: Int): Boolean = MARKS.indexOf(mark).let { it >= 0 && it < sideLevels.getOrElse(side) { 0 } }

    /** A escala depois de ver os [totals] dos lados (por `Side.index`): só sobe. */
    fun advance(totals: List<Int>): ScoreBarScale {
        val top = maxOf(peak, totals.maxOrNull() ?: 0)
        val sides = List(maxOf(sideLevels.size, totals.size)) { i ->
            maxOf(sideLevels.getOrElse(i) { 0 }, totals.getOrNull(i)?.let(::levelFor) ?: 0)
        }
        return ScoreBarScale(maxOf(level, levelFor(top)), top, sides)
    }

    /** Fração (0 a 1) da metade que o [value] ocupa (negativo ocupa pelo valor absoluto, em vermelho). */
    fun fraction(value: Int): Float = (abs(value).toFloat() / max).coerceIn(0f, 1f)

    /** Posição (0 a 1) da marcação do [mark] na metade. */
    fun markFraction(mark: Int): Float = (mark.toFloat() / max).coerceIn(0f, 1f)

    companion object {
        /** §17.11.2 fração (0 a 1) da metade da barra do jogo: de 0 ao alvo, negativo vazio, acima do alvo cheia. */
        fun gameFraction(points: Int, target: Int): Float =
            if (target <= 0) 0f else (points.coerceAtLeast(0).toFloat() / target).coerceIn(0f, 1f)

        /** Marcos da escala: 500, 750 e 1000. */
        val MARKS: List<Int> = listOf(500, 750, 1000)

        /** Nível de um total: 500 e 750 contam ao chegar; 1000 só ao passar. */
        fun levelFor(points: Int): Int = when {
            points > MARKS[2] -> 3
            points >= MARKS[1] -> 2
            points >= MARKS[0] -> 1
            else -> 0
        }
    }
}
