package com.gtranca.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta "luxo noturno de cassino": meia-noite/índigo + ouro; feltro esmeralda só na mesa.
 * Ajuste as cores só aqui. Os nomes antigos (Green, Yellow, Cream...) são papéis mantidos
 * para as telas e apontam para a paleta nova.
 */
object GColors {
    // Paleta base
    val Midnight = Color(0xFF0B1026)
    val Indigo = Color(0xFF1A1F4B)
    val IndigoLight = Color(0xFF262C63)
    val RoyalStart = Color(0xFF2A3290)
    val RoyalEnd = Color(0xFF4A2A8C)
    val Amethyst = Color(0xFF6C5BD4)
    val Gold = Color(0xFFF5C542)
    val GoldDeep = Color(0xFFB8862B)
    val Champagne = Color(0xFFFFE9A8)
    val Bordeaux = Color(0xFF7A1630)
    val Ruby = Color(0xFFD23A4F)
    val Emerald = Color(0xFF2FBF84)
    val Ivory = Color(0xFFF5EFE3)
    val Lavender = Color(0xFF9A9CC4)
    val OnGold = Color(0xFF2A1C05)

    // Papéis (nomes históricos)
    val Green = Amethyst
    val GreenDark = Color(0xFF4B3DAA)
    val Yellow = Gold
    val YellowSoft = Champagne
    val Red = Ruby
    val RedDark = Bordeaux
    val White = Ivory
    val Cream = Indigo
    val Shadow = Color(0x99000000)
    val CreamDeep = IndigoLight
    val Divider = Color(0x66F5C542)
    val Neutral = Color(0xFF3B4080)

    // Mesa
    val Table = Color(0xFF0F4A3A)
    val TableDark = Color(0xFF08291F)
    val OnTable = Ivory
    val OnTableDisabled = Lavender
    val TableAccent = Gold

    // Cartas
    val CardFace = Color(0xFFFFFBF2)
    val CardRed = Color(0xFFC0263C)
    val CardBlack = Color(0xFF1B1B1B)
    val CardBorder = Color(0xFFB9AE96)
    val CardSelectedBorder = Gold
    val CardSelectedFill = Color(0xFFFFF1C4)
    val CardHighlightBorder = Amethyst
    val CardPlayableMark = Color(0xFF1F8A5E)
    val CardDim = Color(0x66000000)
    val CardNewBadge = Color(0xFF1F8A5E)
    val CardBackFrame = Bordeaux
    val CardBackPattern = Gold

    // Canastras
    val CanastraClean = Emerald
    val CanastraDirtyFlash = Champagne
}
