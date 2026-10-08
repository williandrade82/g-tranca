package com.gtranca.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gtranca.R

/** Títulos e logotipo (Cinzel, fonte variável). */
val Cinzel = FontFamily(
    Font(R.font.cinzel, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.cinzel, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.cinzel, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** Interface e números (Poppins). */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.ExtraBold),
)

/** Índices das cartas: Poppins Bold (traço grosso e uniforme, legível em tamanho pequeno). */
val CardFont = FontFamily(
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.ExtraBold),
    Font(R.font.poppins_bold, FontWeight.Black),
)

/** Algarismos de largura fixa (placar e contadores não "dançam"). */
const val TabularNums = "tnum"

private val Base = Typography()

private fun TextStyle.ui(weight: FontWeight) = copy(fontFamily = Poppins, fontWeight = weight)
private fun TextStyle.title(weight: FontWeight) = copy(fontFamily = Cinzel, fontWeight = weight)

/** Tipografia: Cinzel nos títulos grandes, Poppins no resto. */
val GTypography = Typography(
    displayLarge = Base.displayLarge.title(FontWeight.Bold),
    displayMedium = Base.displayMedium.title(FontWeight.Bold),
    displaySmall = Base.displaySmall.title(FontWeight.Bold),
    headlineLarge = Base.headlineLarge.title(FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.title(FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.title(FontWeight.Bold),
    titleLarge = Base.titleLarge.ui(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.ui(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.ui(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.ui(FontWeight.Normal),
    bodyMedium = Base.bodyMedium.ui(FontWeight.Normal),
    bodySmall = Base.bodySmall.ui(FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelMedium = Base.labelMedium.ui(FontWeight.Medium),
    labelSmall = Base.labelSmall.ui(FontWeight.Medium),
)
