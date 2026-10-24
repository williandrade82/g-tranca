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
import com.gtranca.ui.tables.TablesViewModel

@Serializable
object HomeRoute

@Serializable
object ProfileRoute

@Serializable
object StatsRoute

/** §14.4 "Mesas disponíveis": a montagem da mesa e as mesas de outros anfitriões por perto. */
@Serializable
object TablesRoute

/**
 * §16.2 "Mesa só com robôs": a escolha de posições no próprio aparelho, sem rede. [robots] são os personagens marcados na
 * montagem, na ordem da escolha (separados por vírgula); [seed] é a semente da mesa (sorteio dos que sobram e do jogo).
 */
@Serializable
data class LocalSeatsRoute(
    val mode: String,
    val difficulty: String,
    val targetScore: Int,
    val rulesJson: String,
    val stake: Int,
    val robots: String,
    val seed: Long,
) {
    fun toConfig() = GameRoute(mode, difficulty, targetScore, rulesJson = rulesJson, stake = stake).toConfig()

    fun robotList(): List<String> = robots.split(',').filter { it.isNotBlank() }

    companion object {
        fun of(config: GameConfig, robots: List<String>, seed: Long) =
            GameRoute.of(config).let { LocalSeatsRoute(it.mode, it.difficulty, it.targetScore, it.rulesJson, it.stake, robots.joinToString(","), seed) }
    }
}

/** §16.2 sala da mesa multijogador ("Escolha sua posição"); a mesa está em [com.gtranca.multiplayer.MultiplayerHub]. */
@Serializable
object SeatPickerRoute

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
            // §16 Wi-Fi do jogo (Perfil, guardado): ligado, procura jogadores e mesas por perto ao abrir, sem tocar em nada.
            // Se as permissões foram retiradas, a explicação aparece de novo; "Agora não" desliga a configuração.
            val tablesVm = viewModel { TablesViewModel(data.settings, WriteQueue.app, data.wallet) }
            // Voltando do editor de regras: relê as regras personalizadas (e escolhe "Personalizada" se salvou).
            val saved by entry.savedStateHandle.getStateFlow(CUSTOM_RULES_SAVED, false).collectAsStateWithLifecycle()
            LaunchedEffect(saved) {
                if (saved) {
                    entry.savedStateHandle[CUSTOM_RULES_SAVED] = false
                    tablesVm.reloadCustomRules(select = true)
                }
            }
            val wifiSetting by tablesVm.wifiOn.collectAsStateWithLifecycle()
            var granted by remember { mutableStateOf(com.gtranca.net.nearby.MultiplayerAccess.check(context) == null) }
            var asking by remember { mutableStateOf(false) }
            // §16.2 recrutamento imediato (anfitrião montando a mesa e/ou recrutado), com o Wi-Fi do jogo ligado.
            val navScope = androidx.compose.runtime.rememberCoroutineScope()
            val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
            val recStatus by remember(rec) { rec?.status ?: kotlinx.coroutines.flow.flowOf(com.gtranca.net.RecruitStatus.Free) }
                .collectAsStateWithLifecycle(com.gtranca.net.RecruitStatus.Free)
            LaunchedEffect(rec) {
                val r = rec
                tablesVm.recruiter = r?.let { live ->
                    object : com.gtranca.ui.tables.Recruiter {
                        override suspend fun mark(player: com.gtranca.ui.tables.NearbyPlayer) =
                            live.mark(com.gtranca.net.PresenceAdvert(player.key, player.name))
                        override suspend fun unmark(key: String) = live.unmark(key)
                    }
                }
                if (r == null) {
                    tablesVm.onRecruits(emptyList())
                    return@LaunchedEffect
                }
                launch { r.recruits.collect { tablesVm.onRecruits(it) } }
                // §16.2 avisos ao anfitrião: recusou, saiu ou saldo insuficiente.
                r.notices.collect { n ->
                    val text = when {
                        n.reason == com.gtranca.net.RefusalReason.INSUFFICIENT_BALANCE -> context.getString(com.gtranca.R.string.recruit_balance, n.name)
                        n.refused -> context.getString(com.gtranca.R.string.recruit_refused, n.name)
                        else -> context.getString(com.gtranca.R.string.recruit_left, n.name)
                    }
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            LaunchedEffect(tablesVm) {
                tablesVm.notices.collect {
                    val text = context.getString(
                        if (it == com.gtranca.ui.tables.TablesNotice.RECRUITED_ELSEWHERE) com.gtranca.R.string.recruit_recruited else com.gtranca.R.string.recruit_mark_failed,
                    )
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val turnOff = {
                // §16.2 o anfitrião desliga o Wi-Fi do jogo: os recrutados recebem "Mesa encerrada".
                rec?.let { r -> navScope.launch { runCatching { r.closeHosting() } } }
                tablesVm.setWifi(false)
            }
            // O botão liga: explicação e permissões; recusar deixa desligado (no botão e no Perfil).
            if (asking || (wifiSetting && !granted)) {
                com.gtranca.net.nearby.MultiplayerAccessGate(
                    onReady = { granted = true; if (asking) tablesVm.setWifi(true); asking = false },
                    onCancel = { if (!asking) turnOff(); asking = false },
                )
            }
            val wifiOn = wifiSetting && granted
            val failed: (Throwable) -> Unit = { e ->
                // O Nearby recusou a busca (ex.: ApiException com código de status): avisa e desliga, sem fechar o app.
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.gtranca.R.string.mp_discovery_failed, e.message ?: e.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                turnOff()
            }
            val adverts by remember(wifiOn) {
                if (wifiOn) com.gtranca.multiplayer.MultiplayerHub.discover(context).map { list -> list.filter { it.listed } }
                    .catch { e -> failed(e); emit(emptyList()) }
                else kotlinx.coroutines.flow.flowOf(emptyList())
            }.collectAsStateWithLifecycle(emptyList())
            // §16.2 jogadores próximos (presença): aparecem e somem conforme a busca.
            val presences by remember(wifiOn) {
                if (wifiOn) com.gtranca.multiplayer.MultiplayerHub.discoverPresence(context).catch { emit(emptyList()) }
                else kotlinx.coroutines.flow.flowOf(emptyList())
            }.collectAsStateWithLifecycle(emptyList())
            // §16.2 a foto de cada um (pedida uma vez, só em memória), se "Ver fotos nas mesas compartilhadas" está ligada.
            val nearbyPhotos by com.gtranca.multiplayer.MultiplayerHub.presencePhotos.collectAsStateWithLifecycle()
            val seePhotos by remember(data) { data.settings.settings.map { it.showSharedPhotos }.catch { emit(true) } }.collectAsStateWithLifecycle(true)
            val decoded = remember(nearbyPhotos) {
                nearbyPhotos.mapNotNull { (k, v) -> v.takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.let { k to it } }.toMap()
            }
            LaunchedEffect(presences, decoded, seePhotos) {
                val myKey = com.gtranca.multiplayer.MultiplayerHub.myPresenceKey
                tablesVm.onNearby(
                    presences.map {
                        com.gtranca.ui.tables.NearbyPlayer(
                            it.key, it.name, if (seePhotos) decoded[it.key] else null,
                            recruitedElsewhere = it.recruitedBy != null && it.recruitedBy != myKey,
                        )
                    },
                )
            }
            val profile = rememberProfile(data)
            val selfName = profile?.name?.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME
            val self = (profile ?: PlayerProfile()).persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context))
            val tableName = androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, selfName)
            // §16.2 cada mudança da montagem vai na hora a quem aceitou (só para leitura).
            val setupState by tablesVm.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(rec, setupState, selfName) {
                val r = rec ?: return@LaunchedEffect
                val s = setupState
                val participants = listOf(com.gtranca.net.RecruitParticipant(selfName)) + s.picks.map { pick ->
                    when (pick) {
                        is com.gtranca.ui.tables.Pick.Bot -> com.gtranca.net.RecruitParticipant(pick.profile.botName, pick.profile.code)
                        is com.gtranca.ui.tables.Pick.Near -> com.gtranca.net.RecruitParticipant(
                            s.nearby.firstOrNull { it.key == pick.key }?.name.orEmpty(), accepted = pick.key in s.accepted,
                        )
                    }
                }
                runCatching {
                    r.updateConfig(
                        com.gtranca.net.RecruitConfig(
                            selfName, s.effectiveMode, s.difficulty.id, s.stake, s.rules, s.targetScore ?: s.rules.targetScore, participants,
                        ),
                    )
                }
            }
            TablesScreen(
                tablesVm,
                joined = recStatus as? com.gtranca.net.RecruitStatus.Joined,
                onLeaveTable = { rec?.let { r -> navScope.launch { runCatching { r.leave() } } } },
                self = self,
                onChooseSeats = { chosen ->
                    val config = chosen.config
                    if (chosen.nearby.isNotEmpty()) navScope.launch {
                        // §16.2 quem aceitou vai direto à escolha de posições; quem não respondeu vira robô sorteado.
                        val tableId = com.gtranca.net.nearby.newTableId()
                        val accepted = runCatching { rec?.start(tableId) }.getOrNull().orEmpty()
                        com.gtranca.multiplayer.MultiplayerHub.stopRecruiting()
                        val rules = com.gtranca.net.TableRules(
                            tableName = tableName,
                            mode = config.mode,
                            rules = config.rules,
                            targetScore = config.targetScore,
                            stake = config.stake,
                            difficulty = config.difficulty.id,
                        )
                        val others = chosen.picks.mapNotNull { pick ->
                            when (pick) {
                                is com.gtranca.ui.tables.Pick.Bot -> com.gtranca.net.Participant.Robot(pick.profile.code)
                                is com.gtranca.ui.tables.Pick.Near -> accepted.firstOrNull { it.key == pick.key }?.let { com.gtranca.net.Participant.Nearby(it) }
                            }
                        }
                        com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, com.gtranca.multiplayer.SharedPhoto.encode(context, profile))
                            .hostAsync(rules, selfName, tableId, com.gtranca.net.LobbySetup(others, com.gtranca.game.Tables.personaPool, Random.nextLong()))
                        navController.navigate(SeatPickerRoute) { launchSingleTop = true }
                    } else {
                        // §16.2 mesa só com robôs: a escolha de posições sem rede.
                        val robots = chosen.picks.filterIsInstance<com.gtranca.ui.tables.Pick.Bot>().map { it.profile.code }
                        navController.navigate(LocalSeatsRoute.of(config, robots, Random.nextLong())) { launchSingleTop = true }
                    }
                },
                onCustomize = { navController.navigate(RulesEditorRoute(fromCreate = true)) { launchSingleTop = true } },
                onBack = {
                    // §16.2 o anfitrião sai da montagem: os recrutados recebem "Mesa encerrada".
                    rec?.let { r -> navScope.launch { runCatching { r.closeHosting() } } }
                    navController.popBackStack()
                },
                multiplayer = com.gtranca.ui.tables.TablesMultiplayer(
                    wifiOn = wifiOn,
                    onToggle = { if (wifiSetting) turnOff() else asking = true },
                    tables = adverts.distinctBy { it.tableId },
                    onOpen = { advert ->
                        // §16.2 pedido de entrada: aceito pelo anfitrião, abre a escolha de posições.
                        com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, com.gtranca.multiplayer.SharedPhoto.encode(context, profile)).joinAsync(advert.tableId, selfName)
                        navController.navigate(SeatPickerRoute) { launchSingleTop = true }
                    },
                ),
            )
        }
        composable<LocalSeatsRoute> { entry ->
            val route = entry.toRoute<LocalSeatsRoute>()
            val config = remember(route) { route.toConfig() }
            val picker = viewModel { com.gtranca.ui.multiplayer.LocalSeatPicker(config.mode, route.robotList(), route.seed) }
            val seating by picker.seating.collectAsStateWithLifecycle()
            val start by picker.start.collectAsStateWithLifecycle()
            LaunchedEffect(start) { start?.let { navController.startGame(config, route.seed, it) } }
            val balance by remember(data) { data.wallet.wallet.map { it.balance }.catch { emit(0) } }.collectAsStateWithLifecycle(Int.MAX_VALUE)
            val profile = rememberProfile(data)
            val selfName = profile?.name?.takeIf { it.isNotBlank() } ?: PlayerProfile.DEFAULT_NAME
            var shownName by rememberSaveable { mutableStateOf<String?>(null) }
            val rules = com.gtranca.net.TableRules(
                tableName = androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, selfName),
                mode = config.mode,
                rules = config.rules,
                targetScore = config.targetScore,
                stake = config.stake,
                difficulty = config.difficulty.id,
            )
            if (profile != null) {
                com.gtranca.ui.multiplayer.SeatPickerScreen(
                    com.gtranca.ui.multiplayer.SeatPickerUi(
                        seating = seating,
                        myId = com.gtranca.ui.multiplayer.LocalSeatPicker.ME,
                        names = mapOf(com.gtranca.ui.multiplayer.LocalSeatPicker.ME to (shownName?.ifBlank { null } ?: selfName)),
                        rules = rules,
                    ),
                    balance = balance,
                    actions = com.gtranca.ui.multiplayer.SeatPickerActions(
                        rotate = picker::rotate,
                        confirm = picker::confirm,
                        rename = { shownName = it },
                        leave = { navController.popBackStack() },
                    ),
                    self = profile.persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context)),
                    initialName = selfName,
                )
            }
        }
        composable<SeatPickerRoute> {
            val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
            val current = table
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                NetworkSeatPicker(current, data, onStarted = {
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
 * §16.2 "Escolha sua posição" da mesa com jogadores por perto: a mesa ([com.gtranca.multiplayer.MultiplayerTable]) conduz
 * a escolha e o início (2 s depois de todos confirmarem); [onStarted] abre o jogo e [onLeave] sai da mesa.
 */
@Composable
private fun NetworkSeatPicker(table: com.gtranca.multiplayer.MultiplayerTable, data: GameData, onStarted: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val role by table.role.collectAsStateWithLifecycle()
    val seating by table.seating.collectAsStateWithLifecycle()
    val roster by table.roster.collectAsStateWithLifecycle()
    val rules by table.rules.collectAsStateWithLifecycle()
    val photos by table.photos.collectAsStateWithLifecycle()
    val requests by table.joinRequests.collectAsStateWithLifecycle()
    val status by table.guestStatus.collectAsStateWithLifecycle()
    val end by table.end.collectAsStateWithLifecycle()
    val balance by remember(data) { data.wallet.wallet.map { it.balance }.catch { emit(0) } }.collectAsStateWithLifecycle(Int.MAX_VALUE)
    val showPhotos by remember(data) { data.settings.settings.map { it.showSharedPhotos }.catch { emit(true) } }.collectAsStateWithLifecycle(true)
    val started = (role as? com.gtranca.multiplayer.MpRole.Hosting)?.host != null || status == com.gtranca.net.GuestStatus.Playing
    LaunchedEffect(started) { if (started) onStarted() }
    // §16.2 avisos breves da escolha de posições ("<nome> saiu; <robô> assume o lugar", com o motivo).
    var notice by remember { mutableStateOf<com.gtranca.multiplayer.MpNotice?>(null) }
    LaunchedEffect(table) { table.notices.collect { if (it.lobby) notice = it } }
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            kotlinx.coroutines.delay(LOBBY_NOTICE_MILLIS)
            notice = null
        }
    }
    val profile = rememberProfile(data)
    val myId = remember(role, roster, seating) { table.myId }
    com.gtranca.ui.multiplayer.SeatPickerScreen(
        com.gtranca.ui.multiplayer.SeatPickerUi(
            seating = seating,
            myId = myId,
            names = roster.associate { it.id to it.name },
            photos = if (showPhotos) photos else emptyMap(),
            rules = rules,
            waitingApproval = status == com.gtranca.net.GuestStatus.WaitingApproval,
            joinRequests = requests,
            notice = notice?.let { com.gtranca.ui.multiplayer.noticeText(it) },
        ),
        balance = balance,
        actions = com.gtranca.ui.multiplayer.SeatPickerActions(
            rotate = { dir -> scope.launch { table.rotate(dir) } },
            confirm = { scope.launch { table.confirmSeat() } },
            rename = { name -> scope.launch { table.rename(name) } },
            leave = onLeave,
            approve = { id -> scope.launch { table.approveJoin(id) } },
            refuse = { id -> scope.launch { table.refuseJoin(id) } },
        ),
        self = profile?.persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context)),
        initialName = table.name,
        end = end,
    )
}

/** Quanto tempo o aviso breve fica na escolha de posições. */
private const val LOBBY_NOTICE_MILLIS = 3_500L

/**
 * §16.2 com o Wi-Fi do jogo ligado e fora de um jogo: anuncia a presença do jogador (nome) e procura convites; o modal
 * **Convite** aparece em qualquer tela fora de um jogo (10 s, aceito sozinho sem resposta) e, aceito, abre a escolha de
 * posições. Recusa (do convidado ou automática por saldo) mostra o modal **Recusa** com o motivo.
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
    LaunchedEffect(presenceOn, name) {
        if (!presenceOn) return@LaunchedEffect
        val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
        runCatching { com.gtranca.multiplayer.MultiplayerHub.runPresence(context, data, name, photo) }
    }
    // §16.2 recrutamento imediato: o convite (sem prazo) e os avisos ao recrutado, em qualquer tela fora de jogo.
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    val recStatus by remember(rec) { rec?.status ?: kotlinx.coroutines.flow.flowOf(com.gtranca.net.RecruitStatus.Free) }
        .collectAsStateWithLifecycle(com.gtranca.net.RecruitStatus.Free)
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(Unit) {
        com.gtranca.multiplayer.MultiplayerHub.recruitEvents.collect { event ->
            val text = when (event) {
                is com.gtranca.net.RecruitEvent.Removed -> context.getString(com.gtranca.R.string.recruit_removed, event.hostName)
                is com.gtranca.net.RecruitEvent.Closed -> context.getString(com.gtranca.R.string.mp_closed_title)
                is com.gtranca.net.RecruitEvent.InsufficientBalance -> context.getString(com.gtranca.R.string.mp_refusal_balance)
                is com.gtranca.net.RecruitEvent.Start -> null
            }
            text?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val invited = recStatus as? com.gtranca.net.RecruitStatus.Invited
    if (invited != null && !onGame) {
        com.gtranca.ui.theme.GMessage(
            modifier = androidx.compose.ui.Modifier.testTag("invite"),
            title = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_invite_title),
            text = androidx.compose.ui.res.stringResource(com.gtranca.R.string.tables_host, invited.hostName),
            illustration = com.gtranca.ui.theme.MessageIllustration.Question,
            confirmText = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_invite_accept),
            confirmTag = "invite-accept",
            onConfirm = {
                scope.launch {
                    if (rec?.accept() == true) {
                        // §16.2 quem estava em Personalizar regra volta sem salvar; a montagem mostra a mesa do anfitrião.
                        val dest = navController.currentBackStackEntry?.destination
                        if (dest?.hasRoute<RulesEditorRoute>() == true) navController.popBackStack()
                        if (navController.currentBackStackEntry?.destination?.hasRoute<TablesRoute>() != true) {
                            navController.navigate(TablesRoute) { launchSingleTop = true }
                        }
                    }
                }
            },
            dismissText = androidx.compose.ui.res.stringResource(com.gtranca.R.string.mp_invite_refuse),
            dismissTag = "invite-refuse",
            onDismiss = { scope.launch { rec?.refuse() } },
            onDismissRequest = {},
        )
    }
    val current = table ?: return
    val status by current.guestStatus.collectAsStateWithLifecycle()
    val end by current.end.collectAsStateWithLifecycle()
    val onPicker = entry?.destination?.hasRoute<SeatPickerRoute>() == true
    // §16.2 recrutado que recebeu o início (ou pedido de entrada aceito): a escolha de posições.
    LaunchedEffect(status, onPicker, onGame) {
        if (status == com.gtranca.net.GuestStatus.Lobby && !onPicker && !onGame) navController.navigate(SeatPickerRoute) { launchSingleTop = true }
    }
    // Fora da escolha de posições (ela mostra o seu): recusa ou mesa encerrada antes de entrar.
    val reason = end
    if (reason != null && !onPicker && !onGame) {
        com.gtranca.ui.multiplayer.MpEndMessage(reason) { com.gtranca.multiplayer.MultiplayerHub.leave() }
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
