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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
import com.gtranca.engine.PublicEvent
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
import com.gtranca.game.HandOrder
import com.gtranca.game.HandSort
import com.gtranca.game.Stage
import com.gtranca.ui.game.GameUiState
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
        override fun onSortChange(sort: HandSort) {}
        override fun onSpecialColumnToggle() {}
        override fun onDiscardPileClick() {}
        override fun onRevealConfirmed() {}
        override fun onResign() {}
        override fun onConfirmResign() {}
        override fun onDismissResign() {}
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
        override fun onMeldChosen(action: com.gtranca.engine.Action) {}
        override fun onDismissMeldChoice() {}
        override fun onMessageShown() {}
    }

    private val deck = Deck.standard().filterNot { it.isRedThree }

    private fun table(vararg melds: List<Card>): SideTable = melds.fold(SideTable()) { table, cards ->
        (table.createMeld(cards) as RuleResult.Ok).value
    }

    private fun state(
        mode: GameMode,
        hand: List<Card>,
        pile: List<Card>,
        ownTable: SideTable,
        otherTable: SideTable,
        phase: Phase = Phase.PLAYING,
        sort: HandSort = HandSort.BY_SUIT,
        separateSpecial: Boolean = false,
    ): GameUiState {
        val seats = mode.seatCount
        val view = PlayerView(
            mode = mode, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
            phase = phase, result = null, hand = hand, handSizes = listOf(hand.size) + List(seats - 1) { 9 },
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
            Stage.PLAYING, null,
            if (phase == Phase.PLAYING) hand.map { Action.Discard(it) } else listOf(Action.DrawFromStock), null,
            List(seats) { if (it == 0) emptyList() else lastTurn },
        )
        return GameUiState(
            snapshot = snapshot,
            hand = HandOrder.split(hand, sort).takeIf { separateSpecial }?.all ?: HandOrder.sort(hand, sort),
            customHand = if (separateSpecial) HandOrder.split(hand, sort) else null,
            selected = emptyList(),
            highlighted = emptySet(),
            newCards = emptySet(),
            sort = sort,
            redThrees = view.redThrees,
            reveal = null,
            ownSwapPending = false,
            banner = null,
            planChoice = null,
            confirmDecline = false,
            confirmResign = false,
            message = null,
            endScreen = null,
        )
    }

    private fun show(state: GameUiState, width: Dp = 360.dp, height: Dp = 760.dp, fontScale: Float = 1f) = rule.setContent {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
        ) {
            GTrancaTheme { Box(Modifier.width(width).height(height)) { TableScreen(state, noEvents) } }
        }
    }

    /**
     * Matriz de telas pequenas (larguras 320/360/411 dp, altura 640 dp, fonte 1,0/1,3/1,5): os botões da barra de ações
     * cabem numa linha com alvo de 48 dp, o texto da vez fica numa linha, e o lixo e os jogos aparecem inteiros quando
     * rolados para a vista (a mesa cresce com o conteúdo, nunca corta cartas).
     */
    @Test
    fun telasPequenasEFonteGrandeNaoCortamCartasNemBotoes() {
        val c = { text: String -> Card.parse(text) }
        val own = table(listOf(c("4H"), c("5H"), c("6H"), c("7H"), c("8H"), c("9H"), c("TH")), listOf(c("QS"), c("QC"), c("QD")))
        val other = table(listOf(c("9S"), c("9C"), c("9D")), listOf(c("JS"), c("JC"), c("JD"), c("2S")))
        val hand = listOf("4S", "6S", "8S", "TS", "KS", "AS", "4D", "7D", "8D", "KD", "AD", "4C", "7C").map(c)
        val ui = state(GameMode.DUPLAS, hand, listOf("5S", "6D", "7S").map(c), own, other, phase = Phase.AWAITING_DRAW)
        // Um só setContent: o tamanho e a fonte mudam por estado.
        var size by androidx.compose.runtime.mutableStateOf(360.dp to 1f)
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, size.second),
            ) {
                GTrancaTheme { Box(Modifier.width(size.first).height(640.dp)) { TableScreen(ui, noEvents) } }
            }
        }
        for (width in listOf(320.dp, 360.dp, 411.dp)) for (font in listOf(1f, 1.3f, 1.5f)) {
            val label = "$width × 640dp, fonte $font"
            size = width to font
            rule.waitForIdle()
            listOf("action-draw", "action-take-discard", "action-clear").forEach { tag ->
                val b = rule.onNodeWithTag(tag).getBoundsInRoot()
                check(b.right <= width + 0.5.dp) { "$tag fora da largura ($label)" }
                check(b.bottom - b.top >= 47.5.dp) { "$tag com alvo menor que 48 dp ($label)" }
            }
            check(top("action-draw") == top("action-clear")) { "Limpar caiu para outra linha ($label)" }
            listOf("discard-pile", "side-own", "side-opponent").forEach { tag ->
                rule.onNodeWithTag(tag).performScrollTo()
                rule.waitForIdle()
                check(top(tag) >= top("table-scroll") - 0.5.dp && bottom(tag) <= bottom("table-scroll") + 0.5.dp) {
                    "$tag cortado na área da mesa ($label)"
                }
            }
            rule.onNodeWithTag("seat-partner-label", useUnmergedTree = true).assertExists()
        }
    }

    private fun right(tag: String): Dp = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().right
    private fun left(tag: String): Dp = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().left
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
        // Início da vez (etapa de compra): a mesa rola até o fim. 3 preto no topo: lixo travado (§5.3).
        show(state(GameMode.DUPLAS, hand, listOf("5S", "6D", "7S", "8C", "3C").map(c), own, other, phase = Phase.AWAITING_DRAW))
        rule.waitForIdle()

        // Os três outros assentos e os títulos por dupla.
        rule.onNodeWithText("Parceiro").assertExists()
        rule.onNodeWithText("Adv. esq.").assertExists()
        rule.onNodeWithText("Adv. dir.").assertExists()
        // Mãos dos outros assentos como cartas viradas, dentro da largura (9 cartas cada).
        listOf(1, 2, 3).forEach { check(right("seat-hand-$it") <= 360.dp + 0.5.dp) { "mão do assento $it passa da largura" } }
        rule.onAllNodesWithContentDescription("9 cartas na mão", useUnmergedTree = true).assertCountEquals(3)
        // Disposição vista da mesa, na mesma linha: adversário à esquerda (assento 3), parceiro (2) e adversário à direita (1).
        check(left("seat-3") < left("seat-2") && left("seat-2") < left("seat-1")) { "ordem esquerda, parceiro, direita" }
        check(top("seat-3") == top("seat-2") && top("seat-2") == top("seat-1")) { "assentos fora da mesma linha em 360dp" }
        // Jogadores acima do feltro (rolam junto com a mesa em telas baixas).
        listOf(1, 2, 3).forEach { check(bottom("seat-$it") <= top("table-felt") + 0.5.dp) { "assento $it por cima do feltro" } }
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
        listOf("table-scroll", "action-bar", "hand", "side-own", "side-opponent", "seat-1", "seat-2", "seat-3", "stock", "mortos").forEach {
            check(right(it) <= 360.dp + 0.5.dp) { "$it passa da largura: ${right(it)}" }
        }
        // Monte afastado dos mortos; os dois mortos juntos.
        check(left("morto-0") - right("stock") >= 12.dp) { "monte colado nos mortos" }
        check(left("morto-1") - right("morto-0") <= 12.dp) { "mortos separados demais" }

        // §5.3 sem plano de pegar o lixo em legalActions, "Pegar lixo" fica desabilitado, mas visível.
        rule.onNodeWithTag("action-take-discard").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("action-draw").assertIsDisplayed()
    }

    @Test
    fun tocarNoPlacarAbreAsInformacoesDoJogo() {
        show(state(GameMode.DUPLAS, deck.take(11), emptyList(), SideTable(), SideTable()), fontScale = 1.3f)
        rule.onNodeWithTag("match-info-open").performClick()
        rule.onNodeWithTag("match-info").assertIsDisplayed()
        rule.onNodeWithText("Nenhuma partida terminou ainda.").assertExists()
        rule.onNodeWithTag("match-info-close").performClick()
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("match-info")).assertCountEquals(0)
    }

    @Test
    fun ordemPersonalizadaPoe3PretosECoringasNumaColunaAEsquerda() {
        val c = { text: String -> Card.parse(text) }
        // 5 especiais (3 pretos e coringas) e 9 outras cartas.
        val hand = listOf("3S", "3C", "2H", "2D", "2S'", "7H", "8H", "9H", "KS", "KC", "4D", "6C", "JD", "AS").map(c)
        show(state(GameMode.INDIVIDUAL, hand, emptyList(), SideTable(), SideTable(), separateSpecial = true))
        rule.waitForIdle()
        val special = rule.onNodeWithTag("hand-special", useUnmergedTree = true).getBoundsInRoot()
        // Mais especiais do que linhas: a coluna ganha mais uma carta de largura (2 × 49dp).
        check(special.right - special.left >= 96.dp) { "coluna estreita: ${special.right - special.left}" }
        listOf("3S", "3C", "2H", "2D", "2S'").forEach {
            check(right("hand-card-$it") <= special.right + 0.5.dp) { "$it fora da coluna" }
        }
        listOf("7H", "KS", "AS", "4D").forEach {
            check(left("hand-card-$it") >= special.right) { "$it dentro da coluna" }
            check(right("hand-card-$it") <= 360.dp + 0.5.dp) { "$it fora da tela" }
        }
        // Botões de ordem com nome acessível; a separação está ligada.
        rule.onNodeWithTag("sort-custom").assertIsOn()
        rule.onNodeWithTag("sort-by_rank").assertIsNotSelected()
    }
}
