package com.gtranca.ui.online

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtranca.R
import com.gtranca.data.GameData
import com.gtranca.data.InventoryRepository
import com.gtranca.data.Settings
import com.gtranca.game.BotCharacter
import com.gtranca.game.HandPrefs
import com.gtranca.game.Persona
import com.gtranca.game.PlayerProfile
import com.gtranca.online.client.OnlineClient
import com.gtranca.online.contracts.BoosterPrompt
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.SeatInfo
import com.gtranca.online.contracts.SeatKind
import com.gtranca.ui.boosters.BoosterSelectorScreen
import com.gtranca.ui.boosters.BoosterSelectorViewModel
import com.gtranca.ui.game.GameScreen
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A partida online (§18.3) enquanto a tela existe: conecta, guarda o [OnlineTableDriver] e monta nome e avatar de cada
 * assento a partir dos assentos do servidor (o próprio jogador com o nome e a foto do Perfil).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnlineGameHolder(
    private val client: OnlineClient,
    private val account: OnlineAccount,
    tableId: String,
    private val self: Persona,
    /** §17.2/§17.3 Inventário do aparelho: só para o catálogo e a marcação que pré-preenche o seletor online. */
    private val inventory: InventoryRepository,
    /** Mostrada no seletor se não houver conexão agora ao tentar confirmar (antes de qualquer resposta do servidor). */
    private val boosterNetworkErrorMessage: String,
) : ViewModel() {
    private val _driver = MutableStateFlow<OnlineTableDriver?>(null)
    val driver: StateFlow<OnlineTableDriver?> = _driver.asStateFlow()

    private val _ended = MutableStateFlow<OnlineGameEnd?>(null)

    /** Fim sem o fim de jogo normal (mesa encerrada, outro aparelho, recusa): a tela avisa e volta. */
    val ended: StateFlow<OnlineGameEnd?> = _ended.asStateFlow()

    /**
     * §17.3/§18.3 este assento ainda não confirmou os boosters do jogo: a tela abre o seletor (3.19 "Online") em vez de
     * esperar a mesa. `null` = nunca precisou (já confirmado antes) ou já confirmou agora.
     */
    private val _boosterPrompt = MutableStateFlow<BoosterPrompt?>(null)
    val boosterPrompt: StateFlow<BoosterPrompt?> = _boosterPrompt.asStateFlow()

    /** O instante ([android.os.SystemClock.elapsedRealtime]) em que o prazo do [BoosterPrompt] atual acaba. */
    private val _boosterDeadlineAt = MutableStateFlow<Long?>(null)
    val boosterDeadlineAt: StateFlow<Long?> = _boosterDeadlineAt.asStateFlow()

    /** A resposta do servidor a uma tentativa de confirmar, para [OnlineBoosterSource.confirm] (§17.3/§18.3). */
    private val _boosterOutcomes = MutableSharedFlow<BoosterOutcomeEvent>(extraBufferCapacity = 4)

    /** A fonte do seletor online (§17.3/§18.3): pronta assim que a sessão existe (antes mesmo do 1º `Welcome`, ver [init]). */
    private val _boosterSource = MutableStateFlow<OnlineBoosterSource?>(null)
    val boosterSource: StateFlow<OnlineBoosterSource?> = _boosterSource.asStateFlow()

    /** Fotos dos outros jogadores (§18.2), por id do convidado, baixadas uma vez cada. */
    private val photos = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val fetched = mutableSetOf<String>()

    /** Nome e avatar por assento (a tela do jogo os usa no lugar do sorteio local). */
    val personas: StateFlow<List<Persona>> = _driver.flatMapLatest { d ->
        d?.seats?.let { seats ->
            kotlinx.coroutines.flow.combine(seats, photos) { list, pics -> list.map { personaOf(it, d.welcome.you.index, pics) } }
        } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun fetchPhotos(account: OnlineAccount, seats: List<SeatInfo>) {
        seats.mapNotNull { it.guestId }.filter { fetched.add(it) }.forEach { id ->
            viewModelScope.launch {
                val base64 = runCatching { account.call { api, g -> api.photo(g.token, "/api/lobby/guests/$id/photo") } }.getOrNull()
                if (base64 == null) {
                    fetched.remove(id) // tenta de novo na próxima mudança dos assentos
                    return@launch
                }
                runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()?.let { bytes -> photos.value = photos.value + (id to bytes) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val guest = runCatching { account.guest() }.getOrNull()
            if (guest == null) {
                _ended.value = OnlineGameEnd.Failed(OnlineProblem.UNAUTHENTICATED)
                return@launch
            }
            val session = client.matchSession(guest.token, tableId)
            // §17.3/§18.3 pronta desde já: o rascunho inicial (marcação do Inventário) sai assim que o seletor abrir.
            _boosterSource.value = OnlineBoosterSource(
                session = session,
                economy = client,
                account = account,
                deviceSelection = { inventory.selection.first() },
                outcomes = _boosterOutcomes,
                networkErrorMessage = boosterNetworkErrorMessage,
                catalog = inventory.catalog,
            )
            val d = OnlineTableDriver.connect(
                session,
                viewModelScope,
                onFailure = { end ->
                    _ended.value = end
                    // Libera uma tentativa de confirmar que ficou esperando a resposta: a conexão não volta mais.
                    _boosterOutcomes.tryEmit(BoosterOutcomeEvent.Failed(boosterNetworkErrorMessage))
                },
                onBoosterPrompt = { prompt ->
                    _boosterDeadlineAt.value = android.os.SystemClock.elapsedRealtime() + prompt.deadlineMs
                    _boosterPrompt.value = prompt
                },
                onBoosterConfirmed = { boosters ->
                    _boosterPrompt.value = null
                    _boosterOutcomes.tryEmit(BoosterOutcomeEvent.Confirmed(boosters))
                },
                onBoosterConfirmFailed = { message -> _boosterOutcomes.tryEmit(BoosterOutcomeEvent.Failed(message)) },
            )
            _driver.value = d
            if (d != null) launch { d.seats.collect { fetchPhotos(account, it) } }
            d?.ended?.collect { if (it != null) _ended.value = it }
        }
    }

    private fun personaOf(seat: SeatInfo, mine: Int, pics: Map<String, ByteArray>): Persona = when {
        seat.index == mine -> self
        seat.kind == SeatKind.ROBO && seat.profile != null -> {
            val profile = com.gtranca.game.BotProfile.valueOf(seat.profile!!.name)
            val level = when (seat.level) {
                BotLevel.MEDIO -> com.gtranca.game.BotLevel.MADURO
                BotLevel.DIFICIL -> com.gtranca.game.BotLevel.SENIOR
                else -> com.gtranca.game.BotLevel.INICIANTE
            }
            Persona(profile.botName, character = BotCharacter(profile, level))
        }
        else -> Persona(seat.name.ifBlank { "?" }, color = seat.name.hashCode().mod(Persona.COLORS), photoBytes = seat.guestId?.let(pics::get))
    }
}

/** A tela do jogo online (docs/telas.md 3.23, etapa B): a mesa de sempre, conduzida pelo servidor. */
@Composable
internal fun OnlineGameDestination(data: GameData, client: OnlineClient, account: OnlineAccount, tableId: String, onExit: (OnlineGameEnd?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs by produceState<Settings?>(null) { value = data.settings.settings.catch { emit(Settings()) }.first() }
    val loaded = prefs ?: return Loading()
    val profile = PlayerProfile.decode(loaded.profileId)
    val photoPath = com.gtranca.ui.profile.ProfilePhoto.savedPath(context)
    val networkErrorMessage = stringResource(R.string.online_problem_network)
    val holder = viewModel(key = "online-game-$tableId") {
        OnlineGameHolder(client, account, tableId, profile.persona(PlayerProfile.DEFAULT_NAME, photoPath), data.inventory, networkErrorMessage)
    }
    val driver by holder.driver.collectAsStateWithLifecycle()
    val ended by holder.ended.collectAsStateWithLifecycle()
    ended?.let { end ->
        GMessage(
            modifier = Modifier.testTag("online-game-ended"),
            title = stringResource(R.string.online_game_ended_title),
            text = when (end) {
                is OnlineGameEnd.Closed -> end.message
                is OnlineGameEnd.Replaced -> end.message
                is OnlineGameEnd.Failed -> stringResource(end.problem.textRes())
            },
            illustration = MessageIllustration.Info,
            tone = MessageTone.Info,
            confirmText = stringResource(R.string.back),
            onConfirm = { onExit(end) },
            onDismissRequest = { onExit(end) },
        )
        if (driver == null) return
    }
    if (driver == null) {
        // §17.3/§18.3 este assento ainda não confirmou os boosters do jogo: o seletor aparece antes da mesa (nunca a mão
        // antes de confirmar, docs/telas.md 3.19 "Online"). Sem prompt pendente, só falta a conexão terminar (Loading).
        val prompt by holder.boosterPrompt.collectAsStateWithLifecycle()
        val source by holder.boosterSource.collectAsStateWithLifecycle()
        val activePrompt = prompt
        val activeSource = source
        if (activePrompt != null && activeSource != null) {
            val deadlineAt by holder.boosterDeadlineAt.collectAsStateWithLifecycle()
            val boosterViewModel = viewModel(key = "boosters-selector-online-$tableId") {
                BoosterSelectorViewModel(activeSource, activePrompt.skillAllowed, blocked = setOf(com.gtranca.engine.BoosterCatalog.REACTIONS))
            }
            BoosterSelectorScreen(boosterViewModel, deadlineAtMs = deadlineAt) { /* nada a fazer: as vistas seguintes chegam pelo holder.driver */ }
            return
        }
        return Loading()
    }
    val readyDriver = driver ?: return Loading()
    val viewModel = viewModel(key = "online-game-vm-$tableId") {
        GameViewModel(
            readyDriver.config,
            initialHand = HandPrefs.parse(loaded.handSortId),
            playerProfile = profile,
            playerPhoto = photoPath,
            slowAnimations = loaded.slowAnimations,
            externalDriver = readyDriver,
            externalPersonas = holder.personas,
            multiplayer = true,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(com.gtranca.ui.game.LocalTurnClock provides readyDriver.turnClock) {
        GameScreen(viewModel, onExit = { onExit(null) })
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GColors.Gold) }
}
