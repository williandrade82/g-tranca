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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import com.gtranca.game.FaceShape
import com.gtranca.game.Gender
import com.gtranca.game.HairStyle
import com.gtranca.game.Look
import com.gtranca.game.Persona
import com.gtranca.game.Profession

internal val SkinTones = listOf(0xFFF8D9C0, 0xFFEEC39A, 0xFFD49A6A, 0xFFA9714B, 0xFF6F4426).map { Color(it) }
internal val HairColors = listOf(0xFF2B2B2B, 0xFF5A3A22, 0xFF8D5524, 0xFFD8B15A, 0xFFB5482A, 0xFF9E9E9E).map { Color(it) }

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
        Profession.NURSE -> 0xFFB3E5FC
        Profession.POLICE -> 0xFFCFD8DC
        Profession.FARMER -> 0xFFDCEDC8
        Profession.MECHANIC -> 0xFFFFCCBC
        Profession.MUSICIAN -> 0xFFD1C4E9
        Profession.LAWYER -> 0xFFD7CCC8
        Profession.PHOTOGRAPHER -> 0xFFF0F4C3
        Profession.SAILOR -> 0xFFB2EBF2
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

private fun light(color: Color, amount: Float = 0.22f) = lerp(color, Color.White, amount)

private fun dark(color: Color, amount: Float = 0.24f) = lerp(color, Color.Black, amount)

/** Degradê de luz (canto superior esquerdo) para sombra (inferior direito): dá volume a qualquer forma. */
private fun DrawScope.volume(color: Color, left: Float, top: Float, w: Float, h: Float): Brush =
    if (color.alpha < 1f) {
        Brush.linearGradient(listOf(color, color), Offset.Zero, Offset(1f, 1f))
    } else {
        Brush.linearGradient(
            listOf(light(color), color, dark(color)),
            start = Offset(left * size.width, top * size.height),
            end = Offset((left + w) * size.width, (top + h) * size.height),
        )
    }

private fun DrawScope.circle(color: Color, cx: Float, cy: Float, r: Float) = oval(color, cx - r, cy - r, 2 * r, 2 * r)

private fun DrawScope.flatCircle(color: Color, cx: Float, cy: Float, r: Float) =
    drawCircle(color, r * size.width, Offset(cx * size.width, cy * size.height))

private fun DrawScope.oval(color: Color, left: Float, top: Float, w: Float, h: Float) =
    drawOval(volume(color, left, top, w, h), Offset(left * size.width, top * size.height), Size(w * size.width, h * size.height))

private fun DrawScope.box(color: Color, left: Float, top: Float, w: Float, h: Float, corner: Float = 0f) =
    drawRoundRect(
        volume(color, left, top, w, h),
        Offset(left * size.width, top * size.height),
        Size(w * size.width, h * size.height),
        CornerRadius(corner * size.width),
    )

private fun DrawScope.shape(color: Color, build: Path.(Float, Float) -> Unit) {
    val path = Path().apply { build(size.width, size.height) }
    val bounds = path.getBounds()
    val brush = if (color.alpha < 1f) {
        Brush.linearGradient(listOf(color, color), Offset.Zero, Offset(1f, 1f))
    } else {
        Brush.linearGradient(listOf(light(color), color, dark(color)), bounds.topLeft, bounds.bottomRight)
    }
    drawPath(path, brush)
}

private fun DrawScope.stroke(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) =
    drawLine(color, Offset(x1 * size.width, y1 * size.height), Offset(x2 * size.width, y2 * size.height), strokeWidth = width * size.width, cap = StrokeCap.Round)

/** Metade de cima de uma elipse (cabelo, capacete, boina), com um brilho no alto. */
private fun DrawScope.cap(color: Color, left: Float, top: Float, w: Float, h: Float) {
    drawArc(
        volume(color, left, top, w, h * 2), 180f, 180f, true,
        Offset(left * size.width, top * size.height), Size(w * size.width, h * size.height * 2),
    )
    drawArc(
        Color(0x40FFFFFF), 205f, 55f, false,
        Offset((left + w * 0.12f) * size.width, (top + h * 0.2f) * size.height), Size(w * 0.76f * size.width, h * 1.5f * size.height),
        style = Stroke(width = 0.018f * size.width, cap = StrokeCap.Round),
    )
}

/** Contorno da cabeça por formato: pares (meia-largura, altura) do alto ao queixo; a curva passa suave por eles. */
private class FaceSpec(val knots: List<Pair<Float, Float>>, val earHalf: Float)

private val FaceSpecs = mapOf(
    FaceShape.OVAL to FaceSpec(listOf(0f to 0.19f, 0.09f to 0.2f, 0.15f to 0.27f, 0.168f to 0.38f, 0.155f to 0.49f, 0.11f to 0.57f, 0.05f to 0.615f, 0f to 0.625f), 0.168f),
    FaceShape.ROUND to FaceSpec(listOf(0f to 0.2f, 0.1f to 0.205f, 0.165f to 0.28f, 0.182f to 0.4f, 0.172f to 0.5f, 0.125f to 0.59f, 0.055f to 0.63f, 0f to 0.64f), 0.182f),
    FaceShape.SQUARE to FaceSpec(listOf(0f to 0.2f, 0.1f to 0.205f, 0.16f to 0.27f, 0.168f to 0.4f, 0.164f to 0.52f, 0.14f to 0.59f, 0.075f to 0.625f, 0f to 0.628f), 0.168f),
    FaceShape.HEART to FaceSpec(listOf(0f to 0.2f, 0.1f to 0.2f, 0.17f to 0.26f, 0.176f to 0.35f, 0.14f to 0.47f, 0.08f to 0.57f, 0.03f to 0.625f, 0f to 0.645f), 0.176f),
    FaceShape.LONG to FaceSpec(listOf(0f to 0.16f, 0.08f to 0.17f, 0.135f to 0.24f, 0.15f to 0.38f, 0.14f to 0.52f, 0.1f to 0.61f, 0.045f to 0.66f, 0f to 0.67f), 0.15f),
)

private fun DrawScope.headPath(face: FaceSpec): Path {
    val w = size.width
    val h = size.height
    val points = face.knots.map { (half, y) -> Offset((0.5f + half) * w, y * h) } +
        face.knots.reversed().drop(1).dropLast(1).map { (half, y) -> Offset((0.5f - half) * w, y * h) }
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val path = Path()
    val start = mid(points.last(), points.first())
    path.moveTo(start.x, start.y)
    points.indices.forEach { i ->
        val end = mid(points[i], points[(i + 1) % points.size])
        path.quadraticBezierTo(points[i].x, points[i].y, end.x, end.y)
    }
    path.close()
    return path
}

private val EyeColors = listOf(0xFF4E342E, 0xFF5D4037, 0xFF8D6E63, 0xFF1E88E5, 0xFF43A047, 0xFF78909C).map { Color(it) }

private fun DrawScope.drawPersona(persona: Persona) {
    val look = persona.look
    val female = persona.gender == Gender.FEMALE
    val skin = SkinTones[look.skin]
    val skinShade = dark(skin, 0.12f)
    val hair = HairColors[look.hairColor]
    val spec = FaceSpecs.getValue(look.face)
    val background = persona.profession.background()

    // Fundo com luz no centro.
    drawRect(
        Brush.radialGradient(
            listOf(light(background, 0.4f), background, dark(background, 0.14f)),
            center = Offset(0.5f * size.width, 0.38f * size.height),
            radius = 0.78f * size.width,
        ),
    )

    // Cabelo de trás (longos e rabo de cavalo), por baixo dos ombros.
    when (look.hair) {
        HairStyle.LONG -> box(hair, 0.29f, 0.2f, 0.42f, 0.56f, 0.18f)
        HairStyle.BOB -> box(hair, 0.3f, 0.2f, 0.4f, 0.36f, 0.14f)
        HairStyle.CURLY_LONG -> box(hair, 0.28f, 0.24f, 0.44f, 0.46f, 0.18f)
        HairStyle.PONYTAIL -> oval(hair, 0.62f, 0.3f, 0.13f, 0.34f)
        else -> Unit
    }

    drawBody(persona.profession, skinShade)
    // Luz na linha do ombro e sombra do queixo no pescoço.
    stroke(Color(0x33FFFFFF), 0.14f, 0.84f, 0.3f, 0.755f, 0.02f)
    box(Color(0x40000000), 0.435f, 0.585f, 0.13f, 0.075f, 0.035f)

    val head = headPath(spec)
    val earY = 0.44f
    listOf(0.5f - spec.earHalf - 0.004f, 0.5f + spec.earHalf + 0.004f).forEach { x ->
        circle(skin, x, earY, 0.034f)
        flatCircle(Color(0x30000000), x, earY, 0.017f)
    }
    // Cabeça com volume: luz no alto à esquerda, sombra embaixo à direita, e contorno suave.
    drawPath(
        head,
        Brush.radialGradient(
            listOf(light(skin, 0.16f), skin, dark(skin, 0.2f)),
            center = Offset(0.44f * size.width, 0.35f * size.height),
            radius = 0.34f * size.width,
        ),
    )
    drawPath(head, dark(skin, 0.4f).copy(alpha = 0.55f), style = Stroke(width = 0.008f * size.width))

    // Bochechas.
    flatCircle(Color(0x30FF6F61), 0.385f, 0.52f, 0.036f)
    flatCircle(Color(0x30FF6F61), 0.615f, 0.52f, 0.036f)

    // Barba: a parte de baixo do rosto (recortada no contorno) com a boca à mostra.
    if (look.beard) {
        clipPath(head) {
            drawRect(volume(hair, 0.3f, 0.5f, 0.4f, 0.2f), Offset(0f, 0.5f * size.height), Size(size.width, 0.2f * size.height))
        }
        oval(skin, 0.43f, 0.505f, 0.14f, 0.07f)
    }

    // Cabelo da frente.
    when (look.hair) {
        HairStyle.SHORT, HairStyle.LONG, HairStyle.BOB, HairStyle.PONYTAIL -> cap(hair, 0.32f, 0.14f, 0.36f, 0.2f)
        HairStyle.SIDE_SWEEP -> {
            cap(hair, 0.32f, 0.14f, 0.36f, 0.2f)
            oval(hair, 0.34f, 0.27f, 0.24f, 0.09f)
        }
        HairStyle.BUN -> {
            cap(hair, 0.32f, 0.14f, 0.36f, 0.2f)
            circle(hair, 0.5f, 0.13f, 0.075f)
        }
        HairStyle.CURLY_SHORT, HairStyle.CURLY_LONG ->
            listOf(0.36f to 0.27f, 0.43f to 0.2f, 0.5f to 0.18f, 0.57f to 0.2f, 0.64f to 0.27f, 0.34f to 0.35f, 0.66f to 0.35f, 0.4f to 0.23f, 0.6f to 0.23f)
                .forEach { (x, y) -> circle(hair, x, y, 0.065f) }
        HairStyle.BALD -> Unit
    }

    drawFace(look, hair, skin, spec, female)
    drawGear(persona.profession)
}

/** Olhos, sobrancelhas, nariz e boca. */
private fun DrawScope.drawFace(look: Look, hair: Color, skin: Color, spec: FaceSpec, female: Boolean) {
    val w = size.width
    val h = size.height
    val eyeY = if (look.face == FaceShape.LONG) 0.455f else 0.44f
    val iris = EyeColors[if (look.skin >= 3) 0 else (look.hairColor + look.skin) % EyeColors.size]
    listOf(-1f, 1f).forEach { side ->
        val ex = 0.5f + side * 0.075f
        // Olho: branco, íris, pupila e brilho; pálpebra por cima.
        flatCircle(Color(0xFFFDFDFD), ex, eyeY, 0.03f)
        flatCircle(iris, ex, eyeY + 0.002f, 0.019f)
        flatCircle(Ink, ex, eyeY + 0.002f, 0.009f)
        flatCircle(Color(0xFFFFFFFF), ex - 0.006f, eyeY - 0.005f, 0.0055f)
        drawArc(
            dark(skin, 0.45f), 200f, 140f, false,
            Offset((ex - 0.031f) * w, (eyeY - 0.03f) * h), Size(0.062f * w, 0.06f * h),
            style = Stroke(width = 0.011f * w, cap = StrokeCap.Round),
        )
        if (female) {
            stroke(Ink, ex + side * 0.028f, eyeY - 0.012f, ex + side * 0.04f, eyeY - 0.024f, 0.007f)
            stroke(Ink, ex + side * 0.03f, eyeY - 0.004f, ex + side * 0.043f, eyeY - 0.01f, 0.006f)
        }
        // Sobrancelha: fina e arqueada no feminino, mais grossa e reta no masculino.
        drawArc(
            dark(hair, 0.15f), if (female) 205f else 215f, if (female) 120f else 100f, false,
            Offset((ex - 0.04f) * w, (eyeY - 0.075f) * h), Size(0.08f * w, 0.05f * h),
            style = Stroke(width = (if (female) 0.011f else 0.017f) * w, cap = StrokeCap.Round),
        )
    }
    // Nariz: sombra suave do lado e ponta.
    drawArc(
        dark(skin, 0.3f).copy(alpha = 0.75f), 30f, 120f, false,
        Offset(0.465f * w, 0.485f * h), Size(0.07f * w, 0.04f * h),
        style = Stroke(width = 0.009f * w, cap = StrokeCap.Round),
    )
    flatCircle(Color(0x30FFFFFF), 0.49f, 0.47f, 0.013f)
    // Boca: sorriso com dentes e lábios.
    val smile = Path().apply {
        moveTo(0.44f * w, 0.54f * h)
        quadraticBezierTo(0.5f * w, 0.605f * h, 0.56f * w, 0.54f * h)
        quadraticBezierTo(0.5f * w, 0.55f * h, 0.44f * w, 0.54f * h)
        close()
    }
    drawPath(smile, Color(0xFFFDFDFD))
    val lip = if (female) Color(0xFFD35D6E) else Color(0xFFB5524F)
    val lipLine = Path().apply {
        moveTo(0.44f * w, 0.54f * h)
        quadraticBezierTo(0.5f * w, 0.605f * h, 0.56f * w, 0.54f * h)
    }
    drawPath(lipLine, lip, style = Stroke(width = (if (female) 0.018f else 0.013f) * w, cap = StrokeCap.Round))
    drawLine(lip, Offset(0.445f * w, 0.54f * h), Offset(0.555f * w, 0.54f * h), strokeWidth = 0.008f * w, cap = StrokeCap.Round)
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
            Profession.NURSE -> 0xFF4FC3F7
            Profession.POLICE -> 0xFF1F3A5F
            Profession.FARMER -> 0xFFC62828
            Profession.MECHANIC -> 0xFF1E88E5
            Profession.MUSICIAN -> 0xFF7B1FA2
            Profession.LAWYER -> 0xFF455A64
            Profession.PHOTOGRAPHER -> 0xFFA1887F
            Profession.SAILOR -> 0xFFFAFAFA
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
        Profession.NURSE -> {
            shape(White) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.86f * h); close()
            }
            box(Color(0xFF0288D1), 0.64f, 0.84f, 0.1f, 0.08f, 0.012f)
        }
        Profession.POLICE -> {
            shape(White) { w, h ->
                moveTo(0.43f * w, 0.72f * h); lineTo(0.57f * w, 0.72f * h); lineTo(0.5f * w, 0.84f * h); close()
            }
            shape(Ink) { w, h ->
                moveTo(0.485f * w, 0.75f * h); lineTo(0.515f * w, 0.75f * h); lineTo(0.525f * w, 0.9f * h); lineTo(0.5f * w, 0.93f * h); lineTo(0.475f * w, 0.9f * h); close()
            }
            circle(Color(0xFFFFC107), 0.7f, 0.88f, 0.04f)
        }
        Profession.FARMER -> {
            // Macacão jeans sobre camisa xadrez.
            box(Color(0xFF1565C0), 0.34f, 0.84f, 0.32f, 0.2f, 0.03f)
            stroke(Color(0xFF1565C0), 0.38f, 0.74f, 0.4f, 0.86f, 0.045f)
            stroke(Color(0xFF1565C0), 0.62f, 0.74f, 0.6f, 0.86f, 0.045f)
            circle(Color(0xFFFFC107), 0.4f, 0.87f, 0.014f)
            circle(Color(0xFFFFC107), 0.6f, 0.87f, 0.014f)
        }
        Profession.MECHANIC -> {
            box(White, 0.6f, 0.84f, 0.12f, 0.06f, 0.012f)
            stroke(Color(0xFF0D47A1), 0.5f, 0.76f, 0.5f, 1.0f, 0.012f)
            circle(Color(0xFFFFB300), 0.5f, 0.8f, 0.014f)
        }
        Profession.MUSICIAN -> {
            shape(White) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.9f * h); close()
            }
            circle(Color(0xFFFFC107), 0.7f, 0.86f, 0.018f)
        }
        Profession.LAWYER -> {
            shape(White) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.9f * h); close()
            }
            shape(Color(0xFFC62828)) { w, h ->
                moveTo(0.485f * w, 0.76f * h); lineTo(0.515f * w, 0.76f * h); lineTo(0.525f * w, 0.92f * h); lineTo(0.5f * w, 0.96f * h); lineTo(0.475f * w, 0.92f * h); close()
            }
        }
        Profession.PHOTOGRAPHER -> {
            shape(Color(0xFF8D6E63)) { w, h ->
                moveTo(0.36f * w, 0.73f * h); lineTo(0.43f * w, 0.72f * h); lineTo(0.6f * w, 0.98f * h); lineTo(0.53f * w, 1.0f * h); close()
            }
            box(Ink, 0.55f, 0.86f, 0.2f, 0.12f, 0.02f)
            circle(Color(0xFF90A4AE), 0.65f, 0.92f, 0.04f)
            circle(Ink, 0.65f, 0.92f, 0.022f)
        }
        Profession.SAILOR -> {
            // Camisa listrada e lenço azul.
            listOf(0.8f, 0.87f, 0.94f).forEach { y -> stroke(Color(0xFF1565C0), 0.1f, y, 0.9f, y, 0.03f) }
            shape(Color(0xFF1565C0)) { w, h ->
                moveTo(0.42f * w, 0.72f * h); lineTo(0.58f * w, 0.72f * h); lineTo(0.5f * w, 0.8f * h); close()
            }
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
        Profession.NURSE -> {
            // Touca azul com uma cruz.
            cap(Color(0xFF29B6F6), 0.31f, 0.14f, 0.38f, 0.2f)
            box(White, 0.485f, 0.2f, 0.03f, 0.08f)
            box(White, 0.46f, 0.225f, 0.08f, 0.03f)
        }
        Profession.POLICE -> {
            cap(Color(0xFF1F3A5F), 0.31f, 0.13f, 0.38f, 0.2f)
            box(Ink, 0.29f, 0.32f, 0.42f, 0.04f, 0.015f)
            circle(Color(0xFFFFC107), 0.5f, 0.25f, 0.032f)
        }
        Profession.FARMER -> {
            // Chapéu de palha de aba larga.
            oval(Color(0xFFE0B66B), 0.2f, 0.22f, 0.6f, 0.12f)
            cap(Color(0xFFE0B66B), 0.34f, 0.1f, 0.32f, 0.17f)
            box(Color(0xFF8D5524), 0.345f, 0.22f, 0.31f, 0.03f)
        }
        Profession.MECHANIC -> {
            cap(Color(0xFFD32F2F), 0.32f, 0.14f, 0.36f, 0.19f)
            box(Color(0xFFD32F2F), 0.5f, 0.31f, 0.26f, 0.035f, 0.015f)
            // Mancha de graxa na bochecha.
            circle(Color(0x66000000), 0.6f, 0.5f, 0.022f)
        }
        Profession.MUSICIAN -> {
            // Fones: arco sobre a cabeça e conchas nas orelhas.
            drawArc(
                Ink, 180f, 180f, false,
                Offset(0.32f * size.width, 0.12f * size.height), Size(0.36f * size.width, 0.44f * size.height),
                style = Stroke(width = 0.03f * size.width, cap = StrokeCap.Round),
            )
            circle(Color(0xFFFF5722), 0.33f, 0.43f, 0.05f)
            circle(Color(0xFFFF5722), 0.67f, 0.43f, 0.05f)
        }
        Profession.LAWYER -> Unit
        Profession.PHOTOGRAPHER -> {
            // Boné cinza e alça da câmera.
            cap(Color(0xFF607D8B), 0.32f, 0.14f, 0.36f, 0.19f)
            box(Color(0xFF607D8B), 0.3f, 0.31f, 0.22f, 0.035f, 0.015f)
        }
        Profession.SAILOR -> {
            cap(White, 0.3f, 0.14f, 0.4f, 0.2f)
            box(Color(0xFF1565C0), 0.3f, 0.3f, 0.4f, 0.04f, 0.015f)
            circle(Color(0xFF1565C0), 0.5f, 0.12f, 0.025f)
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
