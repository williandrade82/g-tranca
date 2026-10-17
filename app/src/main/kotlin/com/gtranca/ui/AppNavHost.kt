package com.gtranca.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.ui.sound.LocalSoundSettings
import com.gtranca.ui.sound.SoundSettings
import com.gtranca.ui.speed.LocalSpeedSettings
import com.gtranca.ui.speed.SpeedSettings

import com.gtranca.ui.sound.LocalSound

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gtranca.ui.profile.ProfileScreen
import com.gtranca.ui.profile.ProfileViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameData
import com.gtranca.game.WriteQueue
import kotlin.random.Random
import com.gtranca.game.appData
import androidx.lifecycle.ViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.UUID
import com.gtranca.data.SavedGame
import com.gtranca.engine.model.GameMode
import com.gtranca.game.DataGamePersistence
import com.gtranca.game.GameConfig
import com.gtranca.game.HandPrefs
import com.gtranca.game.PlayerProfile
import com.gtranca.data.Settings
import com.gtranca.game.toConfig
import com.gtranca.game.toRestored
import com.gtranca.ui.game.GameScreen
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.home.HomeScreen
import com.gtranca.ui.home.HomeViewModel
import com.gtranca.ui.stats.StatsScreen
import com.gtranca.ui.stats.StatsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.gtranca.engine.RuleSet
import com.gtranca.ui.tables.CreateTableScreen
import com.gtranca.ui.tables.CreateTableViewModel
import com.gtranca.ui.tables.RulesEditorScreen
import com.gtranca.ui.tables.RulesEditorViewModel
import com.gtranca.ui.tables.TableRulesScreen
import com.gtranca.ui.tables.TablesScreen
import com.gtranca.ui.tables.TablesViewModel

@Serializable
object HomeRoute

@Serializable
object ProfileRoute

@Serializable
object StatsRoute

/** §14.4 mesas disponíveis (abertas pelos jogadores virtuais) e "Criar mesa". */
@Serializable
object TablesRoute

/** §14.4 regras da mesa [tableId] da lista de mesas (a lista fica na entrada de [TablesRoute]). */
@Serializable
data class TableRulesRoute(val tableId: Int)

@Serializable
object CreateTableRoute

/** §14.4 editor das regras personalizadas; [fromCreate]: ao salvar, "Criar mesa" escolhe "Personalizada". */
@Serializable
data class RulesEditorRoute(val fromCreate: Boolean = false)

/** Chave do resultado do editor de regras na entrada de [CreateTableRoute]. */
private const val CUSTOM_RULES_SAVED = "custom_rules_saved"

/**
 * Jogo: novo, com a configuração escolhida no início (§14), ou retomado do jogo salvo ([resume]; aí a configuração
 * vem do arquivo).
 */
@Serializable
data class GameRoute(
    val mode: String,
    val difficulty: String,
    val targetScore: Int,
    val resume: Boolean = false,
    /** §14.4 regras da mesa em JSON (vazio = Clássica). */
    val rulesJson: String = "",
    /** §14.4, §14.5 semente da mesa escolhida (o jogo joga com os personagens que ela mostrou); `null` = sorteia. */
    val seed: Long? = null,
) {
    fun toConfig() = GameConfig(
        GameMode.valueOf(mode),
        Difficulty.fromId(difficulty),
        targetScore,
        if (rulesJson.isEmpty()) RuleSet.DEFAULT else Json.decodeFromString(RuleSet.serializer(), rulesJson),
    )

    companion object {
        fun of(config: GameConfig, seed: Long? = null) = GameRoute(
            config.mode.name,
            config.difficulty.id,
            config.targetScore,
            rulesJson = Json.encodeToString(RuleSet.serializer(), config.rules),
            seed = seed,
        )

        /** Continuar o jogo salvo (os campos de configuração não são usados). */
        val RESUME = GameRoute(GameMode.INDIVIDUAL.name, Difficulty.MEDIO.id, 1, resume = true)
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val data = remember { appData(context) }
    // Chave de som (Perfil): vale para o app todo.
    val sound = LocalSound.current
    val soundOn by remember(data) { data.settings.settings.map { it.soundOn }.catch { emit(true) } }.collectAsStateWithLifecycle(true)
    LaunchedEffect(soundOn) { sound.enabled = soundOn }
    val soundSettings = remember(soundOn, data) {
        SoundSettings(soundOn) { WriteQueue.app.enqueue { data.settings.setSoundOn(!soundOn) } }
    }
    // Velocidade das animações (Perfil e botão da mesa): vale para o app todo.
    val slowAnimations by remember(data) { data.settings.settings.map { it.slowAnimations }.catch { emit(true) } }.collectAsStateWithLifecycle(true)
    val speedSettings = remember(slowAnimations, data) {
        SpeedSettings(slowAnimations) { WriteQueue.app.enqueue { data.settings.setSlowAnimations(!slowAnimations) } }
    }
    // Troca de tela: fade com um véu dourado que se apaga (não bloqueia toques; parado sem animações do sistema).
    val motion = com.gtranca.ui.theme.rememberMotionEnabled()
    val entry by navController.currentBackStackEntryAsState()
    val veil = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(entry?.id) {
        if (!motion || entry == null) return@LaunchedEffect
        veil.snapTo(0.16f)
        veil.animateTo(0f, androidx.compose.animation.core.tween(450))
    }
    CompositionLocalProvider(LocalSoundSettings provides soundSettings, LocalSpeedSettings provides speedSettings) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (veil.value > 0f) drawRect(com.gtranca.ui.theme.GColors.Champagne.copy(alpha = veil.value))
            },
    ) {
    NavHost(
        navController,
        startDestination = HomeRoute,
        modifier = androidx.compose.ui.Modifier.background(com.gtranca.ui.theme.GColors.Midnight),
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 300 else 0)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 250 else 0)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 300 else 0)) },
        popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 250 else 0)) },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel { HomeViewModel(data.savedGames, WriteQueue.app, data.stats) },
                onNewGame = { navController.navigateFromHome(TablesRoute) },
                onContinue = { navController.navigateFromHome(GameRoute.RESUME) },
                onStats = { navController.navigateFromHome(StatsRoute) },
                onProfile = { navController.navigateFromHome(ProfileRoute) },
            )
        }
        composable<GameRoute> { entry -> GameDestination(entry.toRoute(), data, navController) }
        composable<ProfileRoute> {
            ProfileScreen(
                viewModel { ProfileViewModel(data.settings, WriteQueue.app, com.gtranca.ui.profile.ProfilePhoto(context.applicationContext)) },
                onRules = { navController.navigate(RulesEditorRoute()) { launchSingleTop = true } },
            ) { navController.popBackStack() }
        }
        composable<TablesRoute> {
            TablesScreen(
                viewModel { TablesViewModel(settings = data.settings) },
                onOpen = { table -> navController.navigate(TableRulesRoute(table.id)) { launchSingleTop = true } },
                onCreate = { navController.navigate(CreateTableRoute) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TableRulesRoute> { entry ->
            val route = entry.toRoute<TableRulesRoute>()
            // A lista sorteada vive na entrada das mesas: a mesa aberta é a mesma que o jogador tocou.
            val tablesEntry = remember(entry) { navController.getBackStackEntry(TablesRoute) }
            val table = viewModel<TablesViewModel>(tablesEntry) { TablesViewModel(settings = data.settings) }.table(route.tableId)
            if (table == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                TableRulesScreen(
                    table,
                    onStart = { config -> navController.startGame(config, table.seed) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<CreateTableRoute> { entry ->
            val vm = viewModel { CreateTableViewModel(data.settings, WriteQueue.app) }
            // Voltando do editor de regras: relê as regras personalizadas (e escolhe "Personalizada" se salvou).
            val saved by entry.savedStateHandle.getStateFlow(CUSTOM_RULES_SAVED, false).collectAsStateWithLifecycle()
            LaunchedEffect(saved) {
                if (saved) {
                    entry.savedStateHandle[CUSTOM_RULES_SAVED] = false
                    vm.reloadCustomRules(select = true)
                }
            }
            CreateTableScreen(
                vm,
                onCreate = { config -> navController.startGame(config) },
                onCustomize = { navController.navigate(RulesEditorRoute(fromCreate = true)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<RulesEditorRoute> { entry ->
            val route = entry.toRoute<RulesEditorRoute>()
            val defaultName = androidx.compose.ui.res.stringResource(com.gtranca.R.string.rules_my_rules)
            RulesEditorScreen(
                viewModel { RulesEditorViewModel(data.settings, WriteQueue.app, defaultName) },
                onSaved = {
                    if (route.fromCreate) navController.previousBackStackEntry?.savedStateHandle?.set(CUSTOM_RULES_SAVED, true)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<StatsRoute> {
            StatsScreen(viewModel { StatsViewModel(data.stats) }) { navController.popBackStack() }
        }
    }
    }
    }
}

/** Abre o jogo da mesa escolhida; voltar do jogo leva ao início (as telas de mesa saem da pilha). */
private fun NavHostController.startGame(config: GameConfig, seed: Long? = null) {
    if (currentBackStackEntry?.destination?.hasRoute<GameRoute>() == true) return
    navigate(GameRoute.of(config, seed)) {
        popUpTo(HomeRoute) { inclusive = false }
        launchSingleTop = true
    }
}

/** Sai do início uma vez só: um toque duplo em "Continuar"/"Novo jogo" nunca abre dois jogos. */
private fun NavHostController.navigateFromHome(route: Any) {
    if (currentBackStackEntry?.destination?.hasRoute<HomeRoute>() != true) return
    navigate(route) { launchSingleTop = true }
}

private sealed interface GameLoad {
    data object Loading : GameLoad

    /** [resumed]: o carregamento foi de um jogo a retomar (então [saved] `null` = o jogo já terminou). */
    data class Ready(val saved: SavedGame?, val hand: HandPrefs, val profile: PlayerProfile, val resumed: Boolean, val slow: Boolean = true) : GameLoad
}

/** Vive enquanto a tela do jogo estiver na pilha (sobrevive a rotação/tema, não à morte do processo). */
class GameSession : ViewModel() {
    /** O [GameViewModel] desta tela já existe. */
    var started = false
}

/**
 * Tela do jogo. O identificador do jogo fica em estado salvável: se o Android matar o processo e recriar esta tela,
 * o jogo é retomado do arquivo salvo — nunca começa outro; se o salvo não for mais esse jogo (ele terminou), volta
 * ao início. Mudança de configuração (tema, fonte) reaproveita o jogo em memória, sem reler o arquivo.
 */
@Composable
private fun GameDestination(route: GameRoute, data: GameData, navController: NavHostController) {
    val goHome = { navController.popBackStack(HomeRoute, inclusive = false) }
    // §14.1 foto do jogador no aparelho (o avatar usa se o perfil pede).
    val photoPath = com.gtranca.ui.profile.ProfilePhoto.savedPath(LocalContext.current)
    val session = viewModel { GameSession() }
    if (session.started) {
        val existing = viewModel<GameViewModel> { error("O jogo desta tela já foi criado") }
        GameScreen(existing) { goHome() }
        return
    }
    /** O jogo desta tela, depois de criado; `null` antes disso. */
    var gameId by rememberSaveable { mutableStateOf<String?>(null) }
    val newGameId = rememberSaveable { UUID.randomUUID().toString() }
    val load by produceState<GameLoad>(GameLoad.Loading) {
        val expected = gameId
        val resumed = route.resume || expected != null
        // Pela fila: lê depois das gravações pendentes.
        val saved = if (resumed) WriteQueue.app.read { data.savedGames.load() } else null
        val prefs = data.settings.settings.catch { emit(Settings()) }.first()
        value = GameLoad.Ready(
            saved?.takeIf { expected == null || it.gameId == expected },
            HandPrefs.parse(prefs.handSortId),
            PlayerProfile.decode(prefs.profileId),
            resumed,
            prefs.slowAnimations,
        )
    }
    when (val ready = load) {
        GameLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is GameLoad.Ready -> {
            val saved = ready.saved
            if (ready.resumed && saved == null) {
                LaunchedEffect(Unit) { goHome() }
            } else {
                val viewModel = viewModel {
                    val persistence = DataGamePersistence(data)
                    if (saved != null) {
                        GameViewModel(
                            saved.config.toConfig().copy(rules = saved.match.rules), // §14.4 regras guardadas no jogo
                            gameSeed = saved.gameSeed,
                            restored = saved.toRestored(),
                            gameId = saved.gameId,
                            persistence = persistence,
                            initialHand = ready.hand,
                            playerProfile = ready.profile,
                            playerPhoto = photoPath,
                            slowAnimations = ready.slow,
                        )
                    } else {
                        GameViewModel(route.toConfig(), gameSeed = route.seed ?: Random.nextLong(), gameId = newGameId, persistence = persistence, initialHand = ready.hand, playerProfile = ready.profile, playerPhoto = photoPath, slowAnimations = ready.slow)
                    }
                }
                session.started = true
                LaunchedEffect(Unit) { gameId = saved?.gameId ?: newGameId }
                GameScreen(viewModel) { goHome() }
            }
        }
    }
}
