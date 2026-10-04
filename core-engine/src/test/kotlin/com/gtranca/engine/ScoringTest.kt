package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideScore
import com.gtranca.engine.model.TableCards
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `3 vermelho na mesa vale 100 cada quando o lado tem canastra`() {
        // §12.1 3 vermelho: +100 cada, se o lado tiver pelo menos uma canastra na mesa
        val s = finished {
            redThrees(0, "3H 3D")
            meld(0, "4H 5H 6H 7H 8H 9H")      // canastra limpa +200; cartas 6 × 8 = +48
        }
        score(s, 0).redThrees shouldBe ScoreLine(2, 200)
        // 200 (3 vermelhos) + 200 (canastra limpa) + 48 (cartas 4 a 9) + 2 × 5 (cartas 3 vermelho)
        score(s, 0).total shouldBe 200 + 200 + 48 + 10
        score(s, 1).redThrees shouldBe ScoreLine(0, 0)
    }

    @Test
    fun `3 vermelho sem canastra do lado vale menos 100 cada`() {
        // §12.1 sem canastra na mesa, cada 3 vermelho baixado vale −100 (conjunto que não é canastra não conta)
        val s = finished {
            redThrees(0, "3H 3D")
            meld(0, "4H 5H 6H 7H 8H")         // 5 cartas: não é canastra; cartas 5 × 8 = +40
        }
        score(s, 0).redThrees shouldBe ScoreLine(2, -200)
        // −200 (3 vermelhos) + 40 (cartas 4 a 8) + 2 × 5 (cartas 3 vermelho)
        score(s, 0).total shouldBe -200 + 40 + 10
    }

    @Test
    fun `canastra suja tambem garante os 100 do 3 vermelho`() {
        // §12.1 basta uma canastra, limpa ou suja
        val s = finished {
            redThrees(0, "3H")
            meld(0, "7S 7C 7D 7H 7S' 2C")     // canastra suja +100; cartas 5 × 8 + 10 = +50
        }
        score(s, 0).redThrees shouldBe ScoreLine(1, 100)
        // 100 (3 vermelho) + 100 (canastra suja) + 50 (cartas do conjunto) + 5 (carta 3 vermelho)
        score(s, 0).total shouldBe 100 + 100 + 50 + 5
    }

    @Test
    fun `3 vermelho sem canastra vale menos 100 com ou sem vencedor`() {
        // §12.1 a regra vale com ou sem vencedor: aqui o lado 1 perdeu (o lado 0 bateu) e não tem canastra
        val s = round {
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            meld(0, "4H 5H 6H 7H 8H 9H")
            redThrees(1, "3D")
            result = RoundResult.GoOut(Side(0), Seat(0))
        }
        score(s, 1).redThrees shouldBe ScoreLine(1, -100)
        // e sem vencedor (finished é NoWinner), também −100
        score(finished { redThrees(1, "3H") }, 1).redThrees shouldBe ScoreLine(1, -100)
    }

    @Test
    fun `3 vermelho na mao nao depende da canastra`() {
        // §12.1 a regra da canastra vale só para o 3 vermelho baixado na mesa; na mão ele é −5 (§12.2)
        val s = finished {
            meld(0, "4H 5H 6H 7H 8H 9H")      // canastra limpa +200; cartas +48
            hand(0, "3D")
        }
        score(s, 0).redThrees shouldBe ScoreLine(0, 0)
        score(s, 0).tableCards.redThrees shouldBe ScoreLine(0, 0)
        score(s, 0).hand.redThrees shouldBe ScoreLine(1, -5)
        score(s, 0).total shouldBe 200 + 48 - 5
    }

    @Test
    fun `em duplas a dupla sem canastra perde 100 por 3 vermelho`() {
        // §12.1 + §12.3 a mesa é da dupla: sem canastra da dupla, cada 3 vermelho baixado por qualquer parceiro vale −100
        val s = finished(GameMode.DUPLAS) {
            redThrees(1, "3H 3D")
            meld(1, "5C 6C 7C")               // não é canastra; cartas 3 × 8 = +24
            meld(0, "QS QC QD QH QS' QC'")    // a canastra é da outra dupla
        }
        score(s, 1).redThrees shouldBe ScoreLine(2, -200)
        // −200 (3 vermelhos) + 24 (cartas 5 a 7) + 2 × 5 (cartas 3 vermelho)
        score(s, 1).total shouldBe -200 + 24 + 10
    }

    @Test
    fun `em duplas a canastra de qualquer parceiro vale para os 3 vermelhos da dupla`() {
        // §12.1 + §6.4 a mesa é do lado: a canastra baixada pelo parceiro libera os +100 da dupla
        val s = finished(GameMode.DUPLAS) {
            redThrees(0, "3H 3D'")
            meld(0, "QS QC QD QH QS' QC'")    // canastra limpa do lado 0 (assentos 0 e 2)
        }
        score(s, 0).redThrees shouldBe ScoreLine(2, 200)
        score(s, 1).redThrees shouldBe ScoreLine(0, 0)
    }

    @Test
    fun `canastra limpa vale 200 e conjunto que nao e canastra so vale as cartas`() {
        // §12.1 canastra limpa +200; o conjunto que não é canastra não tem bônus, só o valor das cartas
        val s = finished {
            meld(0, "4H 5H 6H 7H 8H 9H")      // 200 + 6 × 8
            meld(0, "7S 7C 7D")               // 3 × 8
        }
        score(s, 0).cleanCanastas shouldBe ScoreLine(1, 200)
        score(s, 0).dirtyCanastas shouldBe ScoreLine(0, 0)
        score(s, 0).tableCards.fourToTen shouldBe ScoreLine(9, 72)
        score(s, 0).total shouldBe 200 + 48 + 24
    }

    @Test
    fun `canastra limpa que recebe coringa na partida passa a valer 100`() {
        // §6.3 / §7.3 coringa suja a canastra limpa; §12.1 ela pontua como suja (+100)
        val played = round {
            mortoTaken(0, 0)
            mortoTaken(1, 1)
            hand(0, "2C KS")
            meld(0, "4H 5H 6H 7H 8H 9H")
            phase = Phase.PLAYING
        }.act(0, addTo(0, "2C"))
        val s = played.copy(phase = Phase.FINISHED, result = RoundResult.NoWinner)
        score(s, 0).cleanCanastas shouldBe ScoreLine(0, 0)
        score(s, 0).dirtyCanastas shouldBe ScoreLine(1, 100)
        // §12.1 o coringa baixado também soma o seu valor
        score(s, 0).tableCards.wilds shouldBe ScoreLine(1, 10)
    }

    @Test
    fun `canastra suja vale 100`() {
        // §12.1 canastra suja +100
        val s = finished { meld(0, "4H 5H 6H 7H 8H 2C") }
        score(s, 0).dirtyCanastas shouldBe ScoreLine(1, 100)
        score(s, 0).cleanCanastas shouldBe ScoreLine(0, 0)
        // 100 (canastra suja) + 5 × 8 (4 a 8) + 10 (coringa)
        score(s, 0).total shouldBe 100 + 40 + 10
    }

    @Test
    fun `exemplo da regra - canastra limpa de 4 a 9 de copas vale 248`() {
        // §12.1 exemplo: canastra limpa 4-5-6-7-8-9 de copas = 200 + 6 × 8 = 248
        val s = finished { meld(0, "4H 5H 6H 7H 8H 9H") }
        score(s, 0).cleanCanastas shouldBe ScoreLine(1, 200)
        score(s, 0).tableCards shouldBe TableCards(
            redThrees = ScoreLine.ZERO,
            fourToTen = ScoreLine(6, 48),
            faceCardsAndAces = ScoreLine.ZERO,
            wilds = ScoreLine.ZERO,
        )
        score(s, 0).tableCards.points shouldBe 48
        score(s, 0).total shouldBe 248
    }

    @Test
    fun `cartas em conjunto que nao e canastra somam o seu valor`() {
        // §12.1 contam as cartas de todos os conjuntos do lado, canastras ou não
        val small = finished { meld(0, "4H 5H 6H") }
        val bigger = finished { meld(0, "4H 5H 6H 7H 8H") }
        score(small, 0).tableCards.fourToTen shouldBe ScoreLine(3, 24)
        score(small, 0).total shouldBe 24
        score(bigger, 0).tableCards.fourToTen shouldBe ScoreLine(5, 40)
        score(bigger, 0).total shouldBe 40
        score(bigger, 0).cleanCanastas shouldBe ScoreLine(0, 0)
    }

    @Test
    fun `valor de cada tipo de carta na mesa`() {
        // §12.1 cartas na mesa: 3 vermelho +5, 4 a 10 +8, J/Q/K/A +10, coringa +10
        val s = finished {
            redThrees(0, "3H 3D")
            meld(0, "TS JS QS KS AS")         // 10 (+8) e J, Q, K, A (+10 cada)
            meld(0, "9D 9C 2H")               // dois 9 (+8 cada) e coringa (+10)
        }
        val table = score(s, 0).tableCards
        table.redThrees shouldBe ScoreLine(2, 10)
        table.fourToTen shouldBe ScoreLine(3, 24)
        table.faceCardsAndAces shouldBe ScoreLine(4, 40)
        table.wilds shouldBe ScoreLine(1, 10)
        table.points shouldBe 10 + 24 + 40 + 10
        // sem canastra: 3 vermelhos −200 (§12.1) + 84 das cartas
        score(s, 0).total shouldBe -200 + 84
    }

    @Test
    fun `coringa na mesa soma 10 qualquer que seja a carta que representa`() {
        // §12.1 coringa (2) +10, mesmo travado no lugar de um 4 a 10
        val s = finished { meld(0, "5S 2D 7S") }   // coringa no buraco do 6
        score(s, 0).tableCards.wilds shouldBe ScoreLine(1, 10)
        score(s, 0).tableCards.fourToTen shouldBe ScoreLine(2, 16)
        score(s, 0).total shouldBe 26
    }

    @Test
    fun `3 vermelho baixado com canastra vale 105 e sem canastra menos 95`() {
        // §12.1 o 3 vermelho soma +5 de carta além do ±100
        val with = finished {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H")
        }
        val without = finished { redThrees(0, "3H") }
        val base = finished { meld(0, "4H 5H 6H 7H 8H 9H") }
        (score(with, 0).total - score(base, 0).total) shouldBe 105
        score(without, 0).total shouldBe -95
        score(without, 0).redThrees shouldBe ScoreLine(1, -100)
        score(without, 0).tableCards.redThrees shouldBe ScoreLine(1, 5)
    }

    @Test
    fun `em duplas cada dupla soma as cartas da sua mesa`() {
        // §12.1 + §12.3 a mesa é do lado: soma as cartas baixadas pelos dois parceiros, e só as da dupla
        val s = finished(GameMode.DUPLAS) {
            meld(0, "QS QC QD")               // lado 0: 3 × 10
            meld(0, "4D 5D 6D 2S")            // lado 0: 3 × 8 + 10
            redThrees(0, "3H")                // lado 0: +5 (e −100 sem canastra)
            meld(1, "AH AC AS")               // lado 1: 3 × 10
        }
        score(s, 0).tableCards.points shouldBe 30 + 24 + 10 + 5
        score(s, 0).total shouldBe -100 + 69
        score(s, 1).tableCards.points shouldBe 30
        score(s, 1).total shouldBe 30
    }

    @Test
    fun `cartas na mesa contam com ou sem vencedor`() {
        // §12.1 as cartas na mesa contam sempre, com ou sem vencedor, para os dois lados
        val laidMelds: RoundStateBuilder.() -> Unit = {
            meld(0, "KS KC KD")
            meld(1, "8C 9C TC")
        }
        val noWinner = finished(block = laidMelds)
        val withWinner = finished {
            laidMelds()
            result = RoundResult.GoOut(Side(1), Seat(1))
        }
        for (s in listOf(noWinner, withWinner)) {
            score(s, 0).tableCards.points shouldBe 30
            score(s, 1).tableCards.points shouldBe 24
        }
        score(noWinner, 1).total shouldBe 24
        score(withWinner, 1).total shouldBe 24 + 100   // + batida
        score(withWinner, 0).total shouldBe 30         // o perdedor também soma as cartas da mesa
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
        // 3 vermelho sem canastra do lado: −100 (§12.1) + 5 da carta na mesa (§12.1)
        score(s, 0).total shouldBe -100 + 5 - 33
        score(s, 1).hand.points shouldBe -18
    }

    @Test
    fun `partida exemplo completa conferida a mao`() {
        // §12.1 + §12.2: lado 0 bateu; lado 1 ficou sem morto e com cartas na mão
        val s = round {
            redThrees(0, "3H")                // +100 (há canastra) e +5 da carta
            meld(0, "4H 5H 6H 7H 8H 9H")      // canastra limpa +200; cartas 6 × 8 = +48
            meld(0, "7S 7C 7D 7H 7S' 2C")     // canastra suja +100; cartas 5 × 8 + 10 = +50
            meld(0, "KS KC KD")               // não é canastra; cartas 3 × 10 = +30
            mortoTaken(0, 0)
            redThrees(1, "3D 3D'")            // −100 cada (sem canastra) e +5 de cada carta
            meld(1, "5C 6C 7C")               // cartas 3 × 8 = +24
            hand(1, "AS 9D 2H 3C")            // −10 −8 −10 −5 = −33
            result = RoundResult.GoOut(Side(0), Seat(0))
        }
        // lado 0: 100 (3 vermelho) + 200 + 100 + 100 (batida) = 500 de especiais;
        // cartas na mesa: 5 + 48 + 50 + 30 = 133 ⇒ 633
        score(s, 0).tableCards.points shouldBe 133
        score(s, 0).total shouldBe 633
        // lado 1: −200 (3 vermelhos sem canastra) − 100 (morto não pego) − 33 (mão) = −333;
        // cartas na mesa: 10 + 24 = 34 ⇒ −299
        score(s, 1).tableCards.points shouldBe 34
        score(s, 1).total shouldBe -299
    }

    @Test
    fun `valores de pontuacao vem do RuleSet`() {
        // §14 valores de pontuação reunidos no RuleSet
        RuleSet.DEFAULT.redThreePoints shouldBe 100
        RuleSet.DEFAULT.redThreeWithoutCanastaPoints shouldBe -100
        RuleSet.DEFAULT.cleanCanastaPoints shouldBe 200
        RuleSet.DEFAULT.dirtyCanastaPoints shouldBe 100
        RuleSet.DEFAULT.goOutPoints shouldBe 100
        RuleSet.DEFAULT.mortoNotTakenPoints shouldBe -100
        val s = finished { redThrees(0, "3H") }
        // −50 do RuleSet + 5 da carta na mesa
        scoreRound(s, RuleSet(redThreeWithoutCanastaPoints = -50))[0].total shouldBe -50 + 5
        val withCanasta = finished {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H")
        }
        // 50 do RuleSet + 200 (canastra limpa) + 48 + 5 (cartas na mesa)
        scoreRound(withCanasta, RuleSet(redThreePoints = 50))[0].total shouldBe 50 + 200 + 48 + 5
    }

    @Test
    fun `valores das cartas na mesa vem do RuleSet`() {
        // §12.1 / §14 cartas na mesa: 3 vermelho +5, 4 a 10 +8, J/Q/K/A +10, coringa +10, separados da mão
        RuleSet.DEFAULT.tableRedThreePoints shouldBe 5
        RuleSet.DEFAULT.tableFourToTenPoints shouldBe 8
        RuleSet.DEFAULT.tableFaceCardOrAcePoints shouldBe 10
        RuleSet.DEFAULT.tableWildPoints shouldBe 10
        val s = finished {
            redThrees(0, "3H")
            meld(0, "4D 5D 6D 7D 8D 9D")      // canastra limpa
            meld(0, "KS KC 2H")
        }
        val rules = RuleSet(
            tableRedThreePoints = 1,
            tableFourToTenPoints = 2,
            tableFaceCardOrAcePoints = 3,
            tableWildPoints = 4,
            handFourToTenPoints = -1000,       // a tabela da mão não interfere na da mesa
        )
        val table = scoreRound(s, rules)[0].tableCards
        table shouldBe TableCards(ScoreLine(1, 1), ScoreLine(6, 12), ScoreLine(2, 6), ScoreLine(1, 4))
        // 100 (3 vermelho) + 200 (canastra limpa) + 1 + 12 + 6 + 4
        scoreRound(s, rules)[0].total shouldBe 100 + 200 + 23
    }

    @Test
    fun `detalhamento sem cartas na mesa le JSON antigo`() {
        // §12 o histórico salvo de versões anteriores (sem cartas na mesa) continua legível: o campo vale zero
        val s = finished {
            redThrees(0, "3H")
            meld(0, "4H 5H 6H 7H 8H 9H")
        }
        val score = score(s, 0)
        val json = Json.encodeToString(SideScore.serializer(), score)
        Json.decodeFromString(SideScore.serializer(), json) shouldBe score
        val old = JsonObject(Json.parseToJsonElement(json).jsonObject - "tableCards").toString()
        val decoded = Json.decodeFromString(SideScore.serializer(), old)
        decoded.tableCards shouldBe TableCards.ZERO
        decoded.tableCards.points shouldBe 0
        decoded.copy(tableCards = score.tableCards) shouldBe score
    }

    @Test
    fun `so se pontua partida encerrada`() {
        // §12 pontuação ao final de cada partida
        shouldThrow<IllegalArgumentException> { scoreRound(round { hand(0, "KS") }) }
    }
}
