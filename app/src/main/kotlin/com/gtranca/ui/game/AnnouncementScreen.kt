package com.gtranca.ui.game

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
import com.gtranca.game.GameSnapshot
import com.gtranca.ui.theme.OnTable
import com.gtranca.ui.theme.TableAccent
import com.gtranca.ui.theme.TableGreen

/**
 * Anúncio antes dos pontos: o resultado da partida (§11: quem bateu, ou sem vencedor) ou do jogo (§13), com o
 * botão para seguir.
 */
@Composable
fun AnnouncementScreen(title: String, subtitle: String?, button: String, onContinue: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("announcement"), color = TableGreen) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = TableAccent,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("announcement-title"),
            )
            if (subtitle != null) {
                Text(subtitle, color = OnTable, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Button(
                onContinue,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("announcement-continue"),
                colors = ButtonDefaults.buttonColors(containerColor = TableAccent, contentColor = Color.Black),
            ) { Text(button) }
        }
    }
}

/** §11 anúncio da partida que acabou de terminar (a última do histórico). */
@Composable
fun RoundAnnouncement(snapshot: GameSnapshot, onContinue: () -> Unit) {
    val record = snapshot.history.last()
    val mode = snapshot.view.mode
    AnnouncementScreen(
        title = stringResource(roundAnnouncementRes(mode, record.result, snapshot.viewerSeat)),
        subtitle = roundTeamAnnouncementRes(mode, record.result, snapshot.viewerSide)?.let { stringResource(it) },
        button = stringResource(R.string.announce_see_points),
        onContinue = onContinue,
    )
}

/** §13 anúncio do fim do jogo, antes da tela final. */
@Composable
fun GameAnnouncement(snapshot: GameSnapshot, onContinue: () -> Unit) {
    AnnouncementScreen(
        title = stringResource(R.string.game_over_title),
        subtitle = stringResource(gameResultRes(snapshot.view.mode, won = snapshot.winner == snapshot.viewerSide)),
        button = stringResource(R.string.announce_see_result),
        onContinue = onContinue,
    )
}
