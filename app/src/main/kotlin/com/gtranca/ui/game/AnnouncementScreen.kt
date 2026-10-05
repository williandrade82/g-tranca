package com.gtranca.ui.game

import com.gtranca.ui.theme.MessageBadge
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone

import com.gtranca.engine.model.RoundResult
import com.gtranca.game.Persona
import com.gtranca.game.SeatRole
import com.gtranca.ui.persona.AvatarRow
import com.gtranca.ui.persona.labelRes
import com.gtranca.ui.persona.personasOfSide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import com.gtranca.game.GameSnapshot
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.OnTable
import com.gtranca.ui.theme.TableAccent
import com.gtranca.ui.theme.TableGreen

/**
 * Anúncio antes dos pontos: o resultado da partida (§11: quem bateu, ou sem vencedor) ou do jogo (§13), com o
 * botão para seguir.
 */
@Composable
fun AnnouncementScreen(
    title: String,
    subtitle: String?,
    button: String,
    onContinue: () -> Unit,
    onResign: (() -> Unit)? = null,
    /** §14.1 quem protagoniza o anúncio (quem bateu ou o lado vencedor), com legenda opcional. */
    avatars: List<Persona> = emptyList(),
    caption: String? = null,
    /** Ilustração quando não há avatar (ex.: partida sem vencedor). */
    illustration: MessageIllustration? = null,
) {
    GBackground(Modifier.fillMaxSize().testTag("announcement")) {
        if (onResign != null) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopEnd) {
                TextButton(onResign, Modifier.heightIn(min = 48.dp).testTag("action-resign")) {
                    Text(stringResource(R.string.action_resign), color = OnTable)
                }
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (avatars.isEmpty() && illustration != null) MessageBadge(illustration, MessageTone.Warning, 96.dp)
            if (avatars.isNotEmpty()) {
                AvatarRow(avatars, 96.dp, Modifier.testTag("announcement-avatars"))
                caption?.let { Text(it, color = OnTable, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            }
            GTitle(title, Modifier.semantics { heading() }.testTag("announcement-title"))
            if (subtitle != null) {
                Text(subtitle, color = OnTable, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            GButton(
                onContinue,
                Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("announcement-continue"),
            ) { Text(button, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** §11 anúncio da partida que acabou de terminar (a última do histórico). */
@Composable
fun RoundAnnouncement(
    snapshot: GameSnapshot,
    onContinue: () -> Unit,
    onResign: (() -> Unit)? = null,
    personas: List<Persona> = emptyList(),
) {
    val record = snapshot.history.last()
    val mode = snapshot.view.mode
    val result = record.result
    // §14.1 quem venceu a partida (bateu), pelo nome e pelo título: o próprio jogador continua "Você".
    val who = (result as? RoundResult.GoOut)?.let { personas.getOrNull(it.seat.index) }
    val role = (result as? RoundResult.GoOut)?.let { SeatRole.of(mode, it.seat, snapshot.viewerSeat) }
    val named = who != null && role != SeatRole.YOU
    AnnouncementScreen(
        title = if (named) stringResource(R.string.round_result_named_went_out, who!!.firstName) else stringResource(roundAnnouncementRes(mode, record.result, snapshot.viewerSeat)),
        avatars = listOfNotNull(who),
        illustration = if (result is RoundResult.NoWinner) MessageIllustration.Cards else null,
        caption = who?.let { persona ->
            if (role == SeatRole.YOU) stringResource(R.string.side_you) else stringResource(R.string.persona_role_caption, persona.fullName, stringResource(role!!.labelRes(persona.gender)))
        },
        subtitle = roundTeamAnnouncementRes(mode, record.result, snapshot.viewerSide)?.let { stringResource(it) },
        button = stringResource(R.string.announce_see_points),
        onContinue = onContinue,
        onResign = onResign,
    )
}

/** §13 anúncio do fim do jogo, antes da tela final. */
@Composable
fun GameAnnouncement(snapshot: GameSnapshot, onContinue: () -> Unit, personas: List<Persona> = emptyList()) {
    val winners = snapshot.winner?.let { personasOfSide(snapshot.view.mode, it, personas) }.orEmpty()
    AnnouncementScreen(
        avatars = winners,
        caption = winners.joinToString(" · ") { it.shortName }.ifEmpty { null },
        title = stringResource(R.string.game_over_title),
        subtitle = stringResource(gameResultRes(snapshot.view.mode, won = snapshot.winner == snapshot.viewerSide)),
        button = stringResource(R.string.announce_see_result),
        onContinue = onContinue,
    )
}
