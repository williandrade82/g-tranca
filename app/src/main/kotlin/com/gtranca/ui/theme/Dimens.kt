package com.gtranca.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Escala de espaçamentos (múltiplos de 4 dp). */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Escala de elevações (sombreamento). */
object Elevation {
    val card = 2.dp
    val button = 4.dp
    val dialog = 12.dp
}

/** Escala de raios de canto (use estes, não valores soltos). */
object Corner {
    val xs = 6.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Formas arredondadas do app. */
val GShapes = Shapes(
    extraSmall = RoundedCornerShape(Corner.xs),
    small = RoundedCornerShape(Corner.sm),
    medium = RoundedCornerShape(Corner.md),
    large = RoundedCornerShape(Corner.lg),
    extraLarge = RoundedCornerShape(Corner.xl),
)
