package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScoringTest {

    /** Partida encerrada sem vencedor, com os dois mortos pegos (um por lado) e mãos vazias. */
    private fun finished(mode: GameMode = GameMode.INDIVIDUAL, block: RoundStateBuilder.() -> Unit) = round(mode) {
        mortoTaken(0, 0)
        mortoTaken(1, 1)
        result = RoundResult.NoWinner
        block()
    }

    private fun score(state: com.gtranca.engine.model.RoundState, side: Int) = scoreRound(state)[side]

    @Test
    fun `3 vermelho na mesa vale 100 cada`() {
        // §12.1 3 vermelho: +100 cada
        val s = finished { redThrees(0, "3H 3D") }
        score(s, 0).redThrees shouldBe ScoreLine(2, 200)
        score(s, 0).total shouldBe 200
        score(s, 1).redThrees shouldBe ScoreLine(0, 0)
    }

    @Test
    fun `canastra limpa vale 200 e conjunto que nao e canastra nao vale nada`() {
        // §12.1 canastra limpa +200; cartas em conjuntos não têm valor próprio
        val s = finished {
            meld(0, "4H 5H 6H 7H 8H 9H")
            meld(0, "7S 7C 7D")
        }
        score(s, 0).cleanCanastas shouldBe ScoreLine(1, 200)
        score(s, 0).dirtyCanastas shouldBe ScoreLine(0, 0)
        score(s, 0).total shouldBe 200
    }

    @Test
    fun `canastra suja vale 100`() {
        // §12.1 canastra suja +100
        val s = finished { meld(0, "4H 5H 6H 7H 8H 2C") }
        score(s, 0).dirtyCanastas shouldBe ScoreLine(1, 100)
        score(s, 0).cleanCanastas shouldBe ScoreLine(0, 0)
        score(s, 0).total shouldBe 100
    }

    @Test
    fun `cartas em conjuntos nao tem valor proprio`() {
        // §12.1 conjuntos maiores (não canastra) não somam pontos
        val small = finished { meld(0, "4H 5H 6H") }
        val bigger = finished { meld(0, "4H 5H 6H 7H 8H") }
        score(small, 0).total shouldBe 0
        score(bigger, 0).total shouldBe 0
    }

    @Test
    fun `batida vale 100 somente para o lado vencedor`() {
        // §12.1 batida +100 (somente o lado vencedor)
        val s = finished { result = RoundResult.GoOut(Side(1), Seat(1)) }
        score(s, 1).goOut shouldBe 100
        score(s, 0).goOut shouldBe 0
        score(s, 1).total shouldBe 100
    }

    @Test
    fun `sem vencedor ninguem ganha a batida`() {
        // §12.1 a batida não se aplica a partidas sem vencedor
        val s = finished { }
        score(s, 0).goOut shouldBe 0
        score(s, 1).goOut shouldBe 0
    }

    @Test
    fun `morto nao pego tira 100 do lado`() {
        // §12.2 morto não pego: –100 para cada lado que terminou sem ter pego um morto
        val s = round {
            mortoTaken(0, 0)
            result = RoundResult.NoWinner
        }
        score(s, 0).mortoNotTaken shouldBe 0
        score(s, 1).mortoNotTaken shouldBe -100
        score(s, 1).total shouldBe -100
    }

    @Test
    fun `morto que virou monte tambem conta como morto nao pego`() {
        // §12.2 o lado terminou sem ter pego um morto (§10 morto virou monte)
        val s = round {
            mortoTaken(0, 1)
            mortoBecameStock(1)
            result = RoundResult.NoWinner
        }
        score(s, 0).mortoNotTaken shouldBe -100
        score(s, 1).mortoNotTaken shouldBe 0
    }

    @Test
    fun `em duplas morto nao pego vale por dupla`() {
        // §12.2 / §12.3 a penalidade é do lado (dupla), uma vez
        val s = round(GameMode.DUPLAS) {
            mortoTaken(0, 1)
            result = RoundResult.NoWinner
        }
        score(s, 0).mortoNotTaken shouldBe -100
        score(s, 1).mortoNotTaken shouldBe 0
    }

    @Test
    fun `penalidade de cada tipo de carta na mao`() {
        // §12.2 3 vermelho −5, 3 preto −5, 4 a 10 −8, J/Q/K/A −10, coringa −10
        val s = finished { hand(0, "3H 3S 4C TC JD QD KD AD 2S") }
        val hand = score(s, 0).hand
        hand.redThrees shouldBe ScoreLine(1, -5)
        hand.blackThrees shouldBe ScoreLine(1, -5)
        hand.fourToTen shouldBe ScoreLine(2, -16)
        hand.faceCardsAndAces shouldBe ScoreLine(4, -40)
        hand.wilds shouldBe ScoreLine(1, -10)
        hand.points shouldBe -76
        score(s, 0).total shouldBe -76
    }

    @Test
    fun `em duplas as maos dos dois parceiros sao somadas`() {
        // §12.2 cartas na mão de todos os jogadores do lado; §12.3 pontos dos parceiros somados
        val s = finished(GameMode.DUPLAS) {
            hand(0, "KS 5H")
            hand(2, "2C 3S")
            hand(1, "9D")
            hand(3, "AC")
            redThrees(0, "3D")
        }
        score(s, 0).hand.points shouldBe -33
        score(s, 0).total shouldBe 100 - 33
        score(s, 1).hand.points shouldBe -18
    }

    @Test
    fun `partida exemplo completa conferida a mao`() {
        // §12.1 + §12.2: lado 0 bateu; lado 1 ficou sem morto e com cartas na mão
        val s = round {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H")      // canastra limpa +200
            meld(0, "7S 7C 7D 7H 7S' 2C")     // canastra suja +100
            meld(0, "KS KC KD")               // sem valor
            mortoTaken(0, 0)
            redThrees(1, "3D 3D'")
            meld(1, "5C 6C 7C")
            hand(1, "AS 9D 2H 3C")            // −10 −8 −10 −5 = −33
            result = RoundResult.GoOut(Side(0), Seat(0))
        }
        // lado 0: 100 (3 vermelho) + 200 + 100 + 100 (batida) = 500
        score(s, 0).total shouldBe 500
        // lado 1: 200 (3 vermelhos) − 100 (morto não pego) − 33 (mão) = 67
        score(s, 1).total shouldBe 67
    }

    @Test
    fun `valores de pontuacao vem do RuleSet`() {
        // §14 valores de pontuação reunidos no RuleSet
        RuleSet.DEFAULT.redThreePoints shouldBe 100
        RuleSet.DEFAULT.cleanCanastaPoints shouldBe 200
        RuleSet.DEFAULT.dirtyCanastaPoints shouldBe 100
        RuleSet.DEFAULT.goOutPoints shouldBe 100
        RuleSet.DEFAULT.mortoNotTakenPoints shouldBe -100
        val s = finished { redThrees(0, "3H") }
        scoreRound(s, RuleSet(redThreePoints = 50))[0].total shouldBe 50
    }

    @Test
    fun `so se pontua partida encerrada`() {
        // §12 pontuação ao final de cada partida
        shouldThrow<IllegalArgumentException> { scoreRound(round { hand(0, "KS") }) }
    }
}
