package com.gtranca

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
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

/** Mesa em 360dp de largura com mão grande (22 cartas, como depois de pegar um lixo grande). */
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

    @Test
    fun maoDe22CartasCabeEm360dpComAlvosDe48dp() {
        val deck = Deck.standard().filterNot { it.isRedThree }
        val hand = deck.take(22)
        val pile = deck.drop(22).take(25)
        val view = PlayerView(
            mode = GameMode.INDIVIDUAL, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
            phase = Phase.PLAYING, result = null, hand = hand, handSizes = listOf(22, 9), discardPile = pile,
            stockSize = 30, mortoStatus = listOf(MortoStatus.Available, MortoStatus.Taken(Side(1))),
            mortoSizes = listOf(11, 0), redThrees = listOf(emptyList(), emptyList()), tables = listOf(SideTable(), SideTable()),
        )
        val snapshot = GameSnapshot(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.MEDIO, 3000), Seat(0), view, 1, listOf(0, 0), emptyList(),
            Stage.PLAYING, null, hand.map { Action.Discard(it) }, null, listOf(emptyList(), emptyList()),
        )
        val state = GameUiState(
            snapshot, GameViewModel.sortHand(hand, HandSort.BY_SUIT), emptyList(), emptySet(), HandSort.BY_SUIT,
            null, false, null, false,
        )
        rule.setContent {
            GTrancaTheme { Box(Modifier.width(360.dp).height(760.dp)) { TableScreen(state, noEvents) } }
        }

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
}
