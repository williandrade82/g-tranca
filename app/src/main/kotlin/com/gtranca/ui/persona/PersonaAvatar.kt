package com.gtranca.ui.persona

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import com.gtranca.game.HairStyle
import com.gtranca.game.Persona
import com.gtranca.game.Profession

private val SkinTones = listOf(0xFFF8D9C0, 0xFFEEC39A, 0xFFD49A6A, 0xFFA9714B, 0xFF6F4426).map { Color(it) }
private val HairColors = listOf(0xFF2B2B2B, 0xFF5A3A22, 0xFF8D5524, 0xFFD8B15A, 0xFFB5482A, 0xFF9E9E9E).map { Color(it) }

private val Ink = Color(0xFF2B2B2B)
private val White = Color(0xFFFAFAFA)

/** Fundo do círculo por profissão. */
private fun Profession.background(): Color = Color(
    when (this) {
        Profession.DOCTOR -> 0xFFB2DFDB
        Profession.CHEF -> 0xFFFFE0B2
        Profession.ENGINEER -> 0xFFFFE082
        Profession.TEACHER -> 0xFFC5CAE9
        Profession.FIREFIGHTER -> 0xFFFFCDD2
        Profession.SCIENTIST -> 0xFFC8E6C9
        Profession.PILOT -> 0xFFBBDEFB
        Profession.ARTIST -> 0xFFE1BEE7
    },
)

/**
 * Avatar de uma persona (§14.1): cabeça, pescoço, ombros, parte do peito e dos braços, desenhados no código, num
 * círculo. Sem texto: quem usa dá a descrição (nome e profissão) à tela.
 */
@Composable
fun PersonaAvatar(persona: Persona, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(CircleShape)) { drawPersona(persona) }
}

// ---------- desenho em coordenadas de 0 a 1 ----------

private fun DrawScope.circle(color: Color, cx: Float, cy: Float, r: Float) =
    drawCircle(color, r * size.width, Offset(cx * size.width, cy * size.height))

private fun DrawScope.oval(color: Color, left: Float, top: Float, w: Float, h: Float) =
    drawOval(color, Offset(left * size.width, top * size.height), Size(w * size.width, h * size.height))

private fun DrawScope.box(color: Color, left: Float, top: Float, w: Float, h: Float, corner: Float = 0f) =
    drawRoundRect(
        color,
        Offset(left * size.width, top * size.height),
        Size(w * size.width, h * size.height),
        CornerRadius(corner * size.width),
    )

private fun DrawScope.shape(color: Color, build: Path.(Float, Float) -> Unit) {
    val path = Path().apply { build(size.width, size.height) }
    drawPath(path, color)
}

private fun DrawScope.stroke(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) =
    drawLine(color, Offset(x1 * size.width, y1 * size.height), Offset(x2 * size.width, y2 * size.height), strokeWidth = width * size.width, cap = StrokeCap.Round)

/** Metade de cima de uma elipse (boné de cabelo, capacete, boina). */
private fun DrawScope.cap(color: Color, left: Float, top: Float, w: Float, h: Float) =
    drawArc(color, 180f, 180f, true, Offset(left * size.width, top * size.height), Size(w * size.width, h * size.height * 2))

private fun DrawScope.drawPersona(persona: Persona) {
    val look = persona.look
    val skin = SkinTones[look.skin]
    val skinShade = lerp(skin, Color.Black, 0.12f)
    val hair = HairColors[look.hairColor]

    drawRect(persona.profession.background())

    // Cabelo de trás (longos e rabo de cavalo), por baixo dos ombros.
    when (look.hair) {
        HairStyle.LONG -> box(hair, 0.29f, 0.2f, 0.42f, 0.56f, 0.18f)
        HairStyle.BOB -> box(hair, 0.3f, 0.2f, 0.4f, 0.36f, 0.14f)
        HairStyle.CURLY_LONG -> box(hair, 0.28f, 0.24f, 0.44f, 0.46f, 0.18f)
        HairStyle.PONYTAIL -> oval(hair, 0.62f, 0.3f, 0.13f, 0.34f)
        else -> Unit
    }

    drawBody(persona.profession, skinShade)

    // Cabeça e orelhas.
    circle(skin, 0.335f, 0.43f, 0.035f)
    circle(skin, 0.665f, 0.43f, 0.035f)
    oval(skin, 0.34f, 0.2f, 0.32f, 0.42f)

    // Cabelo da frente.
    when (look.hair) {
        HairStyle.SHORT, HairStyle.LONG, HairStyle.BOB, HairStyle.PONYTAIL -> cap(hair, 0.32f, 0.17f, 0.36f, 0.17f)
        HairStyle.SIDE_SWEEP -> {
            cap(hair, 0.32f, 0.17f, 0.36f, 0.17f)
            oval(hair, 0.34f, 0.27f, 0.24f, 0.09f)
        }
        HairStyle.BUN -> {
            cap(hair, 0.32f, 0.17f, 0.36f, 0.17f)
            circle(hair, 0.5f, 0.15f, 0.075f)
        }
        HairStyle.CURLY_SHORT, HairStyle.CURLY_LONG ->
            listOf(0.36f to 0.27f, 0.43f to 0.2f, 0.5f to 0.18f, 0.57f to 0.2f, 0.64f to 0.27f, 0.34f to 0.35f, 0.66f to 0.35f)
                .forEach { (x, y) -> circle(hair, x, y, 0.065f) }
        HairStyle.BALD -> Unit
    }

    // Rosto: olhos e sorriso.
    circle(Ink, 0.43f, 0.43f, 0.019f)
    circle(Ink, 0.57f, 0.43f, 0.019f)
    if (look.beard) {
        drawArc(hair, 0f, 180f, true, Offset(0.34f * size.width, 0.36f * size.height), Size(0.32f * size.width, 0.26f * size.height))
    }
    drawArc(
        if (look.beard) White else Color(0xFF8D3B3B), 20f, 140f, false,
        Offset(0.44f * size.width, 0.48f * size.height), Size(0.12f * size.width, 0.08f * size.height),
        style = Stroke(width = 0.018f * size.width, cap = StrokeCap.Round),
    )

    drawGear(persona.profession)
}

/** Ombros, peito e braços com o uniforme da profissão, mais o pescoço. */
private fun DrawScope.drawBody(profession: Profession, skinShade: Color) {
    val outfit = Color(
        when (profession) {
            Profession.DOCTOR, Profession.SCIENTIST, Profession.CHEF -> 0xFFF5F5F5
            Profession.ENGINEER -> 0xFF78909C
            Profession.TEACHER -> 0xFF6D4C41
            Profession.FIREFIGHTER -> 0xFF37474F
            Profession.PILOT -> 0xFF1A237E
            Profession.ARTIST -> 0xFFEFEBE9
        },
    )
    box(skinShade, 0.435f, 0.56f, 0.13f, 0.2f, 0.04f)
    shape(outfit) { w, h ->
        moveTo(0.04f * w, 1.02f * h)
        cubicTo(0.04f * w, 0.80f * h, 0.20f * w, 0.73f * h, 0.40f * w, 0.71f * h)
        lineTo(0.60f * w, 0.71f * h)
        cubicTo(0.80f * w, 0.73f * h, 0.96f * w, 0.80f * h, 0.96f * w, 1.02f * h)
        close()
    }
    // Costura das mangas.
    stroke(Color(0x26000000), 0.2f, 0.8f, 0.17f, 1.0f, 0.012f)
    stroke(Color(0x26000000), 0.8f, 0.8f, 0.83f, 1.0f, 0.012f)

    when (profession) {
        Profession.DOCTOR -> {
            // Blusa verde-água em V sob o jaleco.
            shape(Color(0xFF4DB6AC)) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.92f * h); close()
            }
        }
        Profession.CHEF -> {
            circle(Color(0xFF8D8D8D), 0.46f, 0.86f, 0.016f)
            circle(Color(0xFF8D8D8D), 0.54f, 0.86f, 0.016f)
            shape(Color(0xFFD32F2F)) { w, h ->
                moveTo(0.43f * w, 0.73f * h); lineTo(0.57f * w, 0.73f * h); lineTo(0.5f * w, 0.82f * h); close()
            }
        }
        Profession.ENGINEER -> {
            // Colete laranja com faixas refletivas.
            shape(Color(0xFFFF9800)) { w, h ->
                moveTo(0.30f * w, 0.74f * h); lineTo(0.43f * w, 0.72f * h); lineTo(0.47f * w, 1.02f * h); lineTo(0.28f * w, 1.02f * h); close()
                moveTo(0.70f * w, 0.74f * h); lineTo(0.57f * w, 0.72f * h); lineTo(0.53f * w, 1.02f * h); lineTo(0.72f * w, 1.02f * h); close()
            }
            stroke(Color(0xFFFFF176), 0.37f, 0.76f, 0.375f, 1.0f, 0.02f)
            stroke(Color(0xFFFFF176), 0.63f, 0.76f, 0.625f, 1.0f, 0.02f)
        }
        Profession.TEACHER -> {
            shape(White) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.9f * h); close()
            }
        }
        Profession.FIREFIGHTER -> {
            stroke(Color(0xFFFFEB3B), 0.1f, 0.9f, 0.9f, 0.9f, 0.03f)
        }
        Profession.SCIENTIST -> {
            shape(Color(0xFF90CAF9)) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.9f * h); close()
            }
            box(Color(0xFFE53935), 0.66f, 0.86f, 0.012f, 0.06f, 0.006f)
            box(Color(0xFF1E88E5), 0.69f, 0.86f, 0.012f, 0.06f, 0.006f)
        }
        Profession.PILOT -> {
            shape(White) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.92f * h); close()
            }
            shape(Ink) { w, h ->
                moveTo(0.485f * w, 0.76f * h); lineTo(0.515f * w, 0.76f * h); lineTo(0.525f * w, 0.92f * h); lineTo(0.5f * w, 0.95f * h); lineTo(0.475f * w, 0.92f * h); close()
            }
            // Ombreiras douradas.
            box(Color(0xFFFFC107), 0.17f, 0.8f, 0.14f, 0.035f, 0.01f)
            box(Color(0xFFFFC107), 0.69f, 0.8f, 0.14f, 0.035f, 0.01f)
        }
        Profession.ARTIST -> {
            // Respingos de tinta no avental.
            circle(Color(0xFFFDD835), 0.34f, 0.88f, 0.03f)
            circle(Color(0xFFE53935), 0.62f, 0.92f, 0.025f)
            circle(Color(0xFF43A047), 0.5f, 0.95f, 0.02f)
            circle(Color(0xFF1E88E5), 0.74f, 0.86f, 0.018f)
        }
    }
}

/** Acessórios por cima do rosto: chapéus, capacetes, óculos, estetoscópio. */
private fun DrawScope.drawGear(profession: Profession) {
    when (profession) {
        Profession.DOCTOR -> {
            // Estetoscópio: arco no pescoço e disco no peito.
            drawArc(
                Color(0xFF455A64), 0f, 180f, false,
                Offset(0.36f * size.width, 0.64f * size.height), Size(0.28f * size.width, 0.26f * size.height),
                style = Stroke(width = 0.018f * size.width, cap = StrokeCap.Round),
            )
            circle(Color(0xFFB0BEC5), 0.5f, 0.9f, 0.03f)
        }
        Profession.CHEF -> {
            // Chapéu alto de cozinheiro.
            circle(White, 0.41f, 0.14f, 0.075f)
            circle(White, 0.5f, 0.1f, 0.085f)
            circle(White, 0.59f, 0.14f, 0.075f)
            box(White, 0.37f, 0.16f, 0.26f, 0.1f, 0.02f)
            stroke(Color(0x22000000), 0.37f, 0.25f, 0.63f, 0.25f, 0.012f)
        }
        Profession.ENGINEER -> {
            cap(Color(0xFFFFD600), 0.31f, 0.13f, 0.38f, 0.21f)
            box(Color(0xFFFFD600), 0.28f, 0.33f, 0.44f, 0.035f, 0.015f)
            box(Color(0xFFFFB300), 0.465f, 0.12f, 0.07f, 0.05f, 0.015f)
        }
        Profession.TEACHER -> glasses()
        Profession.FIREFIGHTER -> {
            cap(Color(0xFFD32F2F), 0.31f, 0.13f, 0.38f, 0.21f)
            box(Color(0xFFD32F2F), 0.27f, 0.33f, 0.46f, 0.04f, 0.015f)
            shape(Color(0xFFFFEB3B)) { w, h ->
                moveTo(0.46f * w, 0.2f * h); lineTo(0.54f * w, 0.2f * h); lineTo(0.54f * w, 0.27f * h); lineTo(0.5f * w, 0.3f * h); lineTo(0.46f * w, 0.27f * h); close()
            }
        }
        Profession.SCIENTIST -> {
            // Óculos de proteção na testa.
            box(Color(0xFF455A64), 0.33f, 0.31f, 0.34f, 0.035f, 0.01f)
            circle(Color(0xFF80DEEA), 0.44f, 0.328f, 0.04f)
            circle(Color(0xFF80DEEA), 0.56f, 0.328f, 0.04f)
        }
        Profession.PILOT -> {
            cap(Color(0xFF1A237E), 0.31f, 0.13f, 0.38f, 0.2f)
            box(Ink, 0.3f, 0.32f, 0.4f, 0.035f, 0.015f)
            circle(Color(0xFFFFC107), 0.5f, 0.25f, 0.032f)
        }
        Profession.ARTIST -> {
            // Boina inclinada.
            rotate(-12f, Offset(0.5f * size.width, 0.2f * size.height)) {
                oval(Color(0xFFC62828), 0.3f, 0.12f, 0.42f, 0.15f)
            }
            circle(Color(0xFFC62828), 0.52f, 0.11f, 0.018f)
        }
    }
}

private fun DrawScope.glasses() {
    val style = Stroke(width = 0.014f * size.width)
    drawCircle(Ink, 0.05f * size.width, Offset(0.43f * size.width, 0.43f * size.height), style = style)
    drawCircle(Ink, 0.05f * size.width, Offset(0.57f * size.width, 0.43f * size.height), style = style)
    stroke(Ink, 0.48f, 0.43f, 0.52f, 0.43f, 0.014f)
}
