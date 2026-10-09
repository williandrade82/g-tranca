package com.gtranca.game

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * `Random(seed)` que conta quantos números já sorteou, para o jogo salvo guardar a posição do sorteio de cada bot.
 * Os valores são idênticos aos de `Random(seed)` (tudo passa pelo mesmo gerador, um `nextInt` por chamada), então
 * o `:sim` e o app seguem iguais. Ao retomar, [skip] sorteios são descartados e o bot continua de onde parou.
 */
class CountingRandom(seed: Long, skip: Long = 0) : Random() {
    private val source = Random(seed)
    private val count = AtomicLong(0)

    init {
        repeat(skip.coerceAtLeast(0).toInt()) { next() }
    }

    /** Sorteios feitos desde o início do jogo (inclui os descartados ao retomar). */
    val calls: Long get() = count.get()

    private fun next(): Int {
        count.incrementAndGet()
        return source.nextInt()
    }

    override fun nextBits(bitCount: Int): Int =
        if (bitCount == 0) 0 else next().ushr(32 - bitCount) and (-bitCount).shr(31)

    override fun nextInt(): Int = next()
}
