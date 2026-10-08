package com.gtranca

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.HandPenalty
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideScore
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.TableCards
import com.gtranca.game.GameConfig
import com.gtranca.game.GameSnapshot
import com.gtranca.game.HandOrder
import com.gtranca.game.HandSort
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.Stage
import com.gtranca.ui.game.GameOverScreen
import com.gtranca.ui.game.GameUiState
import com.gtranca.ui.game.RoundAnnouncement
import com.gtranca.ui.game.RoundSummaryScreen
import com.gtranca.ui.game.TableEvents
import com.gtranca.ui.game.TableScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gtranca.engine.model.RuleResult
import com.gtranca.ui.game.CanastaFlash
import com.gtranca.ui.game.CanastaPulseKey
import com.gtranca.ui.theme.GTrancaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Telas de anúncio (§11), de pontos (§12) e a faixa de troca de 3 vermelho de outro assento (§6.5). */
@RunWith(AndroidJUnit4::class)
class EndScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val mode = GameMode.DUPLAS

    private fun view(phase: Phase = Phase.FINISHED, result: RoundResult? = RoundResult.GoOut(Side(1), Seat(1))) = PlayerView(
        mode = mode, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
        phase = phase, result = result, hand = listOf("4S", "7H", "KD").map(Card::parse), handSizes = listOf(3, 0, 5, 4),
        discardPile = emptyList(), stockSize = 20, mortoStatus = listOf(MortoStatus.Available, MortoStatus.Taken(Side(1))),
        mortoSizes = listOf(11, 0), redThrees = listOf(emptyList(), emptyList()), tables = listOf(SideTable(), SideTable()),
    )

    private fun line(count: Int, points: Int) = ScoreLine(count, points)

    /** Partida em que a sua dupla tem 2 três vermelhos e nenhuma canastra (§12.1: −200). */
    private fun record() = RoundRecord(
        number = 1,
        result = RoundResult.GoOut(Side(1), Seat(1)),
        scores = listOf(
            SideScore(
                Side(0), redThrees = line(2, -200), cleanCanastas = ScoreLine.ZERO, dirtyCanastas = ScoreLine.ZERO,
                goOut = 0, mortoNotTaken = -100,
                hand = HandPenalty(ScoreLine.ZERO, line(1, -5), line(3, -24), line(2, -20), line(1, -10)),
                // §12.1 cartas na mesa: 2 três vermelhos (+5) e 3 cartas de 4 a 10 (+8) = +34.
                tableCards = TableCards(line(2, 10), line(3, 24), ScoreLine.ZERO, ScoreLine.ZERO),
            ),
            SideScore(
                Side(1), redThrees = line(1, 100), cleanCanastas = line(1, 200), dirtyCanastas = ScoreLine.ZERO,
                goOut = 100, mortoNotTaken = 0,
                hand = HandPenalty(ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO, ScoreLine.ZERO),
                // Canastra limpa de 6 cartas de 4 a 10 (+48) e o 3 vermelho (+5) = +53.
                tableCards = TableCards(line(1, 5), line(6, 48), ScoreLine.ZERO, ScoreLine.ZERO),
            ),
        ),
    )

    private fun snapshot(stage: Stage = Stage.ROUND_OVER): GameSnapshot {
        val record = record()
        return GameSnapshot(
            GameConfig(mode, Difficulty.MEDIO, 3000), Seat(0), view(), 1,
            listOf(record.scores[0].total, record.scores[1].total), listOf(record), stage, null, emptyList(), null,
            List(4) { emptyList() },
        )
    }

    @Test
    fun pontosMostram3VermelhoSemCanastraESecaoDaMaoComSubtotal() {
        rule.setContent { GTrancaTheme { RoundSummaryScreen(snapshot(), {}, {}) } }
        // §12.1 sem canastra, 3 vermelho vale −100 cada: a célula diz o motivo e a nota explica.
        rule.onNodeWithText("\u2212200 (2)\nsem canastra").assertExists()
        rule.onNodeWithText("Sem canastra na mesa, cada 3 vermelho vale −100 aqui e +5 em “Cartas na mesa”: −95 no total.").assertExists()
        // §12.1 seção "Cartas na mesa" com subtotal, depois dos pontos especiais.
        rule.onNodeWithTag("table-section").assertExists()
        rule.onNodeWithText("Cartas na mesa").assertExists()
        rule.onNodeWithText("Subtotal da mesa").assertExists()
        rule.onNodeWithText("+34").assertExists()
        rule.onNodeWithText("+53").assertExists()
        // As linhas somam o total da partida: −200 −100 +34 −59 = −325; 100 + 200 + 100 + 53 = +453.
        // −325 aparece no total da partida e no acumulado (1ª partida).
        rule.onAllNodesWithText("\u2212325").assertCountEquals(2)
        rule.onNodeWithText("+453").assertExists()
        // §12.2 seção "Cartas na mão" destacada, com subtotal: −5 −24 −20 −10 = −59.
        rule.onNodeWithTag("hand-section").assertExists()
        rule.onNodeWithText("Cartas na mão").assertExists()
        rule.onNode(hasText("Subtotal da mão")).assertExists()
        rule.onNodeWithText("\u221259").assertExists()
        // A tela de pontos não oferece desistir (decisão do usuário; §13.1 fica na mesa e no anúncio).
        rule.onNodeWithTag("action-resign").assertDoesNotExist()
    }

    /** Fim de jogo (§13) com o lado [winner] vencedor. */
    private fun finished(winner: Side): GameSnapshot {
        val record = record()
        return GameSnapshot(
            GameConfig(mode, Difficulty.MEDIO, 3000), Seat(0), view(), 1,
            listOf(record.scores[0].total, record.scores[1].total), listOf(record), Stage.GAME_OVER, winner, emptyList(), null,
            List(4) { emptyList() },
        )
    }

    @Test
    fun fimDeJogoComVitoriaMostraTrofeuEConfete() {
        rule.mainClock.autoAdvance = false
        rule.setContent { GTrancaTheme { GameOverScreen(finished(Side(0)), {}) } }
        rule.onNodeWithTag("game-over-trophy").assertExists()
        rule.onNodeWithTag("end-celebration").assertExists()
    }

    @Test
    fun fimDeJogoComDerrotaTemSoAsCartasCaindoESemTrofeu() {
        rule.mainClock.autoAdvance = false
        rule.setContent { GTrancaTheme { GameOverScreen(finished(Side(1)), {}) } }
        rule.onNodeWithTag("game-over-trophy").assertDoesNotExist()
        rule.onNodeWithTag("end-celebration").assertExists()
    }

    @Test
    fun animacaoDesligadaNaoDesenhaNada() {
        rule.setContent { GTrancaTheme { GameOverScreen(finished(Side(0)), {}, animate = false) } }
        rule.onNodeWithTag("end-celebration").assertDoesNotExist()
        rule.onNodeWithTag("game-over").assertExists()
    }

    @Test
    fun anuncioDaPartidaPerdidaTemAnimacaoCurta() {
        rule.mainClock.autoAdvance = false
        rule.setContent { GTrancaTheme { RoundAnnouncement(snapshot(), {}, onResign = {}) } }
        rule.onNodeWithTag("end-celebration").assertExists()
        rule.onNodeWithTag("announcement-trophy").assertDoesNotExist()
    }

    @Test
    fun anuncioMostraQuemBateuEOResultadoDaDuplaAntesDosPontos() {
        var seePoints = false
        rule.setContent { GTrancaTheme { RoundAnnouncement(snapshot(), { seePoints = true }, onResign = {}) } }
        // §4.2 o assento 1 é o adversário à direita; §11.1 ele bateu.
        rule.onNodeWithText("O adversário à direita venceu essa partida.").assertIsDisplayed()
        rule.onNodeWithText("A dupla adversária venceu a partida.").assertIsDisplayed()
        rule.onNodeWithTag("action-resign").assertIsDisplayed()
        rule.onNodeWithText("Ver pontos").performClick()
        check(seePoints) { "Ver pontos não chamou a ação" }
    }

    private val noEvents = object : TableEvents {
        override fun onCardClick(card: Card) {}
        override fun onClearSelection() {}
        override fun onSortChange(sort: HandSort) {}
        override fun onSpecialColumnToggle() {}
        override fun onDraw() {}
        override fun onTakeDiscardPile() {}
        override fun onDiscardPileClick() {}
        override fun onCreateMeld() {}
        override fun onAddToMeld(meldId: MeldId) {}
        override fun onDiscard() {}
        override fun onDeclineDraw() {}
        override fun onConfirmDecline() {}
        override fun onDismissDecline() {}
        override fun onPlanChosen(action: Action.TakeDiscardPile) {}
        override fun onDismissPlanChoice() {}
        override fun onMessageShown() {}
        override fun onRevealConfirmed() {}
        override fun onResign() {}
        override fun onConfirmResign() {}
        override fun onDismissResign() {}
    }

    @Test
    fun faixaDeTrocaDeOutroAssentoDizQuemECarta() {
        val playing = view(phase = Phase.AWAITING_DRAW, result = null)
        val banner = RedThreeNotice(1, Side(0), Seat(2), listOf(Card.parse("3H")), atTurnStart = true)
        val snapshot = GameSnapshot(
            GameConfig(mode, Difficulty.MEDIO, 3000), Seat(0), playing, 1, listOf(0, 0), emptyList(), Stage.PLAYING,
            null, emptyList(), Seat(2), List(4) { emptyList() },
        )
        val state = GameUiState(
            snapshot = snapshot, hand = HandOrder.sort(playing.hand, HandSort.BY_SUIT), customHand = null,
            selected = emptyList(), highlighted = emptySet(), newCards = emptySet(), sort = HandSort.BY_SUIT,
            redThrees = playing.redThrees, reveal = null, ownSwapPending = false, banner = banner, planChoice = null,
            confirmDecline = false, confirmResign = false, message = null, endScreen = null,
        )
        rule.setContent { GTrancaTheme { Box(Modifier.width(360.dp).height(760.dp)) { TableScreen(state, noEvents) } } }
        // §3.5 quem trocou (o parceiro) e a carta, no início da vez; a reposição dele não é mostrada.
        rule.onNodeWithTag("red-three-banner").assertIsDisplayed()
        rule.onNodeWithText("Parceiro baixou 3♥︎ no início da vez", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pulsoDeCanastraVoltaAZeroQuandoODestaqueExpiraNoMeio() {
        // Canastra limpa da sua dupla (§7) com destaque de "fechou agora".
        val cards = listOf("4H", "5H", "6H", "7H", "8H", "9H").map(Card::parse)
        val table = (SideTable().createMeld(cards) as RuleResult.Ok).value
        val base = view(phase = Phase.PLAYING, result = null).copy(tables = listOf(table, SideTable()))
        val snapshot = GameSnapshot(
            GameConfig(mode, Difficulty.MEDIO, 3000), Seat(0), base, 1, listOf(0, 0), emptyList(), Stage.PLAYING,
            null, emptyList(), Seat(1), List(4) { emptyList() },
        )
        val flash = CanastaFlash(1, side = 0, meldId = table.melds.single().id.value, clean = true)
        val withFlash = GameUiState(
            snapshot = snapshot, hand = HandOrder.sort(base.hand, HandSort.BY_SUIT), customHand = null,
            selected = emptyList(), highlighted = emptySet(), newCards = emptySet(), sort = HandSort.BY_SUIT,
            redThrees = base.redThrees, reveal = null, ownSwapPending = false, banner = null, planChoice = null,
            confirmDecline = false, confirmResign = false, message = null, endScreen = null,
            canastaFlashes = listOf(flash), animationMillis = 400,
        )
        var state by mutableStateOf(withFlash)
        rule.mainClock.autoAdvance = false
        rule.setContent { GTrancaTheme { Box(Modifier.width(360.dp).height(760.dp)) { TableScreen(state, noEvents) } } }
        fun pulse() = rule.onNodeWithTag("meld-own-0").fetchSemanticsNode().config[CanastaPulseKey]
        rule.mainClock.advanceTimeBy(250)
        check(pulse() > 0f) { "o pulso deveria estar subindo: ${pulse()}" }
        // O ViewModel remove o destaque (expirou) com o pulso no meio: ele volta a 0, não fica preso.
        state = withFlash.copy(canastaFlashes = emptyList())
        rule.mainClock.advanceTimeBy(100)
        check(pulse() == 0f) { "o pulso ficou preso em ${pulse()}" }
    }
}

