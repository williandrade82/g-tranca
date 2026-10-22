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
import com.gtranca.ui.tables.CreateTableScreen
import com.gtranca.ui.tables.CreateTableViewModel
import com.gtranca.ui.tables.CreatedTableViewModel
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

/**
 * §14.4 tela da mesa recém-criada, com os personagens sorteados com [seed] (o jogador pode sortear outros ou
 * começar); leva a configuração escolhida em "Criar mesa".
 */
@Serializable
data class NewTableRoute(
    val mode: String,
    val difficulty: String,
    val targetScore: Int,
    val rulesJson: String,
    val seed: Long,
    val stake: Int = 0,
    /** §16.2 nome da mesa multijogador; `null` = mesa contra robôs. */
    val multiName: String? = null,
) {
    fun toConfig() = GameRoute(mode, difficulty, targetScore, rulesJson = rulesJson, stake = stake).toConfig()

    companion object {
        fun of(config: GameConfig, seed: Long, multiName: String? = null) =
            GameRoute.of(config).let { NewTableRoute(it.mode, it.difficulty, it.targetScore, it.rulesJson, seed, it.stake, multiName) }
    }
}

/** §16.2 sala da mesa multijogador ("Escolha sua posição"); a mesa está em [com.gtranca.multiplayer.MultiplayerHub]. */
@Serializable
object SeatPickerRoute

/** §16 jogo da mesa multijogador. */
@Serializable
object MultiplayerGameRoute

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
    /** §14.6 aposta da mesa em moedas (0 = sem aposta). */
    val stake: Int = 0,
) {
    fun toConfig() = GameConfig(
        GameMode.valueOf(mode),
        Difficulty.fromId(difficulty),
        targetScore,
        if (rulesJson.isEmpty()) RuleSet.DEFAULT else Json.decodeFromString(RuleSet.serializer(), rulesJson),
        stake,
    )

    companion object {
        fun of(config: GameConfig, seed: Long? = null) = GameRoute(
            config.mode.name,
            config.difficulty.id,
            config.targetScore,
            rulesJson = Json.encodeToString(RuleSet.serializer(), config.rules),
            seed = seed,
            stake = config.stake,
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
    CompositionLocalProvider(
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
        composable<TablesRoute> {
            // §16 Wi-Fi do jogo (Perfil, guardado): ligado, procura mesas por perto ao abrir, sem tocar em nada. Se as
            // permissões foram retiradas, a explicação aparece de novo; "Agora não" desliga a configuração.
            val wifiSetting by remember(data) { data.settings.settings.map { it.wifiOn }.catch { emit(false) } }.collectAsStateWithLifecycle(false)
            var granted by remember { mutableStateOf(com.gtranca.net.nearby.MultiplayerAccess.check(context) == null) }
            val turnOff = { WriteQueue.app.enqueue { data.settings.setWifiOn(false) } }
            if (wifiSetting && !granted) {
                com.gtranca.net.nearby.MultiplayerAccessGate(
                    onReady = { granted = true },
                    onCancel = { turnOff() },
                )
            }
            val wifiOn = wifiSetting && granted
            val adverts by remember(wifiOn) {
                if (wifiOn) com.gtranca.multiplayer.MultiplayerHub.discover(context).map { list -> list.filter { it.listed } }
                    .catch { e ->
                        // O Nearby recusou a busca (ex.: ApiException com código de status): avisa e desliga, sem fechar o app.
                        android.widget.Toast.makeText(
                            context,
                            context.getString(com.gtranca.R.string.mp_discovery_failed, e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        turnOff()
                        emit(emptyList())
                    }
                else kotlinx.coroutines.flow.flowOf(emptyList())
            }.collectAsStateWithLifecycle(emptyList())
            val profile = rememberProfile(data)
            TablesScreen(
                viewModel { TablesViewModel(settings = data.settings, wallet = data.wallet) },
                onOpen = { table -> navController.navigate(TableRulesRoute(table.id)) { launchSingleTop = true } },
                onCreate = { navController.navigate(CreateTableRoute) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
                multiplayer = com.gtranca.ui.tables.TablesMultiplayer(
                    wifiOn = wifiOn,
                    tables = adverts.distinctBy { it.tableId },
                    onOpen = { advert ->
                        com.gtranca.multiplayer.MultiplayerHub.newTable(context, data).joinAsync(advert.tableId, profile?.name.orEmpty())
                        navController.navigate(SeatPickerRoute) { launchSingleTop = true }
                    },
                ),
            )
        }
        composable<SeatPickerRoute> {
            val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
            val balance by remember(data) { data.wallet.wallet.map { it.balance }.catch { emit(0) } }.collectAsStateWithLifecycle(Int.MAX_VALUE)
            val current = table
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                com.gtranca.ui.multiplayer.SeatPickerScreen(
                    current,
                    balance,
                    onStarted = {
                        navController.navigate(MultiplayerGameRoute) {
                            popUpTo(HomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onLeave = {
                        com.gtranca.multiplayer.MultiplayerHub.leave()
                        navController.popBackStack()
                    },
                )
            }
        }
        composable<MultiplayerGameRoute> { MultiplayerGameDestination(data, navController) }
        composable<TableRulesRoute> { entry ->
            val route = entry.toRoute<TableRulesRoute>()
            // A lista sorteada vive na entrada das mesas: a mesa aberta é a mesma que o jogador tocou.
            val tablesEntry = remember(entry) { navController.getBackStackEntry(TablesRoute) }
            val tablesVm = viewModel<TablesViewModel>(tablesEntry) { TablesViewModel(settings = data.settings, wallet = data.wallet) }
            val table = tablesVm.table(route.tableId)
            val balance by tablesVm.balance.collectAsStateWithLifecycle()
            if (table == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                TableRulesScreen(
                    table,
                    onStart = { config -> navController.startGame(config, table.seed) },
                    onBack = { navController.popBackStack() },
                    balance = balance,
                )
            }
        }
        composable<CreateTableRoute> { entry ->
            val vm = viewModel { CreateTableViewModel(data.settings, WriteQueue.app, data.wallet) }
            // Voltando do editor de regras: relê as regras personalizadas (e escolhe "Personalizada" se salvou).
            val saved by entry.savedStateHandle.getStateFlow(CUSTOM_RULES_SAVED, false).collectAsStateWithLifecycle()
            LaunchedEffect(saved) {
                if (saved) {
                    entry.savedStateHandle[CUSTOM_RULES_SAVED] = false
                    vm.reloadCustomRules(select = true)
                }
            }
            val profile = rememberProfile(data)
            CreateTableScreen(
                vm,
                onCreate = { config ->
                    // §14.4 antes de começar, mostra a mesa com os personagens sorteados
                    navController.navigate(NewTableRoute.of(config, System.nanoTime())) { launchSingleTop = true }
                },
                onCustomize = { navController.navigate(RulesEditorRoute(fromCreate = true)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
                // §16.2 mesa multijogador: "Regras da mesa" sem os robôs (só são apresentados no início do jogo).
                onCreateMultiplayer = { config, name ->
                    navController.navigate(NewTableRoute.of(config, System.nanoTime(), name)) { launchSingleTop = true }
                },
                accessGate = { ready, cancel -> com.gtranca.net.nearby.MultiplayerAccessGate(ready, cancel) },
                defaultTableName = androidx.compose.ui.res.stringResource(
                    com.gtranca.R.string.create_table_name_default,
                    profile?.name?.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME,
                ),
            )
        }
        composable<NewTableRoute> { entry ->
            val route = entry.toRoute<NewTableRoute>()
            val vm = viewModel { CreatedTableViewModel(route.toConfig(), route.seed, data.settings, data.wallet) }
            val table by vm.table.collectAsStateWithLifecycle()
            val balance by vm.balance.collectAsStateWithLifecycle()
            val profile = rememberProfile(data)
            val multiName = route.multiName
            TableRulesScreen(
                table,
                onStart = { config ->
                    if (multiName == null) {
                        navController.startGame(config, table.seed)
                    } else {
                        // §16.2 publica a mesa: as regras vão uma vez a quem entrar; os robôs (sorteados sem exibir) vão, na ordem dos
                        // assentos que ficarem vagos.
                        val rules = com.gtranca.net.TableRules(
                            tableName = multiName,
                            mode = config.mode,
                            rules = config.rules,
                            targetScore = config.targetScore,
                            stake = config.stake,
                            difficulty = config.difficulty.id,
                            robots = table.players.mapNotNull { it.character?.profile?.code },
                        )
                        com.gtranca.multiplayer.MultiplayerHub.newTable(context, data)
                            .hostAsync(rules, profile?.name.orEmpty(), com.gtranca.net.nearby.newTableId())
                        navController.navigate(SeatPickerRoute) { launchSingleTop = true }
                    }
                },
                onBack = { navController.popBackStack() },
                onReroll = vm::reroll,
                balance = balance,
                multiplayerName = multiName,
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
            StatsScreen(viewModel { StatsViewModel(data.stats, data.wallet) }) { navController.popBackStack() }
        }
    }
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
) : ViewModel() {
    val personas = table.personas(self, vacant) { name -> disconnected.format(name) }
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
    val holder = viewModel { MultiplayerGameHolder(table, profile.persona(PlayerProfile.DEFAULT_NAME, photoPath), vacant, disconnected) }
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
        )
    }
    val hooks = com.gtranca.ui.multiplayer.rememberMultiplayerHooks(table, personas, goHome)
    GameScreen(viewModel, hooks, goHome)
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
