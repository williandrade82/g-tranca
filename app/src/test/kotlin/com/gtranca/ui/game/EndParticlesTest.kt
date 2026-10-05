package com.gtranca.ui.game

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Matemática das partículas das animações de vitória e derrota (apresentação; nenhuma regra). */
class EndParticlesTest {

    @Test
    fun `mesma semente gera as mesmas particulas`() {
        EndParticles.create(40, 7) shouldBe EndParticles.create(40, 7)
        (EndParticles.create(40, 7) == EndParticles.create(40, 8)) shouldBe false
        EndParticles.create(40, 7).size shouldBe 40
    }

    @Test
    fun `queda continua fica sempre perto da tela e volta ao topo`() {
        val particles = EndParticles.create(80, 3)
        for (step in 0..200) {
            val t = step / 200f
            particles.forEach { p ->
                val y = EndParticles.y(p, t, looping = true)
                (y >= -0.1f - 1e-4f && y <= 1.1f + 1e-4f) shouldBe true
            }
        }
        // Ao fim de um ciclo (t = 1 e t = 0) todas voltam a posições válidas e iguais ao ciclo seguinte.
        particles.forEach { p ->
            val a = EndParticles.y(p, 0f, looping = true)
            val b = EndParticles.y(p, 1f / p.speed, looping = true)
            (kotlin.math.abs(a - b) < 1e-3f) shouldBe true
        }
    }

    @Test
    fun `disparo unico comeca acima da tela e termina descendo`() {
        val particles = EndParticles.create(80, 5)
        particles.forEach { p ->
            (EndParticles.y(p, 0f, looping = false) < 0f) shouldBe true
            (EndParticles.y(p, 1f, looping = false) > EndParticles.y(p, 0.5f, looping = false)) shouldBe true
        }
    }

    @Test
    fun `balanco horizontal e pequeno`() {
        EndParticles.create(80, 9).forEach { p ->
            for (step in 0..20) {
                (kotlin.math.abs(EndParticles.x(p, step / 20f) - p.x) <= 0.031f) shouldBe true
            }
        }
    }
}
