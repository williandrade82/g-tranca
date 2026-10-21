package com.gtranca.multiplayer

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.BotCharacter
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import com.gtranca.game.GameConfig
import com.gtranca.game.Persona
import com.gtranca.net.GuestSession
import com.gtranca.net.GuestStatus
import com.gtranca.net.HostSession
import com.gtranca.net.LocalTransport
import com.gtranca.net.NoticeKind
import com.gtranca.net.PlayerInfo
import com.gtranca.net.TableNotice
import com.gtranca.net.TableRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Papel deste aparelho na mesa multijogador (§16.1); muda na sucessão (§16.10). */
sealed interface MpRole {
    val tableId: String

    /** Anfitrião: [host] existe depois do início do jogo (na sala de espera, só a sessão). */
    data class Hosting(val session: HostSession, val host: MultiplayerHost? = null) : MpRole {
        override val tableId: String get() = session.tableId
    }

    data class Guesting(val session: GuestSession, override val tableId: String) : MpRole
}

/** Fim da mesa para este aparelho (modais "Mesa encerrada", recusas, sessão substituída, §16.5, §16.11). */
sealed interface MpEnd {
    /** Recusa do anfitrião ou da sessão, com o texto pt-BR que veio dela. */
    data class Refused(val message: String) : MpEnd

    /** A mesa sumiu da busca ou fechou. */
    data object Closed : MpEnd

    /** A mesa procurada n�o apareceu na busca ao entrar. */
    data object NotFound : MpEnd
}

/** Aviso a mostrar na mesa (§16.8); [name] e [persona] já resolvidos a partir da sala. */
data class MpNotice(val kind: NoticeKind, val name: String?, val persona: String? = null, val id: Long = nextNoticeId())

private var noticeCounter = 0L

private fun nextNoticeId(): Long = synchronized(MpNotice::class) { ++noticeCounter }

/** Moedas e estatísticas de cada aparelho (§16.13, §16.14): o saldo é local. */
interface MpCoins {
    /** Debita a aposta (no início do jogo ou ao entrar no lugar de um robô); `false` se não havia saldo. */
    suspend fun debit(stake: Int): Boolean

    /** Fim do jogo deste jogador: conta nas estatísticas e credita o que recebeu (uma vez por [gameId]). */
    suspend fun settle(gameId: String, rules: TableRules, result: GameResult, stake: Int, received: Int)

    companion object {
        val None: MpCoins = object : MpCoins {
            override suspend fun debit(stake: Int) = true
            override suspend fun settle(gameId: String, rules: TableRules, result: GameResult, stake: Int, received: Int) = Unit
        }
    }
}

/**
 * A mesa multijogador deste aparelho (§16), viva enquanto o jogador estiver nela (sala de espera, jogo e telas de
 * resultado). Guarda o papel atual ([role]) e o troca na sucessão: o convidado que assume vira anfitrião
 * ([MultiplayerHost.takeOver]) e o anfitrião superado volta como convidado pelo token (§16.10). Nunca valida regra e
 * nunca grava o jogo (§16.12): só sessão em memória.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerTable(
    val transport: LocalTransport,
    private val scope: CoroutineScope,
    private val coins: MpCoins = MpCoins.None,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Robôs do anfitrião; `null` = os do nível da mesa. */
    private val botFactory: ((GameConfig) -> ((Seat, Random) -> BotPlayer))? = null,
    private val newSeed: () -> Long = { Random.nextLong() },
    /** Saída da mesa anterior no mesmo transporte: entrar ou publicar só depois dela (um leitor por transporte). */
    private val after: Job? = null,
) {
    private val _role = MutableStateFlow<MpRole?>(null)
    val role: StateFlow<MpRole?> = _role.asStateFlow()

    private val _rules = MutableStateFlow<TableRules?>(null)

    /** Regras da mesa (§16.2), conhecidas ao publicar (anfitrião) ou ao entrar (convidado). */
    val rules: StateFlow<TableRules?> = _rules.asStateFlow()

    /** Nome deste jogador na mesa. */
    @Volatile
    var name: String = ""
        private set

    private val _end = MutableStateFlow<MpEnd?>(null)
    val end: StateFlow<MpEnd?> = _end.asStateFlow()

    private val _resigned = MutableStateFlow(false)

    /** §16.9 este jogador desistiu: o jogo acabou para ele (derrota por desistência). */
    val resigned: StateFlow<Boolean> = _resigned.asStateFlow()

    private val _received = MutableStateFlow<Int?>(null)

    /** §16.13 moedas recebidas no fim do jogo (já sem a aposta); `null` antes do fim. */
    val received: StateFlow<Int?> = _received.asStateFlow()

    private val _tookOver = MutableStateFlow(false)

    /** §16.10 este aparelho acabou de virar anfitrião (modal "Você agora é o anfitrião"). */
    val tookOver: StateFlow<Boolean> = _tookOver.asStateFlow()

    private val _notices = MutableSharedFlow<MpNotice>(extraBufferCapacity = 64)

    /** Avisos da mesa (§16.8), já com os nomes. */
    val notices: SharedFlow<MpNotice> = _notices.asSharedFlow()

    /** Personagem de cada assento de robô, por índice (§16.8: o substituto é o anunciado). */
    private val robots = MutableStateFlow<Map<Int, String>>(emptyMap())

    private var watchers: List<Job> = emptyList()

    /** Sala atual, do papel atual. */
    val roster: StateFlow<List<PlayerInfo>> = _role.flatMapLatest { role ->
        when (role) {
            is MpRole.Hosting -> role.session.roster
            is MpRole.Guesting -> role.session.roster
            null -> flowOf(emptyList())
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Id público deste jogador (0 enquanto não entrou; o anfitrião original é 0). */
    val myId: Int
        get() = when (val role = _role.value) {
            is MpRole.Hosting -> role.session.hostId
            is MpRole.Guesting -> role.session.playerId
            null -> 0
        }

    /** Assento deste jogador. */
    val mySeat: Seat?
        get() = roster.value.firstOrNull { it.id == myId }?.seat

    val isHost: Boolean get() = _role.value is MpRole.Hosting

    // ---- sala de espera (§16.2) ------------------------------------------------------------------------------------

    /** §16.2 publica a mesa com as [rules] escolhidas; o anfitrião ocupa a posição 1. */
    suspend fun host(rules: TableRules, playerName: String, tableId: String) {
        name = playerName
        val session = HostSession(transport, tableId, rules, playerName)
        session.open(scope)
        _rules.value = rules
        setRole(MpRole.Hosting(session))
    }

    /** Entra na mesa [tableId] (§16.2); falha (exceção) se ela não está na busca. */
    suspend fun join(tableId: String, playerName: String) {
        name = playerName
        val session = GuestSession(transport, playerName)
        setRole(MpRole.Guesting(session, tableId))
        session.join(scope, tableId)
    }

    /** [join] sem esperar: a mesa não achada vira [MpEnd.NotFound]. */
    fun joinAsync(tableId: String, playerName: String) {
        scope.launch {
            after?.join()
            try {
                join(tableId, playerName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.NotFound
            }
        }
    }

    /** [host] sem esperar. */
    fun hostAsync(rules: TableRules, playerName: String, tableId: String) {
        scope.launch {
            after?.join()
            try {
                host(rules, playerName, tableId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.Closed
            }
        }
    }

    suspend fun rename(newName: String) {
        name = newName
        when (val role = _role.value) {
            is MpRole.Hosting -> role.session.renameHost(newName)
            is MpRole.Guesting -> role.session.rename(newName)
            null -> Unit
        }
    }

    suspend fun chooseSeat(seat: Seat) = (_role.value as? MpRole.Guesting)?.session?.chooseSeat(seat)

    suspend fun confirmSeat() = (_role.value as? MpRole.Guesting)?.session?.confirmSeat()

    /** §16.2 o anfitrião inicia: assentos vagos com robôs, quem não confirmou sai; o jogo roda neste aparelho. */
    suspend fun startGame() {
        val hosting = _role.value as? MpRole.Hosting ?: return
        if (hosting.host != null) return
        val rules = _rules.value ?: return
        val seats = hosting.session.startGame().keys
        val config = configOf(rules)
        // §16.13 cada humano debita a aposta no próprio celular ao começar.
        if (rules.stake > 0) coins.debit(rules.stake)
        val host = MultiplayerHost(
            hosting.session, config, newSeed(), seats,
            botFactory = botFactory?.invoke(config) ?: { _, random -> com.gtranca.ai.createBot(config.difficulty, random) },
            computeDispatcher = computeDispatcher,
        )
        setRole(MpRole.Hosting(hosting.session, host))
        scope.launch { runHost(host) }
    }

    /**
     * Sai da mesa e solta o transporte (um só leitor por transporte): na sala avisa a saída; depois do início só
     * desliga (nunca vira desistência). O anfitrião fecha a sessão: uma entrega de resultado ainda aberta (§16.11) acaba
     * aqui. Durante o jogo use [resign].
     */
    suspend fun leave() {
        when (val role = _role.value) {
            is MpRole.Hosting -> role.session.close()
            is MpRole.Guesting -> {
                val status = role.session.status.value
                if (status == GuestStatus.Lobby || status == GuestStatus.Connecting) role.session.leave()
                role.session.release()
            }
            null -> Unit
        }
        watchers.forEach { it.cancel() }
        _role.value = null
    }

    // ---- jogo ------------------------------------------------------------------------------------------------------

    /** §16.9 desistência deste jogador (bandeira, Voltar, Sair). O anfitrião passa o cargo (§16.10). */
    suspend fun resign() {
        when (val role = _role.value) {
            is MpRole.Guesting -> role.session.resign()
            is MpRole.Hosting -> role.host?.handOver(resign = true)
            null -> return
        }
        _resigned.value = true
    }

    /** §16.9 "Voltar à mesa": quem desistiu reingressa pelo token. Devolve se achou a mesa. */
    suspend fun returnToTable(): Boolean {
        val guest = (_role.value as? MpRole.Guesting)?.session ?: return false
        val ok = guest.returnToTable()
        return ok
    }

    /** O jogador viu o modal "Você agora é o anfitrião". */
    fun takeOverSeen() {
        _tookOver.value = false
    }

    /**
     * O jogador saiu das telas da mesa (voltar ao início). Fim do jogo: conta nas estatísticas quem desistiu sem receber
     * nada. O anfitrião mantém a mesa aberta para a entrega do resultado (§16.11), que fecha sozinha.
     */
    suspend fun exitScreens() {
        val rules = _rules.value
        if (_resigned.value && _received.value == null && rules != null) {
            coins.settle(gameId(), rules, GameResult.RESIGNATION, rules.stake, 0)
        }
        when (val role = _role.value) {
            is MpRole.Guesting -> {
                // §16.13 quem sai das telas antes de o prêmio chegar continua esperando por ele (em segundo plano).
                if (!_resigned.value && _received.value == null && _end.value == null) {
                    withTimeoutOrNull(PAYOUT_WAIT_MILLIS) { combine(_received, _end) { r, e -> r != null || e != null }.first { it } }
                }
                role.session.release()
                watchers.forEach { it.cancel() }
                _role.value = null
            }
            is MpRole.Hosting -> if (role.host == null || _resigned.value) {
                role.session.close()
                watchers.forEach { it.cancel() }
                _role.value = null
            }
            null -> Unit
        }
    }

    // ---- nomes e avatares ------------------------------------------------------------------------------------------

    /**
     * §16.6 personas de cada assento para a mesa de jogo: o jogador deste aparelho com o seu perfil ([self], nome da
     * sala), humanos remotos com a inicial (sem foto), "VAGO" para quem desistiu ([vacant]), [disconnected] no nome de
     * quem caiu, e o personagem de cada robô.
     */
    fun personas(self: Persona, vacant: String, disconnected: (String) -> String): StateFlow<List<Persona>> =
        combine(roster, robots, _rules) { roster, robots, rules -> buildPersonas(roster, robots, rules, self, vacant, disconnected) }
            .stateIn(scope, SharingStarted.Eagerly, buildPersonas(roster.value, robots.value, _rules.value, self, vacant, disconnected))

    private fun buildPersonas(
        roster: List<PlayerInfo>,
        robots: Map<Int, String>,
        rules: TableRules?,
        self: Persona,
        vacant: String,
        disconnected: (String) -> String,
    ): List<Persona> {
        val mode = rules?.mode ?: return emptyList()
        val level = BotLevel.of(Difficulty.fromId(rules.difficulty))
        val me = myId
        val used = mutableSetOf<String>()
        robots.values.forEach { used += it }
        return mode.seats.map { seat ->
            val player = roster.firstOrNull { it.seat == seat }
            when {
                player != null && player.id == me -> self.copy(firstName = player.name, lastName = "")
                player != null && player.resigned -> Persona(vacant)
                player != null -> Persona(
                    if (player.connected) player.name else disconnected(player.name),
                    color = player.id.mod(Persona.COLORS),
                )
                else -> {
                    val code = robots[seat.index] ?: BotProfile.entries.map { it.code }.firstOrNull { it !in used }?.also { used += it }
                    val profile = BotProfile.entries.firstOrNull { it.code == code } ?: BotProfile.entries.first()
                    Persona(profile.botName, "", BotCharacter(profile, level))
                }
            }
        }
    }

    // ---- papéis e sucessão (§16.10) --------------------------------------------------------------------------------

    private fun setRole(role: MpRole) {
        watchers.forEach { it.cancel() }
        _role.value = role
        watchers = when (role) {
            is MpRole.Guesting -> watchGuest(role)
            is MpRole.Hosting -> watchHost(role)
        }
    }

    private fun nameOf(id: Int?): String? = id?.let { i -> roster.value.firstOrNull { it.id == i }?.name }

    private fun watchGuest(role: MpRole.Guesting): List<Job> {
        val guest = role.session
        return listOf(
            scope.launch { guest.rules.collect { r -> if (r != null) _rules.value = r } },
            scope.launch {
                var was: GuestStatus? = null
                guest.status.collect { status ->
                    val rules = guest.rules.value
                    when (status) {
                        GuestStatus.Playing -> {
                            // §16.13 quem estava na sala debita a aposta ao começar (quem entra no meio: StakeDebit).
                            if (was == GuestStatus.Lobby && rules != null) {
                                if (rules.stake > 0) coins.debit(rules.stake)
                                seedRobots(rules, guest.roster.value)
                            }
                            if (was == GuestStatus.WaitingApproval && rules != null) seedRobots(rules, guest.roster.value)
                            if (was == GuestStatus.Reconnecting || was == GuestStatus.Resigned) _resigned.value = false
                        }
                        GuestStatus.Resigned -> _resigned.value = true
                        is GuestStatus.Refused -> _end.value = MpEnd.Refused(status.message)
                        GuestStatus.Closed -> if (_received.value == null) _end.value = MpEnd.Closed
                        GuestStatus.TakingOver -> Unit
                        else -> Unit
                    }
                    was = status
                }
            },
            scope.launch { guest.notices.collect { onNotice(it) } },
            scope.launch {
                guest.stakeDebit.collect { amount -> if (amount != null && amount > 0) coins.debit(amount) }
            },
            scope.launch {
                guest.payout.collect { payout ->
                    if (payout != null) {
                        _received.value = payout.received
                        settle(payout.stake, payout.received, guest.view.value?.winner, guest.view.value?.side)
                    }
                }
            },
            scope.launch {
                guest.takeOver.filter { it != null }.first()
                takeOver(role)
            },
        )
    }

    private fun seedRobots(rules: TableRules, roster: List<PlayerInfo>) {
        if (robots.value.isNotEmpty()) return
        val humans = roster.mapNotNull { it.seat }.toSet()
        robots.value = rules.mode.seats.filter { it !in humans }.zip(rules.robots).associate { (seat, code) -> seat.index to code }
    }

    private suspend fun onNotice(notice: TableNotice) {
        if (notice.kind == NoticeKind.REPLACED_BY_ROBOT && notice.seat != null && notice.persona != null) {
            robots.update { it + (notice.seat!!.index to notice.persona!!) }
        }
        if (notice.kind == NoticeKind.JOINED && notice.seat != null) robots.update { it - notice.seat!!.index }
        _notices.emit(MpNotice(notice.kind, nameOf(notice.playerId), notice.persona))
    }

    /** §16.10 este convidado assume: solta a sessão de convidado e abre a mesa como anfitrião no mesmo transporte. */
    private suspend fun takeOver(role: MpRole.Guesting) {
        val guest = role.session
        val state = guest.takeOver.value ?: return
        val rules = guest.rules.value ?: return
        val id = guest.playerId
        val rank = guest.takeOverRank
        guest.release()
        val config = configOf(rules)
        val host = MultiplayerHost.takeOver(
            transport, role.tableId, rules, id, state, scope,
            botFactory = botFactory?.invoke(config),
            computeDispatcher = computeDispatcher,
            provisionalRank = rank,
        )
        _tookOver.value = true
        setRole(MpRole.Hosting(host.hostSession, host))
        scope.launch { runHost(host) }
    }

    private fun watchHost(role: MpRole.Hosting): List<Job> {
        val host = role.host ?: return listOf(scope.launch { diffRoster(role.session) })
        return listOf(
            scope.launch { host.robotPersonas.collect { robots.value = it } },
            scope.launch { diffRoster(role.session) },
            scope.launch {
                host.payouts.collect { payouts ->
                    if (payouts != null && !_resigned.value) {
                        val received = payouts[role.session.hostId] ?: 0
                        _received.value = received
                        val view = host.controller.state.value
                        settle(host.config.stake, received, view.winner, view.viewerSide)
                    }
                }
            },
            // §16.10 outro anfitrião prevaleceu: este aparelho volta como convidado pelo token.
            scope.launch {
                host.superseded.first { it }
                stepDown(role.session, host)
            },
            // §16.11 entrega do resultado encerrada: a mesa fecha (quem está conectado segue nas suas telas).
            scope.launch {
                host.deliveryEnded.first { it }
                role.session.close()
            },
        )
    }

    /** Deixa de ser anfitri�o e volta como jogador comum pelo token (�16.10). Pode ser chamado de um vigia do papel. */
    private fun stepDown(session: HostSession, host: MultiplayerHost) {
        val guest = GuestSession(transport, name)
        scope.launch {
            runCatching { guest.join(scope, session.tableId, token = session.hostToken, seat = host.hostSeat) }
                .onFailure { _end.value = MpEnd.Closed }
        }
        setRole(MpRole.Guesting(guest, session.tableId))
    }

    /** �16.10 o anfitri�o passa o cargo a [playerId] e segue jogando como jogador comum. Devolve se passou. */
    suspend fun passRole(playerId: Int): Boolean {
        val hosting = _role.value as? MpRole.Hosting ?: return false
        val host = hosting.host ?: return false
        host.handOver(playerId, resign = false) ?: return false
        stepDown(hosting.session, host)
        return true
    }

    /** �16.7 libera o assento de rob� [seat] para algu�m entrar (com aprova��o). */
    suspend fun openRobotSeat(seat: Seat) {
        ((_role.value as? MpRole.Hosting)?.host ?: return).openRobotSeat(seat)
    }

    /** O anfitrião não recebe avisos (é ele quem os envia): derivados da sala (§16.8). */
    private suspend fun diffRoster(session: HostSession) {
        var before: Map<Int, PlayerInfo> = session.roster.value.associateBy { it.id }
        session.roster.collect { list ->
            val now = list.associateBy { it.id }
            now.values.forEach { p ->
                val old = before[p.id]
                val kind = when {
                    old == null -> if (p.id != session.hostId && list.any { it.isHost } && rosterStarted(session)) NoticeKind.JOINED else null
                    old.resigned != p.resigned && p.resigned -> NoticeKind.RESIGNED
                    old.connected && !p.connected && !p.resigned -> NoticeKind.DISCONNECTED
                    !old.connected && p.connected -> NoticeKind.RECONNECTED
                    else -> null
                }
                if (kind != null && p.id != session.hostId) _notices.emit(MpNotice(kind, p.name))
            }
            before.values.filter { it.id !in now && it.id != session.hostId }.forEach { gone ->
                val persona = gone.seat?.let { robots.value[it.index] }
                if (rosterStarted(session)) _notices.emit(MpNotice(NoticeKind.REPLACED_BY_ROBOT, gone.name, persona))
            }
            before = now
        }
    }

    private fun rosterStarted(session: HostSession): Boolean = (_role.value as? MpRole.Hosting)?.host != null && session.roster.value.isNotEmpty()

    private suspend fun settle(stake: Int, received: Int, winner: Side?, mySide: Side?) {
        val rules = _rules.value ?: return
        val result = when {
            _resigned.value -> GameResult.RESIGNATION
            winner != null && winner == mySide -> GameResult.WIN
            else -> GameResult.LOSS
        }
        coins.settle(gameId(), rules, result, stake, received)
    }

    /** Roda o jogo do anfitri�o; a passagem do cargo cancela o la�o (normal). Outra falha fecha a mesa para este aparelho. */
    private suspend fun runHost(host: MultiplayerHost) {
        try {
            host.run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _end.value = MpEnd.Closed
        }
    }

    private fun gameId(): String = "mp-${_role.value?.tableId}-$myId"

    companion object {
        /** Espera técnica pelo prêmio de quem sai das telas antes de ele chegar (não é regra de §16.15). */
        const val PAYOUT_WAIT_MILLIS: Long = 30_000

        fun configOf(rules: TableRules): GameConfig =
            GameConfig(rules.mode, Difficulty.fromId(rules.difficulty), rules.targetScore, rules.rules, rules.stake)
    }
}
