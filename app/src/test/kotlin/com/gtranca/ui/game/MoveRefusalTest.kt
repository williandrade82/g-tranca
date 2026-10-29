package com.gtranca.ui.game

import com.gtranca.R
import com.gtranca.engine.Action
import com.gtranca.engine.GroupRanks
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.TableMeld
import com.gtranca.engine.viewFor
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Conteúdo do modal de jogada não permitida: montagem pura, uma verificação por situação. */
class MoveRefusalTest {

    private fun cards(vararg text: String) = text.map(Card::parse)

    private fun meld(vararg text: String): Meld = (Meld.create(cards(*text)) as RuleResult.Ok).value

    /** Canastra limpa de 7 cartas de copas. */
    private val cleanCanasta get() = meld("4H", "5H", "6H", "7H", "8H", "9H", "TH")

    /** Canastra suja (6 copas e um coringa). */
    private val dirtyCanasta get() = meld("4H", "5H", "6H", "7H", "8H", "9H", "2C")

    /** Vista do assento 0 (humano) com o morto do lado 0 já pego, para ficar sem cartas depender da canastra. */
    private fun view(
        rules: RuleSet,
        ownMelds: List<Meld> = emptyList(),
        hand: List<Card> = cards("9S"),
        mortoTaken: Boolean = true,
        mortoAvailable: Boolean = false,
    ): PlayerView {
        val table = SideTable(ownMelds.mapIndexed { i, m -> TableMeld(MeldId(i), m) }, ownMelds.size)
        val status = listOf(
            if (mortoTaken) MortoStatus.Taken(Side(0), Seat(0)) else MortoStatus.Available,
            if (mortoAvailable) MortoStatus.Available else MortoStatus.Taken(Side(1), Seat(1)),
        )
        val state = RoundState(
            mode = GameMode.INDIVIDUAL,
            hands = listOf(hand, cards("5C", "6C", "7C", "8D", "9D", "JD", "QD", "KS", "4S", "5S", "6S")),
            stock = cards("4C", "4D", "4H", "5D", "6D"),
            discardPile = cards("KD"),
            mortos = status.map { s -> if (s is MortoStatus.Available) cards("9C", "9S", "TC") else emptyList() },
            redThrees = listOf(emptyList(), emptyList()),
            tables = listOf(table, SideTable()),
            firstSeat = Seat(0),
            currentSeat = Seat(0),
            phase = Phase.PLAYING,
            mortoStatus = status,
        )
        return state.viewFor(Seat(0), rules)
    }

    private val paulista = RuleSet.PAULISTA
    private val classica = RuleSet.CLASSICA

    private fun RefusalContent.ruleRes() = rule.map { it.res }
    private fun RefusalContent.tipRes() = tip.map { it.res }

    // §11.1 / §14.2 goOutRequiresCleanCanasta: Tranca Paulista exige canastra limpa para bater
    @Test
    fun `paulista sem canastra explica que bater exige canastra limpa e cita a mesa`() {
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(paulista))
        content.title.res shouldBe R.string.refusal_title_go_out
        content.happened.res shouldBe R.string.refusal_happened_empty_none
        val goOut = content.rule.first { it.res == R.string.refusal_rule_go_out_clean }
        goOut.args shouldBe listOf(RuleLabel.Named("Tranca Paulista"), 7)
        content.ruleRes() shouldContain R.string.refusal_rule_morto_taken
        content.tipRes() shouldContain R.string.refusal_tip_go_out_none
    }

    // §11.1 canastra suja não permite bater quando a mesa exige limpa
    @Test
    fun `paulista so com canastra suja diz que a suja nao vale`() {
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(paulista, listOf(dirtyCanasta)))
        content.happened.res shouldBe R.string.refusal_happened_empty_dirty
        content.ruleRes() shouldContain R.string.refusal_rule_go_out_clean
        content.tipRes() shouldBe listOf(R.string.refusal_tip_go_out_dirty)
    }

    // §11.1 Clássica: qualquer canastra serve
    @Test
    fun `classica sem canastra pede qualquer canastra`() {
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(classica))
        content.rule.first { it.res == R.string.refusal_rule_go_out_any }.args shouldBe
            listOf(RuleLabel.Named("Clássica"), 7)
        content.ruleRes() shouldNotContain R.string.refusal_rule_go_out_clean
    }

    // §11.1 / §7.2 sem coringas no jogo não existe canastra suja e a exigência de limpa não se aplica
    @Test
    fun `sem coringas a exigencia de limpa nao se aplica`() {
        val noWilds = paulista.copy(maxWildsPerMeld = 0)
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(noWilds))
        content.ruleRes() shouldContain R.string.refusal_rule_go_out_any
    }

    // §9.5 sem morto disponível o lado bate sem ter pego o morto, mas só com a canastra
    @Test
    fun `sem morto disponivel lembra que so a canastra libera a batida`() {
        val content = MoveRefusal.explain(
            ActionError.NO_MORTO_AVAILABLE,
            view(classica, mortoTaken = false, mortoAvailable = false),
        )
        content.title.res shouldBe R.string.refusal_title_go_out
        content.ruleRes() shouldContain R.string.refusal_rule_no_morto
        content.ruleRes() shouldNotContain R.string.refusal_rule_morto_taken
    }

    // §8 manter cartas para descartar
    @Test
    fun `sobrar uma so carta explica a regra das duas cartas`() {
        val content = MoveRefusal.explain(ActionError.MUST_KEEP_CARD_TO_DISCARD, view(paulista, hand = cards("9S", "KS", "5S")))
        content.title.res shouldBe R.string.refusal_title_keep_card
        content.happened.res shouldBe R.string.refusal_happened_keep_none
        content.ruleRes() shouldContain R.string.refusal_rule_keep_two
        content.ruleRes() shouldContain R.string.refusal_rule_go_out_clean
    }

    @Test
    fun `sobrar uma carta com canastra suja numa mesa que exige limpa`() {
        val content = MoveRefusal.explain(ActionError.MUST_KEEP_CARD_TO_DISCARD, view(paulista, listOf(dirtyCanasta)))
        content.happened.res shouldBe R.string.refusal_happened_keep_dirty
    }

    // §7.1 quantas cartas faltam para o maior jogo virar canastra (informação pública: os jogos do próprio lado)
    @Test
    fun `mostra quantas cartas faltam ao maior jogo`() {
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(classica, listOf(meld("4H", "5H", "6H"))))
        content.tip.first { it.res == R.string.refusal_tip_largest }.args shouldBe listOf(3, 4)
    }

    @Test
    fun `sem nenhum jogo nao mostra a dica de cartas que faltam`() {
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(classica))
        content.tipRes() shouldNotContain R.string.refusal_tip_largest
    }

    // §14.3 regra personalizada aparece como "Personalizada"
    @Test
    fun `regra personalizada usa o rotulo Personalizada`() {
        val custom = RuleSet.CLASSICA.duplicate("custom", "Minha mesa")
        val content = MoveRefusal.explain(ActionError.NO_CANASTA_TO_GO_OUT, view(custom))
        content.rule.first { it.res == R.string.refusal_rule_go_out_any }.args.first() shouldBe RuleLabel.Custom
    }

    // §5.3 3 preto no topo trava o lixo
    @Test
    fun `lixo travado`() {
        val content = MoveRefusal.explain(ActionError.DISCARD_PILE_LOCKED, view(classica))
        content.title.res shouldBe R.string.refusal_title_pile_locked
        content.ruleRes() shouldContain R.string.refusal_rule_pile_locked
        content.tipRes() shouldContain R.string.refusal_tip_buy_stock
    }

    // §5.5 lixo vazio
    @Test
    fun `lixo vazio`() {
        MoveRefusal.explain(ActionError.DISCARD_PILE_EMPTY, view(classica)).title.res shouldBe R.string.refusal_title_pile_empty
    }

    // §5.1 conjunto novo com o topo exige 2 cartas da mão; §6.1 grupos restritos valem também aqui
    @Test
    fun `pegar o lixo sem cartas suficientes cita grupos restritos da mesa`() {
        val content = MoveRefusal.explain(ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS, view(paulista))
        content.ruleRes() shouldBe listOf(R.string.refusal_rule_pile_top, R.string.refusal_rule_group_face)
        MoveRefusal.explain(ActionError.DISCARD_TOP_NEEDS_TWO_HAND_CARDS, view(classica)).ruleRes() shouldBe
            listOf(R.string.refusal_rule_pile_top)
    }

    // §4.3 comprar/pegar o lixo uma vez, no início
    @Test
    fun `comprar fora de hora`() {
        MoveRefusal.explain(ActionError.MUST_DRAW_FIRST, view(classica)).title.res shouldBe R.string.refusal_title_must_draw
        MoveRefusal.explain(ActionError.ALREADY_DREW, view(classica)).title.res shouldBe R.string.refusal_title_already_drew
    }

    // §10 / §14.2 mortoBecomesStock
    @Test
    fun `monte acabado cita se o morto vira monte nesta mesa`() {
        MoveRefusal.explain(ActionError.STOCK_EXHAUSTED, view(classica)).ruleRes() shouldContain
            R.string.refusal_rule_stock_morto_gone
        val paulistaContent = MoveRefusal.explain(ActionError.STOCK_EXHAUSTED, view(paulista))
        paulistaContent.rule.single().res shouldBe R.string.refusal_rule_stock_morto_never
        paulistaContent.rule.single().args shouldBe listOf(RuleLabel.Named("Tranca Paulista"))
    }

    // §10.2
    @Test
    fun `recusar a compra fora de hora`() {
        MoveRefusal.explain(ActionError.DECLINE_NOT_ALLOWED, view(classica)).ruleRes() shouldBe listOf(R.string.refusal_rule_decline)
    }

    // §6.5 / §8
    @Test
    fun `descartar 3 vermelho`() {
        MoveRefusal.explain(ActionError.CANNOT_DISCARD_RED_THREE, view(classica)).title.res shouldBe
            R.string.refusal_title_red_three
    }

    // §6.2 mínimo de cartas por conjunto
    @Test
    fun `poucas cartas cita o minimo da mesa`() {
        val min4 = classica.copy(minMeldSize = 4)
        val content = MoveRefusal.explain(MeldError.TOO_FEW_CARDS, view(min4))
        content.rule.single().args shouldBe listOf(RuleLabel.Named("Clássica"), 4)
    }

    // §6.1 / §14.2 groupRanks
    @Test
    fun `numero que nao forma grupo cita a restricao da mesa`() {
        MoveRefusal.explain(MeldError.GROUP_RANK_NOT_ALLOWED, view(paulista)).rule.single().res shouldBe
            R.string.refusal_rule_group_face
        val none = classica.copy(groupRanks = GroupRanks.NONE)
        MoveRefusal.explain(MeldError.GROUP_RANK_NOT_ALLOWED, view(none)).rule.single().res shouldBe
            R.string.refusal_rule_group_none
        MoveRefusal.explain(MeldError.GROUP_RANK_NOT_ALLOWED, view(classica)).rule.single().res shouldBe
            R.string.refusal_rule_group_rank_generic
    }

    // §6.3 / §14.2 maxWildsPerMeld
    @Test
    fun `coringa demais e coringa proibido`() {
        MoveRefusal.explain(MeldError.TOO_MANY_WILDS, view(classica)).rule.single().res shouldBe R.string.refusal_rule_one_wild
        MoveRefusal.explain(MeldError.TOO_MANY_WILDS, view(classica.copy(maxWildsPerMeld = 0))).rule.single().res shouldBe
            R.string.refusal_rule_no_wilds
        MoveRefusal.explain(MeldError.WILDS_NOT_ALLOWED, view(classica)).title.res shouldBe R.string.refusal_title_no_wilds
    }

    // §6.2 / §6.5 / §6.6 nenhum 3 em conjuntos
    @Test
    fun `tres em conjunto`() {
        MoveRefusal.explain(MeldError.CONTAINS_THREE, view(classica)).title.res shouldBe R.string.refusal_title_three
    }

    // §6.4 sequência nova que continua outra
    @Test
    fun `sequencia que continua outra`() {
        MoveRefusal.explain(MeldError.CONTIGUOUS_SEQUENCE, view(classica)).tipRes() shouldBe
            listOf(R.string.refusal_tip_add_existing)
    }

    // §8 exatamente um descarte
    @Test
    fun `descartar sem escolher exatamente uma carta`() {
        val content = MoveRefusal.explain(UiMessage.SelectOneCardToDiscard, view(classica)).shouldNotBeNull()
        content.title.res shouldBe R.string.refusal_title_select_one
        content.happened.res shouldBe R.string.msg_select_one_to_discard
    }

    @Test
    fun `motivo desconhecido gera conteudo generico`() {
        val content = MoveRefusal.explain(UiMessage.Rejected(null), view(classica)).shouldNotBeNull()
        content.title.res shouldBe R.string.refusal_title_generic
        content.happened.res shouldBe R.string.msg_invalid_generic
    }

    @Test
    fun `mostrar as cartas no multijogador nao e recusa de regra`() {
        MoveRefusal.explain(UiMessage.ShowCardsToPlay, view(classica)).shouldBeNull()
    }

    // Cobertura: todo motivo do motor tem título, texto do que aconteceu e, nos casos de regra, o nome da mesa.
    @Test
    fun `todo motivo de recusa do motor tem explicacao`() {
        val goOutErrors = listOf(ActionError.NO_MORTO_AVAILABLE, ActionError.NO_CANASTA_TO_GO_OUT, ActionError.MUST_KEEP_CARD_TO_DISCARD)
        val errors = ActionError.entries + MeldError.entries
        for (error in errors) {
            for (rules in listOf(classica, paulista)) {
                val content = MoveRefusal.explain(UiMessage.Rejected(error), view(rules)).shouldNotBeNull()
                (content.title.res != 0) shouldBe true
                if (error !in goOutErrors) content.happened.res shouldBe error.messageRes()
            }
        }
    }

    // Ponta a ponta: o motivo vem do motor (Paulista, mão de 1 carta, só canastra suja) e a explicação cita a mesa.
    @Test
    fun `descartar a ultima carta na paulista com canastra suja usa o motivo real do motor`() {
        val state = RoundState(
            mode = GameMode.INDIVIDUAL,
            hands = listOf(cards("9S"), cards("5C", "6C", "7C")),
            stock = cards("4C", "4D"),
            discardPile = cards("KD"),
            mortos = listOf(emptyList(), emptyList()),
            redThrees = listOf(emptyList(), emptyList()),
            tables = listOf(SideTable(listOf(TableMeld(MeldId(0), dirtyCanasta)), 1), SideTable()),
            firstSeat = Seat(0),
            currentSeat = Seat(0),
            phase = Phase.PLAYING,
            mortoStatus = listOf(MortoStatus.Taken(Side(0), Seat(0)), MortoStatus.Taken(Side(1), Seat(1))),
        )
        val failure = RoundEngine.validate(state, Seat(0), Action.Discard(Card.parse("9S")), paulista)
        failure.shouldBeInstanceOf<RuleResult.Failure>()
        failure.error shouldBe ActionError.NO_CANASTA_TO_GO_OUT
        val content = MoveRefusal.explain(UiMessage.Rejected(failure.error), state.viewFor(Seat(0), paulista)).shouldNotBeNull()
        content.happened.res shouldBe R.string.refusal_happened_empty_dirty
        content.rule.first { it.res == R.string.refusal_rule_go_out_clean }.args.first() shouldBe
            RuleLabel.Named("Tranca Paulista")
    }
}
