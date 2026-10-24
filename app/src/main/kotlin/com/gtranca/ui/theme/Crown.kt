package com.gtranca.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtranca.R

/**
 * Coroa dourada do robô (símbolo do app), desenhada no código: faixa, três pontas com pérolas e uma gema azul.
 * Ocupa [size] de largura e 80% disso de altura.
 */
@Composable
fun CrownMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size, size * 0.8f)) { drawCrown() }
}

/** Desenha a coroa preenchendo o espaço do [DrawScope]. */
fun DrawScope.drawCrown(gem: Color = GColors.SkyBlue) {
    val w = size.width
    val h = size.height
    val gold = Brush.linearGradient(listOf(GColors.Champagne, GColors.Gold, GColors.GoldDeep), Offset(0f, 0f), Offset(w, h))
    val body = Path().apply {
        moveTo(w * 0.07f, h * 0.80f)
        lineTo(w * 0.04f, h * 0.26f)
        lineTo(w * 0.29f, h * 0.52f)
        lineTo(w * 0.50f, h * 0.14f)
        lineTo(w * 0.71f, h * 0.52f)
        lineTo(w * 0.96f, h * 0.26f)
        lineTo(w * 0.93f, h * 0.80f)
        close()
    }
    drawPath(body, gold)
    drawPath(body, GColors.GoldDeep, style = Stroke(width = w * 0.02f, join = StrokeJoin.Round))
    // Pérolas nas pontas.
    val pearl = w * 0.06f
    listOf(Offset(w * 0.04f, h * 0.20f), Offset(w * 0.50f, h * 0.08f), Offset(w * 0.96f, h * 0.20f)).forEach {
        drawCircle(gold, pearl, it)
        drawCircle(Color(0x88FFFFFF), pearl * 0.35f, it - Offset(pearl * 0.25f, pearl * 0.25f))
    }
    // Faixa da base e gema.
    val bandTop = h * 0.74f
    drawRoundRect(gold, Offset(w * 0.05f, bandTop), Size(w * 0.90f, h * 0.20f), CornerRadius(w * 0.04f))
    drawRoundRect(GColors.GoldDeep, Offset(w * 0.05f, bandTop), Size(w * 0.90f, h * 0.20f), CornerRadius(w * 0.04f), style = Stroke(w * 0.02f))
    drawCircle(gem, w * 0.055f, Offset(w * 0.50f, bandTop + h * 0.10f))
    drawCircle(Color(0x88FFFFFF), w * 0.02f, Offset(w * 0.485f, bandTop + h * 0.085f))
}

/**
 * Cabeçalho de marca dos diálogos: coroa + "TRANCA-BOT" em Cinzel dourado, com um filete dourado embaixo.
 * Vai acima do título de cada modal.
 */
@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CrownMark(22.dp)
            Text(
                stringResource(R.string.brand_name),
                style = goldTitleStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, fontSize = 14.sp)),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Canvas(Modifier.fillMaxWidth(0.6f).height(1.dp)) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, GColors.Gold.copy(alpha = 0.7f), Color.Transparent)))
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}
