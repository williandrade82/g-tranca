package com.gtranca.ui.multiplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtranca.R
import com.gtranca.multiplayer.MpEnd
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone

/**
 * §16.2 **provisório** ("Prepare-se! O jogo vai começar!", docs/telas.md 3.3 e 3.13): enquanto a mesa do jogo espera os
 * recrutados entrarem, mostra o aviso até o jogo começar; o android-ui-dev troca por a imagem pronta e a tela do recrutado
 * só leitura. [end] mostra o motivo se a mesa acabou antes do início; [onLeave] sai da mesa.
 */
@Composable
fun PrepareScreen(end: MpEnd?, onLeave: () -> Unit) {
    BackHandler(onBack = onLeave)
    Column(
        Modifier.fillMaxSize().background(GColors.Midnight).safeDrawingPadding().padding(24.dp).testTag("get-ready"),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.mp_get_ready),
            color = GColors.Gold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        CircularProgressIndicator(color = GColors.Gold)
        GButton(onLeave, Modifier.testTag("selection-back"), kind = GButtonKind.Secondary) { Text(stringResource(R.string.mp_leave)) }
    }
    end?.let { reason -> MpEndMessage(reason, onLeave) }
}

/** Mensagem de fim da mesa (recusa, mesa não achada, mesa encerrada) com volta. */
@Composable
fun MpEndMessage(reason: MpEnd, onClose: () -> Unit) {
    // §16.2 recusa do convite: o modal Recusa, com o motivo.
    if (reason is MpEnd.Refused && reason.reason != null) {
        GMessage(
            modifier = Modifier.testTag("mp-refusal"),
            title = stringResource(R.string.mp_refusal_title),
            text = stringResource(refusalText(reason.reason)),
            illustration = MessageIllustration.Info,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.mp_ok_short),
            confirmTag = "mp-refusal-ok",
            onConfirm = onClose,
            onDismissRequest = onClose,
        )
        return
    }
    GMessage(
        modifier = Modifier.testTag("mp-end"),
        title = stringResource(R.string.mp_closed_title),
        text = when (reason) {
            is MpEnd.Refused -> reason.message
            MpEnd.Closed -> stringResource(R.string.mp_closed_text)
            MpEnd.NotFound -> stringResource(R.string.mp_not_found)
            is MpEnd.Ended -> stringResource(
                if (reason.reason == com.gtranca.net.CloseReason.HOST_LEFT) R.string.mp_closed_host_left else R.string.mp_closed_host_offline,
            )
        },
        illustration = MessageIllustration.Info,
        tone = MessageTone.Warning,
        confirmText = stringResource(R.string.back_to_home),
        onConfirm = onClose,
        onDismissRequest = onClose,
    )
}

/** §16.2 texto do motivo de uma recusa (o mesmo nos dois lados). */
fun refusalText(reason: com.gtranca.net.RefusalReason): Int = when (reason) {
    com.gtranca.net.RefusalReason.INVITE_REFUSED -> R.string.mp_refusal_invite_refused
    com.gtranca.net.RefusalReason.INSUFFICIENT_BALANCE -> R.string.mp_refusal_balance
    com.gtranca.net.RefusalReason.OUT_OF_RANGE -> R.string.mp_refusal_out_of_range
    com.gtranca.net.RefusalReason.HOST_REFUSED -> R.string.mp_refusal_host
    com.gtranca.net.RefusalReason.TABLE_FULL -> R.string.mp_refusal_full
}
