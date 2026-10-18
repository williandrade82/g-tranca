package com.gtranca.ui.persona

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §14.5 cada pose tem uma animação curta e cíclica (2 a 4 segundos), discreta e com efeito próprio. */
class PoseAnimationTest {

    @Test
    fun `§14_5 toda pose tem ciclo de 2 a 4 segundos`() {
        Pose.entries.forEach {
            (it.animation.cycleMillis in PoseAnimation.MIN_CYCLE_MILLIS..PoseAnimation.MAX_CYCLE_MILLIS) shouldBe true
        }
    }

    @Test
    fun `§14_5 cada pose tem um efeito diferente`() {
        Pose.entries.map { it.animation.effect }.shouldBeUnique()
        Pose.VENCEDOR.animation.effect shouldBe PoseEffect.CONFETTI
        Pose.PERDEDOR.animation.effect shouldBe PoseEffect.DROPS
        Pose.PENSADOR.animation.effect shouldBe PoseEffect.THOUGHTS
        Pose.APRESENTACAO.animation.effect shouldBe PoseEffect.SPARKLE
    }

    @Test
    fun `§14_5 vencedor pula e o mais rapido, perdedor e o mais lento`() {
        Pose.VENCEDOR.animation.jump shouldBe true
        Pose.entries.filter { it != Pose.VENCEDOR }.all { !it.animation.jump } shouldBe true
        Pose.entries.minBy { it.animation.cycleMillis } shouldBe Pose.VENCEDOR
        Pose.entries.maxBy { it.animation.cycleMillis } shouldBe Pose.PERDEDOR
    }

    @Test
    fun `§14_5 ciclo fora de 2 a 4 segundos ou movimento exagerado e recusado`() {
        shouldThrow<IllegalArgumentException> { PoseAnimation(1_000, 0.01f, 0.01f, 1f, false, PoseEffect.SPARKLE) }
        shouldThrow<IllegalArgumentException> { PoseAnimation(5_000, 0.01f, 0.01f, 1f, false, PoseEffect.SPARKLE) }
        shouldThrow<IllegalArgumentException> { PoseAnimation(3_000, 0.5f, 0.01f, 1f, false, PoseEffect.SPARKLE) }
    }

    @Test
    fun `§14_5 poses grandes sao 3 para 2 e o pensador e quadrado`() {
        Pose.entries.filter { it != Pose.PENSADOR }.forEach { it.aspectRatio shouldBe 1.5f }
        Pose.PENSADOR.aspectRatio shouldBe 1f
    }
}
