package com.gtranca.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit

/**
 * Tamanhos das cartas desenhadas. As fontes acompanham o tamanho da carta (em dp), sem escalar com a
 * preferência de fonte do sistema, para o desenho não estourar a carta.
 */
enum class CardSize(val width: Dp, val height: Dp, val cornerFont: Dp, val centerFont: Dp, val showCenter: Boolean) {
    /** Conjuntos na mesa e lixo. */
    SMALL(30.dp, 44.dp, 11.dp, 15.dp, true),

    /** Mão do jogador (largura mínima de 48dp para o toque). */
    MEDIUM(48.dp, 70.dp, 14.dp, 26.dp, true),

    /** Diálogos. */
    LARGE(56.dp, 80.dp, 15.dp, 30.dp, true),
}

private val CardRed = Color(0xFFC62828)
private val CardBlack = Color(0xFF1B1B1B)
private val CardBorder = Color(0xFF9E9E9E)
private val SelectedBorder = Color(0xFF1565C0)
private val SelectedFill = Color(0xFFDCEBFF)
private val HighlightBorder = Color(0xFFFFB300)

/** Marca discreta de "participa de jogada" sobre o branco da carta (contraste ≥ 3:1 com o branco). */
private val PlayableMark = Color(0xFFE65100)
private val DimOverlay = Color(0x66000000)

/**
 * Realce de uma carta da mão (derivado de `legalActions` pelo ViewModel; a carta não decide nada).
 * - [NONE]: sem realce.
 * - [SUBTLE]: participa de alguma jogada (sem seleção): só um ponto laranja no canto, para a mão cheia não
 *   ficar toda contornada.
 * - [STRONG]: completa uma jogada junto com a seleção atual: borda âmbar grossa e o ponto.
 * - [SELECTED]: selecionada: borda azul, fundo azul-claro e marca de seleção.
 * - [DIMMED]: há seleção e a carta não combina com ela: esmaecida (continua tocável).
 */
enum class CardEmphasis { NONE, SUBTLE, STRONG, SELECTED, DIMMED }

/** Símbolo do naipe, com o seletor de apresentação em texto (evita a versão emoji). */
val Suit.glyph: String
    get() = when (this) {
        Suit.HEARTS -> "♥︎"
        Suit.DIAMONDS -> "♦︎"
        Suit.SPADES -> "♠︎"
        Suit.CLUBS -> "♣︎"
    }

/** Índice do valor como impresso na carta: A, 2–10, J, Q, K ("10" para T). */
val Rank.label: String
    get() = when (this) {
        Rank.TEN -> "10"
        Rank.JACK -> "J"
        Rank.QUEEN -> "Q"
        Rank.KING -> "K"
        Rank.ACE -> "A"
        else -> symbol.toString()
    }

/** Rótulo curto (ex.: "10♥"), para textos. */
val Card.shortLabel: String get() = rank.label + suit.glyph

/** Descrição acessível, ex.: "7 de copas", "Ás de espadas". */
@Composable
@ReadOnlyComposable
fun cardDescription(card: Card): String {
    val rank = when (card.rank) {
        Rank.ACE -> stringResource(R.string.rank_ace)
        Rank.JACK -> stringResource(R.string.rank_jack)
        Rank.QUEEN -> stringResource(R.string.rank_queen)
        Rank.KING -> stringResource(R.string.rank_king)
        else -> card.rank.label
    }
    val suit = when (card.suit) {
        Suit.HEARTS -> stringResource(R.string.suit_hearts)
        Suit.DIAMONDS -> stringResource(R.string.suit_diamonds)
        Suit.SPADES -> stringResource(R.string.suit_spades)
        Suit.CLUBS -> stringResource(R.string.suit_clubs)
    }
    return stringResource(R.string.card_description, rank, suit)
}

@Composable
private fun Dp.asFont(): TextUnit = with(LocalDensity.current) { this@asFont.toSp() }

/**
 * Carta aberta desenhada no código, no estilo tradicional: fundo branco, cantos arredondados, valor e naipe
 * nos cantos (o de baixo invertido) e o naipe grande no centro; copas e ouros em vermelho, espadas e paus
 * em preto. Isolada para poder ser trocada por imagens no futuro.
 *
 * @param emphasis realce na mão (ver [CardEmphasis]).
 * @param describe define o `contentDescription` (desligue quando o pai já descreve a carta).
 */
@Composable
fun PlayingCard(
    card: Card,
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.MEDIUM,
    emphasis: CardEmphasis = CardEmphasis.NONE,
    describe: Boolean = true,
) {
    val description = cardDescription(card)
    val color = if (card.suit.isRed) CardRed else CardBlack
    val shape = RoundedCornerShape(size.width * 0.12f)
    val border = when (emphasis) {
        CardEmphasis.SELECTED -> 3.dp to SelectedBorder
        CardEmphasis.STRONG -> 3.dp to HighlightBorder
        else -> 1.dp to CardBorder
    }
    val cornerStyle = TextStyle(
        color = color,
        fontSize = size.cornerFont.asFont(),
        lineHeight = size.cornerFont.asFont(),
        fontWeight = FontWeight.Bold,
    )
    Box(
        modifier
            .size(size.width, size.height)
            .then(if (describe) Modifier.semantics { contentDescription = description } else Modifier)
            .clip(shape)
            .background(if (emphasis == CardEmphasis.SELECTED) SelectedFill else Color.White)
            .border(border.first, border.second, shape),
    ) {
        CornerIndex(card, cornerStyle, Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 2.dp))
        val badge = when (emphasis) {
            CardEmphasis.SUBTLE, CardEmphasis.STRONG -> PlayableMark
            CardEmphasis.SELECTED -> SelectedBorder
            else -> null
        }
        if (badge != null) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(size.width * 0.2f)) {
                drawCircle(badge)
                if (emphasis == CardEmphasis.SELECTED) {
                    // Marca de seleção (✓) branca dentro do círculo.
                    val w = this.size.width
                    val check = Path().apply {
                        moveTo(w * 0.25f, w * 0.52f); lineTo(w * 0.43f, w * 0.7f); lineTo(w * 0.76f, w * 0.32f)
                    }
                    drawPath(check, Color.White, style = Stroke(width = w * 0.14f))
                }
            }
        }
        if (size.showCenter) {
            Text(
                card.suit.glyph,
                style = TextStyle(color = color, fontSize = size.centerFont.asFont(), lineHeight = size.centerFont.asFont()),
                modifier = Modifier.align(if (size == CardSize.SMALL) Alignment.BottomEnd else Alignment.Center)
                    .padding(end = if (size == CardSize.SMALL) 2.dp else 0.dp, bottom = if (size == CardSize.SMALL) 1.dp else 0.dp),
            )
        }
        if (size != CardSize.SMALL) {
            CornerIndex(card, cornerStyle, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp).rotate(180f))
        }
        if (emphasis == CardEmphasis.DIMMED) Box(Modifier.matchParentSize().background(DimOverlay))
    }
}

@Composable
private fun CornerIndex(card: Card, style: TextStyle, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.rank.label, style = style, softWrap = false)
        Text(card.suit.glyph, style = style, softWrap = false)
    }
}

private val BackFrame = Color(0xFF8E1B1B)
private val BackPattern = Color(0xFFE8B4B4)

/**
 * Verso da carta: moldura branca e, dentro dela, um padrão de losangos sobre fundo vermelho-escuro.
 * Isolado para poder ser trocado por imagem no futuro.
 */
@Composable
fun CardBack(
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.SMALL,
    describe: Boolean = true,
) {
    val description = stringResource(R.string.card_back)
    val shape = RoundedCornerShape(size.width * 0.12f)
    Box(
        modifier
            .size(size.width, size.height)
            .then(if (describe) Modifier.semantics { contentDescription = description } else Modifier)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, CardBorder, shape),
    ) {
        Canvas(Modifier.matchParentSize().padding(3.dp)) {
            val radius = CornerRadius(this.size.width * 0.08f)
            drawRoundRect(BackFrame, cornerRadius = radius)
            val step = this.size.width / 4f
            clipRect {
                // Losangos: duas famílias de diagonais.
                var x = -this.size.height
                while (x < this.size.width + this.size.height) {
                    drawLine(BackPattern, Offset(x, 0f), Offset(x + this.size.height, this.size.height), strokeWidth = 1.2f)
                    drawLine(BackPattern, Offset(x, this.size.height), Offset(x + this.size.height, 0f), strokeWidth = 1.2f)
                    x += step
                }
            }
            // Moldura interna.
            val inset = 2.dp.toPx()
            drawRoundRect(
                BackPattern,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                cornerRadius = radius,
                style = Stroke(width = 1.5f),
            )
            // Losango central.
            val cx = this.size.width / 2
            val cy = this.size.height / 2
            val r = this.size.width / 5
            val diamond = Path().apply {
                moveTo(cx, cy - r * 1.4f); lineTo(cx + r, cy); lineTo(cx, cy + r * 1.4f); lineTo(cx - r, cy); close()
            }
            drawPath(diamond, Color.White)
        }
    }
}
