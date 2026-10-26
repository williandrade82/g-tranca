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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.gtranca.ui.tables.TablesViewModel

@Serializable
object HomeRoute

@Serializable
object ProfileRoute

@Serializable
object StatsRoute

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

/** §16.2 *Mesa do recrutado* (docs/telas.md 3.13): a mesa de quem chamou, só para leitura. */
@Serializable
object RecruitedRoute

/** §16.2 "Prepare-se!" da mesa em rede; a mesa está em [com.gtranca.multiplayer.MultiplayerHub]. */
@Serializable
object GetReadyRoute

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
                viewModel { HomeViewModel(data.savedGames, WriteQueue.app, data.stats, data.wallet) },
                onNewGame = { navController.navigateFromHome(TablesRoute) },
                onContinue = { navController.navigateFromHome(GameRoute.RESUME) },
                onStats = { navController.navigateFromHome(StatsRoute) },
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
                onSelectPlayers = { config -> navController.navigate(SelectionRoute.of(config)) { launchSingleTop = true } },
                onCustomize = { navController.navigate(RulesEditorRoute(fromCreate = true)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<SelectionRoute> { entry ->
            val route = entry.toRoute<SelectionRoute>()
            PlayerSelectionDestination(route, data, navController)
        }
        composable<RecruitedRoute> {
            RecruitedDestination(data, navController)
        }
        composable<GetReadyRoute> {
            val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
            val current = table
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                GetReadyDestination(current, onStarted = {
                    navController.navigate(MultiplayerGameRoute) {
                        popUpTo(HomeRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                }) {
                    com.gtranca.multiplayer.MultiplayerHub.leave()
                    navController.popBackStack()
                }
            }
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
                is com.gtranca.net.RecruitEvent.Start -> null
            }
            text?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val invited = recStatus as? com.gtranca.net.RecruitStatus.Invited
    if (invited != null && !onGame) {
        InviteDialog(
            invited,
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
    val status by current.guestStatus.collectAsStateWithLifecycle()
    val end by current.end.collectAsStateWithLifecycle()
    val onGetReady = entry?.destination?.hasRoute<GetReadyRoute>() == true
    // §16.2 o recrutador começou a partida: o recrutado entra na mesa pelo "Prepare-se!".
    LaunchedEffect(status, onGetReady, onGame) {
        val joining = status == com.gtranca.net.GuestStatus.Lobby || status == com.gtranca.net.GuestStatus.Connecting
        if (joining && !onGetReady && !onGame) navController.navigate(GetReadyRoute) { launchSingleTop = true }
    }
    // Fora do "Prepare-se!" (ele mostra o seu): mesa encerrada antes de entrar.
    val reason = end
    if (reason != null && !onGetReady && !onGame) {
        com.gtranca.ui.multiplayer.MpEndMessage(reason) { com.gtranca.multiplayer.MultiplayerHub.leave() }
    }
}

/**
 * §16.2 modal **Convite** (docs/telas.md 4): "<recrutador> está te recrutando", a aposta, o tipo (individual/duplas), a
 * posição (Parceiro/Adversário) e o título da regra; **Detalhes da regra**, **Recusar** e **Aceitar**. Sem prazo.
 */
@Composable
private fun InviteDialog(invited: com.gtranca.net.RecruitStatus.Invited, onAccept: () -> Unit, onRefuse: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    val rules = invited.table.rules
    val partner = invited.table.layout.relationOf(invited.you) == com.gtranca.net.SeatRelation.PARTNER
    val title = rules.rulesTitle.ifBlank { rules.rules.name }
    com.gtranca.ui.theme.GDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("invite"),
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_recruiting, invited.hostName)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                InviteLine(androidx.compose.ui.res.stringResource(com.gtranca.R.string.invite_stake)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.gtranca.R.drawable.coin_bag_small), null, Modifier.size(20.dp))
                    androidx.compose.material3.Text(rules.stake.toString())
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
        // §16.2 avisos ao recrutador: recusou, saiu ou saldo insuficiente (com o motivo).
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
                // §16.2 em rede: os recrutados entram direto; o "Prepare-se!" cobre a tela até o jogo começar.
                val tableId = com.gtranca.net.nearby.newTableId()
                val plan = live.start(tableId, com.gtranca.game.Tables.personaPool, vm.seed)
                if (plan == null) {
                    starting = false
                    return@launch
                }
                started = true
                com.gtranca.multiplayer.MultiplayerHub.stopRecruiting()
                val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
                com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, photo).hostAsync(rules, selfName, tableId, plan)
                navController.navigate(GetReadyRoute) { launchSingleTop = true }
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
        onStart = start,
        onBack = {
            com.gtranca.multiplayer.MultiplayerHub.closeRecruiting()
            navController.popBackStack()
        },
    )
    if (starting) com.gtranca.ui.selection.GetReadyImage()
}

/** §16.2 *Mesa do recrutado* (docs/telas.md 3.13): só leitura; volta à tela anterior quando deixa de estar na mesa. */
@Composable
private fun RecruitedDestination(data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    // O StateFlow dá o valor atual já na 1ª composição (com um inicial "Free" a tela fecharia antes de ler o Joined).
    val statusFlow = rec?.status
    val status = if (statusFlow != null) statusFlow.collectAsStateWithLifecycle().value else com.gtranca.net.RecruitStatus.Free
    val profile = rememberProfile(data)
    val photos = rememberNearbyPhotos(data)
    val joined = status as? com.gtranca.net.RecruitStatus.Joined
    // Saiu, foi removido ou a mesa acabou: de volta à *Iniciar novo jogo* com as opções do próprio jogador.
    LaunchedEffect(joined == null) { if (joined == null) navController.popBackStack() }
    if (joined != null) {
        com.gtranca.ui.multiplayer.RecruitedTableScreen(
            joined,
            self = (profile ?: PlayerProfile()).persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context)),
            photos = photos,
            onLeave = { scope.launch { runCatching { rec?.leave() } } },
        )
    }
}

/**
 * §16.2 "Prepare-se!" da mesa em rede: a imagem por no mínimo 1,5 s e, quando a mesa do jogo começa, o jogo
 * ([onStarted]); [onLeave] sai da mesa.
 */
@Composable
private fun GetReadyDestination(table: com.gtranca.multiplayer.MultiplayerTable, onStarted: () -> Unit, onLeave: () -> Unit) {
    val role by table.role.collectAsStateWithLifecycle()
    val status by table.guestStatus.collectAsStateWithLifecycle()
    val end by table.end.collectAsStateWithLifecycle()
    val shownAt = remember { System.nanoTime() / 1_000_000 }
    val started = (role as? com.gtranca.multiplayer.MpRole.Hosting)?.host != null || status == com.gtranca.net.GuestStatus.Playing
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val left = com.gtranca.net.PREPARE_MILLIS - (System.nanoTime() / 1_000_000 - shownAt)
        if (left > 0) kotlinx.coroutines.delay(left)
        onStarted()
    }
    com.gtranca.ui.multiplayer.GetReadyScreen(end, onLeave)
}
