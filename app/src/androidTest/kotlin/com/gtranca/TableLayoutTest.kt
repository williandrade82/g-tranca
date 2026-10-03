package com.gtranca

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PublicEvent
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.RuleResult
import com.gtranca.game.GameConfig
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.ui.game.GameUiState
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.game.HandSort
import com.gtranca.ui.game.TableEvents
import com.gtranca.ui.game.TableScreen
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mesa em 360dp de largura: mão grande, duplas, jogos do próprio lado visíveis acima da barra de ações. */
@RunWith(AndroidJUnit4::class)
class TableLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val noEvents = object : TableEvents {
        override fun onCardClick(card: Card) {}
        override fun onClearSelection() {}
        override fun onToggleSort() {}
        override fun onDraw() {}
        override fun onTakeDiscardPile() {}
        override fun onCreateMeld() {}
        override fun onAddToMeld(meldId: MeldId) {}
        override fun onDiscard() {}
        override fun onDeclineDraw() {}
        override fun onConfirmDecline() {}
        override fun onDismissDecline() {}
        override fun onPlanChosen(action: Action.TakeDiscardPile) {}
        override fun onDismissPlanChoice() {}
        override fun onMessageShown() {}
    }

    private val deck = Deck.standard().filterNot { it.isRedThree }

    private fun table(vararg melds: List<Card>): SideTable = melds.fold(SideTable()) { table, cards ->
        (table.createMeld(cards) as RuleResult.Ok).value
    }

    private fun state(mode: GameMode, hand: List<Card>, pile: List<Card>, ownTable: SideTable, otherTable: SideTable): GameUiState {
        val seats = mode.seatCount
        val view = PlayerView(
            mode = mode, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
            phase = Phase.PLAYING, result = null, hand = hand, handSizes = listOf(hand.size) + List(seats - 1) { 9 },
            discardPile = pile, stockSize = 30, mortoStatus = listOf(MortoStatus.Available, MortoStatus.Taken(Side(1))),
            mortoSizes = listOf(11, 0), redThrees = listOf(listOf(Card.parse("3H")), emptyList()),
            tables = listOf(ownTable, otherTable),
        )
        val lastTurn = listOf(
            PublicEvent(Seat(1), Action.DrawFromStock),
            PublicEvent(Seat(1), Action.CreateMeld(listOf(Card.parse("9S"), Card.parse("9C"), Card.parse("9D")))),
            PublicEvent(Seat(1), Action.Discard(Card.parse("KC"))),
        )
        val snapshot = GameSnapshot(
            GameConfig(mode, Difficulty.MEDIO, 3000), Seat(0), view, 1, listOf(0, 0), emptyList(),
            Stage.PLAYING, null, hand.map { Action.Discard(it) }, null,
            List(seats) { if (it == 0) emptyList() else lastTurn },
        )
        return GameUiState(
            snapshot, GameViewModel.sortHand(hand, HandSort.BY_SUIT), emptyList(), emptySet(), HandSort.BY_SUIT,
            null, false, null, false,
        )
    }

    private fun show(state: GameUiState) = rule.setContent {
        GTrancaTheme { Box(Modifier.width(360.dp).height(760.dp)) { TableScreen(state, noEvents) } }
    }

    private fun right(tag: String): Dp = rule.onNodeWithTag(tag).getBoundsInRoot().right
    private fun top(tag: String): Dp = rule.onNodeWithTag(tag).getBoundsInRoot().top
    private fun bottom(tag: String): Dp = rule.onNodeWithTag(tag).getBoundsInRoot().bottom

    @Test
    fun maoDe22CartasCabeEm360dpComAlvosDe48dp() {
        val hand = deck.take(22)
        show(state(GameMode.INDIVIDUAL, hand, deck.drop(22).take(25), SideTable(), SideTable()))

        val handCards = SemanticsMatcher("carta da mão") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("hand-card-") == true
        }
        val nodes = rule.onAllNodes(handCards, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.size == 22) { "22 cartas na árvore, obtidas ${nodes.size}" }
        val density = rule.density
        nodes.forEach { node ->
            val width = with(density) { node.size.width.toDp() }
            val right = with(density) { node.boundsInRoot.right.toDp() }
            check(width >= 48.dp) { "alvo de toque estreito: $width" }
            check(right <= 360.dp) { "carta fora da tela: $right" }
        }
        // Acessibilidade: descrição das cartas em pt-BR.
        rule.onNodeWithContentDescription("Ás de copas", useUnmergedTree = true).assertExists()
    }

    @Test
    fun duplasEm360dpMostraOsTresAssentosEOsJogosDaDuplaAcimaDaBarra() {
        val c = { text: String -> Card.parse(text) }
        val own = table(
            listOf(c("4H"), c("5H"), c("6H"), c("7H"), c("8H"), c("9H"), c("TH")),
            listOf(c("QS"), c("QC"), c("QD")),
            listOf(c("5C"), c("6C"), c("2D")),
        )
        val other = table(listOf(c("9S"), c("9C"), c("9D")), listOf(c("JS"), c("JC"), c("JD"), c("2S")))
        val hand = listOf("4S", "6S", "8S", "TS", "KS", "AS", "4D", "7D", "8D", "KD", "AD", "4C", "7C").map(c)
        show(state(GameMode.DUPLAS, hand, listOf("5S", "6D", "7S", "8C", "TC").map(c), own, other))
        rule.waitForIdle()

        // Os três outros assentos e os títulos por dupla.
        rule.onNodeWithText("Parceiro").assertExists()
        rule.onNodeWithText("Adversário à esquerda").assertExists()
        rule.onNodeWithText("Adversário à direita").assertExists()
        rule.onNodeWithText("Jogos da sua dupla").assertExists()
        rule.onNodeWithText("Jogos da dupla adversária").assertExists()
        rule.onNodeWithText("pego pela dupla adversária", substring = true).assertExists()

        // Na vez do humano, a mesa rola até os jogos da dupla, que ficam inteiros acima da barra de ações.
        rule.onNodeWithTag("meld-own-0").assertIsDisplayed()
        check(bottom("side-own") <= top("action-bar") + 0.5.dp) {
            "jogos da dupla cobertos: ${bottom("side-own")} > ${top("action-bar")}"
        }
        check(bottom("table-scroll") <= top("action-bar") + 0.5.dp) { "área da mesa por baixo da barra" }

        // Sem rolagem horizontal da página: nada passa de 360dp.
        listOf("table-scroll", "action-bar", "hand", "side-own", "side-opponent", "seat-1", "seat-2", "seat-3", "stock").forEach {
            check(right(it) <= 360.dp + 0.5.dp) { "$it passa da largura: ${right(it)}" }
        }

        // Sem seleção, "Baixar" e "Descartar" ficam desabilitados, mas visíveis.
        rule.onNodeWithTag("action-meld").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("action-discard").assertIsDisplayed().assertIsNotEnabled()
    }
}
