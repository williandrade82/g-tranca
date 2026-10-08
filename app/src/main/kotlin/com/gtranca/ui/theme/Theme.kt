package com.gtranca.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Aliases mantidos para as telas atuais; a fonte é GColors.
val TableGreen = GColors.Table
val TableGreenDark = GColors.TableDark
val OnTable = GColors.OnTable
val TableAccent = GColors.TableAccent

private val Colors = darkColorScheme(
    primary = GColors.Gold,
    onPrimary = GColors.OnGold,
    primaryContainer = GColors.IndigoLight,
    onPrimaryContainer = GColors.Ivory,
    secondary = GColors.Amethyst,
    onSecondary = GColors.Ivory,
    tertiary = GColors.Gold,
    onTertiary = GColors.OnGold,
    tertiaryContainer = GColors.Champagne,
    onTertiaryContainer = GColors.OnGold,
    background = GColors.Midnight,
    onBackground = GColors.Ivory,
    surface = GColors.Indigo,
    onSurface = GColors.Ivory,
    surfaceVariant = GColors.IndigoLight,
    onSurfaceVariant = GColors.Lavender,
    surfaceContainerHigh = GColors.Indigo,
    surfaceContainerHighest = GColors.IndigoLight,
    outline = GColors.GoldDeep,
    outlineVariant = GColors.Divider,
    error = GColors.Ruby,
    onError = GColors.Ivory,
)

@Composable
fun GTrancaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = GShapes, typography = GTypography, content = content)
}
