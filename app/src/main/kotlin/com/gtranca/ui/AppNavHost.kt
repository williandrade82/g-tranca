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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
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
import com.gtranca.ui.tables.RulesEditorScreen
import com.gtranca.ui.tables.RulesEditorViewModel
import com.gtranca.ui.tables.TablesScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import com.gtranca.ui.tables.TablesViewModel

@Serializable
object HomeRoute

@Serializable
object ProfileRoute

@Serializable
object StatsRoute

/** §17.2 *Inventário*: o armário de boosters (docs/telas.md 3.18). */
@Serializable
object InventoryRoute

/** §14.4 *Iniciar novo jogo*: as opções da mesa (docs/telas.md 3.2). */
@Serializable
object TablesRoute

/**
 * §14.4 *Seleção de jogadores* (docs/telas.md 3.3): a configuração escolhida em *Iniciar novo jogo*. Os lugares não são
 * parâmetro (sempre começam VAGO).
 */
@Serializable
data class SelectionRoute(
    val mode: String,
    val difficulty: String,
    val targetScore: Int,
    val rulesJson: String,
    val stake: Int,
) {
    fun toConfig() = GameRoute(mode, difficulty, targetScore, rulesJson = rulesJson, stake = stake).toConfig()

    companion object {
        fun of(config: GameConfig) = GameRoute.of(config).let { SelectionRoute(it.mode, it.difficulty, it.targetScore, it.rulesJson, it.stake) }
    }
}

/** §16.5, §16.12 *Voltar à mesa*: reingressa na mesa da sessão guardada (docs/telas.md 3.17). */
@Serializable
object RejoinRoute

/** §16.2 *Mesa do recrutado* (docs/telas.md 3.13): a mesa de quem chamou, só para leitura. */
@Serializable
object RecruitedRoute

/** §16 jogo da mesa multijogador. */
@Serializable
object MultiplayerGameRoute

/** §14.4 editor das regras personalizadas; [fromCreate]: ao salvar, a montagem da mesa escolhe "Personalizada". */
@Serializable
data class RulesEditorRoute(val fromCreate: Boolean = false)

/** Chave do resultado do editor de regras na entrada de [TablesRoute]. */
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
    /** §14.6 aposta da mesa em moedas (0 = sem aposta). */
    val stake: Int = 0,
    /** §14.4, §14.5 personagem de cada assento de robô ("1=zen;2=jovem"); vazio = sorteio pela semente. */
    val robots: String = "",
) {
    fun robotMap(): Map<Int, String> = robots.split(';').mapNotNull { part ->
        val (seat, code) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        seat.toIntOrNull()?.let { it to code }
    }.toMap()

    fun toConfig() = GameConfig(
        GameMode.valueOf(mode),
        Difficulty.fromId(difficulty),
        targetScore,
        if (rulesJson.isEmpty()) RuleSet.DEFAULT else Json.decodeFromString(RuleSet.serializer(), rulesJson),
        stake,
    )

    companion object {
        fun of(config: GameConfig, seed: Long? = null, robots: Map<Int, String> = emptyMap()) = GameRoute(
            config.mode.name,
            config.difficulty.id,
            config.targetScore,
            rulesJson = Json.encodeToString(RuleSet.serializer(), config.rules),
            seed = seed,
            stake = config.stake,
            robots = robots.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" },
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
    // Ações impedidas (Perfil e modal da mesa): vale para o app todo.
    val refusalAlert by remember(data) { data.settings.settings.map { it.refusalAlert }.catch { emit(com.gtranca.data.RefusalAlert.EXPLAIN) } }
        .collectAsStateWithLifecycle(com.gtranca.data.RefusalAlert.EXPLAIN)
    val refusalSettings = remember(refusalAlert, data) {
        com.gtranca.ui.game.RefusalSettings(refusalAlert) { alert -> WriteQueue.app.enqueue { data.settings.setRefusalAlert(alert) } }
    }
    // Cor do baralho (Perfil): vale para o app todo.
    val deck by remember(data) {
        data.settings.settings.map { com.gtranca.game.PlayerProfile.decode(it.profileId).deck }.catch { emit(com.gtranca.game.DeckColor.AZUL) }
    }.collectAsStateWithLifecycle(com.gtranca.game.DeckColor.AZUL)
    // Troca de tela: fade com um véu dourado que se apaga (não bloqueia toques; parado sem animações do sistema).
    val motion = com.gtranca.ui.theme.rememberMotionEnabled()
    val entry by navController.currentBackStackEntryAsState()
    val veil = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(entry?.id) {
        if (!motion || entry == null) return@LaunchedEffect
        veil.snapTo(0.16f)
        veil.animateTo(0f, androidx.compose.animation.core.tween(450))
    }
    // Tour guiado (docs/telas.md 3.16): tours já vistos, por tela.
    val tourPrefs by remember(data) { data.settings.settings.map<com.gtranca.data.Settings, Pair<Set<String>, Boolean>?> { it.toursSeen to it.tourAuto }.catch { emit(null) } }
        .collectAsStateWithLifecycle(null)
    val tourSettings = remember(tourPrefs, data) {
        com.gtranca.ui.tour.TourSettings(tourPrefs?.first, tourPrefs?.second ?: true) { id -> WriteQueue.app.enqueue { data.settings.markTourSeen(id) } }
    }
    // Saldo no topo de todas as telas (docs/telas.md 0), ao vivo pela carteira.
    val coinBalance by remember(data) { data.wallet.wallet.map { it.balance as Int? }.catch { emit(null) } }
        .collectAsStateWithLifecycle(null)
    // Na mesa de jogo o saldo vai na linha do cabeçalho (não ganha faixa).
    val onGame = entry?.destination?.let { it.hasRoute<GameRoute>() || it.hasRoute<MultiplayerGameRoute>() } == true
    CompositionLocalProvider(
        com.gtranca.ui.theme.LocalCoinBalance provides coinBalance,
        com.gtranca.ui.tour.LocalTours provides tourSettings,
        LocalSoundSettings provides soundSettings,
        LocalSpeedSettings provides speedSettings,
        com.gtranca.ui.game.LocalRefusalSettings provides refusalSettings,
        com.gtranca.ui.cards.LocalDeckColor provides deck,
    ) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (veil.value > 0f) drawRect(com.gtranca.ui.theme.GColors.Champagne.copy(alpha = veil.value))
            },
    ) {
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
    if (!onGame) com.gtranca.ui.theme.CoinBalanceBar(androidx.compose.ui.Modifier.background(com.gtranca.ui.theme.GColors.Midnight).statusBarsPadding())
    NavHost(
        navController,
        startDestination = HomeRoute,
        modifier = androidx.compose.ui.Modifier.weight(1f)
            .then(if (!onGame) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars) else androidx.compose.ui.Modifier)
            .background(com.gtranca.ui.theme.GColors.Midnight),
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 300 else 0)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 250 else 0)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (motion) 300 else 0)) },
        popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (motion) 250 else 0)) },
    ) {
        composable<HomeRoute> {
            // §14.1 sem nome no perfil: boas-vindas no lugar do Início (o único botão abre o Perfil).
            val welcome by remember(data) {
                data.settings.settings.map { com.gtranca.game.PlayerProfile.needsWelcome(it.profileId) }.catch { emit(false) }
            }.collectAsStateWithLifecycle(null)
            when (welcome) {
                null -> Unit
                true -> com.gtranca.ui.home.WelcomeScreen { navController.navigateFromHome(ProfileRoute) }
                false -> HomeScreen(
                viewModel { HomeViewModel(data.savedGames, WriteQueue.app, data.stats, data.wallet, sessions = data.multiplayerSession) },
                onNewGame = { navController.navigateFromHome(TablesRoute) },
                onContinue = { navController.navigateFromHome(GameRoute.RESUME) },
                onContinueSession = { navController.navigateFromHome(RejoinRoute) },
                onStats = { navController.navigateFromHome(StatsRoute) },
                onInventory = { navController.navigateFromHome(InventoryRoute) },
                onProfile = { navController.navigateFromHome(ProfileRoute) },
            )
            }
        }
        composable<GameRoute> { entry -> GameDestination(entry.toRoute(), data, navController) }
        composable<ProfileRoute> {
            ProfileScreen(
                viewModel { ProfileViewModel(data.settings, WriteQueue.app, com.gtranca.ui.profile.ProfilePhoto(context.applicationContext)) },
                onRules = { navController.navigate(RulesEditorRoute()) { launchSingleTop = true } },
                accessGate = { ready, cancel -> com.gtranca.net.nearby.MultiplayerAccessGate(ready, cancel) },
            ) { navController.popBackStack() }
        }
        composable<TablesRoute> { entry ->
            // §14.4 *Iniciar novo jogo* (docs/telas.md 3.2): as opções da mesa. Com o Wi-Fi do jogo ligado o jogador fica
            // livre para ser chamado (setFree, em NearbyPresence) e vê quem está recrutando.
            val tablesVm = viewModel { TablesViewModel(data.settings, WriteQueue.app, data.wallet) }
            // Voltando do editor de regras: relê as regras personalizadas (e escolhe "Personalizada" se salvou).
            val saved by entry.savedStateHandle.getStateFlow(CUSTOM_RULES_SAVED, false).collectAsStateWithLifecycle()
            LaunchedEffect(saved) {
                if (saved) {
                    entry.savedStateHandle[CUSTOM_RULES_SAVED] = false
                    tablesVm.reloadCustomRules(select = true)
                }
            }
            val wifi = rememberWifiControl(data)
            val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
            val recruiters by remember(rec) { rec?.recruiters ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
            TablesScreen(
                tablesVm,
                recruiters = recruiters,
                wifi = wifi,
                onRefresh = { rec?.refreshNow() },
                onSelectPlayers = { config -> navController.navigate(SelectionRoute.of(config)) { launchSingleTop = true } },
                onCustomize = { navController.navigate(RulesEditorRoute(fromCreate = true)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<SelectionRoute> { entry ->
            val route = entry.toRoute<SelectionRoute>()
            PlayerSelectionDestination(route, data, navController)
        }
        composable<RejoinRoute> { RejoinDestination(data, navController) }
        composable<RecruitedRoute> {
            RecruitedDestination(data, navController)
        }
        composable<MultiplayerGameRoute> { MultiplayerGameDestination(data, navController) }
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
        composable<InventoryRoute> {
            com.gtranca.ui.inventory.InventoryScreen(
                viewModel { com.gtranca.ui.inventory.InventoryViewModel(data.inventory, data.wallet) },
            ) { navController.popBackStack() }
        }
        composable<StatsRoute> {
            StatsScreen(viewModel { StatsViewModel(data.stats, data.wallet) }) { navController.popBackStack() }
        }
    }
    }
    // §16.2 presença e convite: em qualquer tela fora de um jogo, com o Wi-Fi do jogo ligado.
    NearbyPresence(data, navController, onGame)
    }
    }
}

/** Perfil do jogador (nome, avatar), lido uma vez; `null` enquanto carrega. */
@Composable
private fun rememberProfile(data: GameData): PlayerProfile? {
    val profile by produceState<PlayerProfile?>(null, data) {
        value = data.settings.settings.catch { emit(Settings()) }.first().let { PlayerProfile.decode(it.profileId) }
    }
    return profile
}

/**
 * §16 a tela do jogo multijogador: o condutor da mesa ([com.gtranca.multiplayer.MultiplayerDriver]) e os nomes vêm da
 * mesa do [com.gtranca.multiplayer.MultiplayerHub]; nada é gravado (§16.12). Vive enquanto a tela estiver na pilha.
 */
class MultiplayerGameHolder(
    val table: com.gtranca.multiplayer.MultiplayerTable,
    self: com.gtranca.game.Persona,
    vacant: String,
    disconnected: String,
    /** §16.2 "Ver fotos nas mesas compartilhadas" (Perfil). */
    showPhotos: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
) : ViewModel() {
    val personas = table.personas(self, vacant, showPhotos) { name -> disconnected.format(name) }
    val driver = kotlinx.coroutines.flow.MutableStateFlow<com.gtranca.multiplayer.MultiplayerDriver?>(null)

    init {
        val scope = viewModelScope
        scope.launch {
            driver.value = com.gtranca.multiplayer.MultiplayerDriver.create(table, scope) { com.gtranca.game.GameController.DEFAULT_BOT_DELAY_MILLIS }
        }
    }
}

@Composable
private fun MultiplayerGameDestination(data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    val goHome: () -> Unit = {
        com.gtranca.multiplayer.MultiplayerHub.exit()
        navController.popBackStack(HomeRoute, inclusive = false)
    }
    val table = remember { com.gtranca.multiplayer.MultiplayerHub.table.value }
    val profile = rememberProfile(data)
    if (table == null) {
        LaunchedEffect(Unit) { goHome() }
        return
    }
    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val photoPath = com.gtranca.ui.profile.ProfilePhoto.savedPath(context)
    val vacant = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_seat_vacant)
    val disconnected = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_seat_disconnected)
    val holder = viewModel { MultiplayerGameHolder(table, profile.persona(PlayerProfile.DEFAULT_NAME, photoPath), vacant, disconnected, data.settings.settings.map { it.showSharedPhotos }.catch { emit(true) }) }
    val driver by holder.driver.collectAsStateWithLifecycle()
    val personas by holder.personas.collectAsStateWithLifecycle()
    val readyDriver = driver
    val rules = table.rules.value
    if (readyDriver == null || rules == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val prefs by produceState<Settings?>(null) { value = data.settings.settings.catch { emit(Settings()) }.first() }
    val loaded = prefs ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val viewModel = viewModel {
        GameViewModel(
            com.gtranca.multiplayer.MultiplayerTable.configOf(rules),
            initialHand = HandPrefs.parse(loaded.handSortId),
            playerProfile = profile,
            playerPhoto = photoPath,
            slowAnimations = loaded.slowAnimations,
            externalDriver = readyDriver,
            externalPersonas = holder.personas,
            multiplayer = true,
            continuing = table.continuing,
        )
    }
    val wifiOn by remember(data) { data.settings.settings.map { it.wifiOn }.catch { emit(false) } }.collectAsStateWithLifecycle(true)
    val hooks = com.gtranca.ui.multiplayer.rememberMultiplayerHooks(
        table, personas, goHome,
        onResign = viewModel::onResign,
        wifiOn = wifiOn,
        setWifi = { on -> WriteQueue.app.enqueue { data.settings.setWifiOn(on) } },
    )
    GameScreen(viewModel, hooks, onExit = goHome)
}

/** Abre o jogo da mesa escolhida; voltar do jogo leva ao início (as telas de mesa saem da pilha). */
private fun NavHostController.startGame(config: GameConfig, seed: Long? = null, robots: Map<Int, String> = emptyMap()) {
    if (currentBackStackEntry?.destination?.hasRoute<GameRoute>() == true) return
    navigate(GameRoute.of(config, seed, robots)) {
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
        GameScreen(existing, wifi = rememberLocalWifi(existing, data, navController)) { goHome() }
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
                            robots = saved.robots,
                        )
                    } else {
                        GameViewModel(route.toConfig(), gameSeed = route.seed ?: Random.nextLong(), gameId = newGameId, persistence = persistence, initialHand = ready.hand, playerProfile = ready.profile, playerPhoto = photoPath, slowAnimations = ready.slow, robots = route.robotMap())
                    }
                }
                session.started = true
                LaunchedEffect(Unit) { gameId = saved?.gameId ?: newGameId }
                GameScreen(viewModel, wifi = rememberLocalWifi(viewModel, data, navController)) { goHome() }
            }
        }
    }
}

/**
 * §16.2 com o Wi-Fi do jogo ligado e fora de um jogo: anuncia a presença do jogador (nome) e atende o recrutamento. Em
 * qualquer tela fora de um jogo aparece o modal **Convite** (sem prazo); aceito, abre a *Mesa do recrutado*, e quando o
 * recrutador começa a partida o jogador entra na mesa ("Prepare-se!"). Só fica **livre** (pode ser chamado) nas telas
 * *Iniciar novo jogo* e *Personalizar regra*.
 */
@Composable
private fun NearbyPresence(data: GameData, navController: NavHostController, onGame: Boolean) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val wifiSetting by remember(data) { data.settings.settings.map { it.wifiOn }.catch { emit(false) } }.collectAsStateWithLifecycle(false)
    LaunchedEffect(wifiSetting) { if (!wifiSetting) com.gtranca.multiplayer.MultiplayerHub.resetPresence() }
    val granted = remember(wifiSetting) { com.gtranca.net.nearby.MultiplayerAccess.check(context) == null }
    val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
    val profile by remember(data) { data.settings.settings.map { PlayerProfile.decode(it.profileId) }.catch { emit(PlayerProfile()) } }
        .collectAsStateWithLifecycle(null)
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME
    val presenceOn = wifiSetting && granted && !onGame && table == null && profile != null
    // §16.2 a presença (e o recrutamento) só reinicia quando liga ou desliga; trocar o nome só atualiza o anúncio.
    LaunchedEffect(presenceOn) {
        if (!presenceOn) return@LaunchedEffect
        val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
        try {
            com.gtranca.multiplayer.MultiplayerHub.runPresence(context, data, name, photo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // O Nearby recusou a busca (ex.: ApiException com código de status): avisa e desliga, sem fechar o app.
            android.widget.Toast.makeText(
                context,
                context.getString(com.gtranca.R.string.mp_discovery_failed, e.message ?: e.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            WriteQueue.app.enqueue { data.settings.setWifiOn(false) }
        }
    }
    LaunchedEffect(name) { com.gtranca.multiplayer.MultiplayerHub.renamePresence(name) }
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    val recStatus by remember(rec) { rec?.status ?: kotlinx.coroutines.flow.flowOf(com.gtranca.net.RecruitStatus.Free) }
        .collectAsStateWithLifecycle(com.gtranca.net.RecruitStatus.Free)
    val entry by navController.currentBackStackEntryAsState()
    // §16.2 só quem está em *Iniciar novo jogo* (ou no editor de regras que volta a ela) está livre para ser chamado.
    val onFreeScreen = entry?.destination?.let { it.hasRoute<TablesRoute>() || it.hasRoute<RulesEditorRoute>() } == true
    LaunchedEffect(rec, onFreeScreen) { rec?.let { r -> runCatching { r.setFree(onFreeScreen) } } }
    // Avisos ao recrutado: tirado da mesa, mesa encerrada.
    LaunchedEffect(Unit) {
        com.gtranca.multiplayer.MultiplayerHub.recruitEvents.collect { event ->
            val text = when (event) {
                is com.gtranca.net.RecruitEvent.Removed -> context.getString(com.gtranca.R.string.recruit_removed, event.hostName)
                is com.gtranca.net.RecruitEvent.Closed -> context.getString(com.gtranca.R.string.mp_closed_title)
                is com.gtranca.net.RecruitEvent.Refused -> context.getString(
                    if (event.reason == com.gtranca.net.RefusalReason.NO_RESPONSE) com.gtranca.R.string.recruit_no_response else com.gtranca.R.string.recruit_balance_table,
                )
                is com.gtranca.net.RecruitEvent.Start, is com.gtranca.net.RecruitEvent.Called -> null
            }
            text?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val invited = recStatus as? com.gtranca.net.RecruitStatus.Invited
    if (invited != null && !onGame) {
        InviteDialog(
            invited,
            now = { rec?.nowMillis() ?: 0L },
            onAccept = {
                scope.launch {
                    if (rec?.accept() == true) {
                        // §16.2 quem estava em Personalizar regra volta sem salvar; a tela vira a mesa do recrutador (só leitura).
                        if (navController.currentBackStackEntry?.destination?.hasRoute<RulesEditorRoute>() == true) navController.popBackStack()
                        navController.navigate(RecruitedRoute) { launchSingleTop = true }
                    }
                }
            },
            onRefuse = { scope.launch { rec?.refuse() } },
        )
    }
    val current = table ?: return
    CalledToGame(current, navController, onGame)
    val end by current.end.collectAsStateWithLifecycle()
    // A mesa encerrada aparece na própria tela de seleção ou do recrutado (que a mostram); aqui, nas outras.
    val onOwnScreen = entry?.destination?.let { it.hasRoute<SelectionRoute>() || it.hasRoute<RecruitedRoute>() || it.hasRoute<RejoinRoute>() } == true
    val reason = end
    if (reason != null && !onOwnScreen && !onGame) {
        com.gtranca.ui.multiplayer.MpEndMessage(reason) { com.gtranca.multiplayer.MultiplayerHub.leave() }
    }
}

/**
 * §16.7 o anfitrião de uma partida em andamento chamou este jogador livre: mostra o *Convite* da partida (com o débito),
 * responde pela mesa e, aceito, abre o jogo direto (sem "Prepare-se!"). Recusa, falta de resposta em 25 s ou saldo menor que
 * o débito devolvem o jogador à tela em que estava (a mesa de convidado é solta).
 */
@Composable
private fun CalledToGame(table: com.gtranca.multiplayer.MultiplayerTable, navController: NavHostController, onGame: Boolean) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val status by table.guestStatus.collectAsStateWithLifecycle()
    val lowBalance by table.lowBalance.collectAsStateWithLifecycle()
    val clock = { System.nanoTime() / 1_000_000 }
    val called = status as? com.gtranca.net.GuestStatus.Called
    if (called != null && !onGame && !lowBalance) {
        val offer = called.offer
        val deadline = remember(offer) { clock() + com.gtranca.net.INVITE_TIMEOUT_MILLIS }
        // Sem resposta em 25 s o convite vale como recusa (o aceite nunca é automático).
        LaunchedEffect(offer) {
            kotlinx.coroutines.delay(com.gtranca.net.INVITE_TIMEOUT_MILLIS)
            table.answerCall(false, com.gtranca.net.RefusalReason.NO_RESPONSE)
            android.widget.Toast.makeText(context, context.getString(com.gtranca.R.string.recruit_no_response), android.widget.Toast.LENGTH_LONG).show()
        }
        val slots = List(offer.rules.mode.seatCount) { if (it == 0) com.gtranca.net.Slot.Host else com.gtranca.net.Slot.Vacant }
        val invited = com.gtranca.net.RecruitStatus.Invited(
            hostKey = "",
            hostName = offer.hostName,
            table = com.gtranca.net.RecruitTable(offer.hostName, offer.rules, com.gtranca.net.TableLayout(offer.rules.mode, slots)),
            you = offer.seat.index,
            deadlineMillis = deadline,
        )
        InviteDialog(
            invited,
            now = clock,
            onAccept = { scope.launch { table.answerCall(true) } },
            onRefuse = { scope.launch { table.answerCall(false) } },
            debit = offer.debit,
        )
    }
    // Recusou, não respondeu ou o saldo não cobre: a mesa de convidado é solta e o jogador volta à tela em que estava.
    LaunchedEffect(status) {
        if (status == com.gtranca.net.GuestStatus.Declined) {
            if (lowBalance) {
                android.widget.Toast.makeText(context, context.getString(com.gtranca.R.string.recruit_balance_table), android.widget.Toast.LENGTH_LONG).show()
            }
            com.gtranca.multiplayer.MultiplayerHub.leave()
        }
    }
    // Aceitou e o anfitrião aprovou: entra no jogo (sem "Prepare-se!").
    LaunchedEffect(status) {
        if (table.viaCall && status == com.gtranca.net.GuestStatus.Playing && !onGame) {
            navController.navigate(MultiplayerGameRoute) {
                popUpTo(HomeRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

/**
 * §16.2 modal **Convite** (docs/telas.md 4): "<recrutador> está te recrutando", a aposta, o tipo (individual/duplas), a
 * posição (Parceiro/Adversário) e o título da regra; **Detalhes da regra**, **Recusar** e **Aceitar**. Tem **25 s** para
 * responder (§16.15), com uma barra que esvazia e os segundos restantes (vermelha nos últimos 5 s); ao zerar a rede
 * devolve o jogador a "livre" e o modal some. O aceite nunca é automático. [now] é o relógio de `Recruitment.nowMillis`.
 * [debit] não nulo = chamada para uma partida em andamento (§16.7): troca o título e mostra o débito.
 */
@Composable
private fun InviteDialog(
    invited: com.gtranca.net.RecruitStatus.Invited,
    now: () -> Long,
    onAccept: () -> Unit,
    onRefuse: () -> Unit,
    debit: Int? = null,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    val rules = invited.table.rules
    val partner = invited.table.layout.relationOf(invited.you) == com.gtranca.net.SeatRelation.PARTNER
    val title = rules.rulesTitle.ifBlank { rules.rules.name }
    // O que falta, a cada ~100 ms.
    val remaining by androidx.compose.runtime.produceState(invited.remainingMillis(now()), invited.deadlineMillis) {
        while (true) {
            value = invited.remainingMillis(now())
            kotlinx.coroutines.delay(100)
        }
    }
    com.gtranca.ui.theme.GDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("invite"),
        title = {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(
                    if (debit != null) com.gtranca.R.string.invite_calling else com.gtranca.R.string.invite_recruiting,
                    invited.hostName,
                ),
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_stake)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.gtranca.R.drawable.coin_bag_small), null, Modifier.size(20.dp))
                    androidx.compose.material3.Text(rules.stake.toString())
                }
                // §16.7 no lugar de um robô debita a aposta cheia; no de quem saiu, herda a retida.
                if (debit != null) {
                    InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_debit)) {
                        androidx.compose.material3.Text(
                            if (debit > 0) debit.toString() else androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_debit_none),
                            Modifier.testTag("invite-debit"),
                        )
                    }
                }
                InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_type)) {
                    androidx.compose.material3.Text(com.gtranca.ui.tables.modeLabel(rules.mode))
                }
                InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_position)) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(if (partner) com.gtranca.R.string.invite_partner else com.gtranca.R.string.invite_opponent),
                    )
                }
                InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_rules)) { androidx.compose.material3.Text(title) }
                InviteCountdown(remaining)
                androidx.compose.material3.TextButton({ details = true }, Modifier.heightIn(min = 48.dp).testTag("invite-details")) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_details), color = com.gtranca.ui.theme.GColors.Gold)
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onRefuse, Modifier.heightIn(min = 48.dp).testTag("invite-refuse")) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_invite_refuse))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onAccept, Modifier.heightIn(min = 48.dp).testTag("invite-accept")) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_invite_accept), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
    )
    if (details) com.gtranca.ui.tables.RulesDetailsDialog(title, rules.rules, rules.targetScore) { details = false }
}

/**
 * A contagem do convite: barra que esvazia + segundos restantes, vermelha nos últimos 5 s. O leitor de tela lê só nos
 * marcos de 25, 20, 10 e 5 s (sem repetir a cada quadro).
 */
@Composable
private fun InviteCountdown(remainingMillis: Long) {
    val fraction = (remainingMillis / com.gtranca.net.INVITE_TIMEOUT_MILLIS.toFloat()).coerceIn(0f, 1f)
    val seconds = kotlin.math.ceil(remainingMillis / 1000.0).toInt()
    val urgent = remainingMillis <= 5_000
    val color = if (urgent) com.gtranca.ui.theme.GColors.Ruby else com.gtranca.ui.theme.GColors.Gold
    val milestone = listOf(25, 20, 10, 5).lastOrNull { it >= seconds } ?: 5
    val description = androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_countdown_a11y, milestone)
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }
            .testTag("invite-countdown"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f).height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
            color = color,
            trackColor = com.gtranca.ui.theme.GColors.Midnight,
        )
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_countdown_seconds, seconds),
            color = color,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

@Composable
private fun InviteLine(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Text(label, color = com.gtranca.ui.theme.GColors.Steel)
        content()
    }
}

/**
 * §16.7 botão de Wi-Fi da mesa de jogo num jogo local só com robôs: o jogo está fora da rede (apagado). Ligar (passando
 * pela explicação das permissões, se preciso) faz o jogador **virar anfitrião** sem mudar o jogo: o estado exato segue
 * hospedado ([com.gtranca.multiplayer.MultiplayerTable.hostRobotGame]) e, por §16.12, o jogo salvo é apagado e deixa de
 * ser gravado.
 */
@Composable
private fun rememberLocalWifi(viewModel: GameViewModel, data: GameData, navController: NavHostController): com.gtranca.ui.game.TableWifi {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val tableName = androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, "%s")
    val goOnline: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                val handOff = viewModel.handOffToHost()
                if (handOff == null) {
                    busy = false
                    return@launch
                }
                WriteQueue.app.enqueue { data.settings.setWifiOn(true) }
                val profile = data.settings.settings.catch { emit(Settings()) }.first().let { PlayerProfile.decode(it.profileId) }
                val name = profile.name.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME
                val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
                val config = handOff.config
                val rules = com.gtranca.net.TableRules(
                    tableName = tableName.format(name),
                    mode = config.mode,
                    rules = config.rules,
                    targetScore = config.targetScore,
                    stake = config.stake,
                    difficulty = config.difficulty.id,
                )
                com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, photo).hostRobotGameAsync(
                    rules, name, com.gtranca.net.nearby.newTableId(), handOff.gameSeed, handOff.snapshot, handOff.robots,
                )
                navController.navigate(MultiplayerGameRoute) {
                    popUpTo(HomeRoute) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    if (asking) {
        com.gtranca.net.nearby.MultiplayerAccessGate(
            onReady = { asking = false; goOnline() },
            onCancel = { asking = false },
        )
    }
    return com.gtranca.ui.game.TableWifi(on = false) {
        if (com.gtranca.net.nearby.MultiplayerAccess.check(context) == null) goOnline() else asking = true
    }
}

/**
 * §16.7 o Wi-Fi do jogo (configuração do Perfil): o estado e o toque. Ligar passa pela explicação das permissões (recusar
 * deixa desligado); desligar é imediato. Nada trava: a tela segue usável enquanto a rede liga ou desliga.
 */
@Composable
private fun rememberWifiControl(data: GameData): com.gtranca.ui.tables.WifiControl {
    val context = LocalContext.current
    val setting by remember(data) { data.settings.settings.map { it.wifiOn }.catch { emit(false) } }.collectAsStateWithLifecycle(false)
    var granted by remember { mutableStateOf(com.gtranca.net.nearby.MultiplayerAccess.check(context) == null) }
    var asking by remember { mutableStateOf(false) }
    val write = { on: Boolean -> WriteQueue.app.enqueue { data.settings.setWifiOn(on) } }
    // Permissões retiradas com a configuração ligada: a explicação aparece de novo; "Agora não" desliga a configuração.
    if (asking || (setting && !granted)) {
        com.gtranca.net.nearby.MultiplayerAccessGate(
            onReady = { granted = true; if (asking) write(true); asking = false },
            onCancel = { if (!asking) write(false); asking = false },
        )
    }
    return com.gtranca.ui.tables.WifiControl(setting && granted) { if (setting) write(false) else asking = true }
}

/** Fotos dos jogadores próximos por chave de presença (só em memória), já vazias se "Ver fotos nas mesas compartilhadas" está desligada. */
@Composable
private fun rememberNearbyPhotos(data: GameData): Map<String, ByteArray> {
    val raw by com.gtranca.multiplayer.MultiplayerHub.presencePhotos.collectAsStateWithLifecycle()
    val see by remember(data) { data.settings.settings.map { it.showSharedPhotos }.catch { emit(true) } }.collectAsStateWithLifecycle(true)
    return remember(raw, see) {
        if (!see) emptyMap() else raw.mapNotNull { (k, v) ->
            v.takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.let { k to it }
        }.toMap()
    }
}

/** §16.2 as operações de lugares na rede: o `Recruitment` atrás da interface que a *Seleção de jogadores* usa. */
private class RecruitmentSeats(
    private val rec: com.gtranca.net.Recruitment,
    private val rules: com.gtranca.net.TableRules,
    private val hostName: String,
) : com.gtranca.ui.selection.SeatRecruiter {
    override suspend fun open() = rec.openTable(rules, hostName)
    override suspend fun call(seat: Int, presence: com.gtranca.net.PresenceAdvert) = rec.call(seat, presence)
    override suspend fun placeRobot(seat: Int, persona: String) = rec.placeRobot(seat, persona)
    override suspend fun removeSeat(seat: Int) = rec.removeSeat(seat)
    override suspend fun move(from: Int, to: Int) = rec.move(from, to)
    override suspend fun fillWithRobots(pool: List<String>, seed: Long) = rec.fillWithRobots(pool, seed)
}

/** §14.4, §16.2 *Seleção de jogadores* (docs/telas.md 3.3): monta os lugares e começa a partida (local ou em rede). */
@Composable
private fun PlayerSelectionDestination(route: SelectionRoute, data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val config = remember(route) { route.toConfig() }
    val vm = viewModel { com.gtranca.ui.selection.PlayerSelectionViewModel(config) }
    val profile = rememberProfile(data)
    val selfName = profile?.name?.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME
    val self = (profile ?: PlayerProfile()).persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context))
    val wifi = rememberWifiControl(data)
    val photos = rememberNearbyPhotos(data)
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    val free by remember(rec) { rec?.free ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
    var starting by rememberSaveable { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val tableName = androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, selfName)
    val rulesTitle = com.gtranca.ui.tables.rulesName(config.rules)
    val rules = remember(config, tableName, rulesTitle) {
        com.gtranca.net.TableRules(
            tableName = tableName, mode = config.mode, rules = config.rules, targetScore = config.targetScore,
            stake = config.stake, difficulty = config.difficulty.id, rulesTitle = rulesTitle,
        )
    }
    // §16.2 com o Wi-Fi ligado a mesa de lugares passa a viver no recrutamento (e os chamados aparecem); sem ele, é local.
    LaunchedEffect(rec, profile != null) {
        val live = rec
        if (live == null) {
            vm.detach()
            return@LaunchedEffect
        }
        if (profile == null) return@LaunchedEffect
        vm.attach(RecruitmentSeats(live, rules, selfName))
        launch { live.layout.collect { it?.let(vm::onRemoteLayout) } }
        // §16.2 avisos ao recrutador: recusou, saiu, não respondeu a tempo ou saldo insuficiente (com o motivo).
        live.notices.collect { n ->
            val text = when {
                n.reason == com.gtranca.net.RefusalReason.INSUFFICIENT_BALANCE -> context.getString(com.gtranca.R.string.recruit_balance, n.name)
                n.reason == com.gtranca.net.RefusalReason.NO_RESPONSE -> context.getString(com.gtranca.R.string.recruit_no_response_host, n.name)
                n.refused -> context.getString(com.gtranca.R.string.recruit_refused, n.name)
                else -> context.getString(com.gtranca.R.string.recruit_left, n.name)
            }
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // §16.2 sair da tela sem começar encerra o recrutamento (chamados cancelados, recrutados recebem "Mesa encerrada").
    DisposableEffect(Unit) {
        onDispose { if (!started) com.gtranca.multiplayer.MultiplayerHub.closeRecruiting() }
    }
    val start: () -> Unit = start@{
        if (!vm.canStart || starting) return@start
        starting = true
        scope.launch {
            val live = rec
            if (live != null && vm.hasRemotes) {
                // §16.2 em rede: os recrutados entram direto; a camada "Prepare-se!" cobre a tela até o jogo começar.
                val tableId = com.gtranca.net.nearby.newTableId()
                // §16.2, §16.15 ordem do início: 1) plano; 2) larga a leitura dos eventos; 3) abre a mesa e ANUNCIA; 4) só então envia
                // o início aos recrutados (assim o anúncio da mesa já está no ar quando eles a procuram).
                val plan = live.plan(com.gtranca.game.Tables.personaPool, vm.seed)
                if (plan == null) {
                    starting = false
                    return@launch
                }
                started = true
                com.gtranca.multiplayer.MultiplayerHub.stopRecruiting()
                val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
                com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, photo).hostAsync(rules, selfName, tableId, plan) {
                    com.gtranca.multiplayer.MultiplayerHub.debug("start: table advertised, sending start")
                    live.sendStart(tableId, plan)
                }
            } else {
                // §16.3 mesa só com robôs: o jogo começa no próprio aparelho, sem rede, depois de no mínimo 1,5 s de "Prepare-se!".
                started = true
                live?.closeHosting()
                kotlinx.coroutines.delay(com.gtranca.net.PREPARE_MILLIS)
                navController.startGame(config, vm.seed, com.gtranca.game.Tables.relativeRobots(config.mode, com.gtranca.engine.model.Seat(0), vm.layout.robots))
            }
        }
    }
    com.gtranca.ui.selection.PlayerSelectionScreen(
        vm = vm, self = self, hostName = selfName, wifi = wifi, free = free,
        myKey = com.gtranca.multiplayer.MultiplayerHub.myPresenceKey, photos = photos, starting = starting,
        onRefresh = { rec?.refreshNow() },
        onStart = start,
        onBack = {
            com.gtranca.multiplayer.MultiplayerHub.closeRecruiting()
            navController.popBackStack()
        },
    )
    if (starting) com.gtranca.ui.selection.GetReadyOverlay()
    // Em rede: a mesa do jogo é criada; quando começa, abre o jogo (depois de no mínimo 1,5 s de camada).
    val current = table
    if (started && current != null) {
        GetReadyEffect(current) {
            navController.navigate(MultiplayerGameRoute) {
                popUpTo(HomeRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
        val end by current.end.collectAsStateWithLifecycle()
        end?.let { reason ->
            com.gtranca.ui.multiplayer.MpEndMessage(reason) {
                com.gtranca.multiplayer.MultiplayerHub.leave()
                navController.popBackStack()
            }
        }
    }
}

/**
 * §16.2 *Mesa do recrutado* (docs/telas.md 3.13): só leitura; volta à tela anterior quando deixa de estar na mesa. Quando
 * o recrutador começa a partida, a camada "Prepare-se!" cobre a própria tela até o jogo começar.
 */
@Composable
private fun RecruitedDestination(data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    // O StateFlow dá o valor atual já na 1ª composição (com um inicial "Free" a tela fecharia antes de ler o Joined).
    val statusFlow = rec?.status
    val status = if (statusFlow != null) statusFlow.collectAsStateWithLifecycle().value else com.gtranca.net.RecruitStatus.Free
    val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
    val joining by com.gtranca.multiplayer.MultiplayerHub.joining.collectAsStateWithLifecycle()
    val profile = rememberProfile(data)
    val photos = rememberNearbyPhotos(data)
    val joined = status as? com.gtranca.net.RecruitStatus.Joined
    // Depois do início o recrutamento se encerra (Free): a tela continua desenhando a última mesa por baixo da camada.
    var lastJoined by remember { mutableStateOf<com.gtranca.net.RecruitStatus.Joined?>(null) }
    if (joined != null) lastJoined = joined
    val gettingReady = joining || table != null
    // Saiu, foi removido ou a mesa acabou: de volta à *Iniciar novo jogo* com as opções do próprio jogador.
    LaunchedEffect(joined == null, gettingReady) { if (joined == null && !gettingReady) navController.popBackStack() }
    val shown = joined ?: lastJoined
    if (shown != null) {
        com.gtranca.ui.multiplayer.RecruitedTableScreen(
            shown,
            self = (profile ?: PlayerProfile()).persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context)),
            photos = photos,
            onLeave = {
                if (table != null) {
                    com.gtranca.multiplayer.MultiplayerHub.leave()
                } else {
                    scope.launch { runCatching { rec?.leave() } }
                }
            },
        )
    }
    if (gettingReady) {
        com.gtranca.ui.selection.GetReadyOverlay()
        val current = table
        if (current != null) {
            GetReadyEffect(current) {
                navController.navigate(MultiplayerGameRoute) {
                    popUpTo(HomeRoute) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val end by current.end.collectAsStateWithLifecycle()
            end?.let { reason ->
                com.gtranca.ui.multiplayer.MpEndMessage(reason) { com.gtranca.multiplayer.MultiplayerHub.leave() }
            }
        }
    }
}

/**
 * §16.2 "Prepare-se!": com a camada na tela, espera a mesa do jogo começar (anfitrião com o jogo criado, convidado
 * jogando) e abre o jogo ([onStarted]) depois de no mínimo 1,5 s de camada. Não desenha nada.
 */
@Composable
private fun GetReadyEffect(table: com.gtranca.multiplayer.MultiplayerTable, onStarted: () -> Unit) {
    val role by table.role.collectAsStateWithLifecycle()
    val status by table.guestStatus.collectAsStateWithLifecycle()
    val shownAt = remember { System.nanoTime() / 1_000_000 }
    val started = (role as? com.gtranca.multiplayer.MpRole.Hosting)?.host != null || status == com.gtranca.net.GuestStatus.Playing
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val left = com.gtranca.net.PREPARE_MILLIS - (System.nanoTime() / 1_000_000 - shownAt)
        if (left > 0) kotlinx.coroutines.delay(left)
        onStarted()
    }
}

/**
 * §16.5, §16.12 *Voltar à mesa* (docs/telas.md 3.17): reingressa, como jogador comum, na mesa da sessão guardada. Com o
 * Wi-Fi do jogo desligado, pede confirmação para ligá-lo (com o modal de permissões); depois mostra "Procurando a
 * mesa…" (até 30 s, com Cancelar) e, com o assento de volta, abre a mesa de jogo.
 */
@Composable
private fun RejoinDestination(data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    // `null` = ainda lendo; Optional vazio = não há sessão (sumiu ou expirou entre o Início e aqui).
    val loaded by produceState<java.util.Optional<com.gtranca.data.MultiplayerSession>?>(null, data) {
        value = java.util.Optional.ofNullable(data.multiplayerSession.load())
    }
    val wifiSetting by remember(data) { data.settings.settings.map { it.wifiOn }.catch { emit(false) } }.collectAsStateWithLifecycle(null)
    val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(com.gtranca.net.nearby.MultiplayerAccess.check(context) == null) }
    var asking by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = {
        com.gtranca.multiplayer.MultiplayerHub.leaveKeepingSession()
        navController.popBackStack()
    }
    val current = loaded?.orElse(null)
    val wifi = wifiSetting
    if (loaded?.isPresent == false) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    if (current == null || wifi == null) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("rejoin"), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // 1. Wi-Fi do jogo desligado (ou sem permissão): confirmação antes de ligar.
    if (!searching && (!wifi || !granted)) {
        if (asking) {
            com.gtranca.net.nearby.MultiplayerAccessGate(
                onReady = { granted = true; WriteQueue.app.enqueue { data.settings.setWifiOn(true) }; asking = false; searching = true },
                onCancel = { asking = false; navController.popBackStack() },
            )
        } else {
            com.gtranca.ui.theme.GMessage(
                modifier = Modifier.testTag("rejoin-wifi"),
                title = androidx.compose.ui.res.stringResource(com.gtranca.R.string.rejoin_wifi_title),
                text = androidx.compose.ui.res.stringResource(com.gtranca.R.string.rejoin_wifi_text, current.hostName),
                illustration = com.gtranca.ui.theme.MessageIllustration.Question,
                confirmText = androidx.compose.ui.res.stringResource(com.gtranca.R.string.rejoin_wifi_on),
                confirmTag = "rejoin-wifi-on",
                onConfirm = { asking = true },
                dismissText = androidx.compose.ui.res.stringResource(com.gtranca.R.string.cancel),
                onDismissRequest = { navController.popBackStack() },
            )
        }
        return
    }
    // 2. Procurando a mesa e reingressando com o token.
    LaunchedEffect(Unit) {
        if (!searching) searching = true
        if (com.gtranca.multiplayer.MultiplayerHub.table.value == null) {
            val profile = data.settings.settings.catch { emit(Settings()) }.first().let { PlayerProfile.decode(it.profileId) }
            val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
            com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, photo).rejoinAsync(current)
        }
    }
    val mine = table
    val status = mine?.guestStatus?.collectAsStateWithLifecycle()?.value
    val end = mine?.end?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(status) {
        if (status == com.gtranca.net.GuestStatus.Playing) {
            navController.navigate(MultiplayerGameRoute) {
                popUpTo(HomeRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("rejoin"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = com.gtranca.ui.theme.GColors.Gold)
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(com.gtranca.R.string.rejoin_searching),
            Modifier.testTag("rejoin-searching"),
            color = com.gtranca.ui.theme.GColors.Ivory,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, current.hostName),
            color = com.gtranca.ui.theme.GColors.Steel,
        )
        com.gtranca.ui.theme.GButton(leave, Modifier.heightIn(min = 48.dp).testTag("rejoin-cancel"), kind = com.gtranca.ui.theme.GButtonKind.Secondary) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.gtranca.R.string.rejoin_cancel))
        }
    }
    // 3. Falhas: mesa não encontrada, vaga preenchida, mesa encerrada (ou a mensagem do anfitrião, ex.: outra versão).
    end?.let { reason ->
        val failure = rejoinFailure(reason)
        com.gtranca.ui.theme.GMessage(
            modifier = Modifier.testTag("rejoin-failed"),
            title = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_closed_title),
            text = failure.res?.let { androidx.compose.ui.res.stringResource(it) } ?: failure.text,
            illustration = com.gtranca.ui.theme.MessageIllustration.Info,
            tone = com.gtranca.ui.theme.MessageTone.Warning,
            confirmText = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_ok_short),
            onConfirm = leave,
            onDismissRequest = leave,
        )
    }
}

/** O texto de uma falha ao voltar à mesa: um texto do app ([res]) ou a mensagem do anfitrião ([text]). */
data class RejoinFailure(val res: Int?, val text: String? = null)

/** §16.5, §16.12 a mensagem de cada fim de "Voltar à mesa": mesa não encontrada, vaga preenchida ou mesa encerrada. */
internal fun rejoinFailure(end: com.gtranca.multiplayer.MpEnd): RejoinFailure = when (end) {
    com.gtranca.multiplayer.MpEnd.NotFound, com.gtranca.multiplayer.MpEnd.ConnectFailed, com.gtranca.multiplayer.MpEnd.Timeout ->
        RejoinFailure(com.gtranca.R.string.rejoin_not_found)
    com.gtranca.multiplayer.MpEnd.Closed, is com.gtranca.multiplayer.MpEnd.Ended -> RejoinFailure(com.gtranca.R.string.rejoin_closed)
    // O anfitrião recusou o token: o assento já foi ocupado (ou substituído). Outra versão do jogo traz a própria mensagem.
    is com.gtranca.multiplayer.MpEnd.Refused ->
        if (end.message.contains("ocupado") || end.message.contains("preenchida")) RejoinFailure(com.gtranca.R.string.rejoin_taken)
        else RejoinFailure(null, end.message)
}
