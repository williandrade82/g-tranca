package com.gtranca.ui.online

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gtranca.BuildConfig
import com.gtranca.R
import com.gtranca.data.GameData
import com.gtranca.game.PlayerProfile
import com.gtranca.online.client.OnlineClient
import com.gtranca.online.client.OnlineEndpoint
import com.gtranca.ui.ProfileRoute
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** Rotas do jogo online (docs/telas.md 3.21 a 3.24). */
@Serializable
object OnlineHomeRoute

@Serializable
object OnlineCreateRoute

@Serializable
object OnlineJoinRoute

@Serializable
data class OnlineRoomRoute(val tableId: String)

/** Chave, na entrada de [OnlineHomeRoute], do aviso de volta de uma sala (um [OnlineRoomEnd]). */
private const val ONLINE_NOTICE = "online-notice"

/**
 * Cliente do servidor informado no Perfil (§18.1). Texto puro (`http`/`ws`) só no build de teste (debug); o release usa
 * sempre TLS. Um cliente por endereço; trocar o endereço fecha o anterior.
 */
internal object OnlineServices {
    private var cached: OnlineClient? = null

    @Synchronized
    fun client(server: String): OnlineClient? {
        val endpoint = OnlineEndpoint.parse(server, cleartext = BuildConfig.DEBUG) ?: return null
        cached?.takeIf { it.endpoint == endpoint }?.let { return it }
        cached?.shutdown()
        return OnlineClient(endpoint, OnlineClient.newHttpClient()).also { cached = it }
    }
}

/** Conta e endereço para o servidor atual do Perfil. */
internal class OnlineEnv(val account: OnlineAccount, val endpoint: OnlineEndpoint)

private fun accountFor(data: GameData, defaultName: String, server: String): OnlineEnv? {
    val client = OnlineServices.client(server) ?: return null
    val account = OnlineAccount(client, data.settings) {
        PlayerProfile.decode(data.settings.settings.first().profileId).name.trim().ifBlank { defaultName }
    }
    return OnlineEnv(account, client.endpoint)
}

/** Lê o servidor do Perfil uma vez; `null` enquanto carrega e `Unit`-vazio se o online está desligado. */
@Composable
private fun rememberOnlineEnv(data: GameData): Result<OnlineEnv?>? {
    val defaultName = LocalContext.current.getString(R.string.online_default_name)
    val env by produceState<Result<OnlineEnv?>?>(null, data) {
        val server = data.settings.settings.catch { emit(com.gtranca.data.Settings()) }.first().onlineServer
        value = Result.success(server.takeIf { it.isNotBlank() }?.let { accountFor(data, defaultName, it) })
    }
    return env
}

/** Registra as telas online no NavHost do app (um único ponto de contato com [com.gtranca.ui.AppNavHost]). */
fun NavGraphBuilder.onlineGraph(navController: NavHostController, data: GameData) {
    composable<OnlineHomeRoute> { entry ->
        val defaultName = LocalContext.current.getString(R.string.online_default_name)
        val notice by entry.savedStateHandle.getStateFlow<String?>(ONLINE_NOTICE, null).collectAsStateWithLifecycle()
        val vm = viewModel { OnlineHomeViewModel(data.settings) { server -> accountFor(data, defaultName, server)?.account } }
        // Voltando do Perfil (servidor trocado) ou de uma sala: lê de novo.
        LaunchedEffect(Unit) { vm.refresh() }
        OnlineHomeScreen(
            vm,
            onCreate = { entry.savedStateHandle[ONLINE_NOTICE] = null; navController.navigate(OnlineCreateRoute) { launchSingleTop = true } },
            onJoin = { entry.savedStateHandle[ONLINE_NOTICE] = null; navController.navigate(OnlineJoinRoute) { launchSingleTop = true } },
            onProfile = { navController.navigate(ProfileRoute) { launchSingleTop = true } },
            onBack = { navController.popBackStack() },
            notice = notice?.let { name -> OnlineRoomEnd.entries.firstOrNull { it.name == name } },
        )
    }
    composable<OnlineCreateRoute> {
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineCreateViewModel(online.account, data.settings) }
            OnlineCreateScreen(
                vm,
                onCreated = { id ->
                    navController.navigate(OnlineRoomRoute(id)) { popUpTo(OnlineCreateRoute) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
    composable<OnlineJoinRoute> {
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineJoinViewModel(online.account) }
            OnlineJoinScreen(
                vm,
                onJoined = { id ->
                    navController.navigate(OnlineRoomRoute(id)) { popUpTo(OnlineJoinRoute) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
    composable<OnlineRoomRoute> { entry ->
        val route = entry.toRoute<OnlineRoomRoute>()
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineRoomViewModel(online.account, route.tableId) }
            val state by vm.uiState.collectAsStateWithLifecycle()
            val link = remember(online, route.tableId) { online.endpoint.inviteLink(route.tableId) }
            val leaveRoom = {
                // O anfitrião encerra a mesa (com confirmação) e o convidado sai dela: nenhuma mesa fica esquecida aberta.
                when {
                    state.started -> navController.popBackStack(OnlineHomeRoute, inclusive = false)
                    state.isHost -> vm.onAskClose()
                    else -> vm.onLeave()
                }
                Unit
            }
            BackHandler(enabled = state.ended == null, onBack = leaveRoom)
            OnlineRoomScreen(
                vm,
                inviteLink = link,
                onEnded = { end ->
                    runCatching { navController.getBackStackEntry<OnlineHomeRoute>() }.getOrNull()
                        ?.savedStateHandle?.set(ONLINE_NOTICE, end.name)
                    if (!navController.popBackStack(OnlineHomeRoute, inclusive = false)) {
                        navController.navigate(OnlineHomeRoute) { popUpTo(OnlineRoomRoute(route.tableId)) { inclusive = true } }
                    }
                },
                onBack = { navController.popBackStack(OnlineHomeRoute, inclusive = false) },
            )
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
}
