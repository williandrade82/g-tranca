package com.gtranca.ui.sound

import com.gtranca.engine.model.Card
import com.gtranca.ui.game.AnimAnchor
import com.gtranca.ui.game.CardFlight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Efeitos sonoros (apresentação): síntese por código e qual som acompanha cada animação. */
class SoundTest {

    private fun peak(samples: ShortArray) = samples.maxOf { abs(it.toInt()) }

    @Test
    fun `todo efeito tem som audivel, sem estourar e sem estalo no fim`() {
        SoundEffect.entries.forEach { effect ->
            val samples = SoundSynth.render(effect)
            // Audível: pico razoável; sem estourar: abaixo do limite de 90% do máximo.
            (peak(samples) > 3_000) shouldBe true
            (peak(samples) <= (Short.MAX_VALUE * 0.9).toInt()) shouldBe true
            // O fim some (sem estalo de corte seco).
            (abs(samples.last().toInt()) < 200) shouldBe true
        }
    }

    @Test
    fun `duracoes combinam com o tipo de som`() {
        // Efeitos de carta são curtos; canastra e 3 vermelho, médios; vitória e derrota, longos.
        listOf(SoundEffect.FLIP, SoundEffect.TAP, SoundEffect.SLIDE).forEach { (SoundSynth.durationMillis(it) in 50..250) shouldBe true }
        listOf(SoundEffect.CHIME, SoundEffect.CANASTA).forEach { (SoundSynth.durationMillis(it) in 300..800) shouldBe true }
        listOf(SoundEffect.WIN, SoundEffect.LOSE).forEach { (SoundSynth.durationMillis(it) in 1000..2000) shouldBe true }
    }

    @Test
    fun `a sintese e deterministica`() {
        SoundEffect.entries.forEach { SoundSynth.render(it).toList() shouldBe SoundSynth.render(it).toList() }
    }

    @Test
    fun `efeitos diferentes soam diferente`() {
        val renders = SoundEffect.entries.map { SoundSynth.render(it).toList() }
        renders.toSet().size shouldBe renders.size
    }

    @Test
    fun `volumes relativos ficam entre zero e um`() {
        SoundEffect.entries.forEach { (it.gain > 0f && it.gain <= 1f) shouldBe true }
    }

    private fun flight(from: AnimAnchor, to: AnimAnchor) = CardFlight(1, from, to, Card.parse("7H"))

    @Test
    fun `cada animacao de carta tem o seu som`() {
        // Compra e carta do lixo para a mão: virar carta.
        SoundMap.forFlight(flight(AnimAnchor.Stock, AnimAnchor.OwnHand)) shouldBe SoundEffect.FLIP
        SoundMap.forFlight(flight(AnimAnchor.Stock, AnimAnchor.SeatHand(1))) shouldBe SoundEffect.FLIP
        SoundMap.forFlight(flight(AnimAnchor.Morto(0), AnimAnchor.OwnHand)) shouldBe SoundEffect.FLIP
        SoundMap.forFlight(flight(AnimAnchor.DiscardPile, AnimAnchor.OwnHand)) shouldBe SoundEffect.FLIP
        // Descarte.
        SoundMap.forFlight(flight(AnimAnchor.OwnHand, AnimAnchor.DiscardPile)) shouldBe SoundEffect.TAP
        SoundMap.forFlight(flight(AnimAnchor.SeatHand(2), AnimAnchor.DiscardPile)) shouldBe SoundEffect.TAP
        // Baixar e acrescentar a um jogo, e o topo do lixo indo ao jogo.
        SoundMap.forFlight(flight(AnimAnchor.OwnHand, AnimAnchor.Meld(0, 3))) shouldBe SoundEffect.SLIDE
        SoundMap.forFlight(flight(AnimAnchor.DiscardPile, AnimAnchor.SideArea(1))) shouldBe SoundEffect.SLIDE
        // 3 vermelho para a mesa.
        SoundMap.forFlight(flight(AnimAnchor.SeatHand(1), AnimAnchor.RedThrees(1))) shouldBe SoundEffect.CHIME
        SoundMap.forFlight(flight(AnimAnchor.Morto(1), AnimAnchor.RedThrees(0))) shouldBe SoundEffect.CHIME
    }

    @Test
    fun `voo sem origem nem destino marcantes fica em silencio`() {
        SoundMap.forFlight(flight(AnimAnchor.SeatHand(1), AnimAnchor.OwnHand)) shouldBe null
    }

    @Test
    fun `sem som nao toca nada`() {
        NoSound.play(SoundEffect.WIN)
        NoSound.enabled shouldBe false
    }
}
