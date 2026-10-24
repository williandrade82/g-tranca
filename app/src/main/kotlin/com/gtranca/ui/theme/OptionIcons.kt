package com.gtranca.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gtranca.R

/** Ícones do menu de Opções da mesa (arte do usuário: só o desenho, tingido pela cor do tema). */
enum class OptionIcon(@DrawableRes val res: Int) {
    SLOW(R.drawable.ic_opt_slow),
    FAST(R.drawable.ic_opt_fast),
    RESIGN(R.drawable.ic_opt_resign),
    LEGEND(R.drawable.ic_opt_legend),
    PASS_TABLE(R.drawable.ic_opt_pass_table),
    SWAP_PLAYER(R.drawable.ic_opt_swap_player),
}

/** Decorativo: o texto do item do menu já diz o que é. */
@Composable
fun OptionGlyph(icon: OptionIcon, color: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp) {
    Icon(painterResource(icon.res), contentDescription = null, tint = color, modifier = Modifier.size(size))
}
