package com.gtranca.net.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.gtranca.R
import com.gtranca.ui.theme.GDialog
import kotlinx.coroutines.CoroutineScope

/** O que impede o modo multijogador neste aparelho agora. */
enum class MultiplayerBlock { NO_PLAY_SERVICES, PERMISSIONS, LOCATION_OFF }

/** Ponto de entrada do modo multijogador para as telas (etapa 6): verificação do aparelho e o transporte. */
object MultiplayerAccess {
    /** Aparelho sem Google Play Services (ou desatualizado) fica fora por ora (decisão do usuário). */
    fun hasPlayServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun missingPermissions(context: Context): List<String> =
        NearbyPermissions.required(Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun locationOff(context: Context): Boolean {
        if (!NearbyPermissions.needsLocationOn(Build.VERSION.SDK_INT)) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }

    /** O primeiro impedimento, ou `null` se o modo multijogador pode começar. */
    fun check(context: Context): MultiplayerBlock? = when {
        !hasPlayServices(context) -> MultiplayerBlock.NO_PLAY_SERVICES
        missingPermissions(context).isNotEmpty() -> MultiplayerBlock.PERMISSIONS
        locationOff(context) -> MultiplayerBlock.LOCATION_OFF
        else -> null
    }

    /**
     * O transporte real (Nearby `P2P_STAR`). Um por modo multijogador: [NearbyTransport.close] ao sair. Chame só depois
     * de [check] devolver `null`.
     */
    fun transport(context: Context, scope: CoroutineScope): NearbyTransport = NearbyTransport(
        GmsNearbyApi(context), scope,
        log = if (com.gtranca.BuildConfig.DEBUG) { message -> android.util.Log.d("GTrancaNearby", message) } else { _ -> },
    )
}

/**
 * Porta do modo multijogador: explica por que o app precisa achar aparelhos por perto, pede as permissões e trata recusa,
 * Play Services ausente e localização desligada com mensagens em pt-BR. Chama [onReady] quando tudo está certo e
 * [onCancel] se o jogador desiste (o app segue offline normal).
 */
@Composable
fun MultiplayerAccessGate(onReady: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var block by remember { mutableStateOf(MultiplayerAccess.check(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        block = MultiplayerAccess.check(context)
    }
    LaunchedEffect(block) { if (block == null) onReady() }
    val current = block ?: return
    val (title, body, action) = when {
        current == MultiplayerBlock.NO_PLAY_SERVICES ->
            Triple(R.string.mp_access_title, R.string.mp_access_no_play_services, null)
        current == MultiplayerBlock.PERMISSIONS && denied ->
            Triple(R.string.mp_access_title, R.string.mp_access_denied, R.string.mp_access_retry)
        current == MultiplayerBlock.PERMISSIONS ->
            Triple(R.string.mp_access_title, R.string.mp_access_explain, R.string.mp_access_allow)
        else -> Triple(R.string.mp_access_title, R.string.mp_access_location_off, R.string.mp_access_retry)
    }
    GDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            if (action != null) {
                TextButton(onClick = {
                    if (current == MultiplayerBlock.PERMISSIONS) {
                        launcher.launch(MultiplayerAccess.missingPermissions(context).toTypedArray())
                    } else {
                        block = MultiplayerAccess.check(context)
                    }
                }) { Text(stringResource(action)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.mp_access_close)) }
            }
        },
        dismissButton = if (action != null) {
            { TextButton(onClick = onCancel) { Text(stringResource(R.string.mp_access_not_now)) } }
        } else {
            null
        },
    )
}
