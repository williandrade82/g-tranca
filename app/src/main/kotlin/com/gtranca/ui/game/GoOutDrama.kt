package com.gtranca.ui.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dramatização da batida (docs/telas.md 3.9): última jogada em câmera lenta, a carta que cresce, fogos de artifício e só
 * então o banner. Lógica pura (sem Android), testável na JVM.
 */
object GoOutDrama {
    /** O voo da última jogada dura tantas vezes o normal (fácil de ajustar) (e a pausa entre as cartas do movimento também). */
    const val SLOWDOWN: Float = 3f

    /** Tamanho máximo da carta no meio do voo, em relação ao normal. */
    const val PEAK_SCALE: Float = 1.4f

    /** Fração do voo em que a carta cresce (0 a 40%) e em que termina de ficar grande (40% a 60%); o resto é a volta. */
    const val GROW_END: Float = 0.4f
    const val HOLD_END: Float = 0.6f

    /** Duração dos fogos de artifício. */
    const val FIREWORKS_MILLIS: Long = 1_500

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Escala da carta no instante [t] (0 a 1) do voo da última jogada: cresce, fica grande e volta ao normal ao pousar. */
    fun flightScale(t: Float): Float = when {
        t <= GROW_END -> 1f + (PEAK_SCALE - 1f) * smooth(t / GROW_END)
        t <= HOLD_END -> PEAK_SCALE
        else -> 1f + (PEAK_SCALE - 1f) * (1f - smooth((t - HOLD_END) / (1f - HOLD_END)))
    }

    /** Duração do tremor da mesa quando a última carta chega (junto com os fogos). */
    const val SHAKE_MILLIS: Int = 450

    /** Tremor amortecido da mesa no instante [t] (0 a 1): deslocamentos x e y e rotação, cada um entre -1 e 1; zero no fim. */
    fun shake(t: Float): Triple<Float, Float, Float> {
        if (t <= 0f || t >= 1f) return Triple(0f, 0f, 0f)
        val decay = (1f - t) * (1f - t)
        val w = t * 2f * PI.toFloat()
        return Triple(sin(w * 5f) * decay, sin(w * 6.5f + 1f) * decay, sin(w * 4f + 2f) * decay)
    }

    /** Tempos da dramatização no overlay do fim da partida. */
    enum class OverlayPhase { FIREWORKS, BANNER }

    /** Fase em que o overlay começa: com batida e animações ligadas, os fogos vêm antes do banner. */
    fun startPhase(end: RoundEnd, animate: Boolean): OverlayPhase =
        if (animate && end != RoundEnd.NO_WINNER) OverlayPhase.FIREWORKS else OverlayPhase.BANNER

    /** Depois dos fogos vem o banner (que não tem fase seguinte: ele mesmo encerra o overlay). */
    fun next(phase: OverlayPhase): OverlayPhase = OverlayPhase.BANNER

    /** Fogos verdes se o lado do jogador venceu a partida, vermelhos se perdeu. */
    fun fireworksWin(end: RoundEnd): Boolean = end == RoundEnd.YOU || end == RoundEnd.PARTNER
}

/** Uma faísca de um estouro: direção, alcance e tom (0 a 2, escolhe a cor na paleta verde ou vermelha). */
data class Spark(val angle: Float, val speed: Float, val shade: Int)

/** Um estouro de fogos: centro em frações da tela, instante de início em fração da duração total. */
data class FireworkBurst(val x: Float, val y: Float, val start: Float, val sparks: List<Spark>)

/** Uma faísca desenhada: posição em frações da tela, opacidade e tamanho relativo. */
data class SparkState(val x: Float, val y: Float, val alpha: Float, val size: Float)

/** Matemática dos fogos de artifício (pura): estouros sorteados com semente e a posição de cada faísca no tempo. */
object Fireworks {
    /** Duração total dos fogos (1,5 s). */
    const val TOTAL_MILLIS: Long = GoOutDrama.FIREWORKS_MILLIS

    const val BURSTS: Int = 5
    const val SPARKS_PER_BURST: Int = 26

    /** Duração de cada estouro, em fração da duração total dos fogos. */
    const val BURST_LIFE: Float = 0.5f

    private const val REACH = 0.22f
    private const val GRAVITY = 0.12f

    fun create(seed: Long): List<FireworkBurst> {
        val random = Random(seed)
        return List(BURSTS) { i ->
            FireworkBurst(
                x = 0.15f + random.nextFloat() * 0.7f,
                y = 0.15f + random.nextFloat() * 0.4f,
                // Os estouros começam escalonados e o último ainda tem tempo de acabar dentro da duração.
                start = (1f - BURST_LIFE) * i / (BURSTS - 1),
                sparks = List(SPARKS_PER_BURST) { k ->
                    Spark(
                        angle = (k + random.nextFloat() * 0.6f) / SPARKS_PER_BURST * 2f * PI.toFloat(),
                        speed = 0.6f + random.nextFloat() * 0.4f,
                        shade = random.nextInt(3),
                    )
                },
            )
        }
    }

    /** Estado da [spark] do [burst] no instante [t] (0 a 1 dos fogos); `null` antes de o estouro começar ou depois de apagar. */
    fun state(burst: FireworkBurst, spark: Spark, t: Float): SparkState? {
        val life = (t - burst.start) / BURST_LIFE
        if (life < 0f || life >= 1f) return null
        // Sai rápido e desacelera; depois cai um pouco.
        val out = 1f - (1f - life) * (1f - life)
        return SparkState(
            x = burst.x + cos(spark.angle) * spark.speed * REACH * out,
            y = burst.y + sin(spark.angle) * spark.speed * REACH * out + GRAVITY * life * life,
            alpha = (1f - life).coerceIn(0f, 1f),
            size = 1f - 0.5f * life,
        )
    }
}
