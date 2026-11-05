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

/** Rotas do jogo online (docs/telas.md 3.21 e 3.23; sem criar mesa nem entrar por id nesta fase, §18.2). */
@Serializable
object OnlineHomeRoute

@Serializable
data class OnlineRoomRoute(val tableId: String)

/** A partida online da mesa (etapa B, §18.3). */
@Serializable
data class OnlineGameRoute(val tableId: String)

/** §17.10/§18.5 *Loja online* (docs/telas.md 3.20a), aberta de [OnlineHomeRoute]. */
@Serializable
object OnlineShopRoute

/** §18.1 *Conta online* (docs/telas.md 3.22), aberta de [OnlineHomeRoute] enquanto a conta ainda é convidado. */
@Serializable
object OnlineAccountRoute

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
        // §18.2: a chave do aplicativo (local.properties, fora do git) assina cada pedido.
        val appKey = BuildConfig.ONLINE_APP_KEY.takeIf { it.isNotBlank() }
        return OnlineClient(endpoint, OnlineClient.newHttpClient(), appKey = appKey).also { cached = it }
    }
}

/** Conta e endereço para o servidor atual do Perfil. */
internal class OnlineEnv(val account: OnlineAccount, val endpoint: OnlineEndpoint, val client: OnlineClient)

private fun accountFor(data: GameData, defaultName: String, server: String): OnlineEnv? {
    val client = OnlineServices.client(server) ?: return null
    val account = OnlineAccount(client, data.settings) {
        PlayerProfile.decode(data.settings.settings.first().profileId).name.trim().ifBlank { defaultName }
    }
    return OnlineEnv(account, client.endpoint, client)
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
/** O próprio jogador (nome e foto do Perfil) para o lugar dele nas mesas online. */
@Composable
private fun rememberSelf(data: GameData): com.gtranca.game.Persona {
    val context = LocalContext.current
    val photo = remember { com.gtranca.ui.profile.ProfilePhoto.savedPath(context) }
    val profileId by data.settings.settings.collectAsStateWithLifecycle(initialValue = com.gtranca.data.Settings())
    return PlayerProfile.decode(profileId.profileId).persona(PlayerProfile.DEFAULT_NAME, photo)
}

fun NavGraphBuilder.onlineGraph(navController: NavHostController, data: GameData) {
    composable<OnlineHomeRoute> { entry ->
        val defaultName = LocalContext.current.getString(R.string.online_default_name)
        val notice by entry.savedStateHandle.getStateFlow<String?>(ONLINE_NOTICE, null).collectAsStateWithLifecycle()
        val context = LocalContext.current.applicationContext
        val vm = viewModel {
            OnlineHomeViewModel(
                data.settings,
                myPhoto = {
                    val profile = PlayerProfile.decode(data.settings.settings.catch { emit(com.gtranca.data.Settings()) }.first().profileId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
                },
                accountFor = { server -> accountFor(data, defaultName, server)?.account },
                // §18.5: a mesma conexão (OnlineClient) também fala com o economy-service.
                economyFor = { server -> accountFor(data, defaultName, server)?.client },
                // §18.6 "Trazer minha carteira": só leitura (nunca grava no save local a partir daqui).
                wallet = data.wallet,
                inventory = data.inventory,
            )
        }
        // Voltando do Perfil (servidor trocado) ou de uma sala: lê de novo.
        LaunchedEffect(Unit) { vm.refresh() }
        OnlineHomeScreen(
            vm,
            self = rememberSelf(data),
            onJoined = { id ->
                entry.savedStateHandle[ONLINE_NOTICE] = null
                navController.navigate(OnlineRoomRoute(id)) { launchSingleTop = true }
            },
            onProfile = { navController.navigate(ProfileRoute) { launchSingleTop = true } },
            onBack = { navController.popBackStack() },
            onShop = { navController.navigate(OnlineShopRoute) { launchSingleTop = true } },
            onLogin = { navController.navigate(OnlineAccountRoute) { launchSingleTop = true } },
            notice = notice?.let { name -> OnlineRoomEnd.entries.firstOrNull { it.name == name } },
        )
    }
    composable<OnlineShopRoute> {
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineShopViewModel(online.account, online.client, data.inventory.catalog) }
            OnlineShopScreen(vm, onBack = { navController.popBackStack() })
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
    composable<OnlineAccountRoute> {
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineAccountViewModel(online.account, online.client) }
            OnlineAccountScreen(vm, onDone = { navController.popBackStack() }, onBack = { navController.popBackStack() })
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
    composable<OnlineRoomRoute> { entry ->
        val route = entry.toRoute<OnlineRoomRoute>()
        val env = rememberOnlineEnv(data)
        env?.getOrNull()?.let { online ->
            val vm = viewModel { OnlineRoomViewModel(online.account, route.tableId) }
            val state by vm.uiState.collectAsStateWithLifecycle()
            val leaveRoom = {
                // Voltar sai da mesa (o anfitrião também: o cargo passa ao próximo, §18.2); assim ninguém fica esquecido nela.
                when {
                    // Jogo iniciado, ou sem rede (sair não chegaria ao servidor): volta direto; a sala esvazia sozinha.
                    state.started || state.connectionLost || state.problem == OnlineProblem.NETWORK || state.problem == OnlineProblem.MAINTENANCE ->
                        navController.popBackStack(OnlineHomeRoute, inclusive = false)
                    else -> vm.onLeave()
                }
                Unit
            }
            BackHandler(enabled = state.ended == null, onBack = leaveRoom)
            OnlineRoomScreen(
                vm,
                self = rememberSelf(data),
                onEnded = { end ->
                    runCatching { navController.getBackStackEntry<OnlineHomeRoute>() }.getOrNull()
                        ?.savedStateHandle?.set(ONLINE_NOTICE, end.name)
                    if (!navController.popBackStack(OnlineHomeRoute, inclusive = false)) {
                        navController.navigate(OnlineHomeRoute) { popUpTo(OnlineRoomRoute(route.tableId)) { inclusive = true } }
                    }
                },
                onBack = { navController.popBackStack(OnlineHomeRoute, inclusive = false) },
                // §18.3 o anfitrião iniciou: todos vão à mesa de jogo.
                onStarted = {
                    navController.navigate(OnlineGameRoute(route.tableId)) { popUpTo(OnlineRoomRoute(route.tableId)) { inclusive = true } }
                },
            )
        } ?: env?.let { LaunchedEffect(Unit) { navController.popBackStack() } }
    }
    composable<OnlineGameRoute> { entry ->
        val route = entry.toRoute<OnlineGameRoute>()
        val env = rememberOnlineEnv(data)
        val leave: () -> Unit = {
            if (!navController.popBackStack(OnlineHomeRoute, inclusive = false)) {
                navController.navigate(OnlineHomeRoute) { popUpTo(OnlineGameRoute(route.tableId)) { inclusive = true } }
            }
        }
        env?.getOrNull()?.let { online ->
            OnlineGameDestination(data, online.client, online.account, route.tableId, onExit = { leave() })
        } ?: env?.let { LaunchedEffect(Unit) { leave() } }
    }
}
