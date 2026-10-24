package com.gtranca.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Color
import com.gtranca.ui.theme.GoldBrush
import com.gtranca.ui.theme.TabularNums
import androidx.compose.ui.Alignment
import com.gtranca.game.Persona
import com.gtranca.ui.persona.AvatarRow
import com.gtranca.ui.persona.leaderPoses
import com.gtranca.ui.persona.personasOfSide
import com.gtranca.ui.persona.sideLabel

import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GBanner
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GPanel
import com.gtranca.ui.theme.GSubPanel
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.cards.frameColor
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.rotate

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import androidx.annotation.StringRes
import com.gtranca.game.SeatRole
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.SideScore
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit
import androidx.compose.ui.semantics.contentDescription


/**
 * Fim de partida: detalhamento §12 por lado, a partir de [SideScore] do histórico. Sem a opção de desistir (decisão do
 * usuário, §13.1): a desistência fica na mesa, na encenação e no anúncio do resultado.
 */
@Composable
fun RoundSummaryScreen(
    snapshot: GameSnapshot,
    onNextRound: () -> Unit,
    onContinue: () -> Unit,
    /** §14.1 perfis por assento (vazio = sem avatares). */
    personas: List<Persona> = emptyList(),
) {
    val record = snapshot.history.last()
    val view = snapshot.view
    val sides = listOf(view.side) + view.mode.sides.filter { it != view.side }
    val scores = sides.map { side -> record.scores.first { it.side == side } }
    val resultText = when (val result = record.result) {
        RoundResult.NoWinner -> stringResource(R.string.round_result_no_winner)
        // §11.1 quem bateu, pelo assento (em duplas: você, parceiro ou um dos adversários).
        is RoundResult.GoOut -> stringResource(SeatRole.of(view.mode, result.seat, snapshot.viewerSeat).wentOutRes())
    }
    val role = (record.result as? RoundResult.GoOut)?.let { SeatRole.of(view.mode, it.seat, snapshot.viewerSeat) }
    val (bannerColor, onBanner) = when {
        role == null -> GColors.GoldDeep to GColors.Ivory
        role == SeatRole.YOU || role == SeatRole.PARTNER -> GColors.RoyalEnd to GColors.Ivory
        else -> GColors.Bordeaux to GColors.Ivory
    }
    // §14.5 o robô aparece feliz se o lado dele lidera o placar acumulado e frustrado se está perdendo; o jogador, sempre como é
    val poses = leaderPoses(snapshot.totals, sides)
    GBackground(Modifier.fillMaxSize().testTag("round-summary"), royal = true) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GTitle(stringResource(R.string.round_over_title, record.number))
            GBanner(resultText, bannerColor, onBanner)

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = Spacing.sm)) {
            GPanel(Modifier.fillMaxWidth()) {
            // Cabeçalho com o avatar de cada lado (§14.1) e o nome: no individual o da persona; em duplas, "Nós" e "Eles".
            // Cada lado ocupa duas colunas (quantidade e pontos); o título leva o avatar, o ícone de 3 cartas (cor do baralho
            // do jogador no seu lado, cinza-claro no adversário) e o nome do lado.
            val ownDeck = com.gtranca.ui.cards.LocalDeckColor.current
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.score_item), Modifier.weight(LABEL_WEIGHT).align(Alignment.Bottom), fontWeight = FontWeight.Bold)
                Box(Modifier.weight(VALUE_WEIGHT))
                sides.forEach { side ->
                    Column(Modifier.weight(COUNT_WEIGHT + POINTS_WEIGHT), horizontalAlignment = Alignment.End) {
                        AvatarRow(personasOfSide(view.mode, side, personas), 26.dp, pose = poses.getValue(side))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ThreeCardsIcon(if (side == view.side) ownDeck.frameColor() else OpponentCards, Modifier.testTag("score-cards-icon-${side.index}"))
                            com.gtranca.ui.theme.FitText(
                                sideLabel(view.mode, side, view.side, personas),
                                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = GColors.Champagne, textAlign = TextAlign.End),
                                minFontSp = 10,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(LABEL_WEIGHT))
                Text(stringResource(R.string.score_col_value), Modifier.weight(VALUE_WEIGHT), style = MaterialTheme.typography.labelSmall, color = GColors.Steel, textAlign = TextAlign.End)
                sides.forEach { _ ->
                    Text(stringResource(R.string.score_col_count), Modifier.weight(COUNT_WEIGHT), style = MaterialTheme.typography.labelSmall, color = GColors.Steel, textAlign = TextAlign.End)
                    Text(stringResource(R.string.score_col_points), Modifier.weight(POINTS_WEIGHT), style = MaterialTheme.typography.labelSmall, color = GColors.Steel, textAlign = TextAlign.End)
                }
            }
            HorizontalDivider(color = GColors.Divider, thickness = 2.dp)
            // Tudo vem de ScoreBreakdown: a soma das linhas exibidas é o "Total da partida" de cada lado.
            val lines = scores.map { ScoreBreakdown.lines(it) }
            // §12.1 pontos especiais e §12.2 morto não pego.
            ScoreItem.entries.filter { it.section == ScoreSection.SPECIAL }.forEach { item ->
                val noCanasta = stringResource(R.string.score_red_threes_no_canasta)
                ScoreRow(
                    stringResource(item.labelRes()),
                    examples = item.examples(),
                    value = item.unitValue(LocalTableRules.current),
                    values = lines.mapIndexed { i, side ->
                        val line = side.getValue(item)
                        // §12.1 sem canastra na mesa, 3 vermelho vale −100: a célula diz o motivo.
                        line.cell(item, sides[i].index, note = noCanasta.takeIf { item == ScoreItem.RED_THREES && line.points < 0 })
                    },
                    tag = if (item == ScoreItem.RED_THREES) "red-threes-row" else null,
                )
            }
            // §12.1 cartas na mesa e §12.2 cartas na mão: seções destacadas, com subtotal.
            ScoreSectionBox(ScoreSection.TABLE, scores, lines)
            ScoreSectionBox(ScoreSection.HAND, scores, lines)
            HorizontalDivider(color = GColors.Divider, thickness = 2.dp)
            ScoreRow(stringResource(R.string.score_round_total), scores.map { ScoreCell(null, signed(it.total)) }, bold = true, pill = true)
            ScoreRow(
                stringResource(R.string.score_cumulative),
                sides.map { ScoreCell(null, plainPoints(snapshot.totals[it.index])) },
                bold = true,
                tag = "cumulative",
                pill = true,
            )
            }
            }

            val tie = snapshot.stage == Stage.ROUND_OVER && snapshot.totals.any { it >= snapshot.config.targetScore }
            if (tie) GBanner(stringResource(R.string.round_tie_notice), GColors.GoldDeep, GColors.Ivory, Modifier.testTag("tie-notice"))

            if (snapshot.stage == Stage.GAME_OVER) {
                GButton(onContinue, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("show-final")) {
                    Text(stringResource(R.string.announce_continue))
                }
            } else {
                GButton(onNextRound, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("next-round")) {
                    Text(stringResource(R.string.next_round))
                }
            }
        }
    }
}

/** Uma célula de um lado: quantidade de cartas (coluna própria, sem parênteses; `null` = vazia), pontos e nota. */
private data class ScoreCell(val count: String?, val points: String, val note: String? = null, val countTag: String? = null)

private fun ScoreBreakdown.Line.cell(item: ScoreItem, side: Int, note: String? = null): ScoreCell =
    ScoreCell(count?.toString(), if (count == 0) "0" else signed(points), note, "count-${item.name}-$side")

private const val LABEL_WEIGHT = 1.1f
private const val VALUE_WEIGHT = 0.5f
private const val COUNT_WEIGHT = 0.42f
private const val POINTS_WEIGHT = 0.78f

/** Cinza-claro das cartas do adversário no título. */
private val OpponentCards = Color(0xFFD5D8DE)

/** Ícone de 3 cartas em leque, desenhado no código. */
@Composable
private fun ThreeCardsIcon(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.padding(end = 2.dp).size(width = 22.dp, height = 18.dp)) {
        val w = size.width
        val h = size.height
        val cw = w * 0.5f
        listOf(-14f, 0f, 14f).forEach { angle ->
            rotate(angle, pivot = androidx.compose.ui.geometry.Offset(w / 2, h)) {
                val left = w / 2 - cw / 2
                val topLeft = androidx.compose.ui.geometry.Offset(left, h * 0.05f)
                val cardSize = androidx.compose.ui.geometry.Size(cw, h * 0.9f)
                drawRoundRect(color, topLeft, cardSize, androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(GColors.Ivory, topLeft, cardSize, androidx.compose.ui.geometry.CornerRadius(3f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
            }
        }
    }
}

@Composable
private fun ScoreSectionBox(section: ScoreSection, scores: List<SideScore>, lines: List<Map<ScoreItem, ScoreBreakdown.Line>>) {
    val tag = if (section == ScoreSection.TABLE) "table-section" else "hand-section"
    GSubPanel(Modifier.testTag(tag)) {
        Text(
            stringResource(if (section == ScoreSection.TABLE) R.string.score_table_header else R.string.score_hand_header),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        // §1 sem coringas: sem canastra suja nem linhas de coringa; "Canastras" sem "limpas".
        val noWilds = LocalTableRules.current.noWilds
        val hidden = if (noWilds) setOf(ScoreItem.DIRTY_CANASTAS, ScoreItem.TABLE_WILDS, ScoreItem.HAND_WILDS) else emptySet()
        ScoreItem.entries.filter { it.section == section && it !in hidden }.forEach { item ->
            val label = if (noWilds && item == ScoreItem.CLEAN_CANASTAS) R.string.score_canastas else item.labelRes()
            ScoreRow(stringResource(label), lines.mapIndexed { i, side -> side.getValue(item).cell(item, scores[i].side.index) }, examples = item.examples(), value = item.unitValue(LocalTableRules.current))
        }
        HorizontalDivider(color = GColors.Divider, thickness = 2.dp)
        ScoreRow(
            stringResource(if (section == ScoreSection.TABLE) R.string.score_table_subtotal else R.string.score_hand_subtotal),
            scores.map { ScoreCell(null, signed(ScoreBreakdown.subtotal(it, section))) },
            bold = true,
            tag = if (section == ScoreSection.TABLE) "table-subtotal" else "hand-subtotal",
        )
    }
}

@StringRes
private fun ScoreItem.labelRes(): Int = when (this) {
    ScoreItem.RED_THREES -> R.string.score_red_threes
    ScoreItem.CLEAN_CANASTAS -> R.string.score_clean_canastas
    ScoreItem.DIRTY_CANASTAS -> R.string.score_dirty_canastas
    ScoreItem.GO_OUT -> R.string.score_go_out
    ScoreItem.MORTO_NOT_TAKEN -> R.string.score_morto_not_taken
    ScoreItem.TABLE_RED_THREES -> R.string.score_table_red_threes
    ScoreItem.TABLE_FOUR_TO_TEN -> R.string.score_table_four_to_ten
    ScoreItem.TABLE_FACES -> R.string.score_table_faces
    ScoreItem.TABLE_WILDS -> R.string.score_table_wilds
    ScoreItem.HAND_RED_THREES -> R.string.score_hand_red_threes
    ScoreItem.HAND_BLACK_THREES -> R.string.score_hand_black_threes
    ScoreItem.HAND_FOUR_TO_TEN -> R.string.score_hand_four_to_ten
    ScoreItem.HAND_FACES -> R.string.score_hand_faces
    ScoreItem.HAND_WILDS -> R.string.score_hand_wilds
}

/**
 * Exemplos de cartas que mostram o item no lugar da descrição (docs/telas.md 3.10), com naipes variados; `null` = item
 * sem cartas (batida, morto não pego), que continua com o texto.
 */
private fun ScoreItem.examples(): List<Card>? = when (this) {
    ScoreItem.RED_THREES, ScoreItem.TABLE_RED_THREES, ScoreItem.HAND_RED_THREES ->
        listOf(Card(Rank.THREE, Suit.HEARTS), Card(Rank.THREE, Suit.DIAMONDS))
    ScoreItem.HAND_BLACK_THREES -> listOf(Card(Rank.THREE, Suit.SPADES), Card(Rank.THREE, Suit.CLUBS))
    ScoreItem.CLEAN_CANASTAS -> listOf(Card(Rank.NINE, Suit.HEARTS), Card(Rank.TEN, Suit.HEARTS), Card(Rank.JACK, Suit.HEARTS), Card(Rank.QUEEN, Suit.HEARTS))
    ScoreItem.DIRTY_CANASTAS -> listOf(Card(Rank.NINE, Suit.CLUBS), Card(Rank.TWO, Suit.DIAMONDS), Card(Rank.JACK, Suit.CLUBS), Card(Rank.QUEEN, Suit.CLUBS))
    ScoreItem.TABLE_FOUR_TO_TEN, ScoreItem.HAND_FOUR_TO_TEN ->
        listOf(Card(Rank.FOUR, Suit.CLUBS), Card(Rank.SEVEN, Suit.HEARTS), Card(Rank.TEN, Suit.SPADES))
    ScoreItem.TABLE_FACES, ScoreItem.HAND_FACES -> listOf(Card(Rank.JACK, Suit.DIAMONDS), Card(Rank.KING, Suit.SPADES), Card(Rank.ACE, Suit.HEARTS))
    ScoreItem.TABLE_WILDS, ScoreItem.HAND_WILDS -> listOf(Card(Rank.TWO, Suit.SPADES), Card(Rank.TWO, Suit.HEARTS))
    ScoreItem.GO_OUT, ScoreItem.MORTO_NOT_TAKEN -> null
}

/** §12 valor de cada carta (ou de cada canastra, batida e morto) pelas regras da mesa. */
private fun ScoreItem.unitValue(rules: com.gtranca.engine.RuleSet): String = signed(
    when (this) {
        ScoreItem.RED_THREES -> rules.redThreePoints
        ScoreItem.CLEAN_CANASTAS -> rules.cleanCanastaPoints
        ScoreItem.DIRTY_CANASTAS -> rules.dirtyCanastaPoints
        ScoreItem.GO_OUT -> rules.goOutPoints
        ScoreItem.MORTO_NOT_TAKEN -> rules.mortoNotTakenPoints
        ScoreItem.TABLE_RED_THREES -> rules.tableRedThreePoints
        ScoreItem.TABLE_FOUR_TO_TEN -> rules.tableFourToTenPoints
        ScoreItem.TABLE_FACES -> rules.tableFaceCardOrAcePoints
        ScoreItem.TABLE_WILDS -> rules.tableWildPoints
        ScoreItem.HAND_RED_THREES -> rules.handRedThreePoints
        ScoreItem.HAND_BLACK_THREES -> rules.handBlackThreePoints
        ScoreItem.HAND_FOUR_TO_TEN -> rules.handFourToTenPoints
        ScoreItem.HAND_FACES -> rules.handFaceCardOrAcePoints
        ScoreItem.HAND_WILDS -> rules.handWildPoints
    },
)

/** Cartas de exemplo em leque (sobrepostas); o leitor de tela lê a descrição do item. */
@Composable
private fun CardExamples(cards: List<Card>, description: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = description }) {
        cards.forEachIndexed { i, card ->
            com.gtranca.ui.cards.PlayingCard(card, Modifier.padding(start = (13 * i).dp), size = com.gtranca.ui.cards.CardSize.MINI, describe = false)
        }
    }
}

@Composable
private fun ScoreRow(label: String, values: List<ScoreCell>, bold: Boolean = false, tag: String? = null, pill: Boolean = false, examples: List<Card>? = null, value: String? = null) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val numbers = LocalTextStyle.current.copy(fontFeatureSettings = TabularNums, fontWeight = weight, textAlign = TextAlign.End)
    Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (examples != null) {
            Box(Modifier.weight(LABEL_WEIGHT)) { CardExamples(examples, label) }
        } else {
            Text(label, Modifier.weight(LABEL_WEIGHT), fontWeight = weight, color = if (pill) GColors.Gold else Color.Unspecified)
        }
        // §12 valor de cada carta/item (vazio nos subtotais e totais).
        Box(Modifier.weight(VALUE_WEIGHT), contentAlignment = Alignment.CenterEnd) {
            value?.let { com.gtranca.ui.theme.FitText(it, numbers.copy(color = GColors.Champagne), minFontSp = 9) }
        }
        values.forEach { cell ->
            // Quantidade de cartas: coluna própria, sem parênteses (vazia quando a linha não tem quantidade).
            Box(Modifier.weight(COUNT_WEIGHT), contentAlignment = Alignment.CenterEnd) {
                cell.count?.let { count ->
                    com.gtranca.ui.theme.FitText(
                        count,
                        numbers.copy(color = GColors.Steel),
                        if (cell.countTag != null) Modifier.testTag(cell.countTag) else Modifier,
                        minFontSp = 9,
                    )
                }
            }
            if (pill) {
                // Totais em cápsula dourada, alinhados à direita.
                Box(Modifier.weight(POINTS_WEIGHT), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.padding(vertical = 2.dp).background(GoldBrush, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        com.gtranca.ui.theme.FitText(cell.points, numbers.copy(fontWeight = FontWeight.Bold, color = GColors.OnGold), minFontSp = 9)
                    }
                }
            } else {
                Column(Modifier.weight(POINTS_WEIGHT), horizontalAlignment = Alignment.End) {
                    com.gtranca.ui.theme.FitText(cell.points, numbers.copy(color = androidx.compose.material3.LocalContentColor.current), minFontSp = 9)
                    cell.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End) }
                }
            }
        }
    }
}
