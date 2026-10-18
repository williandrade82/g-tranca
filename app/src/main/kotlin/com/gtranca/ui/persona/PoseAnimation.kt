package com.gtranca.ui.persona

/** Efeito desenhado sobre o retrato, por pose (§14.5). */
enum class PoseEffect { SPARKLE, CONFETTI, DROPS, THOUGHTS }

/**
 * Animação cíclica de uma pose (§14.5): dura [cycleMillis] (2 a 4 segundos) e se repete.
 *
 * @property floatFraction quanto o retrato sobe, como fração da altura.
 * @property breathe aumento da escala no ponto alto do ciclo.
 * @property swayDegrees balanço lateral máximo, em graus.
 * @property jump sobe e desce duas vezes por ciclo (vitória), em vez de flutuar uma.
 */
data class PoseAnimation(
    val cycleMillis: Int,
    val floatFraction: Float,
    val breathe: Float,
    val swayDegrees: Float,
    val jump: Boolean,
    val effect: PoseEffect,
) {
    init {
        require(cycleMillis in MIN_CYCLE_MILLIS..MAX_CYCLE_MILLIS) { "O ciclo deve durar de 2 a 4 segundos: $cycleMillis" }
        require(floatFraction in 0f..0.1f && breathe in 0f..0.05f && swayDegrees in 0f..5f) { "Movimento fora do discreto" }
    }

    companion object {
        const val MIN_CYCLE_MILLIS = 2_000
        const val MAX_CYCLE_MILLIS = 4_000
    }
}

/** Animação da pose. */
val Pose.animation: PoseAnimation
    get() = when (this) {
        Pose.APRESENTACAO -> PoseAnimation(3_200, 0.012f, 0.015f, 0.8f, jump = false, effect = PoseEffect.SPARKLE)
        Pose.VENCEDOR -> PoseAnimation(2_000, 0.05f, 0.02f, 1.5f, jump = true, effect = PoseEffect.CONFETTI)
        Pose.PERDEDOR -> PoseAnimation(4_000, 0.008f, 0.01f, 2.5f, jump = false, effect = PoseEffect.DROPS)
        Pose.PENSADOR -> PoseAnimation(3_600, 0.01f, 0.02f, 1.2f, jump = false, effect = PoseEffect.THOUGHTS)
    }

/** Proporção (largura / altura) da imagem da pose: 3:2 nas grandes e quadrada no pensador. */
val Pose.aspectRatio: Float get() = if (this == Pose.PENSADOR) 1f else 1.5f
