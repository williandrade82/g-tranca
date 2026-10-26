package com.gtranca.ui.multiplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.gtranca.R
import com.gtranca.multiplayer.MpEnd
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone

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
    com.gtranca.net.RefusalReason.NO_RESPONSE -> R.string.mp_refusal_no_response
}
