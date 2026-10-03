package com.gtranca.engine.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MeldCreateTest {

    // ---------- §6.1 tipos ----------

    @Test
    fun `sequencia de cartas consecutivas do mesmo naipe`() {
        // §6.1 sequência
        val m = meld("5H 6H 7H")
        m.kind shouldBe MeldKind.Sequence(Suit.HEARTS)
        m.lowRank shouldBe Rank.FIVE
        m.highRank shouldBe Rank.SEVEN
        m.wildState shouldBe WildState.None
    }

    @Test
    fun `cartas fora de ordem formam a sequencia ordenada`() {
        // §6.1 sequência: a ordem de entrada não importa
        meld("7H 5H 6H").cards shouldContainExactly cards("5H 6H 7H")
    }

    @Test
    fun `grupo de cartas do mesmo numero com naipes quaisquer e repeticao`() {
        // §6.1 grupo: naipes quaisquer, repetições permitidas
        val m = meld("7H 7S 7H'")
        m.kind shouldBe MeldKind.Group(Rank.SEVEN)
        m.cards.size shouldBe 3
    }

    @Test
    fun `grupo de ases e permitido`() {
        // §6.1 grupo de qualquer número (exceto 2 e 3, ver §6.2/§6.3)
        meld("AH AS AC").kind shouldBe MeldKind.Group(Rank.ACE)
    }

    @Test
    fun `cartas que nao formam sequencia nem grupo sao rejeitadas`() {
        // §6.1 nem sequência (naipes diferentes) nem grupo (números diferentes)
        Meld.create(cards("5H 6S 7H")) shouldFailWith MeldError.NOT_A_SEQUENCE_OR_GROUP
        Meld.create(cards("7H 8S 7C")) shouldFailWith MeldError.NOT_A_SEQUENCE_OR_GROUP
    }

    // ---------- §6.2 regras gerais ----------

    @Test
    fun `conjunto precisa de pelo menos 3 cartas`() {
        // §6.2 mínimo de 3 cartas
        Meld.create(cards("5H 6H")) shouldFailWith MeldError.TOO_FEW_CARDS
        Meld.create(cards("7H 7S")) shouldFailWith MeldError.TOO_FEW_CARDS
        Meld.create(emptyList()) shouldFailWith MeldError.TOO_FEW_CARDS
    }

    @Test
    fun `nenhum 3 compoe conjuntos`() {
        // §6.2 nenhum 3 (vermelho ou preto) em conjunto
        Meld.create(cards("3S 3C 3S'")) shouldFailWith MeldError.CONTAINS_THREE
        Meld.create(cards("3H 4H 5H")) shouldFailWith MeldError.CONTAINS_THREE
        Meld.create(cards("4C 5C 6C 3C")) shouldFailWith MeldError.CONTAINS_THREE
    }

    @Test
    fun `A-2-3 e invalida`() {
        // §6.2 não existe A-2-3 (tem 3; o 2 seria coringa)
        Meld.create(cards("AH 2H 3H")) shouldFailWith MeldError.CONTAINS_THREE
    }

    @Test
    fun `sequencia nao passa do As para baixo`() {
        // §6.2 Ás não é circular: A-2-4 e K-A-4 não são sequência
        Meld.create(cards("AH 2H 4H")) shouldFailWith MeldError.NOT_CONSECUTIVE
        Meld.create(cards("KH AH 4H")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `Q-K-A e valida com o As alto`() {
        // §6.2 Ás vale somente como carta alta
        val m = meld("QH KH AH")
        m.lowRank shouldBe Rank.QUEEN
        m.highRank shouldBe Rank.ACE
    }

    @Test
    fun `4 a A com 11 cartas e a maior sequencia possivel`() {
        // §6.2 a sequência mais longa possível é 4-5-6-7-8-9-10-J-Q-K-A
        val m = meld("4S 5S 6S 7S 8S 9S TS JS QS KS AS")
        m.cards.size shouldBe 11
        m.lowRank shouldBe Rank.FOUR
        m.highRank shouldBe Rank.ACE
    }

    @Test
    fun `4 a A completa com coringa fica com o coringa sem posicao`() {
        // §6.3 sequência completa 4..A: o coringa permanece sem representar nenhuma carta
        val m = meld("4S 5S 6S 7S 8S 9S TS JS QS KS AS 2H")
        m.cards.size shouldBe 12
        m.wildState shouldBe WildState.Unplaced
        m.wildRank.shouldBeNull()
    }

    @Test
    fun `sequencia com buraco sem coringa e invalida`() {
        // §6.1 cartas consecutivas
        Meld.create(cards("5H 6H 8H")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `um coringa nao cobre dois buracos`() {
        // §6.3 máx. 1 coringa
        Meld.create(cards("5H 8H 2C")) shouldFailWith MeldError.NOT_CONSECUTIVE
    }

    @Test
    fun `sequencia nao repete numero`() {
        // §6.1 cartas consecutivas (sem repetição)
        Meld.create(cards("5H 5H' 6H")) shouldFailWith MeldError.REPEATED_RANK
    }

    @Test
    fun `mesma carta fisica duas vezes e rejeitada`() {
        Meld.create(cards("7H 7H 7S")) shouldFailWith MeldError.DUPLICATE_CARD
    }

    // ---------- §6.3 coringa ----------

    @Test
    fun `5-6-2 coringa fica solto na ponta`() {
        // §6.3 sem buraco entre as naturais, o coringa fica solto (sem valor fixo)
        val m = meld("5H 6H 2C")
        m.wildState shouldBe WildState.Loose
        m.wildRank.shouldBeNull()
        m.lowRank shouldBe Rank.FIVE
        m.highRank shouldBe Rank.SIX
        m.cards shouldContainExactly cards("5H 6H 2C")
    }

    @Test
    fun `5-2-7 coringa travado no buraco vale 6`() {
        // §6.3 coringa ocupa o buraco entre naturais e fica travado
        val m = meld("5H 2D 7H")
        m.wildState shouldBe WildState.Locked(Rank.SIX)
        m.wildRank shouldBe Rank.SIX
        m.cards shouldContainExactly cards("5H 2D 7H")
    }

    @Test
    fun `K-A-2 e valida com o coringa solto embaixo`() {
        // §6.3 sequência que chega ao Ás: o coringa só pode estar na ponta de baixo; §6.2 não há volta K-A-2
        val m = meld("KH AH 2H")
        m.wildState shouldBe WildState.Loose
        m.cards shouldContainExactly cards("2H KH AH")
    }

    @Test
    fun `5 a A com coringa deixa o coringa solto`() {
        // §6.3 sem buraco: solto (só pode ser o 4, §6.2)
        val m = meld("5S 6S 7S 8S 9S TS JS QS KS AS 2D")
        m.wildState shouldBe WildState.Loose
        m.cards.size shouldBe 11
    }

    @Test
    fun `coringa de qualquer naipe substitui na sequencia`() {
        // §6.3 o coringa substitui qualquer carta
        meld("5H 6H 2S").kind shouldBe MeldKind.Sequence(Suit.HEARTS)
    }

    @Test
    fun `grupo com coringa`() {
        // §6.3 no grupo o coringa vale o número do grupo
        val m = meld("7H 7S 2D")
        m.kind shouldBe MeldKind.Group(Rank.SEVEN)
        m.wildState shouldBe WildState.Locked(Rank.SEVEN)
        m.wildRank shouldBe Rank.SEVEN
        m.hasWild shouldBe true
    }

    @Test
    fun `dois coringas no mesmo conjunto sao invalidos`() {
        // §6.3 máximo de 1 coringa por conjunto
        Meld.create(cards("5H 6H 2C 2D")) shouldFailWith MeldError.TOO_MANY_WILDS
        Meld.create(cards("5H 2C 2D")) shouldFailWith MeldError.TOO_MANY_WILDS
        Meld.create(cards("7H 7S 2C 2D")) shouldFailWith MeldError.TOO_MANY_WILDS
    }

    @Test
    fun `nao existe grupo de 2`() {
        // §6.3 o 2 nunca é natural; não existe grupo de 2
        Meld.create(cards("2H 2S 2C")) shouldFailWith MeldError.TOO_MANY_WILDS
    }
}
