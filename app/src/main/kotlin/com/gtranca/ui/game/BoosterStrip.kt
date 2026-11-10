package com.gtranca.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.ui.boosters.boosterKindRes
import com.gtranca.ui.inventory.boosterIconRes
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.Spacing

/** Os boosters do jogador nesta partida, na ordem do catálogo (ids fora do catálogo ficam de fora). */
private fun ownBoosters(ids: List<String>): List<BoosterDef> = BoosterCatalog.DEFAULT.filter { it.id in ids }

/**
 * §17.3 fileira de boosters na linha da mão: um ícone pequeno de cada booster do jogador (só os dele, nunca os dos
 * outros assentos); no b3, as reações que sobram. Pulsa uma vez em dourado quando a distribuição da 1ª partida termina.
 * Tocar abre *Seus boosters*.
 */
@Composable
internal fun BoosterStrip(state: GameUiState) {
    val boosters = ownBoosters(state.boosters)
    if (boosters.isEmpty()) return
    var showInfo by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(0f) }
    var pulsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.dealPending, state.snapshot.roundNumber) {
        if (pulsed || state.dealPending || state.snapshot.roundNumber != 1) return@LaunchedEffect
        pulsed = true
        if (state.animationMillis <= 0) return@LaunchedEffect
        pulse.animateTo(1f, tween(350))
        pulse.animateTo(0f, tween(450))
    }
    val description = stringResource(R.string.booster_strip_description, boosters.joinToString { it.name })
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { showInfo = true }
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp)
            .graphicsLayer {
                val grow = 1f + 0.15f * pulse.value
                scaleX = grow
                scaleY = grow
            }
            .background(GColors.Gold.copy(alpha = 0.35f * pulse.value), shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.4f + 0.6f * pulse.value), shape)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("booster-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        boosters.forEach { def ->
            Box(Modifier.testTag("booster-strip-${def.id}"), contentAlignment = Alignment.BottomEnd) {
                boosterIconRes(def.id)?.let { Image(painterResource(it), null, Modifier.size(20.dp), contentScale = ContentScale.Fit) }
                    ?: Text(def.name.take(1), color = GColors.Gold, style = MaterialTheme.typography.labelSmall)
                if (def.id == BoosterCatalog.REACTIONS) {
                    Text(
                        "${state.reactionsLeft}",
                        Modifier.background(GColors.Midnight, RoundedCornerShape(4.dp)).padding(horizontal = 2.dp),
                        color = GColors.Gold,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
    if (showInfo) BoostersInfoDialog(boosters, state.reactionsLeft) { showInfo = false }
}

/** Modal *Seus boosters*: ícone, nome, tipo e resumo de cada booster do jogo. */
@Composable
private fun BoostersInfoDialog(boosters: List<BoosterDef>, reactionsLeft: Int, onClose: () -> Unit) {
    GDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.boosters_info_title)) },
        text = {
            Column(Modifier.testTag("boosters-info"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                boosters.forEach { def ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        boosterIconRes(def.id)?.let { Image(painterResource(it), null, Modifier.size(40.dp), contentScale = ContentScale.Fit) }
                        Column(Modifier.weight(1f)) {
                            Text(def.name, style = MaterialTheme.typography.titleSmall, color = GColors.Gold, fontWeight = FontWeight.Bold)
                            Text(def.summary, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(boosterKindRes(def.kind)), style = MaterialTheme.typography.labelSmall, color = GColors.Champagne)
                            if (def.id == BoosterCatalog.REACTIONS) {
                                Text(stringResource(R.string.boosters_info_reactions_left, reactionsLeft), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.boosters_info_whole_game), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClose, Modifier.testTag("boosters-info-close")) { Text(stringResource(R.string.close)) } },
    )
}
