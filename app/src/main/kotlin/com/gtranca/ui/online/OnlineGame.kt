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
import com.gtranca.data.Settings
import com.gtranca.game.BotCharacter
import com.gtranca.game.HandPrefs
import com.gtranca.game.Persona
import com.gtranca.game.PlayerProfile
import com.gtranca.online.client.OnlineClient
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.SeatInfo
import com.gtranca.online.contracts.SeatKind
import com.gtranca.ui.game.GameScreen
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GMessage
import com.gtranca.ui.theme.MessageIllustration
import com.gtranca.ui.theme.MessageTone
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
class OnlineGameHolder(client: OnlineClient, account: OnlineAccount, tableId: String, private val self: Persona) : ViewModel() {
    private val _driver = MutableStateFlow<OnlineTableDriver?>(null)
    val driver: StateFlow<OnlineTableDriver?> = _driver.asStateFlow()

    private val _ended = MutableStateFlow<OnlineGameEnd?>(null)

    /** Fim sem o fim de jogo normal (mesa encerrada, outro aparelho, recusa): a tela avisa e volta. */
    val ended: StateFlow<OnlineGameEnd?> = _ended.asStateFlow()

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
            val token = runCatching { account.guest().token }.getOrNull()
            if (token == null) {
                _ended.value = OnlineGameEnd.Failed(OnlineProblem.UNAUTHENTICATED)
                return@launch
            }
            val d = OnlineTableDriver.connect(client.matchSession(token, tableId), viewModelScope, onFailure = { _ended.value = it })
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
    val holder = viewModel(key = "online-game-$tableId") { OnlineGameHolder(client, account, tableId, profile.persona(PlayerProfile.DEFAULT_NAME, photoPath)) }
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
    GameScreen(viewModel, onExit = { onExit(null) })
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GColors.Gold) }
}
