package com.gtranca.ui.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Síntese dos efeitos sonoros: amostras PCM de 16 bits, mono, geradas por código (sem arquivos de áudio nem licenças).
 * Pura e determinística: o ruído usa semente fixa por efeito, então a mesma chamada gera as mesmas amostras.
 */
object SoundSynth {
    const val RATE = 44_100

    /** Fração do volume máximo que nenhum efeito ultrapassa (evita estalos por corte). */
    private const val HEADROOM = 0.9

    fun render(effect: SoundEffect): ShortArray = when (effect) {
        SoundEffect.FLIP -> flip()
        SoundEffect.TAP -> tap()
        SoundEffect.SLIDE -> slide()
        SoundEffect.CHIME -> chime()
        SoundEffect.CANASTA -> canasta()
        SoundEffect.WIN -> win()
        SoundEffect.LOSE -> lose()
    }

    /** Duração do efeito, em milissegundos. */
    fun durationMillis(effect: SoundEffect): Int = render(effect).size * 1000 / RATE

    // ---------- blocos ----------

    private const val TWO_PI = 2 * PI

    private fun tone(frequency: Double, t: Double) = sin(TWO_PI * frequency * t)

    /** Timbre de sino/teclado: fundamental e um harmônico mais fraco. */
    private fun bell(frequency: Double, t: Double) = tone(frequency, t) + 0.35 * tone(frequency * 2, t) + 0.12 * tone(frequency * 3, t)

    /** Entrada rápida e queda exponencial. */
    private fun envelope(t: Double, attack: Double, decay: Double) = min(1.0, t / attack) * exp(-t * decay)

    /** Uma nota que começa em [start] segundos; antes disso, silêncio. */
    private fun note(frequency: Double, start: Double, t: Double, decay: Double, amplitude: Double): Double =
        if (t < start) 0.0 else amplitude * envelope(t - start, 0.004, decay) * bell(frequency, t - start)

    /** Ruído com realce dos agudos (diferença entre amostras vizinhas), para estalidos de papel. */
    private fun crisp(seed: Long, samples: Int): DoubleArray {
        val random = Random(seed)
        val raw = DoubleArray(samples + 1) { random.nextDouble() * 2 - 1 }
        return DoubleArray(samples) { (raw[it + 1] - raw[it]) / 2 }
    }

    /** Gera [millis] ms de som; o fim some em 6 ms para não estalar, e o pico fica abaixo do limite. */
    private fun buffer(millis: Int, generate: (index: Int, t: Double) -> Double): ShortArray {
        val n = RATE * millis / 1000
        val fadeSamples = RATE * 0.006
        return ShortArray(n) { i ->
            val fade = min(1.0, (n - 1 - i) / fadeSamples)
            val v = (generate(i, i.toDouble() / RATE) * fade).coerceIn(-1.0, 1.0)
            (v * Short.MAX_VALUE * HEADROOM).toInt().toShort()
        }
    }

    // ---------- efeitos ----------

    private fun flip(): ShortArray {
        val noise = crisp(1, RATE * 80 / 1000)
        return buffer(80) { i, t -> noise[i] * 1.9 * envelope(t, 0.002, 55.0) + 0.08 * tone(2200.0, t) * envelope(t, 0.001, 90.0) }
    }

    private fun tap(): ShortArray {
        val noise = crisp(2, RATE * 120 / 1000)
        return buffer(120) { i, t ->
            0.75 * tone(185.0, t) * envelope(t, 0.002, 36.0) + 0.5 * noise[i] * envelope(t, 0.001, 160.0) * 1.6
        }
    }

    private fun slide(): ShortArray {
        val noise = crisp(3, RATE * 170 / 1000)
        return buffer(170) { i, t ->
            val shape = sin(PI * t / 0.17)
            // Varredura de 600 a 950 Hz, bem suave, sob o frufru do papel.
            val sweep = sin(TWO_PI * (600.0 * t + 0.5 * (350.0 / 0.17) * t * t))
            0.55 * shape * noise[i] * 1.5 + 0.12 * shape * sweep
        }
    }

    private fun chime(): ShortArray = buffer(480) { _, t ->
        0.5 * envelope(t, 0.003, 7.0) * (tone(880.0, t) + 0.5 * tone(1320.0, t) + 0.25 * tone(1760.0, t)) / 1.75 * 1.6
    }

    private fun canasta(): ShortArray = buffer(620) { _, t ->
        listOf(523.25, 659.25, 783.99, 1046.5).withIndex().sumOf { (index, f) -> note(f, index * 0.1, t, 6.5, 0.28) }
    }

    private fun win(): ShortArray = buffer(1500) { _, t ->
        // Fanfarra: quatro notas subindo e um acorde longo.
        val run = listOf(523.25, 659.25, 783.99, 1046.5).withIndex().sumOf { (index, f) -> note(f, index * 0.13, t, 7.0, 0.3) }
        val chord = listOf(1046.5, 1318.5, 1568.0).sumOf { f -> note(f, 0.56, t, 2.4, 0.2) }
        run + chord
    }

    private fun lose(): ShortArray = buffer(1400) { _, t ->
        // Três notas descendo, suaves e longas (sol, mi bemol, dó grave).
        note(392.0, 0.0, t, 3.5, 0.4) + note(311.1, 0.33, t, 3.2, 0.4) + note(261.6, 0.68, t, 2.2, 0.45)
    }
}
