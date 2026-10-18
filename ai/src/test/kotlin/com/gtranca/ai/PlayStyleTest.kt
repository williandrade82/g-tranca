package com.gtranca.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §14.5 estilos de jogo: só mudam preferências (pesos), o neutro é o jogo de antes e o nível continua mandando na força. */
class PlayStyleTest {
    private val base = MediumWeights()

    @Test
    fun `§14_5 estilo neutro nao muda nenhum peso`() {
        PlayStyle.NEUTRO.apply(base) shouldBe base
    }

    @Test
    fun `§14_5 cinco estilos com ids unicos e leitura por id`() {
        PlayStyle.ALL.map { it.id }.shouldBeUnique()
        PlayStyle.ALL.size shouldBe 5
        PlayStyle.ALL.forEach { PlayStyle.fromId(it.id) shouldBe it }
        shouldThrow<IllegalArgumentException> { PlayStyle.fromId("tímido") }
    }

    @Test
    fun `§14_5 sobrio pega menos o lixo, guarda coringa e bate mais tarde`() {
        val w = PlayStyle.SOBRIO.apply(base)
        w.drawValue shouldBeGreaterThan base.drawValue
        w.wildKeep shouldBeGreaterThan base.wildKeep
        w.dirtyCleanCanasta shouldBeGreaterThan base.dirtyCleanCanasta
        w.emptyHand shouldBeLessThan base.emptyHand
        w.oppNatural shouldBeGreaterThan base.oppNatural // evita alimentar o adversário
    }

    @Test
    fun `§14_5 sagaz aproveita o lixo e bate cedo`() {
        val w = PlayStyle.SAGAZ.apply(base)
        w.drawValue shouldBeLessThan base.drawValue
        w.emptyHand shouldBeGreaterThan base.emptyHand
        w.oneCardLeft shouldBeGreaterThan base.oneCardLeft
    }

    @Test
    fun `§14_5 ousado pega o lixo, gasta coringa e sorteia descartes parecidos`() {
        val w = PlayStyle.OUSADO.apply(base)
        w.drawValue shouldBeLessThan base.drawValue
        w.wildKeep shouldBeLessThan base.wildKeep
        w.discardTieMargin shouldBeGreaterThan base.discardTieMargin
        PlayStyle.OUSADO.easyScatter shouldBeGreaterThan 0.0
    }

    @Test
    fun `§14_5 agressivo tira material do adversario, baixa e bate cedo`() {
        val w = PlayStyle.AGRESSIVO.apply(base)
        w.denyPile shouldBeGreaterThan 0.0
        w.drawValue shouldBeLessThan base.drawValue
        w.cardToTable shouldBeGreaterThan base.cardToTable
        w.emptyHand shouldBeGreaterThan base.emptyHand
    }

    @Test
    fun `§14_5 fabrica entrega o bot da dificuldade com o estilo`() {
        createBot(Difficulty.FACIL, Random(1), style = PlayStyle.SOBRIO).shouldBeInstanceOf<EasyBot>()
        createBot(Difficulty.MEDIO, Random(1), style = PlayStyle.SOBRIO).shouldBeInstanceOf<MediumBot>()
        createBot(Difficulty.DIFICIL, Random(1), style = PlayStyle.SOBRIO).shouldBeInstanceOf<com.gtranca.ai.hard.HardBot>()
    }

    @Test
    fun `§14_5 fatores invalidos sao recusados`() {
        shouldThrow<IllegalArgumentException> { PlayStyle("x", takePile = 0.0) }
        shouldThrow<IllegalArgumentException> { PlayStyle("x", surprise = -1.0) }
        shouldThrow<IllegalArgumentException> { PlayStyle("x", easyTakeRate = 1.5) }
    }
}
