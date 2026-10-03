package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.WildState
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DiscardPileTest {

    @Test
    fun `lixo vazio nao pode ser pego`() {
        // §5.5 não é possível pegar o lixo vazio
        val s = round { hand(0, "7S 7D KS") }
        s.check(0, takeNew("7S 7D")) shouldFailWith ActionError.DISCARD_PILE_EMPTY
    }

    @Test
    fun `pegar o lixo formando conjunto novo com o topo e 2 cartas da mao`() {
        // §5.1 novo conjunto com o topo + 2 cartas da mão; §5.2 demais cartas vão para a mão
        val s = round {
            hand(0, "7S 7D KS")
            discard("4C 9H 7H")
        }.act(0, takeNew("7S 7D"))
        s.table(0).meld(MeldId(0))!!.cards shouldContainExactlyInAnyOrder cards("7S 7D 7H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS 4C 9H")
        s.discardPile.shouldBeEmpty()
        s.phase shouldBe Phase.PLAYING
    }

    @Test
    fun `pegar o lixo usando coringa da mao`() {
        // §5.1 um coringa da mão pode ser usado
        val s = round {
            hand(0, "7S 2C KS")
            discard("7H")
        }
        s.check(0, takeNew("7S 2C")).shouldBeOk()
    }

    @Test
    fun `conjunto novo exige pelo menos 2 cartas da mao`() {
        // §5.1 pelo menos 2 cartas da própria mão
        val s = round {
            hand(0, "7S KS")
            discard("7D 7H")
        }
        s.check(0, takeNew("7S")) shouldFailWith ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS
    }

    @Test
    fun `o topo nao se combina com as demais cartas do lixo`() {
        // §5.1 a carta do topo não pode ser combinada com as demais cartas do lixo
        val s = round {
            hand(0, "7S KS QS")
            discard("7D 7H")
        }
        s.check(0, takeNew("7S 7D")) shouldFailWith ActionError.CARD_NOT_IN_HAND
    }

    @Test
    fun `pegar o lixo acrescentando o topo sozinho a conjunto do lado`() {
        // §5.1 acrescentar o topo a conjunto do seu lado
        val s = round {
            hand(0, "KS QD")
            meld(0, "4H 5H 6H")
            discard("9C 7H")
        }.act(0, takeAdd(0))
        s.table(0).meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H 7H")
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD 9C")
    }

    @Test
    fun `pegar o lixo acrescentando topo e coringa da mao a canastra limpa`() {
        // §5.1 + §6.3 topo 10♥ e coringa da mão entram na canastra limpa 4..9♥, que fica suja
        val s = round {
            hand(0, "2C KS QD")
            meld(0, "4H 5H 6H 7H 8H 9H")
            discard("TH")
        }.act(0, takeAdd(0, "2C"))
        s.table(0).meld(MeldId(0))!!.isDirtyCanasta() shouldBe true
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD")
    }

    @Test
    fun `pegar o lixo formando sequencia com coringa ao lado de canastra limpa`() {
        // §5.1 + §6.4 exceção: 10♥ do topo + J♥ e coringa da mão como conjunto separado
        val s = round {
            hand(0, "JH 2C KS")
            meld(0, "4H 5H 6H 7H 8H 9H")
            discard("TH")
        }.act(0, takeNew("JH 2C"))
        s.table(0).melds.size shouldBe 2
        s.table(0).meld(MeldId(0))!!.isCleanCanasta() shouldBe true
    }

    @Test
    fun `pegar o lixo acrescentando o topo junto com cartas da mao`() {
        // §5.1 ex.: com 4-5-6♥ na mesa e 8♥ no topo, acrescenta 7♥ da mão e o 8♥ juntos
        val s = round {
            hand(0, "7H KS QD")
            meld(0, "4H 5H 6H")
            discard("8H")
        }
        s.check(0, takeAdd(0)) shouldFailWith MeldError.NOT_CONSECUTIVE
        val after = s.act(0, takeAdd(0, "7H"))
        after.table(0).meld(MeldId(0))!!.cards shouldContainExactly cards("4H 5H 6H 7H 8H")
        after.hand(0) shouldContainExactlyInAnyOrder cards("KS QD")
    }

    @Test
    fun `nao se acrescenta o topo a conjunto do outro lado`() {
        // §5.1 conjunto do SEU lado
        val s = round {
            hand(0, "KS QD")
            meld(1, "4H 5H 6H")
            discard("7H")
        }
        s.check(0, takeAdd(0)) shouldFailWith MeldError.MELD_NOT_FOUND
    }

    @Test
    fun `regras de mesa valem ao pegar o lixo`() {
        // §6.4 sequência nova não pode ser continuação de outra do mesmo naipe
        val s = round {
            hand(0, "8H 9H KS")
            meld(0, "4H 5H 6H")
            discard("7H")
        }
        s.check(0, takeNew("8H 9H")) shouldFailWith MeldError.CONTIGUOUS_SEQUENCE
        s.check(0, takeAdd(0, "8H 9H")).shouldBeOk()
    }

    @Test
    fun `pegar o lixo abrindo segundo grupo do mesmo numero`() {
        // §6.4 mais de um grupo do mesmo número é permitido
        val s = round {
            hand(0, "7S 7D KS")
            meld(0, "7C 7C' 7S'")
            discard("7H")
        }.act(0, takeNew("7S 7D"))
        s.table(0).melds.size shouldBe 2
    }

    @Test
    fun `cartas recebidas do lixo podem ser usadas na mesma jogada`() {
        // §5.2 as demais cartas podem ser usadas normalmente na mesma jogada
        val s = round {
            hand(0, "7S 7D KS")
            discard("9C 9D 9H 7H")
        }.act(0, takeNew("7S 7D"))
        val after = s.act(0, create("9C 9D 9H"))
        after.hand(0) shouldContainExactly cards("KS")
    }

    @Test
    fun `pegar o lixo depois de comprar e proibido`() {
        // §4.3 etapa 1: comprar OU pegar o lixo
        val s = round {
            hand(0, "7S 7D KS")
            discard("7H")
        }.act(0, Action.DrawFromStock)
        s.check(0, takeNew("7S 7D")) shouldFailWith ActionError.ALREADY_DREW
    }

    @Test
    fun `comprar do monte depois de pegar o lixo e proibido`() {
        // §4.3 etapa 1: alternativas exclusivas, uma única vez
        val s = round {
            hand(0, "7S 7D KS")
            discard("9C 7H")
        }.act(0, takeNew("7S 7D"))
        s.check(0, Action.DrawFromStock) shouldFailWith ActionError.ALREADY_DREW
    }

    @Test
    fun `3 preto no topo trava o lixo so para o proximo jogador`() {
        // §5.3 / §6.6 3 preto descartado trava o lixo para o próximo jogador
        val s = round {
            hand(0, "3S JS JC QD")
            hand(1, "8C 8D JH")
            stock("4H 5S 6S")
            discard("8H")
            phase = Phase.PLAYING
        }.act(0, discardCard("3S"))
        s.discardPile.last() shouldBe c("3S")
        s.check(1, takeNew("8C 8D")) shouldFailWith ActionError.DISCARD_PILE_LOCKED
        s.legalActions(1).filterIsInstance<Action.TakeDiscardPile>().shouldBeEmpty()

        // o próximo compra do monte e descarta; o jogador seguinte pode pegar, e o 3 preto vai para a mão
        val next = s.act(1, Action.DrawFromStock).act(1, discardCard("JH"))
        next.discardPile shouldContainExactly cards("8H 3S JH")
        val taken = next.act(0, takeNew("JS JC"))
        taken.hand(0) shouldContainExactlyInAnyOrder cards("QD 8H 3S")
    }

    @Test
    fun `coringa no topo acrescentado a conjunto sem coringa leva todo o lixo`() {
        // §5.4 coringa no topo pode ser pego; §5.2 as demais cartas do lixo vão para a mão
        val s = round {
            hand(0, "KS QD")
            meld(0, "4H 5H 6H")
            discard("9C 7H 2C")
        }.act(0, takeAdd(0))
        val meld = s.table(0).meld(MeldId(0))!!
        meld.cards shouldContainExactlyInAnyOrder cards("4H 5H 6H 2C")
        meld.wildState shouldBe WildState.Loose
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD 9C 7H")
        s.discardPile.shouldBeEmpty()
    }

    @Test
    fun `coringa no topo forma conjunto novo com 2 naturais da mao`() {
        // §5.4 + §5.1 conjunto novo: coringa do topo + pelo menos 2 naturais da mão
        val seq = round {
            hand(0, "5H 6H KS")
            discard("4C 2C")
        }.act(0, takeNew("5H 6H"))
        seq.table(0).melds.single().meld.cards shouldContainExactlyInAnyOrder cards("5H 6H 2C")
        seq.hand(0) shouldContainExactlyInAnyOrder cards("KS 4C")
        val group = round {
            hand(0, "KS KD 5H")
            discard("2C")
        }.act(0, takeNew("KS KD"))
        group.table(0).melds.single().meld.cards shouldContainExactlyInAnyOrder cards("KS KD 2C")
    }

    @Test
    fun `coringa no topo suja canastra limpa se o jogador escolher`() {
        // §5.4 + §6.3 o coringa do topo pode entrar na canastra limpa, que passa a ser suja
        val s = round {
            hand(0, "KS QD")
            meld(0, "4H 5H 6H 7H 8H 9H")
            discard("TS 2C")
        }.act(0, takeAdd(0))
        s.table(0).meld(MeldId(0))!!.isDirtyCanasta() shouldBe true
        s.hand(0) shouldContainExactlyInAnyOrder cards("KS QD TS")
    }

    @Test
    fun `coringa no topo ao lado de canastra limpa pode formar conjunto separado ou suja-la`() {
        // §5.4 + §6.4 exceção: 10♥-J♥ da mão com o coringa do topo, separado ou na canastra limpa 4..9♥
        val s = round {
            hand(0, "TH JH KS")
            meld(0, "4H 5H 6H 7H 8H 9H")
            discard("2C")
        }
        val separate = s.act(0, takeNew("TH JH"))
        separate.table(0).melds.size shouldBe 2
        separate.table(0).meld(MeldId(0))!!.isCleanCanasta() shouldBe true
        s.act(0, takeAdd(0, "TH JH")).table(0).meld(MeldId(0))!!.isDirtyCanasta() shouldBe true
    }

    @Test
    fun `coringa no topo respeita as regras de coringa`() {
        // §5.4 + §6.3 máx. 1 coringa por conjunto; §5.1 conjunto novo exige 2 cartas da mão
        val s = round {
            hand(0, "5H 6H 2S KS")
            meld(0, "7S 2D 9S")
            discard("4C 2C")
        }
        s.check(0, takeAdd(0)) shouldFailWith MeldError.TOO_MANY_WILDS
        s.check(0, takeNew("5H 2S")) shouldFailWith MeldError.TOO_MANY_WILDS
        s.check(0, takeNew("5H")) shouldFailWith ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS
    }
}
