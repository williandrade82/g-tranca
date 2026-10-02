package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MortoTest {

    private val morto0 = cards("4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' AC'")
    private val morto1 = cards("4D' 5D' 6D' 7D' 8D' 9D' TD' JD' QD' KD' AD'")

    @Test
    fun `morto direto ao baixar todas as cartas e a jogada continua`() {
        // §9.2 baixou todas as cartas: pega o morto imediatamente e continua jogando
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.hand(0) shouldContainExactlyInAnyOrder morto0
        s.mortoStatus[0] shouldBe MortoStatus.Taken(Side(0))
        s.mortos[0].shouldBeEmpty()
        s.mortoStatus[1] shouldBe MortoStatus.Available
        s.phase shouldBe Phase.PLAYING
        s.currentSeat shouldBe Seat(0)

        // §9.2 volta à etapa 2: pode baixar com as cartas do morto e depois descartar
        val after = s.act(0, create("4C' 5C' 6C'")).act(0, discardCard("AC'"))
        after.currentSeat shouldBe Seat(1)
    }

    @Test
    fun `morto direto pega o primeiro morto ainda disponivel`() {
        // §9.1 morto disponível: não pego por nenhum lado nem transformado em monte
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.hand(0) shouldContainExactlyInAnyOrder morto1
        s.mortoStatus[1] shouldBe MortoStatus.Taken(Side(0))
    }

    @Test
    fun `morto direto ao pegar o lixo ficando sem cartas`() {
        // §9.2 baixou todas as cartas (inclusive ao levar o topo do lixo à mesa)
        val s = round {
            hand(0, "7S 7D")
            hand(1, "9C 9D")
            discard("7H")
        }.act(0, takeNew("7S 7D"))
        s.hand(0) shouldContainExactlyInAnyOrder morto0
        s.phase shouldBe Phase.PLAYING
    }

    @Test
    fun `morto indireto so e jogado na proxima vez`() {
        // §9.3 descartou a última carta: pega o morto e joga com ele somente na sua próxima vez
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.discardPile.last() shouldBe c("KS")
        s.hand(0) shouldContainExactlyInAnyOrder morto0
        s.mortoStatus[0] shouldBe MortoStatus.Taken(Side(0))
        s.currentSeat shouldBe Seat(1)
        s.phase shouldBe Phase.AWAITING_DRAW
        s.check(0, create("4C' 5C' 6C'")) shouldFailWith ActionError.NOT_YOUR_TURN
        // logo após o descarte, o dono do morto não tem nenhuma ação
        s.legalActions(0).shouldBeEmpty()

        // o próximo jogador joga a sua vez…
        val afterNext = s.act(1, Action.DrawFromStock).act(1, discardCard("9C"))
        afterNext.currentSeat shouldBe Seat(0)
        // …e, na sua próxima vez, o dono joga com as cartas do morto
        val back = afterNext.act(0, Action.DrawFromStock)
        back.legalActions(0).createPlans() shouldContain cards("4C' 5C' 6C'").toSet()
        back.act(0, create("4C' 5C' 6C'")).table(0).melds.size shouldBe 1
    }

    @Test
    fun `morto indireto com 3 vermelho e monte vazio usa o outro morto como monte`() {
        // §9.3 + §9.4 3 vermelho do morto reposto; §10.1 monte vazio: o outro morto disponível vira monte
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            morto(0, "3H 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.mortoStatus[0] shouldBe MortoStatus.Taken(Side(0))
        s.mortoStatus[1] shouldBe MortoStatus.BecameStock
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' 4D'")
        s.stock shouldContainExactly cards("5D' 6D' 7D' 8D' 9D' TD' JD' QD' KD' AD'")
        s.currentSeat shouldBe Seat(1)
        s.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `morto direto com 3 vermelho e monte vazio usa o outro morto como monte`() {
        // §9.2 + §9.4 + §10.1
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            morto(0, "3D 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("")
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.mortoStatus[1] shouldBe MortoStatus.BecameStock
        s.redThreesOf(Side(0)) shouldContainExactly cards("3D")
        s.hand(0) shouldContainExactlyInAnyOrder cards("4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' 4D'")
        s.phase shouldBe Phase.PLAYING
        s.currentSeat shouldBe Seat(0)
    }

    @Test
    fun `3 vermelhos do morto sao baixados e repostos`() {
        // §9.4 3 vermelhos do morto são baixados e repostos automaticamente (§6.5)
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            morto(0, "3H 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("AS KS")
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.redThreesOf(Side(0)) shouldContainExactly cards("3H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC' AS")
        s.stock shouldContainExactly cards("KS")
    }

    @Test
    fun `lado que ja pegou morto nao pega outro`() {
        // §9.1 no máximo um morto por lado: sem canastra, não pode ficar sem cartas
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            mortoTaken(0, 0)
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 7H")) shouldFailWith ActionError.NO_CANASTA_TO_GO_OUT
    }

    @Test
    fun `em duplas o parceiro de quem pegou o morto bate sem pegar outro`() {
        // §9.1 o parceiro não pega outro morto; §11.1 vale o morto pego por qualquer parceiro
        val s = round(GameMode.DUPLAS) {
            hand(2, "KS KD KC")
            hand(0, "9C 9D")
            hand(1, "8C 8D")
            hand(3, "7C 7D")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H")
            current = 2
            phase = Phase.PLAYING
        }.act(2, create("KS KD KC"))
        s.phase shouldBe Phase.FINISHED
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(2))
        s.mortoStatus[1] shouldBe MortoStatus.Available
    }

    @Test
    fun `em duplas o primeiro parceiro que fica sem cartas pega o morto do lado`() {
        // §9.1 o morto é pego pelo primeiro jogador da dupla que ficar sem cartas
        val s = round(GameMode.DUPLAS) {
            hand(1, "5H 6H 7H")
            hand(0, "9C 9D")
            hand(2, "8C 8D")
            hand(3, "7C 7D")
            current = 1
            phase = Phase.PLAYING
        }.act(1, create("5H 6H 7H"))
        s.mortoStatus[0] shouldBe MortoStatus.Taken(Side(1))
        s.hand(1) shouldContainExactlyInAnyOrder morto0
        s.hand(3) shouldContainExactly cards("7C 7D")
    }

    @Test
    fun `sem morto disponivel o lado sem morto nao fica sem cartas`() {
        // §9.5 sem morto disponível o jogador não pode ficar sem cartas
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            mortoTaken(0, 1)
            mortoBecameStock(1)
            meld(0, "4S 5S 6S 7S 8S 9S")
            phase = Phase.PLAYING
        }
        s.check(0, create("5H 6H 7H")) shouldFailWith ActionError.NO_MORTO_AVAILABLE
    }
}
