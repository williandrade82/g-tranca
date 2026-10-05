package com.gtranca.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gtranca.R

/** Estado da chave de som do jogador e como invertê-la (grava nas preferências); fornecido pelo app todo. */
data class SoundSettings(val on: Boolean = true, val toggle: () -> Unit = {})

val LocalSoundSettings = androidx.compose.runtime.staticCompositionLocalOf { SoundSettings() }

/**
 * Botão de alto-falante que liga e desliga os sons (a mesma chave do Perfil). Ao ligar, toca um sino de pré-escuta.
 * Alvo de toque de 48 dp, ícone desenhado no código: ondas com o som ligado, "x" com ele desligado.
 */
@Composable
fun SoundButton(color: Color, modifier: Modifier = Modifier) {
    val settings = LocalSoundSettings.current
    val sound = LocalSound.current
    val description = stringResource(if (settings.on) R.string.sound_toggle_on else R.string.sound_toggle_off)
    Box(
        modifier
            .size(48.dp)
            .clickable(onClickLabel = description, role = Role.Switch) {
                if (!settings.on) sound.play(SoundEffect.CHIME, force = true)
                settings.toggle()
            }
            .semantics { contentDescription = description }
            .testTag("sound-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
            val body = Path().apply {
                moveTo(w * 0.08f, w * 0.38f); lineTo(w * 0.28f, w * 0.38f); lineTo(w * 0.5f, w * 0.18f)
                lineTo(w * 0.5f, w * 0.82f); lineTo(w * 0.28f, w * 0.62f); lineTo(w * 0.08f, w * 0.62f); close()
            }
            drawPath(body, color)
            if (settings.on) {
                listOf(0.18f, 0.32f).forEach { r ->
                    drawArc(color, -45f, 90f, false, Offset(w * (0.5f - r + 0.06f), w * (0.5f - r)), Size(w * 2 * r, w * 2 * r), style = stroke)
                }
            } else {
                drawLine(color, Offset(w * 0.64f, w * 0.36f), Offset(w * 0.94f, w * 0.64f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.94f, w * 0.36f), Offset(w * 0.64f, w * 0.64f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            }
        }
    }
}
