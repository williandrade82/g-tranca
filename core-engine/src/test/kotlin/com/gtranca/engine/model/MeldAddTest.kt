package com.gtranca.engine.model

import com.gtranca.engine.RuleSet
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MeldAddTest {

    // ---------- prolongar / preencher ----------

    @Test
    fun `sequencia aceita carta em qualquer ponta`() {
        // §4.3 acrescentar cartas a conjuntos; §6.1 continua consecutiva
        meld("5H 6H 7H").add(cards("8H")).shouldBeOk().cards shouldContainExactly cards("5H 6H 7H 8H")
        meld("5H 6H 7H").add(cards("4H")).shouldBeOk().cards shouldContainExactly cards("4H 5H 6H 7H")
    }

    @Test
    fun `sequencia aceita varias cartas de uma vez`() {
        // §4.3 acrescentar cartas
        val m = meld("5H 6H 7H").add(cards("9H 8H TH")).shouldBeOk()
        m.lowRank shouldBe Rank.FIVE
        m.highRank shouldBe Rank.TEN
    }

    @Test
    fun `natural que deixaria buraco e rejeitada`() {
        // §6.1 consecutivas: 4-5-6 + 8 deixa buraco → rejeitado
        meld("4H 5H 6H").add(cards("8H")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `sequencia rejeita outro naipe e numero repetido`() {
        // §6.1 mesmo naipe, sem repetição
        meld("4H 5H 6H").add(cards("7S")) shouldFailWith MeldError.WRONG_SUIT
        meld("4H 5H 6H").add(cards("5H'")) shouldFailWith MeldError.REPEATED_RANK
    }

    @Test
    fun `sequencia nao passa do As`() {
        // §6.2 Ás não é circular
        meld("QH KH AH").add(cards("4H")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `grupo recebe naturais do mesmo numero`() {
        // §6.1 grupo: mesmo número, naipes quaisquer
        meld("7H 7S 7C").add(cards("7D 7H'")).shouldBeOk().cards.size shouldBe 5
        meld("7H 7S 7C").add(cards("8D")) shouldFailWith MeldError.WRONG_RANK
    }

    @Test
    fun `nao se acrescenta 3 nem lista vazia nem carta ja no conjunto`() {
        // §6.2 nenhum 3
        meld("4H 5H 6H").add(cards("3H")) shouldFailWith MeldError.CONTAINS_THREE
        meld("7H 7S 7C").add(emptyList()) shouldFailWith MeldError.NO_CARDS
        meld("7H 7S 7C").add(cards("7H")) shouldFailWith MeldError.DUPLICATE_CARD
    }

    // ---------- §6.3 coringa ----------

    @Test
    fun `coringa acrescentado sem buraco fica solto`() {
        // §6.3 sem buraco entre as naturais o coringa fica solto
        meld("5H 6H 7H").add(cards("2C")).shouldBeOk().wildState shouldBe WildState.Loose
    }

    @Test
    fun `coringa acrescentado com natural preenche o buraco e trava`() {
        // §6.3 coringa ocupa o buraco entre naturais
        val m = meld("5H 6H 7H").add(cards("9H 2C")).shouldBeOk()
        m.wildState shouldBe WildState.Locked(Rank.EIGHT)
        m.cards shouldContainExactly cards("5H 6H 7H 2C 9H")
    }

    @Test
    fun `segundo coringa e recusado`() {
        // §6.3 máximo de 1 coringa por conjunto
        meld("5H 6H 2C").add(cards("2D")) shouldFailWith MeldError.TOO_MANY_WILDS
        meld("7H 7S 2C").add(cards("2D")) shouldFailWith MeldError.TOO_MANY_WILDS
    }

    @Test
    fun `coringa solto serve dos dois lados`() {
        // §6.3 ex.: em 6-7-2 pode-se acrescentar 8, 5, 5 e 4, ou apenas 4 (o coringa trava no 5)
        val m = meld("6H 7H 2C")
        m.add(cards("8H")).shouldBeOk().wildState shouldBe WildState.Loose
        m.add(cards("5H")).shouldBeOk().wildState shouldBe WildState.Loose
        m.add(cards("5H 4H")).shouldBeOk().wildState shouldBe WildState.Loose
        val only4 = m.add(cards("4H")).shouldBeOk()
        only4.wildState shouldBe WildState.Locked(Rank.FIVE)
        only4.cards shouldContainExactly cards("4H 2C 6H 7H")
        m.add(cards("9H")).shouldBeOk().wildState shouldBe WildState.Locked(Rank.EIGHT)
    }

    @Test
    fun `coringa travado so aceita cartas fora do buraco`() {
        // §6.3 em 5-2-7 o coringa vale 6: acrescentam-se cartas do 4 para baixo ou do 8 para cima
        val m = meld("5H 2C 7H")
        m.add(cards("4H")).shouldBeOk().wildState shouldBe WildState.Locked(Rank.SIX)
        m.add(cards("8H")).shouldBeOk().wildState shouldBe WildState.Locked(Rank.SIX)
        m.add(cards("9H")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `natural do buraco libera o coringa que volta a ficar solto`() {
        // §6.3 coringa que corre: 5-W(6)-7 + 6 → solto; depois pode travar de novo
        val freed = meld("5H 2C 7H").add(cards("6H")).shouldBeOk()
        freed.wildState shouldBe WildState.Loose
        freed.cards shouldContainExactly cards("5H 6H 7H 2C")
        freed.hasWild.shouldBeTrue()
        freed.add(cards("9H")).shouldBeOk().wildState shouldBe WildState.Locked(Rank.EIGHT)
        freed.add(cards("3H")) shouldFailWith MeldError.CONTAINS_THREE
    }

    @Test
    fun `coringa solto de sequencia que chega ao As fica embaixo`() {
        // §6.3 K-A com coringa + Q natural → coringa continua solto, só cabe embaixo
        val m = meld("KH AH 2C").add(cards("QH")).shouldBeOk()
        m.wildState shouldBe WildState.Loose
        m.cards shouldContainExactly cards("2C QH KH AH")
    }

    @Test
    fun `natural que completa 4 a A deixa o coringa sem posicao`() {
        // §6.3 4..A com o coringa travado no 7: o 7 natural é aceito e o coringa fica sem posição
        val m = meld("4S 5S 6S 2H 8S 9S TS JS QS KS AS")
        m.wildState shouldBe WildState.Locked(Rank.SEVEN)
        val full = m.add(cards("7S")).shouldBeOk()
        full.wildState shouldBe WildState.Unplaced
        full.cards.size shouldBe 12
        full.isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `4 natural em 5 a A com coringa solto deixa o coringa sem posicao`() {
        // §6.3 o 4 completa 4..A: o coringa solto passa a ficar sem posição
        val m = meld("5S 6S 7S 8S 9S TS JS QS KS AS 2H").add(cards("4S")).shouldBeOk()
        m.wildState shouldBe WildState.Unplaced
        m.cards.size shouldBe 12
    }

    @Test
    fun `coringa acrescentado a sequencia completa limpa fica sem posicao`() {
        // §6.3 estratégia: sujar a canastra limpa completa 4..A (ex.: para poder bater)
        val m = meld("4S 5S 6S 7S 8S 9S TS JS QS KS AS").add(cards("2H")).shouldBeOk()
        m.wildState shouldBe WildState.Unplaced
        m.isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `coringa acrescentado a canastra limpa a torna suja`() {
        // §6.3 / §7.3 canastra limpa pode receber coringa e passa a ser suja
        val seq = meld("4H 5H 6H 7H 8H 9H TH").add(cards("2C")).shouldBeOk()
        seq.wildState shouldBe WildState.Loose
        seq.isDirtyCanasta().shouldBeTrue()
        meld("7H 7S 7C 7D 7H' 7S' 7C'").add(cards("2C")).shouldBeOk().isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `canastra que ficou suja nao recebe segundo coringa`() {
        // §6.3 máx. 1 coringa por conjunto, também em canastra
        meld("4H 5H 6H 7H 8H 9H 2C").add(cards("2S")) shouldFailWith MeldError.TOO_MANY_WILDS
    }

    @Test
    fun `coringa aceito em conjunto que ainda nao e canastra`() {
        // §6.3 coringa completa a canastra (suja)
        val m = meld("4H 5H 6H 7H 8H 9H").add(cards("2C")).shouldBeOk()
        m.cards.size shouldBe 7
        m.isCanasta().shouldBeTrue()
        m.isClean.shouldBeFalse()
    }

    // ---------- §7 canastras ----------

    @Test
    fun `canastra exige 7 ou mais cartas`() {
        // §7.1 conjunto com 7 ou mais cartas
        meld("4H 5H 6H 7H 8H 9H").isCanasta().shouldBeFalse()
        meld("7H 7S 7C 7D 7H' 7S'").isCanasta().shouldBeFalse()
        meld("4H 5H 6H 7H 8H 9H TH").isCanasta().shouldBeTrue()
        meld("7H 7S 7C 7D 7H' 7S' 7C'").isCanasta().shouldBeTrue()
    }

    @Test
    fun `canastra limpa sem coringa e suja com coringa`() {
        // §7.2 limpa: sem coringa; suja: com coringa
        val clean = meld("4H 5H 6H 7H 8H 9H TH")
        clean.isCleanCanasta().shouldBeTrue()
        clean.isDirtyCanasta().shouldBeFalse()
        val dirty = meld("4H 5H 6H 7H 8H 9H 2C")
        dirty.isDirtyCanasta().shouldBeTrue()
        dirty.isCleanCanasta().shouldBeFalse()
        meld("4H 5H 2C").isDirtyCanasta().shouldBeFalse()
    }

    @Test
    fun `canastra continua recebendo naturais`() {
        // §7.3 canastra pode continuar recebendo cartas naturais
        meld("4H 5H 6H 7H 8H 9H TH").add(cards("JH")).shouldBeOk().isCleanCanasta().shouldBeTrue()
        meld("4H 5H 6H 7H 8H 9H 2C").add(cards("TH")).shouldBeOk().isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `acrescimo de natural e coringa juntos`() {
        // §6.3 4-5-6-7-8♥ + 10♥ e um coringa juntos → canastra suja de 7 cartas
        val m = meld("4H 5H 6H 7H 8H").add(cards("TH 2C")).shouldBeOk()
        m.cards.size shouldBe 7
        m.wildRank shouldBe Rank.NINE
        m.isDirtyCanasta().shouldBeTrue()
        // §7.3 o mesmo vale para canastra limpa, que passa a ser suja
        meld("4H 5H 6H 7H 8H 9H").add(cards("JH 2C")).shouldBeOk().isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `tamanho minimo de canastra vem do RuleSet`() {
        // §14 tamanho mínimo de canastra no RuleSet
        RuleSet.DEFAULT.minCanastaSize shouldBe 7
        meld("4H 5H 6H 7H 8H 9H TH").isCanasta(RuleSet(minCanastaSize = 8)).shouldBeFalse()
    }
}
