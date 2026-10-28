package com.gtranca.ui.cards

import com.gtranca.ui.theme.Cinzel
import com.gtranca.ui.theme.CrownMark
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.CardFont
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
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
import com.gtranca.game.DeckColor
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.Suit

/**
 * Tamanhos das cartas desenhadas. As fontes acompanham o tamanho da carta (em dp), sem escalar com a
 * preferência de fonte do sistema, para o desenho não estourar a carta.
 */
enum class CardSize(val width: Dp, val height: Dp, val cornerFont: Dp, val centerFont: Dp, val showCenter: Boolean) {
    /** Versos das mãos dos outros assentos (só [CardBack]). */
    TINY(16.dp, 24.dp, 8.dp, 10.dp, false),

    /** Exemplos de cartas na tabela de pontos (só o índice do canto). */
    MINI(20.dp, 28.dp, 10.dp, 0.dp, false),

    /** Conjuntos na mesa e lixo. */
    SMALL(34.dp, 50.dp, 12.dp, 17.dp, true),

    /** Mão do jogador (largura mínima de 48dp para o toque). */
    MEDIUM(48.dp, 70.dp, 14.dp, 26.dp, true),

    /** Diálogos. */
    LARGE(56.dp, 80.dp, 15.dp, 30.dp, true),
}


/** Marca discreta de "participa de jogada" sobre o branco da carta (contraste ≥ 3:1 com o branco). */

/**
 * Realce de uma carta da mão (derivado de `legalActions` pelo ViewModel; a carta não decide nada).
 * - [NONE]: sem realce.
 * - [SUBTLE]: participa de alguma jogada (sem seleção): só um ponto verde no canto, para a mão cheia não
 *   ficar toda contornada.
 * - [STRONG]: completa uma jogada junto com a seleção atual: borda azul grossa e o ponto.
 * - [SELECTED]: selecionada: borda ouro profundo, fundo creme, marca de seleção e leve aumento.
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
    badge: String? = null,
    describe: Boolean = true,
    /** §17.7 b2 Red-LED: como a carta se encaixa nos jogos do adversário (círculo vermelho; pulsa se fecha uma canastra dele). */
    opponentFit: com.gtranca.engine.OpponentFit = com.gtranca.engine.OpponentFit.NONE,
) {
    val description = cardDescription(card)
    val color = if (card.suit.isRed) GColors.CardRed else GColors.CardBlack
    val shape = RoundedCornerShape(size.width * 0.12f)
    val border = when (emphasis) {
        CardEmphasis.SELECTED -> 3.dp to GColors.CardSelectedBorder
        CardEmphasis.STRONG -> 3.dp to GColors.CardHighlightBorder
        else -> 1.dp to GColors.CardBorder
    }
    val cornerStyle = TextStyle(
        color = color,
        fontSize = size.cornerFont.asFont(),
        lineHeight = size.cornerFont.asFont(),
        fontWeight = FontWeight.Bold,
        fontFamily = CardFont,
    )
    // Marcada: cresce de leve (e volta ao desmarcar), com animação; fica por cima das vizinhas.
    val scale by animateFloatAsState(if (emphasis == CardEmphasis.SELECTED) 1.1f else 1f, label = "card-scale")
    Box(
        modifier
            .zIndex(if (emphasis == CardEmphasis.SELECTED) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size.width, size.height)
            .then(if (describe) Modifier.semantics { contentDescription = description } else Modifier)
            // Selecionada: borda ouro e leve elevação (sombra dourada).
            .shadow(if (emphasis == CardEmphasis.SELECTED) 8.dp else 1.dp, shape, ambientColor = GColors.GoldDeep, spotColor = GColors.GoldDeep)
            .clip(shape)
            .background(if (emphasis == CardEmphasis.SELECTED) GColors.CardSelectedFill else GColors.CardFace)
            .border(border.first, border.second, shape),
    ) {
        CornerIndex(card, cornerStyle, Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 2.dp))
        val mark = when (emphasis) {
            CardEmphasis.SUBTLE, CardEmphasis.STRONG -> GColors.CardPlayableMark
            CardEmphasis.SELECTED -> GColors.CardSelectedBorder
            else -> null
        }
        if (mark != null) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(size.width * 0.2f)) {
                drawCircle(mark)
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
        if (opponentFit != com.gtranca.engine.OpponentFit.NONE) {
            // Mesmo tamanho do ponto verde (§17.6), no canto inferior esquerdo.
            Canvas(Modifier.align(Alignment.BottomStart).padding(bottom = 5.dp, start = 5.dp).size(size.width * 0.2f)) { drawCircle(GColors.CardOpponentMark) }
            if (opponentFit == com.gtranca.engine.OpponentFit.CLOSES_CANASTA) ClosesCanastaPulse()
        }
        if (size.showCenter) {
            Text(
                card.suit.glyph,
                style = TextStyle(color = color, fontSize = size.centerFont.asFont(), lineHeight = size.centerFont.asFont()),
                modifier = Modifier.align(if (size == CardSize.SMALL) Alignment.BottomEnd else Alignment.Center)
                    .padding(end = if (size == CardSize.SMALL) 2.dp else 0.dp, bottom = if (size == CardSize.SMALL) 1.dp else 0.dp),
            )
        }
        if (size != CardSize.SMALL && size != CardSize.MINI) {
            CornerIndex(card, cornerStyle, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp).rotate(180f))
        }
        if (emphasis == CardEmphasis.DIMMED) Box(Modifier.matchParentSize().background(GColors.CardDim))
        if (badge != null) {
            // Rótulo (ex.: "nova") sobre a parte de baixo da carta, branco sobre verde-escuro (contraste ≥ 7:1).
            Text(
                badge,
                // Proporcional à carta (era fixo em 10dp): na carta grande cresce junto.
                style = TextStyle(color = Color.White, fontSize = (size.width * 0.22f).asFont(), lineHeight = (size.width * 0.25f).asFont(), fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(GColors.CardNewBadge).padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun CornerIndex(card: Card, style: TextStyle, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.rank.label, style = style, softWrap = false)
        Text(card.suit.glyph, style = style, softWrap = false)
    }
}


/** Cor do baralho do jogador (verso das cartas), definida no app inteiro a partir do perfil. */
val LocalDeckColor = androidx.compose.runtime.staticCompositionLocalOf { DeckColor.AZUL }

/** Cor da moldura do verso da carta para o baralho. */
fun DeckColor.frameColor(): androidx.compose.ui.graphics.Color = when (this) {
    DeckColor.AZUL -> GColors.DeckBlue
    DeckColor.VERDE -> GColors.DeckGreen
    DeckColor.VERMELHO -> GColors.DeckRed
}

/**
 * Verso da carta: moldura marfim e, dentro dela, losangos dourados sobre a cor do baralho ([deck]), com a coroa do Tranca-Bot.
 * Isolado para poder ser trocado por imagem no futuro.
 */
@Composable
fun CardBack(
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.SMALL,
    describe: Boolean = true,
    deck: DeckColor = LocalDeckColor.current,
) {
    val description = stringResource(R.string.card_back)
    val frame = deck.frameColor()
    val shape = RoundedCornerShape(size.width * 0.12f)
    Box(
        modifier
            .size(size.width, size.height)
            .then(if (describe) Modifier.semantics { contentDescription = description } else Modifier)
            .clip(shape)
            .background(GColors.CardFace)
            .border(1.dp, GColors.CardBorder, shape),
    ) {
        Canvas(Modifier.matchParentSize().padding(3.dp)) {
            val radius = CornerRadius(this.size.width * 0.08f)
            drawRoundRect(frame, cornerRadius = radius)
            val step = this.size.width / 4f
            clipRect {
                // Losangos: duas famílias de diagonais.
                var x = -this.size.height
                while (x < this.size.width + this.size.height) {
                    drawLine(GColors.CardBackPattern.copy(alpha = 0.45f), Offset(x, 0f), Offset(x + this.size.height, this.size.height), strokeWidth = 1f)
                    drawLine(GColors.CardBackPattern.copy(alpha = 0.45f), Offset(x, this.size.height), Offset(x + this.size.height, 0f), strokeWidth = 1f)
                    x += step
                }
            }
            // Moldura interna.
            val inset = 2.dp.toPx()
            drawRoundRect(
                GColors.CardBackPattern,
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
            drawPath(diamond, frame)
            drawPath(diamond, GColors.Gold, style = Stroke(width = 1.5f))
        }
        if (size != CardSize.SMALL) {
            CrownMark(size.width * 0.34f, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * §17.7 a cor da carta pulsa quando acrescentá-la fecharia uma canastra do adversário. Sem animações (sistema ou
 * preferência do jogador), fica um tom fixo.
 */
@Composable
private fun BoxScope.ClosesCanastaPulse() {
    val motion = com.gtranca.ui.theme.rememberMotionEnabled()
    val alpha = if (motion) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "closes-canasta")
        val pulse by transition.animateFloat(
            0.08f, 0.4f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "closes-canasta-alpha",
        )
        pulse
    } else 0.25f
    Box(Modifier.matchParentSize().background(GColors.CardOpponentMark.copy(alpha = alpha)))
}
