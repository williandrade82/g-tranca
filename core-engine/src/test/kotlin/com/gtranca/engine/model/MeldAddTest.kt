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
    fun `coringa acrescentado vai para a ponta de cima`() {
        // §6.3 posição definida pelo jogo: ponta de cima
        val m = meld("5H 6H 7H").add(cards("2C")).shouldBeOk()
        m.wildRank shouldBe Rank.EIGHT
    }

    @Test
    fun `coringa acrescentado com natural preenche o buraco`() {
        // §6.3 coringa ocupa o buraco entre naturais
        val m = meld("5H 6H 7H").add(cards("9H 2C")).shouldBeOk()
        m.wildRank shouldBe Rank.EIGHT
        m.cards shouldContainExactly cards("5H 6H 7H 2C 9H")
    }

    @Test
    fun `segundo coringa e recusado`() {
        // §6.3 máximo de 1 coringa por conjunto
        meld("5H 6H 2C").add(cards("2D")) shouldFailWith MeldError.TOO_MANY_WILDS
        meld("7H 7S 2C").add(cards("2D")) shouldFailWith MeldError.TOO_MANY_WILDS
    }

    @Test
    fun `coringa corre para a ponta de cima quando a natural e baixada`() {
        // §6.3 coringa que corre: 5-6-W(7) + 7 natural → W vai a 8
        val m = meld("5H 6H 2C").add(cards("7H")).shouldBeOk()
        m.wildRank shouldBe Rank.EIGHT
        m.cards shouldContainExactly cards("5H 6H 7H 2C")
        m.hasWild.shouldBeTrue()
    }

    @Test
    fun `coringa no buraco corre para a ponta de cima`() {
        // §6.3 5-W(6)-7 + 6 → W vai a 8
        meld("5H 2C 7H").add(cards("6H")).shouldBeOk().wildRank shouldBe Rank.EIGHT
    }

    @Test
    fun `coringa corre para a ponta de baixo se nao couber acima do As`() {
        // §6.3 K-A com W(Q) + Q natural → W vai a J
        val m = meld("KH AH 2C").add(cards("QH")).shouldBeOk()
        m.wildRank shouldBe Rank.JACK
        m.cards shouldContainExactly cards("2C QH KH AH")
    }

    @Test
    fun `natural rejeitada quando o coringa nao cabe em nenhuma ponta`() {
        // §6.3 4..A completa com coringa no lugar do 7: o 7 natural não pode ser baixado
        val m = meld("4S 5S 6S 2H 8S 9S TS JS QS KS AS")
        m.wildRank shouldBe Rank.SEVEN
        m.add(cards("7S")) shouldFailWith MeldError.WILD_DOES_NOT_FIT
    }

    @Test
    fun `natural ao lado do coringa na ponta prolonga a sequencia`() {
        // §6.3 5-6-W(7) + 8 → 5-6-W-8, coringa continua valendo 7
        meld("5H 6H 2C").add(cards("8H")).shouldBeOk().wildRank shouldBe Rank.SEVEN
    }

    @Test
    fun `coringa recusado em canastra limpa`() {
        // §6.3 / §7.3 canastra limpa não recebe coringa
        meld("4H 5H 6H 7H 8H 9H").add(cards("2C")) shouldFailWith MeldError.WILD_IN_CLEAN_CANASTA
        meld("7H 7S 7C 7D 7H' 7S'").add(cards("2C")) shouldFailWith MeldError.WILD_IN_CLEAN_CANASTA
    }

    @Test
    fun `coringa aceito em conjunto que ainda nao e canastra`() {
        // §6.3 a proibição vale só para canastra limpa
        val m = meld("4H 5H 6H 7H 8H").add(cards("2C")).shouldBeOk()
        m.cards.size shouldBe 6
        m.isCanasta().shouldBeTrue()
        m.isClean.shouldBeFalse()
    }

    // ---------- §7 canastras ----------

    @Test
    fun `canastra exige 6 ou mais cartas`() {
        // §7.1 conjunto com 6 ou mais cartas
        meld("4H 5H 6H 7H 8H").isCanasta().shouldBeFalse()
        meld("4H 5H 6H 7H 8H 9H").isCanasta().shouldBeTrue()
        meld("7H 7S 7C 7D 7H' 7S' 7C'").isCanasta().shouldBeTrue()
    }

    @Test
    fun `canastra limpa sem coringa e suja com coringa`() {
        // §7.2 limpa: sem coringa; suja: com coringa
        val clean = meld("4H 5H 6H 7H 8H 9H")
        clean.isCleanCanasta().shouldBeTrue()
        clean.isDirtyCanasta().shouldBeFalse()
        val dirty = meld("4H 5H 6H 7H 8H 2C")
        dirty.isDirtyCanasta().shouldBeTrue()
        dirty.isCleanCanasta().shouldBeFalse()
        meld("4H 5H 2C").isDirtyCanasta().shouldBeFalse()
    }

    @Test
    fun `canastra continua recebendo naturais`() {
        // §7.3 canastra pode continuar recebendo cartas naturais
        meld("4H 5H 6H 7H 8H 9H").add(cards("TH")).shouldBeOk().isCleanCanasta().shouldBeTrue()
        meld("4H 5H 6H 7H 8H 2C").add(cards("TH")).shouldBeOk().isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `acrescimo de varias cartas vale o estado antes do acrescimo`() {
        // §7.3 ex.: 4-5-6-7-8♥ + 10♥ e um coringa juntos → canastra suja de 7 cartas
        val m = meld("4H 5H 6H 7H 8H").add(cards("TH 2C")).shouldBeOk()
        m.cards.size shouldBe 7
        m.wildRank shouldBe Rank.NINE
        m.isDirtyCanasta().shouldBeTrue()
        // já canastra limpa, o mesmo acréscimo é recusado
        meld("4H 5H 6H 7H 8H 9H").add(cards("JH 2C")) shouldFailWith MeldError.WILD_IN_CLEAN_CANASTA
    }

    @Test
    fun `tamanho minimo de canastra vem do RuleSet`() {
        // §14 tamanho mínimo de canastra no RuleSet
        RuleSet.DEFAULT.minCanastaSize shouldBe 6
        meld("4H 5H 6H 7H 8H 9H").isCanasta(RuleSet(minCanastaSize = 7)).shouldBeFalse()
    }
}
