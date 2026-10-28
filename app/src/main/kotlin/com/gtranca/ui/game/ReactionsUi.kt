package com.gtranca.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.gtranca.R
import kotlinx.coroutines.launch
import com.gtranca.engine.Reaction
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GColors

/** Imagem (cartão recortado de `reacts-v1.png`) de cada reação. */
fun reactionIconRes(reaction: Reaction): Int = when (reaction) {
    Reaction.APPLAUSE -> R.drawable.reaction_clap
    Reaction.LOVE -> R.drawable.reaction_love
    Reaction.THINKING -> R.drawable.reaction_tired
    Reaction.ANGRY -> R.drawable.reaction_angry
    Reaction.CHICKEN -> R.drawable.reaction_chicken
}

fun reactionLabelRes(reaction: Reaction): Int = when (reaction) {
    Reaction.APPLAUSE -> R.string.reaction_applause
    Reaction.LOVE -> R.string.reaction_love
    Reaction.THINKING -> R.string.reaction_thinking
    Reaction.ANGRY -> R.string.reaction_angry
    Reaction.CHICKEN -> R.string.reaction_chicken
}

/** Fica acima do âncora, alinhado à direita dele e dentro da janela. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * §17.8 botão de reações da linha "Sua mão" (só com o b3 na partida; docs/telas.md 3.8): mostra os usos restantes, abre o
 * balão de reações acima dele e, depois do envio, a reação escolhida aparece por alguns segundos.
 */
@Composable
internal fun ReactionsButton(state: GameUiState, onReaction: (Reaction) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val left = state.reactionsLeft
    val enabled = left > 0
    val description = stringResource(R.string.reactions_button, left)
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button) { open = true }
            .semantics { contentDescription = description }
            .testTag("reactions-button"),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.booster_b3), null, Modifier.size(32.dp), contentScale = ContentScale.Fit)
        Text(
            left.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = GColors.Gold,
            modifier = Modifier.align(Alignment.BottomEnd).background(GColors.Midnight.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 4.dp).testTag("reactions-left"),
        )
        if (open && enabled) {
            ReactionsBalloon(left, onDismiss = { open = false }) { reaction ->
                open = false
                onReaction(reaction)
            }
        }
    }
}

/** O balão de fala acima do botão: as 5 reações (imagem e nome) e quantas restam; fecha ao escolher ou ao tocar fora. */
@Composable
private fun ReactionsBalloon(left: Int, onDismiss: () -> Unit, onPick: (Reaction) -> Unit) {
    Popup(popupPositionProvider = AboveAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("reactions-balloon")) {
            Column(
                Modifier
                    .background(GColors.Midnight.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                    .border(1.dp, GColors.Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    LocalContext.current.resources.getQuantityString(R.plurals.reactions_left, left, left),
                    style = MaterialTheme.typography.labelMedium,
                    color = GColors.Gold,
                    modifier = Modifier.testTag("reactions-balloon-left"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Reaction.entries.forEach { reaction ->
                        val label = stringResource(reactionLabelRes(reaction))
                        Column(
                            Modifier
                                .width(54.dp)
                                .clickable(role = Role.Button) { onPick(reaction) }
                                .semantics { contentDescription = label }
                                .testTag("reaction-${reaction.code}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(painterResource(reactionIconRes(reaction)), null, Modifier.size(54.dp), contentScale = ContentScale.Fit)
                            FitText(label, TextStyle(color = GColors.Ivory, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), minFontSp = 7)
                        }
                    }
                }
            }
            // Ponta do balão, apontando para o botão.
            Canvas(Modifier.padding(end = 14.dp).size(width = 16.dp, height = 8.dp)) {
                val tail = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
                drawPath(tail, GColors.Midnight.copy(alpha = 0.96f))
            }
        }
    }
}

/**
 * §17.8 a reação enviada por qualquer assento, em tamanho médio no **meio da tela**: sai da direção de quem a enviou (a própria,
 * de baixo; as dos outros, de cima, de lado conforme o assento) com um zoom até o centro, fica um instante e some
 * rapidamente ficando transparente. Sem animações (sistema ou Perfil), só aparece e some. Não captura toques.
 */
@Composable
fun ReactionCenterOverlay(event: ReactionEvent?, viewerSeat: com.gtranca.engine.model.Seat, seatCount: Int) {
    if (event == null) return
    androidx.compose.runtime.key(event.id) {
        val motion = com.gtranca.ui.theme.rememberMotionEnabled()
        val scale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (motion) 0.25f else 1f) }
        val travel = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (motion) 1f else 0f) }
        val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (motion) {
                kotlinx.coroutines.coroutineScope {
                    launch { scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 220f)) }
                    launch { travel.animateTo(0f, androidx.compose.animation.core.tween(REACTION_ZOOM_MILLIS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                }
            }
            kotlinx.coroutines.delay(REACTION_HOLD_MILLIS)
            alpha.animateTo(0f, androidx.compose.animation.core.tween(REACTION_FADE_MILLIS))
        }
        // De onde ela sai: a própria, de baixo (a mão); as dos outros, de cima, de lado conforme o assento.
        val relative = ((event.seat.index - viewerSeat.index) % seatCount + seatCount) % seatCount
        val originX = if (relative == 0 || seatCount == 2) 0 else if (relative == 1) -120 else if (relative == 3) 120 else 0
        val originY = if (relative == 0) 260 else -260
        Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            Image(
                painterResource(reactionIconRes(event.reaction)),
                stringResource(reactionLabelRes(event.reaction)),
                Modifier
                    .size(REACTION_CENTER_SIZE_DP.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                        translationX = originX.dp.toPx() * travel.value
                        translationY = originY.dp.toPx() * travel.value
                    }
                    .testTag("reaction-center"),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Tamanho "médio" da reação no centro (*provisório, revisar com produto*). */
private const val REACTION_CENTER_SIZE_DP = 160
private const val REACTION_ZOOM_MILLIS = 450
private const val REACTION_HOLD_MILLIS = 1_100L
private const val REACTION_FADE_MILLIS = 350
