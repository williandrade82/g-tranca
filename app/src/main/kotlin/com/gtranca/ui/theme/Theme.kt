package com.gtranca.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Aliases mantidos para as telas atuais; a fonte é GColors.
val TableGreen = GColors.Table
val TableGreenDark = GColors.TableDark
val OnTable = GColors.OnTable
val TableAccent = GColors.TableAccent

private val Colors = lightColorScheme(
    primary = GColors.Green,
    onPrimary = GColors.White,
    primaryContainer = GColors.Cream,
    secondary = GColors.Red,
    onSecondary = GColors.White,
    tertiary = GColors.Yellow,
    onTertiary = GColors.CardBlack,
    tertiaryContainer = GColors.YellowSoft,
    background = GColors.Cream,
    surface = GColors.White,
    error = GColors.RedDark,
)

@Composable
fun GTrancaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = GShapes, typography = GTypography, content = content)
}
