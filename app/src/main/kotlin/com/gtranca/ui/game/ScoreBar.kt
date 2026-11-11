package com.gtranca.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gtranca.game.Persona
import com.gtranca.ui.persona.PersonaAvatar
import com.gtranca.ui.sound.LocalSound
import com.gtranca.ui.sound.SoundEffect
import com.gtranca.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** §17.11.1 tempos da barra de progressão. */
object ScoreBarTiming {
    /** Giro dos números com a pontuação final da partida. */
    const val FINAL_ROLL_MILLIS: Int = 500

    /** Destaque do novo valor final. */
    const val HIGHLIGHT_MILLIS: Int = 600

    /** Animação de um marco (500, 750, 1000). */
    const val MILESTONE_MILLIS: Int = 1_600

    /** Giro e crescimento da barra durante o jogo, quando as animações do sistema não dão um tempo. */
    const val LIVE_MILLIS: Int = 450
}

/**
 * §17.11.1 estado da barra de progressão que precisa sobreviver à troca da mesa em jogo pela mesa final: a escala da
 * partida (que nunca volta) e a pontuação final mostrada depois dos fogos.
 */
@Stable
class ScoreBarHolder {
    private var round = -1
    var scale by mutableStateOf(ScoreBarScale())
        private set

    /** Pontuação final da partida por lado ([com.gtranca.engine.model.Side.index]); `null` = total da mesa ao vivo. */
    var finals by mutableStateOf<List<Int>?>(null)
        private set

    /** Marco recém-alcançado (1, 2 ou 3) e um contador que dispara a animação dele. */
    var milestone by mutableIntStateOf(0)
        private set
    var milestoneTick by mutableIntStateOf(0)
        private set

    /** Muda a cada novo valor final (dispara o destaque dos números). */
    var highlightTick by mutableIntStateOf(0)
        private set

    /** Começa do zero ao mudar a partida [number]. */
    fun startRound(number: Int) {
        if (number == round) return
        round = number
        scale = ScoreBarScale()
        finals = null
        milestone = 0
    }

    /** Os totais [totals] apareceram na barra: a escala sobe se passaram de um marco (e anima o maior). */
    fun see(totals: List<Int>) {
        val next = scale.advance(totals)
        if (next.level > scale.level) {
            milestone = next.level
            milestoneTick++
        }
        scale = next
    }

    /** Mostra a pontuação final [scores]; devolve se ela vai alcançar um marco novo (o fim espera a animação). */
    fun showFinal(scores: List<Int>): Boolean {
        if (finals == scores) return false
        finals = scores
        highlightTick++
        return scale.advance(scores).level > scale.level
    }
}

/** §17.11.1 a barra da partida em andamento (fornecida pelo [GameScreen]; sem ela, a mesa usa uma local). */
val LocalScoreBar = staticCompositionLocalOf<ScoreBarHolder?> { null }

/** Vermelho da barra e dos totais negativos (o mesmo do b4, §17.11). */
internal val ScoreBarRed = Color(0xFFFF8A80)

/**
 * §17.11.1 barra de progressão do b4: zero no meio, o adversário à esquerda e o jogador à direita, arte e número nas
 * pontas. [ownTotal]/[opponentTotal] são os totais da mesa ao vivo (`null` até a primeira jogada pousar); a pontuação
 * final do [holder], quando existe, toma o lugar deles.
 */
@Composable
fun ScoreBar(
    holder: ScoreBarHolder,
    ownSide: Int,
    opponentSide: Int,
    ownTotal: Int?,
    opponentTotal: Int?,
    ownPersonas: List<Persona>,
    opponentPersonas: List<Persona>,
    animationMillis: Long,
    modifier: Modifier = Modifier,
) {
    val finals = holder.finals
    val own = finals?.getOrNull(ownSide) ?: ownTotal ?: return
    val opponent = finals?.getOrNull(opponentSide) ?: opponentTotal ?: return
    val animate = animationMillis > 0
    val duration = when {
        !animate -> 0
        finals != null -> ScoreBarTiming.FINAL_ROLL_MILLIS
        else -> animationMillis.toInt().coerceIn(250, ScoreBarTiming.LIVE_MILLIS * 2)
    }
    // A escala sobe quando o valor aparece; no fim, depois do giro e do destaque.
    LaunchedEffect(own, opponent, finals != null) {
        if (finals != null && animate) delay((ScoreBarTiming.FINAL_ROLL_MILLIS + ScoreBarTiming.HIGHLIGHT_MILLIS).toLong())
        holder.see(List(maxOf(ownSide, opponentSide) + 1) { i -> if (i == ownSide) own else if (i == opponentSide) opponent else 0 })
    }
    val sound = LocalSound.current
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(holder.milestoneTick) {
        if (holder.milestoneTick == 0) return@LaunchedEffect
        when (holder.milestone) {
            1 -> sound.play(SoundEffect.CHIME)
            2 -> sound.play(SoundEffect.CANASTA)
            else -> sound.play(SoundEffect.WIN)
        }
        if (!animate) return@LaunchedEffect
        pulse.snapTo(0f)
        pulse.animateTo(1f, tween(ScoreBarTiming.MILESTONE_MILLIS, easing = LinearEasing))
        pulse.snapTo(0f)
    }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(holder.highlightTick) {
        if (holder.highlightTick == 0 || !animate) return@LaunchedEffect
        delay(ScoreBarTiming.FINAL_ROLL_MILLIS.toLong())
        highlight.snapTo(0f)
        highlight.animateTo(1f, tween(ScoreBarTiming.HIGHLIGHT_MILLIS, easing = LinearEasing))
        highlight.snapTo(0f)
    }
    val scale = holder.scale
    val p = pulse.value
    val level = holder.milestone
    // Tremor no marco do 750 e mais forte no 1000.
    val shake = if (p in 0.01f..0.99f && level >= 2) sin(p * 40f) * (1f - p) * (if (level >= 3) 5f else 3f) else 0f
    Box(
        modifier.fillMaxWidth().zIndex(2f).testTag("score-bar")
            .graphicsLayer { translationX = shake * density },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(GColors.Midnight.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .border(1.dp, GColors.Gold.copy(alpha = 0.35f + 0.65f * glow(p, level)), RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SideArt(opponentPersonas, centerLast = true)
            BarNumber(opponent, duration, highlight.value, Modifier.testTag("score-bar-opponent"))
            Track(
                scale = scale,
                ownSide = ownSide,
                opponentSide = opponentSide,
                own = own,
                opponent = opponent,
                duration = duration,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            BarNumber(own, duration, highlight.value, Modifier.testTag("score-bar-own"))
            SideArt(ownPersonas, centerLast = false)
        }
        if (p in 0.001f..0.999f) MilestoneEffect(level, p, Modifier.matchParentSize())
    }
}

/** Brilho da borda no marco: pulsa duas vezes (500) ou três (750, 1000). */
private fun glow(p: Float, level: Int): Float {
    if (p <= 0f || p >= 1f) return 0f
    val beats = if (level >= 2) 3f else 2f
    return ((1f - cos(p * beats * 2f * PI.toFloat())) / 2f) * (1f - p * 0.5f)
}

/** Arte de um lado; em duplas, lado a lado, a mais perto do centro cobrindo 40% da outra. */
@Composable
private fun SideArt(personas: List<Persona>, centerLast: Boolean) {
    if (personas.isEmpty()) return
    val size: Dp = 26.dp
    // A de dentro é desenhada por último (por cima). À esquerda, a de dentro fica à direita; à direita, à esquerda.
    val ordered = if (centerLast) personas.reversed() else personas
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(-(size * 0.4f))) {
        ordered.forEachIndexed { i, persona ->
            val inner = if (centerLast) i == ordered.lastIndex else i == 0
            PersonaAvatar(
                persona, size,
                Modifier.zIndex(if (inner) 1f else 0f).border(1.5.dp, GColors.Gold.copy(alpha = 0.8f), CircleShape),
            )
        }
    }
}

/** O total de um lado, girando como letreiro; dourado se positivo, vermelho se negativo. */
@Composable
private fun BarNumber(value: Int, duration: Int, highlight: Float, modifier: Modifier = Modifier) {
    val pop = if (highlight in 0.001f..0.999f) sin(highlight * PI.toFloat()) else 0f
    RollingNumber(
        value = value,
        durationMillis = duration,
        color = if (value < 0) ScoreBarRed else GColors.Gold,
        glow = pop,
        modifier = modifier.widthIn(min = 38.dp).padding(horizontal = 3.dp)
            .graphicsLayer { scaleX = 1f + 0.45f * pop; scaleY = 1f + 0.45f * pop },
    )
}

/**
 * Número estilo letreiro: passa pelos valores intermediários em [durationMillis] e cada dígito desliza na vertical
 * (para cima quando sobe, para baixo quando desce). [glow] (0 a 1) acende um brilho dourado em volta.
 */
@Composable
fun RollingNumber(value: Int, durationMillis: Int, color: Color, modifier: Modifier = Modifier, glow: Float = 0f) {
    val shown by animateIntAsState(value, tween(durationMillis, easing = FastOutSlowInEasing), label = "rolling")
    val last = remember { IntArray(2).also { it[0] = shown; it[1] = 1 } }
    if (shown != last[0]) {
        last[1] = if (shown > last[0]) 1 else -1
        last[0] = shown
    }
    val up = last[1] > 0
    val text = if (shown < 0) "−${-shown}" else "$shown"
    val digit = (durationMillis / 5).coerceIn(0, 90)
    val style = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.Bold,
        shadow = if (glow > 0f) Shadow(GColors.Gold.copy(alpha = glow), Offset.Zero, 18f * glow) else null,
    )
    Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        text.forEachIndexed { i, c ->
            key(text.length - i) {
                AnimatedContent(
                    targetState = c,
                    transitionSpec = {
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(digit)) { h -> h * dir } + fadeIn(tween(digit))) togetherWith
                            (slideOutVertically(tween(digit)) { h -> -h * dir } + fadeOut(tween(digit))) using SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { ch -> Text("$ch", color = color, style = style, textAlign = TextAlign.Center) }
            }
        }
    }
}

/**
 * A trilha: zero no meio, cada metade enche do centro para fora. Nos marcos alcançados, com o número embaixo: estrela na
 * metade do lado que já chegou lá, linha fina na do outro.
 */
@Composable
private fun Track(scale: ScoreBarScale, ownSide: Int, opponentSide: Int, own: Int, opponent: Int, duration: Int, modifier: Modifier) {
    val spec = tween<Float>(duration, easing = FastOutSlowInEasing)
    val ownFill by animateFloatAsState(scale.fraction(own), spec, label = "own-fill")
    val oppFill by animateFloatAsState(scale.fraction(opponent), spec, label = "opp-fill")
    BoxWithConstraints(modifier.height(30.dp)) {
        val half = maxWidth / 2
        val trackTop = 5.dp
        val trackHeight = 12.dp
        // Fundo
        Box(
            Modifier.offset(y = trackTop).fillMaxWidth().height(trackHeight)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .border(1.dp, GColors.Gold.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
        )
        // Adversário: do centro para a esquerda.
        Box(
            Modifier.offset(x = half - half * oppFill, y = trackTop).width(half * oppFill).height(trackHeight)
                .background(fillColor(opponent), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
        )
        // Jogador: do centro para a direita.
        Box(
            Modifier.offset(x = half, y = trackTop).width(half * ownFill).height(trackHeight)
                .background(fillColor(own), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
        )
        // Zero no meio.
        Box(Modifier.offset(x = half - 1.dp, y = trackTop - 2.dp).width(2.dp).height(trackHeight + 4.dp).background(GColors.Champagne))
        // Marcações (as duas metades), escorregando quando o teto sobe.
        scale.marks.forEach { mark ->
            val f by animateFloatAsState(scale.markFraction(mark), spec, label = "mark-$mark")
            listOf(ownSide to half + half * f, opponentSide to half - half * f).forEach { (side, x) ->
                val tag = if (side == ownSide) "own" else "opponent"
                if (scale.hasStar(side, mark)) {
                    Text(
                        "★",
                        Modifier.offset(x = x - 6.dp, y = trackTop - 3.dp).width(12.dp).testTag("score-bar-star-$tag-$mark"),
                        color = Color(0xFFFFF3B0),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Box(
                        Modifier.offset(x = x - 0.5.dp, y = trackTop - 1.dp).width(1.dp).height(trackHeight + 2.dp)
                            .background(GColors.Champagne.copy(alpha = 0.8f)).testTag("score-bar-tick-$tag-$mark"),
                    )
                }
                Text(
                    "$mark",
                    Modifier.offset(x = x - 14.dp, y = trackTop + trackHeight).width(28.dp),
                    color = GColors.Champagne,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun fillColor(value: Int): Color = if (value < 0) ScoreBarRed else GColors.Gold

/**
 * Animação de um marco, por cima da barra ([p] de 0 a 1): 500 só o brilho da borda (no [ScoreBar]); 750 chuva de
 * estrelinhas; 1000 mini fogos.
 */
@Composable
private fun MilestoneEffect(level: Int, p: Float, modifier: Modifier) {
    if (level < 2) return
    val seeds = remember(level) {
        val rnd = Random(level * 31)
        List(if (level >= 3) 5 else 22) { Triple(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
    }
    Canvas(modifier) {
        if (level == 2) {
            // Estrelinhas caindo e girando sobre a barra.
            seeds.forEach { (x, delayF, s) ->
                val t = ((p - delayF * 0.4f) / 0.6f).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) return@forEach
                val cx = x * size.width
                val cy = -size.height * 0.6f + t * size.height * 1.8f
                val r = (2.5f + s * 3f) * density
                val alpha = 1f - t
                drawStar(Offset(cx, cy), r, Color(0xFFFFF3B0).copy(alpha = alpha), t * 6f)
            }
        } else {
            // Mini fogos: estouros ao longo da barra.
            seeds.forEachIndexed { b, (x, delayF, s) ->
                val t = ((p - delayF * 0.45f) / 0.55f).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) return@forEachIndexed
                val center = Offset(x * size.width, size.height * (0.2f + s * 0.6f))
                val reach = size.height * (1.1f + s) * t
                val colors = listOf(GColors.Gold, Color(0xFFFFF3B0), Color(0xFF7FD3FF), Color(0xFFFF8A80))
                repeat(12) { k ->
                    val a = k / 12f * 2f * PI.toFloat() + b
                    drawCircle(
                        colors[(k + b) % colors.size].copy(alpha = 1f - t),
                        radius = 2.2f * density * (1f - t * 0.5f),
                        center = center + Offset(cos(a) * reach, sin(a) * reach),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(center: Offset, r: Float, color: Color, turn: Float) {
    val path = androidx.compose.ui.graphics.Path()
    repeat(10) { i ->
        val radius = if (i % 2 == 0) r else r * 0.45f
        val a = turn + i * PI.toFloat() / 5f - PI.toFloat() / 2f
        val point = Offset(center.x + cos(a) * radius, center.y + sin(a) * radius)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color)
}
