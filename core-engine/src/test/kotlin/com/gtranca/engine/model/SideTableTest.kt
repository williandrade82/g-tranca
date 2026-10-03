package com.gtranca.engine.model

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SideTableTest {

    private fun table(vararg melds: String): SideTable =
        melds.fold(SideTable()) { t, text -> t.createMeld(cards(text)).shouldBeOk() }

    @Test
    fun `conjuntos criados recebem identificadores estaveis`() {
        // §6.4 o jogador indica o conjunto de destino → cada conjunto tem um id
        val t = table("4H 5H 6H", "7S 7C 7D")
        t.melds.map { it.id } shouldContainExactly listOf(MeldId(0), MeldId(1))
        val after = t.addToMeld(MeldId(0), cards("7H")).shouldBeOk()
        after.melds.map { it.id } shouldContainExactly listOf(MeldId(0), MeldId(1))
        after.meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H 7H")
        after.createMeld(cards("KD KS KC")).shouldBeOk().melds.last().id shouldBe MeldId(2)
    }

    @Test
    fun `criacao invalida propaga o erro do conjunto`() {
        // §6.2 regras do conjunto valem na mesa
        SideTable().createMeld(cards("3S 3C 3S'")) shouldFailWith MeldError.CONTAINS_THREE
    }

    @Test
    fun `mais de um grupo do mesmo numero por lado`() {
        // §6.4 o lado pode ter vários grupos do mesmo número, mesmo que as cartas coubessem no existente
        val t = table("7H 7S 7C")
        val two = t.createMeld(cards("7D 7H' 7S'")).shouldBeOk()
        two.melds shouldHaveSize 2
        two.createMeld(cards("7C' 7D' 2C")).shouldBeOk().melds shouldHaveSize 3
    }

    @Test
    fun `segundo grupo permite baixar coringa quando o primeiro ja tem coringa`() {
        // §6.4 estratégia: 7-7-2 na mesa e 7-7-2 na mão → segundo grupo de 7
        val t = table("7H 7S 2C")
        t.addToMeld(MeldId(0), cards("2D")) shouldFailWith MeldError.TOO_MANY_WILDS
        t.createMeld(cards("7C 7D 2D")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `grupo e sequencia com o mesmo numero convivem`() {
        // §6.4 grupos e sequências com o mesmo número convivem
        table("7H 7S 7C", "6D 7D 8D").melds shouldHaveSize 2
    }

    @Test
    fun `nova sequencia continuacao de outra do mesmo naipe e proibida`() {
        // §6.4 com 4-5-6♥ na mesa não se pode baixar 7-8-9♥ como conjunto novo
        table("4H 5H 6H").createMeld(cards("7H 8H 9H")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
        // continuação pela ponta de baixo também
        table("7H 8H 9H").createMeld(cards("4H 5H 6H")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
    }

    @Test
    fun `nova sequencia com buraco em relacao a existente e permitida`() {
        // §6.4 8-9-10♥ não é continuação de 4-5-6♥ (falta o 7)
        table("4H 5H 6H").createMeld(cards("8H 9H TH")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `nova sequencia de outro naipe pode encostar`() {
        // §6.4 a restrição é para o mesmo naipe
        table("4H 5H 6H").createMeld(cards("7S 8S 9S")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `sobreposicao com cartas do segundo baralho nao e continuacao`() {
        // §6.4 5-6-7♥' sobrepõe 4-5-6♥ (não apenas prolonga) → permitido
        table("4H 5H 6H").createMeld(cards("5H' 6H' 7H'")).shouldBeOk().melds shouldHaveSize 2
        // §6.4 6♥'-7♥-8♥ não cabe em 4-5-6♥ (o 6 se repetiria) → permitido
        table("4H 5H 6H").createMeld(cards("6H' 7H 8H")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `nova sequencia com coringa que caberia na existente e proibida`() {
        // §6.4 8♥-9♥-2 cabe em 4-5-6♥ como 4-5-6-2-8-9 (coringa vale 7) → proibido
        table("4H 5H 6H").createMeld(cards("8H 9H 2C")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
    }

    @Test
    fun `nova sequencia com coringa ao lado de canastra limpa e permitida`() {
        // §6.4 exceção: não se considera sujar a canastra limpa 4..9♥ → 10♥-J♥-2 pode ser conjunto separado
        table("4H 5H 6H 7H 8H 9H").createMeld(cards("TH JH 2C")).shouldBeOk().melds shouldHaveSize 2
        // §6.4 exceção vale também com o coringa no buraco (J♥-2-Q♥ valeria 4..9-2-J-Q)
        table("4H 5H 6H 7H 8H 9H").createMeld(cards("JH QH 2C")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `cartas com coringa podem sujar a canastra limpa se o jogador escolher`() {
        // §6.3 / §6.4 a alternativa à sequência separada: acrescentar à canastra, que fica suja
        val t = table("4H 5H 6H 7H 8H 9H").addToMeld(MeldId(0), cards("TH JH 2C")).shouldBeOk()
        t.meld(MeldId(0))!!.isDirtyCanasta().shouldBeTrue()
    }

    @Test
    fun `nova sequencia sem coringa que cabe em canastra limpa e proibida`() {
        // §6.4 10♥-J♥-Q♥ cabe na canastra limpa 4..9♥ sem sujá-la → continuação
        table("4H 5H 6H 7H 8H 9H").createMeld(cards("TH JH QH")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
    }

    @Test
    fun `excecao da canastra limpa nao vale para sequencia que ainda nao e canastra`() {
        // §6.4 4-5-6-7-8♥ não é canastra: 9♥-10♥-2 caberia (4..8-9-10-2) → proibido
        table("4H 5H 6H 7H 8H").createMeld(cards("9H TH 2C")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
    }

    @Test
    fun `continuacao nao considera mudar a situacao do coringa da sequencia existente`() {
        // §6.4 exceção: 4-5-6-2♥ (solto) + 8-9-10♥ exigiria travar o coringa no 7 → permitido
        table("4H 5H 6H 2C").createMeld(cards("8H 9H TH")).shouldBeOk().melds shouldHaveSize 2
        // §6.4 exceção: K-A-2♥ + 9-10-J♥ exigiria travar o coringa na Q → permitido
        table("KH AH 2C").createMeld(cards("9H TH JH")).shouldBeOk().melds shouldHaveSize 2
        // §6.4 4-5-6-2♥ (solto) + 7-8-9♥ cabe e o coringa continua solto → proibido
        table("4H 5H 6H 2C").createMeld(cards("7H 8H 9H")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
        // §6.4 5-2-7♥ (travado no 6) + 8-9-10♥ cabe e o coringa continua no 6 → proibido
        table("5H 2C 7H").createMeld(cards("8H 9H TH")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
        // 4..A + coringa sem posição: 5-6-7♥ do 2º baralho repetiria números → permitido
        table("4H 5H 6H 7H 8H 9H TH JH QH KH AH 2C").createMeld(cards("5H' 6H' 7H'")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `nova sequencia com coringa ao lado de sequencia que ja tem coringa e permitida`() {
        // §6.4 + §6.3 acrescentar 10♥-J♥-2 a 4-2-6-7-8-9♥ daria 2 coringas → não cabe → permitido
        table("4H 2S 6H 7H 8H 9H").createMeld(cards("TH JH 2C")).shouldBeOk().melds shouldHaveSize 2
    }

    @Test
    fun `carta que serve a duas sequencias entra na escolhida sem uni-las`() {
        // §6.4 sequências nunca se unem; o jogador escolhe o destino
        val t = table("4H 5H 6H", "8H 9H TH")
        val first = t.addToMeld(MeldId(0), cards("7H")).shouldBeOk()
        first.melds shouldHaveSize 2
        first.meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H 7H")
        first.meld(MeldId(1))!!.cards shouldContainExactly cards("8H 9H TH")

        val second = t.addToMeld(MeldId(1), cards("7H")).shouldBeOk()
        second.melds shouldHaveSize 2
        second.meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H")
        second.meld(MeldId(1))!!.cards shouldContainExactly cards("7H 8H 9H TH")
    }

    @Test
    fun `acrescimo invalido ou em conjunto inexistente nao altera a mesa`() {
        // §6.4 destino indicado deve existir; regras de §6 valem no acréscimo
        val t = table("4H 5H 6H")
        t.addToMeld(MeldId(9), cards("7H")) shouldFailWith MeldError.MELD_NOT_FOUND
        t.addToMeld(MeldId(0), cards("7S")) shouldFailWith MeldError.WRONG_SUIT
        t.meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H")
    }

    @Test
    fun `todas as cartas da mesa`() {
        table("4H 5H 6H", "7S 7C 2D").allCards() shouldHaveSize 6
    }
}
