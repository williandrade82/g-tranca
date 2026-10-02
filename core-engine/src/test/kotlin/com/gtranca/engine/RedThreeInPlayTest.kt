package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RedThreeInPlayTest {

    @Test
    fun `3 vermelho comprado vai para a mesa e e reposto do monte`() {
        // §6.5 baixado automaticamente ao entrar na mão (compra), com reposição do monte
        val s = round {
            hand(0, "KS QD")
            redThrees(0, "3D'")
            stock("3H KH 4S")
        }.act(0, Action.DrawFromStock)
        s.redThreesOf(Side(0)) shouldContainExactly cards("3D' 3H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD KH")
        s.stock shouldContainExactly cards("4S")
        s.phase shouldBe Phase.PLAYING
    }

    @Test
    fun `reposicao em cadeia durante o jogo`() {
        // §6.5 se a reposição também for 3 vermelho, o processo se repete
        val s = round {
            hand(0, "KS QD")
            stock("3H 3D 3H' KH 4S")
        }.act(0, Action.DrawFromStock)
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H 3D 3H'")
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD KH")
        s.stock shouldContainExactly cards("4S")
    }

    @Test
    fun `em duplas o 3 vermelho vai para o lado de quem comprou`() {
        // §6.5 + §1.1 assento 3 pertence ao lado 1
        val s = round(GameMode.DUPLAS) {
            hand(3, "KS QD")
            stock("3H KH")
            current = 3
        }.act(3, Action.DrawFromStock)
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H")
        s.redThreesOf(Side(0)).shouldBeEmpty()
    }

    @Test
    fun `reposicao com monte vazio usa o morto que vira monte`() {
        // §6.5 + §10.1 monte vazio na reposição: morto disponível vira monte
        val s = round {
            hand(0, "KS QD")
            stock("3H")
        }.act(0, Action.DrawFromStock)
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H")
        s.mortoStatus[0] shouldBe MortoStatus.BecameStock
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD 4C'")
        s.stock shouldContainExactly cards("5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' AC'")
    }

    @Test
    fun `sem monte nem morto o 3 vermelho e baixado sem reposicao`() {
        // §6.5 / §10.2 reposição de 3 vermelho sem monte nem morto: fica sem reposição
        val s = round {
            hand(0, "KS QD")
            stock("3H")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.act(0, Action.DrawFromStock)
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD")
        s.stock.shouldBeEmpty()
        s.phase shouldBe Phase.PLAYING
    }
}
