package com.gtranca.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta "Tranca-Bot": azul-marinho/royal do boné e da jaqueta do robô, azul-pele como realce, ouro das coroas e
 * creme; feltro verde só na mesa.
 * Ajuste as cores só aqui. Os nomes antigos (Green, Yellow, Cream...) são papéis mantidos
 * para as telas e apontam para a paleta nova.
 */
object GColors {
    // Paleta base
    val Midnight = Color(0xFF081634)
    val Indigo = Color(0xFF102A5C)
    val IndigoLight = Color(0xFF1B3B7E)
    val RoyalStart = Color(0xFF1F46C8)
    val RoyalEnd = Color(0xFF14307F)
    val RoyalBlue = Color(0xFF2F5FD6)
    val SkyBlue = Color(0xFF6EB8EC)
    val Gold = Color(0xFFF5C542)
    val GoldDeep = Color(0xFFB8862B)
    val Champagne = Color(0xFFFFE9A8)
    val Bordeaux = Color(0xFF7A1630)
    val Ruby = Color(0xFFD23A4F)
    val Emerald = Color(0xFF2FBF84)
    val Ivory = Color(0xFFF5EFE3)
    val Steel = Color(0xFFA3BCE6)
    val OnGold = Color(0xFF2A1C05)

    // Papéis (nomes históricos)
    val Green = RoyalBlue
    val GreenDark = Color(0xFF1F3E9E)
    val Yellow = Gold
    val YellowSoft = Champagne
    val Red = Ruby
    val RedDark = Bordeaux
    val White = Ivory
    val Cream = Indigo
    val Shadow = Color(0x99000000)
    val CreamDeep = IndigoLight
    val Divider = Color(0x66F5C542)
    val Neutral = Color(0xFF2E4A8C)

    // Mesa
    val Table = Color(0xFF0F4A3A)
    val TableDark = Color(0xFF08291F)
    val OnTable = Ivory
    val OnTableDisabled = Steel
    val TableAccent = Gold

    // Cartas
    val CardFace = Color(0xFFFFFBF2)
    val CardRed = Color(0xFFC0263C)
    val CardBlack = Color(0xFF1B1B1B)
    val CardBorder = Color(0xFFB9AE96)
    // Ouro profundo: ~3:1 sobre a carta (o ouro claro dava 1,6:1).
    val CardSelectedBorder = GoldDeep
    val CardSelectedFill = Color(0xFFFFF1C4)
    val CardHighlightBorder = SkyBlue
    val CardPlayableMark = Color(0xFF1F8A5E)

    /** §17.7 b2 Yellow-LED: círculo amarelo (se encaixa no jogo do adversário) e o amarelo que pulsa na carta que fecha a canastra dele. */
    val CardOpponentMark = Color(0xFFF2B600)
    val CardOpponentMarkRing = Color(0xFFB07A00)
    val CardDim =Color(0x66000000)
    val CardNewBadge = Color(0xFF1F8A5E)
    val CardBackFrame = Color(0xFF173C9A)

    // Cor do baralho à escolha do jogador (§14.1): azul (padrão), verde (mais vivo que o feltro da mesa) e vermelho.
    val DeckBlue = CardBackFrame
    val DeckGreen = Color(0xFF1B8A4E)
    val DeckRed = Color(0xFFB3192F)
    val CardBackPattern = Gold

    // Canastras
    val CanastraClean = Emerald
    val CanastraDirtyFlash = Champagne

    // Bronze: cor própria da canastra suja (o azul-aço lia como "inativo").
    val CanastraDirty = Color(0xFFCD8A45)
}
