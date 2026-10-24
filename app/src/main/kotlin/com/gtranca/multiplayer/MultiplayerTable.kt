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
import kotlinx.coroutines.cancelAndJoin
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
    /** Recusa do anfitrião ou da sessão, com o texto pt-BR que veio dela e, no convite/pedido de entrada, o motivo (§16.2). */
    data class Refused(val message: String, val reason: com.gtranca.net.RefusalReason? = null) : MpEnd

    /** §16.2, §16.7 o anfitrião encerrou a mesa: desfez antes do início ou desligou o Wi-Fi do jogo. */
    data class Ended(val reason: com.gtranca.net.CloseReason) : MpEnd

    /** A mesa sumiu da busca ou fechou. */
    data object Closed : MpEnd

    /** A mesa procurada n�o apareceu na busca ao entrar. */
    data object NotFound : MpEnd
}

/** Aviso a mostrar na mesa (§16.8); [name] e [persona] já resolvidos a partir da sala. */
data class MpNotice(
    val kind: NoticeKind,
    val name: String?,
    val persona: String? = null,
    /** §16.2 na escolha de posições, por que o robô assumiu (recusa, saldo, fora do alcance); `null` = saiu. */
    val refusal: com.gtranca.net.RefusalReason? = null,
    /** Aviso da escolha de posições (antes do início do jogo). */
    val lobby: Boolean = false,
    val id: Long = nextNoticeId(),
)

private var noticeCounter = 0L

private fun nextNoticeId(): Long = synchronized(MpNotice::class) { ++noticeCounter }

/** Moedas e estatísticas de cada aparelho (§16.13, §16.14): o saldo é local. */
interface MpCoins {
    /** Debita a aposta (no início do jogo ou ao entrar no lugar de um robô); `false` se não havia saldo. */
    suspend fun debit(stake: Int): Boolean

    /** §14.6, §16.2 o saldo deste aparelho cobre a aposta (senão o convite é recusado sozinho). */
    suspend fun canAfford(stake: Int): Boolean = true

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

    /**
     * §16.7, §16.8, §16.10 as ações de anfitrião deste aparelho (passar o cargo, liberar assento de robô, decisões da
     * pausa): o jogo do anfitrião quando este aparelho é o anfitrião com o jogo em andamento e não desistiu; `null` caso
     * contrário. Segue o papel atual: quem recebe a mesa (passagem do cargo ou sucessão) passa a tê-las e quem a deixa as
     * perde.
     */
    val hostControls: StateFlow<MultiplayerHost?> by lazy {
        combine(_role, _resigned) { role, resigned -> (role as? MpRole.Hosting)?.host?.takeIf { !resigned } }
            .stateIn(scope, SharingStarted.Eagerly, (_role.value as? MpRole.Hosting)?.host?.takeIf { !_resigned.value })
    }

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

    /** §16.2 foto do perfil deste jogador (Base64, JPEG pequeno) a enviar à mesa; `null` = sem foto. */
    @Volatile
    var myPhoto: String? = null

    private val photoCache = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())

    private val _feed = kotlinx.coroutines.channels.Channel<com.gtranca.game.GameSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /**
     * §16.6 todas as vistas do assento deste aparelho, em ordem e **sem juntar**, desde o início do jogo (o anfitrião
     * assina o controlador antes de ele rodar; o convidado, as vistas recebidas desde a entrada). A tela do jogo consome
     * daqui (um só consumidor): mesmo aberta depois de os robôs jogarem, ela começa na distribuição e anima cada jogada.
     * No reingresso, a primeira vista é a atual da mesa.
     */
    val feed: kotlinx.coroutines.channels.ReceiveChannel<com.gtranca.game.GameSnapshot> get() = _feed

    /**
     * §16.2 fotos dos outros jogadores (id → bytes do JPEG), recebidas uma vez cada e guardadas **só em memória** enquanto
     * esta mesa existe (nunca em disco); sobrevivem à troca de papel (§16.10).
     */
    val photos: StateFlow<Map<Int, ByteArray>> = photoCache.asStateFlow()

    private fun mergePhotos(received: Map<Int, String>) {
        val fresh = received.filterKeys { it !in photoCache.value }.mapNotNull { (id, data) ->
            runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()?.let { id to it }
        }
        if (fresh.isNotEmpty()) photoCache.update { it + fresh }
    }

    private var watchers: List<Job> = emptyList()

    /** Sala atual, do papel atual. */
    val roster: StateFlow<List<PlayerInfo>> = _role.flatMapLatest { role ->
        when (role) {
            is MpRole.Hosting -> role.session.roster
            is MpRole.Guesting -> role.session.roster
            null -> flowOf(emptyList())
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * §16.15 qualidade da conexão deste convidado com o anfitrião (1 a 3 barras); `null` quando este aparelho é o
     * anfitrião ou está fora da mesa.
     */
    val hostLinkQuality: StateFlow<Int?> = _role.flatMapLatest { role ->
        when (role) {
            is MpRole.Guesting -> role.session.linkQuality
            else -> flowOf(null)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

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

    /**
     * §14.4, §16.2 abre a mesa montada: os jogadores próximos de [lobby] recebem o convite e todos passam à escolha de
     * posições; o jogo começa sozinho 2 s depois que todos confirmam ([HostSession.gameStart]).
     */
    suspend fun host(rules: TableRules, playerName: String, tableId: String, lobby: com.gtranca.net.LobbySetup) {
        name = playerName
        val session = HostSession(transport, tableId, rules, playerName, hostPhoto = myPhoto, lobby = lobby)
        session.open(scope)
        _rules.value = rules
        setRole(MpRole.Hosting(session))
    }

    /**
     * Entra na mesa [tableId] (§16.2): pelo convite (com a chave de presença [inviteKey]) ou por pedido de entrada; falha
     * (exceção) se ela não está na busca.
     */
    suspend fun join(tableId: String, playerName: String, inviteKey: String? = null) {
        name = playerName
        val session = GuestSession(transport, playerName, photo = myPhoto, canAfford = coins::canAfford)
        setRole(MpRole.Guesting(session, tableId))
        session.join(scope, tableId, inviteKey = inviteKey)
    }

    /** [join] sem esperar: a mesa não achada vira [MpEnd.NotFound]. */
    fun joinAsync(tableId: String, playerName: String, inviteKey: String? = null) {
        scope.launch {
            after?.join()
            try {
                join(tableId, playerName, inviteKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.NotFound
            }
        }
    }

    /**
     * §16.7 "Wi-Fi ligado no meio de um jogo só com robôs": o jogo local continua hospedado neste aparelho
     * ([HostSession.forRobotGame]) a partir do estado exato ([snapshot]): mesma mão, vez e placar. A aposta já foi
     * debitada no início (nada é debitado de novo). O jogador vira anfitrião e pode liberar assentos de robô.
     */
    suspend fun hostRobotGame(
        rules: TableRules,
        playerName: String,
        tableId: String,
        gameSeed: Long,
        snapshot: com.gtranca.game.SaveSnapshot,
        robots: Map<Int, String>,
        hostSeat: Seat = Seat(0),
        ledger: com.gtranca.engine.GroupBetLedger? = null,
    ) {
        name = playerName
        // §16.7 o jogo continua de onde estava: a tela não refaz nenhuma animação (como no reingresso).
        continuing = true
        val session = HostSession.forRobotGame(transport, tableId, rules, playerName, hostSeat, robots, hostPhoto = myPhoto)
        _rules.value = rules
        val config = configOf(rules)
        val state = com.gtranca.net.SuccessorState(
            stateSeq = 0,
            match = snapshot.match,
            events = snapshot.events,
            botRandomCalls = snapshot.botRandomCalls,
            members = session.membersForSuccessor(),
            nextPlayerId = session.nextPlayerId(),
            robotPersonas = robots,
            ledger = ledger,
        )
        val host = MultiplayerHost(
            session, config, gameSeed, emptySet(),
            botFactory = botFactory?.invoke(config) ?: { _, random -> com.gtranca.ai.createBot(config.difficulty, random) },
            computeDispatcher = computeDispatcher,
            restored = state,
        )
        session.open(scope)
        setRole(MpRole.Hosting(session, host))
        hostJob = scope.launch { runHost(host) }
    }

    /** [hostRobotGame] sem esperar. */
    fun hostRobotGameAsync(rules: TableRules, playerName: String, tableId: String, gameSeed: Long, snapshot: com.gtranca.game.SaveSnapshot, robots: Map<Int, String>) {
        scope.launch {
            after?.join()
            try {
                hostRobotGame(rules, playerName, tableId, gameSeed, snapshot, robots)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.Closed
            }
        }
    }

    /** [host] sem esperar. */
    fun hostAsync(rules: TableRules, playerName: String, tableId: String, lobby: com.gtranca.net.LobbySetup) {
        scope.launch {
            after?.join()
            try {
                host(rules, playerName, tableId, lobby)
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

    /** §16.2 escolha de posições (ocupantes, confirmações, trava), do papel atual; `null` antes de entrar. */
    val seating: StateFlow<com.gtranca.net.SeatingState?> = _role.flatMapLatest { role ->
        when (role) {
            is MpRole.Hosting -> role.session.seating
            is MpRole.Guesting -> role.session.seating
            null -> flowOf(null)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** §16.2 situação do convidado (`null` no anfitrião). */
    val guestStatus: StateFlow<GuestStatus?> = _role.flatMapLatest { role ->
        (role as? MpRole.Guesting)?.session?.status ?: flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** §16.2, §16.7 pedidos de entrada esperando este anfitrião (vazio no convidado). */
    val joinRequests: StateFlow<List<com.gtranca.net.JoinRequest>> = _role.flatMapLatest { role ->
        (role as? MpRole.Hosting)?.session?.joinRequests ?: flowOf(emptyList())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** §16.2 rotaciona a mesa (troca com o próximo ocupante não confirmado na [direction]). */
    suspend fun rotate(direction: com.gtranca.net.RotateDirection) {
        when (val role = _role.value) {
            is MpRole.Hosting -> role.session.rotate(direction)
            is MpRole.Guesting -> role.session.rotate(direction)
            null -> Unit
        }
    }

    /** §16.2 confirma a posição (trava o lugar). */
    suspend fun confirmSeat() {
        when (val role = _role.value) {
            is MpRole.Hosting -> role.session.confirmPosition()
            is MpRole.Guesting -> role.session.confirmSeat()
            null -> Unit
        }
    }

    /** §16.2, §16.7 o anfitrião aceita o pedido de entrada [requestId] (na escolha de posições ou no jogo). */
    suspend fun approveJoin(requestId: Int) {
        val hosting = _role.value as? MpRole.Hosting ?: return
        val host = hosting.host
        if (host != null) host.approveJoin(requestId) else hosting.session.approveJoin(requestId)
    }

    /** §16.2, §16.7 o anfitrião recusa o pedido de entrada [requestId] (o candidato vê o motivo). */
    suspend fun refuseJoin(requestId: Int) {
        (_role.value as? MpRole.Hosting)?.session?.refuseJoin(requestId)
    }

    /** Laço do jogo do anfitrião neste aparelho (parado para reabrir a mesa, §16.7). */
    @Volatile
    private var hostJob: Job? = null

    /**
     * §16.7 este aparelho continua um jogo que já estava na tela (o local só com robôs que virou online): a primeira vista
     * é a atual, sem distribuição nem replay.
     */
    @Volatile
    var continuing: Boolean = false
        private set

    /**
     * §16.7 Wi-Fi ligado de novo depois de desligado (jogo hospedado fora da rede): reabre a mesa com
     * [HostSession.forRobotGame] a partir do estado atual (mesma mão, vez, placar, personagens e apostas retidas; nada
     * é debitado nem gravado, §16.12). Pode repetir quantas vezes o jogador quiser.
     */
    suspend fun goOnline() {
        val hosting = _role.value as? MpRole.Hosting ?: return
        val old = hosting.host ?: return
        if (!_offline.value) return
        hostJob?.cancelAndJoin()
        val snapshot = old.controller.saveSnapshot()
        hostRobotGame(
            _rules.value ?: return, name, com.gtranca.net.nearby.newTableId(), old.gameSeed, snapshot,
            old.robotPersonas.value, old.hostSeat, old.ledger.value,
        )
        _offline.value = false
    }

    private val _offline = MutableStateFlow(false)

    /** §16.7 o anfitrião desligou o Wi-Fi do jogo: a mesa saiu da rede e o jogo segue só neste aparelho. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    /**
     * §16.7 o anfitrião desliga o Wi-Fi do jogo no meio do jogo: avisa os convidados, encerra a sessão e põe um robô no
     * lugar de cada humano remoto, com as regras de aposta da opção 2 de §16.8 ([MultiplayerHost.replaceWithRobot]).
     */
    suspend fun goOffline() {
        val hosting = _role.value as? MpRole.Hosting ?: return
        val host = hosting.host ?: return
        _offline.value = true
        val remote = hosting.session.goOffline()
        remote.keys.forEach { host.replaceWithRobot(it) }
    }

    /** §16.2 todos confirmaram: o jogo começa neste aparelho (anfitrião), com os robôs da escolha de posições. */
    private suspend fun startHosted(hosting: MpRole.Hosting, seats: Set<Seat>) {
        if (hosting.host != null || _role.value !== hosting) return
        val rules = _rules.value ?: return
        val config = configOf(rules)
        // §16.13 cada humano debita a aposta no próprio celular ao começar.
        if (rules.stake > 0) coins.debit(rules.stake)
        val host = MultiplayerHost(
            hosting.session, config, newSeed(), seats,
            botFactory = botFactory?.invoke(config) ?: { _, random -> com.gtranca.ai.createBot(config.difficulty, random) },
            computeDispatcher = computeDispatcher,
        )
        setRole(MpRole.Hosting(hosting.session, host))
        hostJob = scope.launch { runHost(host) }
    }

    /**
     * Sai da mesa e solta o transporte (um só leitor por transporte): na sala avisa a saída; depois do início só
     * desliga (nunca vira desistência). O anfitrião fecha a sessão: uma entrega de resultado ainda aberta (§16.11) acaba
     * aqui. Durante o jogo use [resign].
     */
    suspend fun leave() {
        when (val role = _role.value) {
            // §16.2 "Sair" do anfitrião na escolha de posições desfaz a mesa e avisa os convidados.
            is MpRole.Hosting -> if (role.host == null) role.session.disband() else role.session.close()
            is MpRole.Guesting -> {
                val status = role.session.status.value
                if (status == GuestStatus.Lobby || status == GuestStatus.Connecting || status == GuestStatus.Invited ||
                    status == GuestStatus.WaitingApproval
                ) {
                    role.session.leave()
                }
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
    fun personas(
        self: Persona,
        vacant: String,
        /** §16.2 "Ver fotos nas mesas compartilhadas" (Perfil): desligada, os outros humanos aparecem com a inicial. */
        showPhotos: kotlinx.coroutines.flow.Flow<Boolean> = flowOf(true),
        disconnected: (String) -> String,
    ): StateFlow<List<Persona>> =
        combine(roster, robots, _rules, photoCache, showPhotos) { roster, robots, rules, photos, show ->
            buildPersonas(roster, robots, rules, self, vacant, disconnected, if (show) photos else emptyMap())
        }.stateIn(scope, SharingStarted.Eagerly, buildPersonas(roster.value, robots.value, _rules.value, self, vacant, disconnected, emptyMap()))

    private fun buildPersonas(
        roster: List<PlayerInfo>,
        robots: Map<Int, String>,
        rules: TableRules?,
        self: Persona,
        vacant: String,
        disconnected: (String) -> String,
        photos: Map<Int, ByteArray>,
    ): List<Persona> {
        val mode = rules?.mode ?: return emptyList()
        val level = BotLevel.of(Difficulty.fromId(rules.difficulty))
        val me = myId
        // Antes de o anfitrião anunciar os robôs (não acontece com o jogo em andamento): a mesma conta que ele faz no início.
        val fallback = mode.seats.filter { seat -> roster.none { it.seat == seat } }.zip(rules.robots).associate { (seat, code) -> seat.index to code }
        return mode.seats.map { seat ->
            val player = roster.firstOrNull { it.seat == seat }
            when {
                player != null && player.id == me -> self.copy(firstName = player.name, lastName = "")
                player != null && player.resigned -> Persona(vacant)
                player != null -> Persona(
                    if (player.connected) player.name else disconnected(player.name),
                    color = player.id.mod(Persona.COLORS),
                    photoBytes = photos[player.id],
                )
                else -> {
                    val code = robots[seat.index] ?: fallback[seat.index]
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

    private fun nameOf(id: Int?): String? = id?.let { i -> roster.value.firstOrNull { it.id == i }?.name ?: knownNames[i] }

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
                            }
                            if (was == GuestStatus.Reconnecting || was == GuestStatus.Resigned) _resigned.value = false
                        }
                        GuestStatus.Resigned -> _resigned.value = true
                        is GuestStatus.Refused -> _end.value = MpEnd.Refused(status.message, status.reason)
                        is GuestStatus.TableEnded -> if (_received.value == null) _end.value = MpEnd.Ended(status.reason)
                        GuestStatus.Closed -> if (_received.value == null) _end.value = MpEnd.Closed
                        GuestStatus.TakingOver -> Unit
                        else -> Unit
                    }
                    was = status
                }
            },
            // §16.2 os robôs são sempre os escolhidos pelo anfitrião (nunca sorteados aqui), por assento.
            scope.launch { guest.robots.collect { if (it.isNotEmpty()) robots.value = it } },
            scope.launch { guest.photos.collect(::mergePhotos) },
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                guest.viewUpdates.collect { view -> guest.rules.value?.let { _feed.trySend(view.toSnapshot(it, 0)) } }
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

    private suspend fun onNotice(notice: TableNotice) {
        if (notice.kind == NoticeKind.REPLACED_BY_ROBOT && notice.seat != null && notice.persona != null) {
            robots.update { it + (notice.seat!!.index to notice.persona!!) }
        }
        if (notice.kind == NoticeKind.JOINED && notice.seat != null) robots.update { it - notice.seat!!.index }
        _notices.emit(MpNotice(notice.kind, nameOf(notice.playerId), notice.persona, notice.refusal, lobby = inLobby()))
    }

    /** Na escolha de posições (o jogo ainda não começou neste aparelho). */
    private fun inLobby(): Boolean = when (val role = _role.value) {
        is MpRole.Hosting -> role.host == null
        is MpRole.Guesting -> role.session.status.value.let { it == GuestStatus.Lobby || it == GuestStatus.Invited || it == GuestStatus.Connecting }
        null -> false
    }

    /** Nomes de todos que já passaram pela sala (quem sai some da sala antes do aviso chegar à tela). */
    private val knownNames = java.util.concurrent.ConcurrentHashMap<Int, String>()

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
            hostPhoto = myPhoto,
            botFactory = botFactory?.invoke(config),
            computeDispatcher = computeDispatcher,
            provisionalRank = rank,
        )
        _tookOver.value = true
        setRole(MpRole.Hosting(host.hostSession, host))
        hostJob = scope.launch { runHost(host) }
    }

    private fun watchHost(role: MpRole.Hosting): List<Job> {
        val host = role.host ?: return listOf(
            scope.launch { diffRoster(role.session) },
            scope.launch { role.session.photos.collect(::mergePhotos) },
            // §16.2 avisos da escolha de posições (quem saiu, o robô que assumiu e o motivo).
            scope.launch { role.session.lobbyNotices.collect { onNotice(it) } },
            // §16.2 todos confirmaram: 2 s depois o jogo começa sozinho.
            scope.launch {
                val seats = role.session.gameStart.filter { it != null }.first()!!
                scope.launch { startHosted(role, seats.keys) }
            },
        )
        return listOf(
            scope.launch { host.robotPersonas.collect { robots.value = it } },
            scope.launch { role.session.photos.collect(::mergePhotos) },
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                host.controller.updatesOf(host.hostSeat).collect { _feed.trySend(it) }
            },
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
        val guest = GuestSession(transport, name, photo = myPhoto)
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
            list.forEach { knownNames[it.id] = it.name }
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
