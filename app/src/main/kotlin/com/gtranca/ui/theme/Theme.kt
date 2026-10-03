package com.gtranca.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Feltro verde da mesa. */
val TableGreen = Color(0xFF1E5631)
val TableGreenDark = Color(0xFF143D22)
val OnTable = Color(0xFFF1F8E9)
val TableAccent = Color(0xFFFFD54F)

private val Colors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    secondary = Color(0xFF8E1B1B),
    onSecondary = Color.White,
)

@Composable
fun GTrancaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
