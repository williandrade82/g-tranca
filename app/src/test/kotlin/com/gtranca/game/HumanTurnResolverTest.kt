package com.gtranca.game

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.TableMeld
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class HumanTurnResolverTest {

    private fun cards(vararg text: String) = text.map(Card::parse)

    private fun meld(vararg text: String): Meld = (Meld.create(cards(*text)) as RuleResult.Ok).value

    /** Partida individual montada à mão; o assento 0 é o humano. */
    private fun state(
        hand: List<Card>,
        phase: Phase = Phase.PLAYING,
        discardPile: List<Card> = cards("KD"),
        table: SideTable = SideTable(),
    ) = RoundState(
        mode = GameMode.INDIVIDUAL,
        hands = listOf(hand, cards("5C", "6C", "7C", "8D", "9D", "JD", "QD", "KS", "4S", "5S", "6S")),
        stock = cards("4C", "4D", "4H", "5D", "6D"),
        discardPile = discardPile,
        mortos = listOf(cards("9C", "9S", "TC"), cards("TD", "TS", "JC")),
        redThrees = listOf(emptyList(), emptyList()),
        tables = listOf(table, SideTable()),
        firstSeat = Seat(0),
        currentSeat = Seat(0),
        phase = phase,
    )

    private fun resolve(state: RoundState, intent: PlayIntent, selection: List<Card>): Resolution =
        HumanTurnResolver.resolve(intent, selection, RoundEngine.legalActions(state, Seat(0))) { action ->
            (RoundEngine.validate(state, Seat(0), action) as? RuleResult.Failure)?.error
        }

    private val hand = cards("7H", "7H'", "8H", "9H", "QS", "QC", "QS'", "5D", "2C", "AS")

    @Test
    fun `selecao valida vira a acao legal correspondente`() {
        val result = resolve(state(hand), PlayIntent.CreateMeld, cards("9H", "7H", "8H"))
        val action = result.shouldBeInstanceOf<Resolution.Play>().action.shouldBeInstanceOf<Action.CreateMeld>()
        action.cards.classCounts() shouldBe cards("7H", "8H", "9H").classCounts()
        RoundEngine.legalActions(state(hand), Seat(0)) shouldContain action
    }

    @Test
    fun `selecao com a outra copia da carta tambem casa (cartas representativas)`() {
        val result = resolve(state(hand), PlayIntent.CreateMeld, cards("7H'", "8H", "9H"))
        val action = result.shouldBeInstanceOf<Resolution.Play>().action
        // Aplica-se a ação de legal (com a cópia representativa), nunca a montada com a carta física.
        RoundEngine.legalActions(state(hand), Seat(0)) shouldContain action
        RoundEngine.apply(state(hand), Seat(0), action).tables[0].melds.size shouldBe 1
    }

    @Test
    fun `selecao invalida produz o motivo do motor`() {
        // §6.1 naturais que não são do mesmo naipe nem do mesmo número.
        resolve(state(hand), PlayIntent.CreateMeld, cards("7H", "QS", "5D")) shouldBe
            Resolution.Rejected(MeldError.NOT_A_SEQUENCE_OR_GROUP)
        // §6.2 menos de 3 cartas.
        resolve(state(hand), PlayIntent.CreateMeld, cards("7H", "8H")) shouldBe Resolution.Rejected(MeldError.TOO_FEW_CARDS)
        // §4.3 antes de comprar não se baixa.
        resolve(state(hand, Phase.AWAITING_DRAW), PlayIntent.CreateMeld, cards("7H", "8H", "9H")) shouldBe
            Resolution.Rejected(ActionError.MUST_DRAW_FIRST)
    }

    @Test
    fun `descarte exige exatamente uma carta e casa pela classe`() {
        resolve(state(hand), PlayIntent.Discard, cards("7H", "8H")) shouldBe Resolution.SelectOneCardToDiscard
        resolve(state(hand), PlayIntent.Discard, emptyList()) shouldBe Resolution.SelectOneCardToDiscard
        val play = resolve(state(hand), PlayIntent.Discard, cards("QS'")).shouldBeInstanceOf<Resolution.Play>()
        (play.action as Action.Discard).card.cardClass shouldBe Card.parse("QS").cardClass
    }

    @Test
    fun `acrescimo casa com o conjunto indicado`() {
        val table = SideTable(listOf(TableMeld(MeldId(4), meld("4H", "5H", "6H"))), nextMeldId = 5)
        val result = resolve(state(hand, table = table), PlayIntent.AddToMeld(MeldId(4)), cards("7H'"))
        val action = result.shouldBeInstanceOf<Resolution.Play>().action.shouldBeInstanceOf<Action.AddToMeld>()
        action.meldId shouldBe MeldId(4)
        // §6.4 conjunto inexistente.
        resolve(state(hand, table = table), PlayIntent.AddToMeld(MeldId(9)), cards("7H")) shouldBe
            Resolution.Rejected(MeldError.MELD_NOT_FOUND)
    }

    @Test
    fun `pegar o lixo com mais de um plano compativel pede escolha`() {
        // Topo 8H; na mesa 4-5-6H e um grupo de 8. Selecionando 7H, ele pode ir à sequência com o topo (§5.1).
        val table = SideTable(
            listOf(TableMeld(MeldId(0), meld("4H", "5H", "6H")), TableMeld(MeldId(1), meld("8C", "8D", "8S"))),
            nextMeldId = 2,
        )
        val s = state(hand, Phase.AWAITING_DRAW, discardPile = cards("5C", "8H'"), table = table)
        // Sem seleção: nunca executa sozinho; mostra todos os planos legais, do que usa menos cartas da mão ao que usa
        // mais (o topo sozinho no grupo de 8 vem primeiro).
        val all = RoundEngine.legalActions(s, Seat(0)).filterIsInstance<Action.TakeDiscardPile>()
        val choice0 = resolve(s, PlayIntent.TakeDiscardPile, emptyList()).shouldBeInstanceOf<Resolution.ChoosePlan>()
        choice0.options shouldContainExactlyInAnyOrder all
        choice0.options.first() shouldBe Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(1)))
        choice0.options.map { HumanTurnResolver.handCardsOf(it).size } shouldBe choice0.options.map { HumanTurnResolver.handCardsOf(it).size }.sorted()
        // 7H: só o acréscimo à sequência 4-5-6H com o topo (7-8 sozinhos não formam conjunto novo).
        val withSeven = resolve(s, PlayIntent.TakeDiscardPile, cards("7H'"))
        withSeven.shouldBeInstanceOf<Resolution.Play>().action shouldBe
            Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(0), cards("7H")))
        // 8H + 2C: o topo 8H' forma grupo novo de 8 ou entra no grupo de 8 da mesa: o humano escolhe.
        val choice = resolve(s, PlayIntent.TakeDiscardPile, cards("8H", "2C"))
        choice.shouldBeInstanceOf<Resolution.ChoosePlan>().options.map { it.plan } shouldContainExactlyInAnyOrder listOf(
            DiscardPlan.NewMeld(cards("8H", "2C")),
            DiscardPlan.AddToMeld(MeldId(1), cards("8H", "2C")),
        )
    }

    @Test
    fun `lixo travado por 3 preto explica o motivo`() {
        // §5.3 3 preto no topo trava o lixo.
        val s = state(hand, Phase.AWAITING_DRAW, discardPile = cards("QH", "3S"))
        resolve(s, PlayIntent.TakeDiscardPile, cards("QS", "QC")) shouldBe Resolution.Rejected(ActionError.DISCARD_PILE_LOCKED)
    }

    @Test
    fun `destaque vem das jogadas legais e acompanha a selecao`() {
        val legal = RoundEngine.legalActions(state(hand), Seat(0))
        val all = HumanTurnResolver.highlightCounts(legal, emptyList())
        all.keys shouldContain Card.parse("7H").cardClass
        all.keys shouldContain Card.parse("QS").cardClass
        // 5D não forma conjunto com nenhuma carta da mão.
        (Card.parse("5D").cardClass in all) shouldBe false
        // Selecionando QS, só cartas que completam algum conjunto com ela.
        val withQueen = HumanTurnResolver.highlightCounts(legal, cards("QS"))
        (Card.parse("7H").cardClass in withQueen) shouldBe false
        withQueen.keys shouldContain Card.parse("QC").cardClass
    }

    @Test
    fun `destaque por copias - a 2a copia de uma carta selecionada so acende se alguma jogada usa as duas`() {
        // §6.1 8-9-10♥ usa um só 8♥; não há grupo de 8 (só dois 8 e nenhum coringa).
        val hand2 = cards("8H", "8H'", "9H", "TH", "KS", "KC", "5D", "6C", "4S", "AS")
        val legal = RoundEngine.legalActions(state(hand2), Seat(0))
        val sorted = hand2
        HumanTurnResolver.highlightedCards(sorted, legal, cards("8H")) shouldBe cards("9H", "TH").toSet()
        // Sem seleção, também por contagem: um só 8♥ é destacado (o primeiro na ordem da mão).
        HumanTurnResolver.highlightedCards(sorted, legal, emptyList()) shouldBe cards("8H", "9H", "TH").toSet()
        // Com o coringa na mão, o grupo 8♥ 8♥' 2♣ usa as duas cópias: a 2ª acende com a 1ª selecionada.
        val hand3 = hand2 + cards("2C")
        val legal3 = RoundEngine.legalActions(state(hand3), Seat(0))
        HumanTurnResolver.highlightedCards(hand3, legal3, cards("8H")) shouldContain Card.parse("8H'")
    }

    @Test
    fun `§5_1 cenario do usuario - sem selecao pede escolha entre acrescentar ao grupo e sequencia nova com coringa`() {
        // Na mesa, um grupo de 8 que aceita o topo 8♥ sozinho; na mão, 2♣ e 9♥, que com o topo formam 8♥ 9♥ 2♣.
        val hand = cards("2C", "9H", "QS", "QC", "5D", "AS", "KD")
        val table = SideTable(listOf(TableMeld(MeldId(1), meld("8C", "8D", "8S"))), nextMeldId = 2)
        val s = state(hand, Phase.AWAITING_DRAW, discardPile = cards("5C", "8H"), table = table)
        val legal = RoundEngine.legalActions(s, Seat(0))
        val addToGroup = Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(1)))
        val sequence = legal.filterIsInstance<Action.TakeDiscardPile>().single {
            it.plan is DiscardPlan.NewMeld && HumanTurnResolver.handCardsOf(it).classCounts() == cards("2C", "9H").classCounts()
        }
        // Seleção vazia: escolha com os dois planos; nada é aplicado.
        val choice = resolve(s, PlayIntent.TakeDiscardPile, emptyList()).shouldBeInstanceOf<Resolution.ChoosePlan>()
        choice.options shouldContain addToGroup
        choice.options shouldContain sequence
        // Escolher a sequência aplica a sequência.
        val after = RoundEngine.apply(s, Seat(0), sequence)
        after.tables[0].melds.last().meld.cards.classCounts() shouldBe cards("8H", "9H", "2C").classCounts()
        // Com 2♣ e 9♥ selecionados, só a sequência usa exatamente essas cartas: executa direto.
        resolve(s, PlayIntent.TakeDiscardPile, cards("9H", "2C")) shouldBe Resolution.Play(sequence)
    }

    @Test
    fun `§5_1 plano unico no total com selecao vazia ainda pede confirmacao`() {
        // Só o grupo de 8 aceita o topo; nenhuma combinação da mão forma conjunto com ele.
        val hand = cards("QS", "QC", "5D", "AS", "KD", "4S", "7C")
        val table = SideTable(listOf(TableMeld(MeldId(1), meld("8C", "8D", "8S"))), nextMeldId = 2)
        val s = state(hand, Phase.AWAITING_DRAW, discardPile = cards("5C", "8H"), table = table)
        val only = Action.TakeDiscardPile(DiscardPlan.AddToMeld(MeldId(1)))
        RoundEngine.legalActions(s, Seat(0)).filterIsInstance<Action.TakeDiscardPile>() shouldBe listOf(only)
        resolve(s, PlayIntent.TakeDiscardPile, emptyList()) shouldBe Resolution.ChoosePlan(listOf(only))
    }
}

