package com.gtranca.game

import com.gtranca.ai.BotPlayer
import com.gtranca.engine.PublicEvent
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PlayerView
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundRecord
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** Etapa do jogo, do ponto de vista de quem acompanha. */
enum class Stage {
    /** Partida em andamento. */
    PLAYING,

    /** Partida encerrada e pontuada (§12); aguarda a confirmação para a próxima (§13: inclui o empate). */
    ROUND_OVER,

    /** §13 jogo terminado com vencedor (inclusive por desistência, §13.1). */
    GAME_OVER,
}


/**
 * Estado publicado pelo [GameController]. Tudo o que a interface precisa, **sem** o estado completo da
 * partida: a mesa vem de `viewFor(viewerSeat)` (mãos dos outros, monte e mortos só como tamanhos).
 *
 * @property view vista do assento [viewerSeat] (o humano).
 * @property history partidas encerradas, com o detalhamento §12 ([RoundRecord.scores]).
 * @property humanLegal ações legais do humano quando o controlador espera a jogada dele; vazia caso contrário.
 * @property humanRequestId identificador do pedido ao humano a que [humanLegal] se refere (0 sem pedido); a
 *   interface o devolve em [GameController.submit] e [GameController.explain].
 * @property thinkingSeat bot que está decidindo (inclui a pausa entre jogadas), ou `null`.
 * @property turnEvents por assento ([Seat.index]), as ações públicas da vez mais recente (ou atual) dele
 *   nesta partida.
 * @property turnRedThrees por assento ([Seat.index]), os 3 vermelhos baixados na vez mais recente (ou atual) dele.
 * @property resigned §13.1 o humano desistiu: [winner] é o outro lado e a partida em andamento não foi pontuada.
 */
data class GameSnapshot(
    val config: GameConfig,
    val viewerSeat: Seat,
    val view: PlayerView,
    val roundNumber: Int,
    val totals: List<Int>,
    val history: List<RoundRecord>,
    val stage: Stage,
    val winner: Side?,
    val humanLegal: List<Action>,
    val thinkingSeat: Seat?,
    val turnEvents: List<List<PublicEvent>>,
    val humanRequestId: Long = 0,
    val resigned: Boolean = false,
    /** §3.5, §6.5 por assento, os 3 vermelhos que ele baixou na vez mais recente (ou atual) dele. */
    val turnRedThrees: List<List<Card>> = emptyList(),
    /** Por assento, depois de quantas ações da vez cada 3 vermelho de [turnRedThrees] foi baixado (ordem real da vez). */
    val turnRedThreeAfter: List<List<Int>> = emptyList(),
) {
    val viewerSide: Side get() = view.side

    /** É a vez do humano e o controlador espera a escolha dele. */
    val isHumanTurn: Boolean get() = humanLegal.isNotEmpty()
}

/**
 * Laço do jogo (§15): pergunta a ação ao jogador da vez, confere que está em `legalActions`, monta o
 * [PublicEvent] **antes** de aplicar (depois o lixo já foi levado), aplica no motor, avisa todos os bots e
 * publica o novo [state]. A cada partida distribuída chama `onNewRound()` em todos os bots. Ao fim de cada
 * partida pontua (`finishRound`) e, se houver humano, espera [continueToNextRound] antes de distribuir a
 * próxima.
 *
 * Mesmo procedimento de `Simulator.runGame` (`:sim`): o jogo é `startMatch(mode, alvo, Random(gameSeed))` e
 * cada bot recebe `Random(botSeed(gameSeed, assento))`. Com os mesmos bots e semente, o resultado é idêntico,
 * desde que nenhuma decisão dependa do relógio: o Difícil do app tem teto de 800 ms (`HardBotConfig.DEFAULT`) e,
 * quando o atinge, a escolha passa a depender da máquina. Por isso a partida salva guarda o `Match` e os eventos,
 * e nunca é refeita a partir da semente.
 *
 * Os 3 vermelhos (§6.5) continuam automáticos no motor; quem baixou cada um está no registro público
 * `PlayerView.redThreeLog`, que a interface usa para encenar as trocas.
 *
 * Kotlin puro (sem Android): testável na JVM. Todo o processamento (motor e decisões dos bots, inclusive a
 * busca de CPU do Difícil) roda em [computeDispatcher], nunca na thread principal.
 *
 * **Vários humanos (§16, multijogador):** [players] pode ter um [HumanPlayer] por assento. Cada humano tem o seu pedido
 * (`humanRequestId` do snapshot dele), a sua vista ([stateOf]) e o seu [explain]/[submit]; [state] é a vista do
 * [viewerSeat]. Cada jogada aplicada sai uma vez em [events]. Um assento pode trocar de dono durante o jogo
 * ([replaceWithBot], [replacePlayer]) sem reiniciar nada. Com [PacingPolicy.FREE] o laço não espera nenhuma encenação
 * de um aparelho: só pára para a decisão de um humano.
 *
 * @param players quem ocupa cada assento, por [Seat.index].
 * @param viewerSeat assento cuja vista é publicada em [state] (o humano deste aparelho; num jogo só de bots, qualquer um).
 * @param botDelayMillis pausa antes de cada jogada de bot, para o humano acompanhar (0 nos testes; ignorada em [PacingPolicy.FREE]).
 * @param pacing ver [PacingPolicy]; o padrão é o jogo local de sempre.
 */
class GameController(
    val config: GameConfig,
    /**
     * Semente do jogo: determina **todas** as cartas (mãos, monte e mortos de todas as partidas). Só para
     * persistência e testes; nunca a exiba nem a coloque no estado da interface.
     */
    val gameSeed: Long,
    private val players: List<SeatPlayer>,
    private val viewerSeat: Seat = Seat(0),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val botDelayMillis: Long = DEFAULT_BOT_DELAY_MILLIS,
    /** Pausa depois da distribuição de cada partida (com humano), para a interface animar as cartas antes da 1ª jogada. */
    private val dealDelayMillis: Long = 0,
    /** Jogo salvo a retomar (mesmo `Match` e os eventos públicos da partida atual), ou `null` para um jogo novo. */
    private val restored: RestoredGame? = null,
    /**
     * Chamado sob a trava, sem suspender, a cada ponto de salvamento: depois de cada ação aplicada, ao pontuar a
     * partida e ao distribuir a próxima. Deve só repassar a foto (a gravação fica fora do laço).
     */
    private val onSave: ((SaveSnapshot) -> Unit)? = null,
    private val pacing: PacingPolicy = PacingPolicy.LOCAL_ANIMATED,
) {
    init {
        require(players.size == config.mode.seatCount) { "Um jogador por assento: ${config.mode.seatCount}" }
        require(players.filterIsInstance<HumanPlayer>().let { it.size == it.distinct().size }) { "Um HumanPlayer por assento" }
        config.mode.requireSeat(viewerSeat)
        require(botDelayMillis >= 0) { "Pausa negativa" }
    }

    /** Quem ocupa cada assento agora (muda com [replacePlayer]); só se altera sob a trava. */
    @Volatile
    private var current: List<SeatPlayer> = players.toList()

    private fun humanSeats(): List<Seat> = config.mode.seats.filter { current[it.index] is HumanPlayer }

    private fun bots(): List<BotPlayer> = current.filterIsInstance<BotSeatPlayer>().map { it.bot }

    /** Há humano e o ritmo é o do aparelho local: o laço espera as encenações da interface. */
    private fun animated(): Boolean = pacing == PacingPolicy.LOCAL_ANIMATED && humanSeats().isNotEmpty()

    /** Assento do humano a que [submit] e [explain] sem assento se referem: o único humano, ou o do [viewerSeat]. */
    private fun primarySeat(): Seat = humanSeats().singleOrNull() ?: viewerSeat
    private val started = AtomicBoolean(false)
    private val nextRoundSignal = Channel<Unit>(Channel.CONFLATED)
    private val awaitingNextRound = AtomicBoolean(false)

    /** Escala da pausa da distribuição (velocidade das animações escolhida pelo jogador; 1 = normal). */
    @Volatile
    var dealDelayScale: Double = 1.0
    private val requestCounter = AtomicLong(0)
    private val presentationSignal = Channel<Unit>(Channel.CONFLATED)
    private val dealAnimationSignal = Channel<Int>(Channel.CONFLATED)

    /**
     * Encenação de trocas de 3 vermelho que o controlador espera (§3.5, §6.5): a partida e o tamanho do registro
     * (`redThreeLog`) depois das trocas a encenar; `null` se nenhuma. Ver [presentationDone].
     */
    private data class PresentationKey(val roundNumber: Int, val logSize: Int)

    private val awaitingPresentation = AtomicReference<PresentationKey?>(null)

    /**
     * Trava única do estado do jogo: aplicar uma ação, encerrar/iniciar partida, publicar o snapshot e desistir
     * (§13.1) acontecem sob ela. Assim a desistência, vinda da thread principal, nunca intercala com o laço (que
     * roda em [computeDispatcher]): ou a ação/pontuação já aconteceu antes, ou não acontece mais. Nunca suspender
     * com a trava.
     */
    private val lock = Any()

    @Volatile
    private var match: Match = restored?.match ?: startMatch(config.mode, config.targetScore, Random(gameSeed), config.rules)

    @Volatile
    private var turnEvents: List<List<PublicEvent>> = List(config.mode.seatCount) { emptyList() }

    /** §3.5 os 3 vermelhos baixados na vez mais recente de cada assento (detalhe da última jogada). */
    private val turnReds = TurnRedThrees(config.mode.seatCount)

    /** Eventos públicos da partida atual, em ordem (vão para o jogo salvo). */
    @Volatile
    private var roundEvents: List<PublicEvent> = emptyList()

    init {
        require(restored == null || restored.match.mode == config.mode) { "Jogo salvo de outro modo" }
        if (restored != null) {
            // Retomada: a vista da última vez de cada assento vem do log; trocas já passadas não se reencenam.
            roundEvents = restored.events
            restored.events.forEach(::recordEvent)
        } else if (animated() && hasStartSwaps()) {
            // §3.5 com humano, a 1ª jogada espera a encenação das trocas do início da vez do 1º jogador (ver [dealPresentationDone]).
            awaitingPresentation.set(PresentationKey(1, match.currentRound.redThreeLog.size))
        }
    }

    /** Por assento ([Seat.index]), identificador e partida do pedido em aberto ao humano dele; ausente fora da espera. */
    private val humanRequests = java.util.concurrent.atomic.AtomicReference<Map<Int, Pair<Long, RoundState>>>(emptyMap())

    private fun setRequest(seat: Seat, request: Pair<Long, RoundState>?) {
        humanRequests.updateAndGet { if (request == null) it - seat.index else it + (seat.index to request) }
    }

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Cada jogada processada, em ordem (§16.6). Só informação pública ([GameEvent]). Quem retransmite deve consumir sem
     * demora: o buffer é de [EVENT_BUFFER] eventos e, se encher, o evento novo é descartado (nunca bloqueia o jogo).
     */
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /** §13.1 o humano desistiu: nada mais é aplicado nem publicado além do fim de jogo. */
    @Volatile
    private var resignedWinner: Side? = null

    @Volatile
    private var loopJob: Job? = null

    /** Uma vista por assento humano (mais a do [viewerSeat]), por [Seat.index]. */
    private val seatStates = ConcurrentHashMap<Int, MutableStateFlow<GameSnapshot>>().also { states ->
        (humanSeats() + viewerSeat).distinct().forEach { seat ->
            states[seat.index] = MutableStateFlow(snapshot(seat, initialStage(), humanLegal = emptyList(), thinkingSeat = null))
        }
    }

    /** Estado atual para a interface deste aparelho: a vista do [viewerSeat]. */
    val state: StateFlow<GameSnapshot> = seatStates.getValue(viewerSeat.index).asStateFlow()

    /**
     * Estado atual na vista do [seat] humano (§16.4): a mão é só a dele, e as ações legais e o pedido são os dele.
     * Só existe para os assentos humanos (e o [viewerSeat]).
     */
    fun stateOf(seat: Seat): StateFlow<GameSnapshot> =
        (seatStates[seat.index] ?: error("Sem vista para o assento ${seat.index}")).asStateFlow()

    /** Fluxos sem junção por assento ([updatesOf]), criados sob a trava quando alguém pede. */
    private val seatUpdates = ConcurrentHashMap<Int, MutableSharedFlow<GameSnapshot>>()

    /**
     * §16.6 cada vista publicada para o [seat], em ordem e **sem juntar** ([stateOf] guarda só a última): uma por jogada,
     * para outro aparelho animar uma jogada por vez. Começa pela vista atual. Só o multijogador usa; o jogo local segue
     * com [state].
     */
    fun updatesOf(seat: Seat): Flow<GameSnapshot> = synchronized(lock) {
        val current = stateOf(seat).value
        seatUpdates.getOrPut(seat.index) {
            MutableSharedFlow<GameSnapshot>(replay = 1, extraBufferCapacity = SEAT_UPDATES_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .also { it.tryEmit(current) }
        }.asSharedFlow()
    }

    /** Publica a vista do assento [index] no [StateFlow] dele e no fluxo sem junção; chamar só com a trava. */
    private fun setSeatLocked(index: Int, flow: MutableStateFlow<GameSnapshot>, snapshot: GameSnapshot) {
        flow.value = snapshot
        seatUpdates[index]?.tryEmit(snapshot)
    }

    /**
     * Jogo atual completo, para persistência e testes. **Não** use para desenhar a mesa (contém as mãos de
     * todos, o monte e os mortos): a interface usa [state].
     */
    val currentMatch: Match get() = match

    private val resigned: Boolean get() = resignedWinner != null

    /**
     * Roda o jogo até o fim (§13) ou até a desistência (§13.1). Só pode ser chamado uma vez. Cancelável: ao
     * cancelar, a espera pelo humano ou pela confirmação de nova partida é interrompida.
     */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "O jogo já foi iniciado" }
        coroutineScope {
            val job = launch(computeDispatcher) { loop() }
            loopJob = job
            if (resigned) job.cancel()
        }
    }

    private suspend fun loop() {
        var resuming = restored != null
        while (true) {
            // §13 partida já pontuada sem a próxima distribuída (fim de partida, ou jogo retomado nesse ponto): espera a
            // confirmação do humano e distribui a próxima (§4.1).
            val awaitNext = synchronized(lock) {
                if (match.isOver || resigned) return
                if (!match.isAwaitingNextRound) return@synchronized false
                if (humanSeats().isNotEmpty()) awaitingNextRound.set(true)
                publishLocked(Stage.ROUND_OVER)
                true
            }
            if (awaitNext) {
                if (humanSeats().isNotEmpty()) nextRoundSignal.receive()
                synchronized(lock) {
                    if (resigned) return
                    match = match.startNextRound()
                    roundEvents = emptyList()
                    save()
                }
                resuming = false
            }
            val freshDeal = !resuming
            val waitDeal = synchronized(lock) {
                if (match.isOver || resigned) return
                bots().forEach { it.onNewRound() }
                if (resuming) {
                    // Retomada no meio da partida: os bots reconstroem a memória com os eventos públicos já aplicados.
                    roundEvents.forEach { event -> bots().forEach { it.observe(event) } }
                    publishLocked(Stage.PLAYING)
                    return@synchronized false
                }
                turnEvents = List(config.mode.seatCount) { emptyList() }
                turnReds.reset(match.currentRound.redThreeLog)
                turnReds.beginTurn(match.currentRound.currentSeat)
                roundEvents = emptyList()
                // Com humano, a 1ª jogada só acontece depois de a interface encenar as trocas de 3 vermelho do início da
                // vez do 1º jogador (§3.5). Sem humano (bots, :sim), não há espera.
                val wait = animated() && hasStartSwaps()
                // Na 1ª partida a espera já foi armada na construção (o 1º snapshot pode ser igual ao inicial).
                if (wait && match.roundNumber > 1) {
                    awaitingPresentation.set(PresentationKey(match.roundNumber, match.currentRound.redThreeLog.size))
                }
                _events.tryEmit(GameEvent.RoundStarted(match.roundNumber))
                publishLocked(Stage.PLAYING)
                wait
            }
            resuming = false
            // A pausa da distribuição vem antes da espera das trocas do início da vez: elas só são encenadas depois da
            // distribuição, então o tempo total é o maior dos dois, e não a soma (sem pausa morta depois do "Baixar").
            if (freshDeal && animated() && dealDelayMillis > 0) awaitDealAnimation(match.roundNumber)
            if (waitDeal) presentationSignal.receive()
            while (true) {
                val round = synchronized(lock) {
                    if (resigned) return
                    match.currentRound
                }
                if (round.phase == Phase.FINISHED) break
                val seat = round.currentSeat
                val legal = RoundEngine.legalActions(round, seat, match.rules)
                check(legal.isNotEmpty()) { "Assento ${seat.index} sem ações legais na fase ${round.phase}" }
                val player = current[seat.index]
                val chosen: Action? = when (player) {
                    is BotSeatPlayer -> {
                        publish(Stage.PLAYING, thinkingSeat = seat)
                        if (botDelayMillis > 0 && pacing == PacingPolicy.LOCAL_ANIMATED) delay(botDelayMillis)
                        player.bot.chooseAction(round.viewFor(seat, match.rules), legal)
                    }
                    is HumanPlayer -> {
                        val requestId = requestCounter.incrementAndGet()
                        val registered = synchronized(lock) {
                            if (resigned) return
                            // §16.8 o assento mudou de dono antes de o pedido abrir: refaz a vez com o novo jogador.
                            if (current[seat.index] !== player) return@synchronized false
                            setRequest(seat, requestId to round)
                            publishLocked(Stage.PLAYING, humanSeat = seat, humanLegal = legal, requestId = requestId)
                            true
                        }
                        if (!registered) {
                            null
                        } else {
                            try {
                                player.chooseAction(legal, requestId)
                            } catch (_: SeatReplacedException) {
                                null
                            } finally {
                                setRequest(seat, null)
                            }
                        }
                    }
                }
                // Assento trocado durante a decisão (§16.8, §16.9): a escolha do antigo dono é descartada.
                val action = chosen ?: continue
                check(action in legal) { "Assento ${seat.index}: ação fora de legalActions: $action" }
                val waitSwaps: Boolean? = synchronized(lock) {
                    // §13.1 desistência durante a decisão (ex.: bot pensando): nada mais é aplicado.
                    if (resigned) return
                    if (current[seat.index] !== player) return@synchronized null
                    val event = PublicEvent.of(round.discardPile, seat, action)
                    match = match.play(seat, action)
                    recordEvent(event)
                    val laid = match.currentRound.redThreeLog.drop(round.redThreeLog.size)
                    turnReds.record(seat, startsTurn(event), laid, eventsInTurn = turnEvents[seat.index].size)
                    // §3.5 a vez passou: a do próximo começa já (com as trocas de 3 vermelho do início dela), antes da compra.
                    match.currentRound.let { next ->
                        if (next.phase != Phase.FINISHED && next.currentSeat != seat) {
                            turnReds.beginTurn(next.currentSeat)
                        }
                    }
                    roundEvents = roundEvents + event
                    bots().forEach { it.observe(event) }
                    _events.tryEmit(GameEvent.ActionApplied(match.roundNumber, event, laid))
                    save()
                    // §4.3/§6.5 com humano, uma ação que fez alguém trocar 3 vermelho (compra, reposição em cadeia,
                    // morto) só é seguida da próxima depois de a interface encenar essas trocas: assim a ordem vista é a
                    // real (compra/troca → baixas → descarte) e o descarte é sempre o último movimento de quem joga.
                    val after = match.currentRound
                    // Inclui as trocas do início da vez do PRÓXIMO jogador (§3.5), feitas na mesma passagem de vez.
                    val wait = animated() && after.phase != Phase.FINISHED && after.redThreeLog.size > round.redThreeLog.size
                    if (wait) awaitingPresentation.set(PresentationKey(match.roundNumber, after.redThreeLog.size))
                    // Durante a encenação, quem trocou continua "jogando" (a vez passa ao humano só depois): o texto e o
                    // destaque seguem o adversário em vez de um "Aguarde…" neutro (cada vista exclui o próprio assento).
                    publishLocked(Stage.PLAYING, thinkingSeat = after.redThreeLog.lastOrNull()?.seat.takeIf { wait })
                    wait
                }
                if (waitSwaps == null) continue
                if (waitSwaps) presentationSignal.receive()
            }
            synchronized(lock) {
                // §13.1 a partida que terminou depois da desistência nunca é pontuada.
                if (resigned) return
                val scoredRound = match.roundNumber
                match = match.finishRound()
                lastScored = scoredRound to match.currentRound
                _events.tryEmit(GameEvent.RoundScored(scoredRound, match.history.last(), match.totals, match.winner))
                if (match.isOver) {
                    match.winner?.let { _events.tryEmit(GameEvent.GameFinished(it, resigned = false)) }
                    publishLocked(Stage.GAME_OVER)
                    return
                }
                save()
            }
            // A espera da próxima partida fica no começo do laço (mesmo caminho do jogo retomado em ROUND_OVER).
        }
    }

    /**
     * §3.5 espera a interface avisar que a distribuição da partida [roundNumber] terminou ([dealAnimationDone]); sem aviso,
     * segue depois do tempo da animação mais uma folga (rede de segurança para quem não avisa).
     */
    private suspend fun awaitDealAnimation(roundNumber: Int) {
        withTimeoutOrNull((dealDelayMillis * dealDelayScale).toLong() + DEAL_SIGNAL_GRACE_MILLIS) {
            while (dealAnimationSignal.receive() != roundNumber) Unit
        }
    }

    /** A interface terminou de animar a distribuição da partida [roundNumber]: libera a 1ª jogada (e as trocas do início). */
    fun dealAnimationDone(roundNumber: Int) {
        dealAnimationSignal.trySend(roundNumber)
    }

    /** Ponto de salvamento: repassa a foto do jogo (sob a trava; a gravação fica fora do laço). */
    private fun save() {
        onSave?.invoke(SaveSnapshot(match, roundEvents, botRandomCalls()))
    }

    /** Etapa do primeiro snapshot (o jogo retomado pode estar no fim de uma partida). */
    private fun initialStage(): Stage = when {
        match.isOver -> Stage.GAME_OVER
        match.isAwaitingNextRound -> Stage.ROUND_OVER
        else -> Stage.PLAYING
    }

    /** Foto atual do jogo para salvar (mesmo conteúdo dos pontos de salvamento). */
    fun saveSnapshot(): SaveSnapshot = synchronized(lock) { SaveSnapshot(match, roundEvents, botRandomCalls()) }

    /** Posição do sorteio de cada assento (0 para o humano), para o jogo retomado continuar igual. */
    private fun botRandomCalls(): List<Long> = current.map { (it as? BotSeatPlayer)?.random?.calls ?: 0L }

    /**
     * Confirmação do humano para distribuir a próxima partida (tela de fim de partida). Ignorada fora da
     * etapa [Stage.ROUND_OVER]; devolve se foi aceita.
     */
    fun continueToNextRound(): Boolean {
        if (resigned) return false
        if (!awaitingNextRound.compareAndSet(true, false)) return false
        return nextRoundSignal.trySend(Unit).isSuccess
    }

    /**
     * A interface terminou de encenar as trocas de 3 vermelho da distribuição da partida [roundNumber]: libera a 1ª
     * jogada. Ignorada se o controlador não espera essa partida; devolve se foi aceita.
     */
    fun dealPresentationDone(roundNumber: Int): Boolean {
        val key = awaitingPresentation.get() ?: return false
        return key.roundNumber == roundNumber && hasStartSwaps() && presentationDone(roundNumber, key.logSize)
    }

    /**
     * A interface terminou de encenar as trocas de 3 vermelho da partida [roundNumber] até o registro ter [logSize]
     * entradas (§3.5, §6.5): libera a próxima ação. Aceita só a espera exata em aberto; confirmação tardia, de outra
     * partida, de um registro menor ou depois da desistência é recusada. Devolve se foi aceita.
     */
    fun presentationDone(roundNumber: Int, logSize: Int): Boolean {
        if (resigned) return false
        val key = PresentationKey(roundNumber, logSize)
        val current = awaitingPresentation.get() ?: return false
        if (current != key || !awaitingPresentation.compareAndSet(current, null)) return false
        return presentationSignal.trySend(Unit).isSuccess
    }

    /**
     * Envia a jogada do humano para o pedido [requestId] (o de [GameSnapshot.humanRequestId] em que ela foi
     * resolvida). Recusada se o pedido não é mais o em aberto ou se a ação não está em `legal`.
     */
    fun submit(action: Action, requestId: Long): Boolean = submit(primarySeat(), action, requestId)

    /** Como [submit], para o humano do [seat] (§16.1: o anfitrião só aceita a jogada de quem tem o pedido em aberto). */
    fun submit(seat: Seat, action: Action, requestId: Long): Boolean {
        if (resigned) return false
        return (current.getOrNull(seat.index) as? HumanPlayer)?.submit(action, requestId) ?: false
    }

    /**
     * Motivo pelo qual [action] do humano seria recusada (`RoundEngine.validate`), ou `null` se ela é válida ou se
     * o pedido [requestId] não é mais o em aberto. Valida contra a partida desse pedido, a mesma das ações
     * publicadas em [GameSnapshot.humanLegal]: um toque atrasado nunca recebe o motivo de outra situação.
     * Só devolve o motivo, nunca o estado oculto.
     */
    fun explain(action: Action, requestId: Long): RuleError? = explain(primarySeat(), action, requestId)

    /** Como [explain], para o humano do [seat]. */
    fun explain(seat: Seat, action: Action, requestId: Long): RuleError? {
        val (id, round) = humanRequests.get()[seat.index] ?: return null
        if (id != requestId || resigned) return null
        return (RoundEngine.validate(round, seat, action, match.rules) as? RuleResult.Failure)?.error
    }

    /** Última partida pontuada (número e a partida encerrada), para a vista de fim de partida de cada assento (§16.11). */
    @Volatile
    private var lastScored: Pair<Int, RoundState>? = null

    /**
     * §16.11 vista do [seat] na partida [roundNumber] já encerrada (a mesa final, só com a mão dele), mesmo que a próxima
     * já tenha sido distribuída; `null` se essa não é a última partida pontuada.
     */
    fun roundEndViewOf(seat: Seat, roundNumber: Int): PlayerView? {
        val (number, round) = lastScored ?: return null
        return if (number == roundNumber) round.viewFor(seat, match.rules) else null
    }

    /** §13.1 desistência do humano deste aparelho: [resign] do [viewerSeat] (o jogo local de sempre). */
    fun resign(): Boolean = resign(viewerSeat)

    /**
     * §13.1 desistência do humano do [seat]: o jogo termina imediatamente, o outro lado vence e a partida em andamento não
     * é pontuada (os totais ficam como estavam). Publica [Stage.GAME_OVER] e encerra o laço, inclusive se um bot
     * estiver pensando (a decisão dele é descartada). Devolve `false` se o jogo já tinha terminado.
     */
    fun resign(seat: Seat): Boolean {
        config.mode.requireSeat(seat)
        val accepted = synchronized(lock) {
            if (match.isOver || resigned) return@synchronized false
            resignedWinner = config.mode.sides.first { it != config.mode.sideOf(seat) }
            awaitingNextRound.set(false)
            awaitingPresentation.set(null)
            // Sob a trava, `match` não está no meio de uma ação nem de uma pontuação: os totais e o histórico são
            // os de antes da partida em andamento (ela nunca é pontuada).
            seatStates.forEach { (index, flow) -> setSeatLocked(index, flow, snapshot(Seat(index), Stage.GAME_OVER, humanLegal = emptyList(), thinkingSeat = null)) }
            resignedWinner?.let { _events.tryEmit(GameEvent.GameFinished(it, resigned = true)) }
            true
        }
        // Interrompe esperas (humano, distribuição, próxima partida, pausa); decisões de CPU em curso terminam e
        // são descartadas na próxima seção sob a trava.
        if (accepted) loopJob?.cancel()
        return accepted
    }

    /** A ação abre a vez do jogador (compra, recusa do lixo ou lixo). */
    private fun startsTurn(event: PublicEvent): Boolean =
        event.action == Action.DrawFromStock || event.action == Action.DeclineDraw || event.action is Action.TakeDiscardPile

    private fun recordEvent(event: PublicEvent) {
        val index = event.seat.index
        val startsTurn = startsTurn(event)
        turnEvents = turnEvents.mapIndexed { i, events ->
            when {
                i != index -> events
                startsTurn -> listOf(event)
                else -> events + event
            }
        }
    }

    /** §3.5 houve troca de 3 vermelho no início da vez do 1º jogador (registro público do motor ainda não vazio). */
    private fun hasStartSwaps(): Boolean = match.currentRound.redThreeLog.isNotEmpty()

    /**
     * §16.8, §16.9 troca o jogador do [seat] por [newPlayer] (um robô ou outro humano), sem reiniciar o jogo: a mão e a vez
     * do assento ficam como estão. O pedido em aberto do antigo humano é abandonado, a decisão em curso do antigo bot é
     * descartada e o laço refaz a vez com o novo jogador. Um robô novo reconstrói a memória com os eventos públicos da
     * partida (como no jogo retomado). Devolve `false` se o jogo já terminou (ou foi desistido).
     */
    fun replacePlayer(seat: Seat, newPlayer: SeatPlayer): Boolean {
        config.mode.requireSeat(seat)
        var old: SeatPlayer? = null
        val accepted = synchronized(lock) {
            if (match.isOver || resigned) return@synchronized false
            old = current[seat.index]
            if (old === newPlayer) return@synchronized false
            require(newPlayer !is HumanPlayer || current.none { it === newPlayer }) { "Esse HumanPlayer já ocupa outro assento" }
            current = current.toMutableList().also { it[seat.index] = newPlayer }
            when (newPlayer) {
                is BotSeatPlayer -> {
                    newPlayer.bot.onNewRound()
                    roundEvents.forEach { newPlayer.bot.observe(it) }
                }
                is HumanPlayer -> seatStates.getOrPut(seat.index) {
                    MutableStateFlow(snapshot(seat, initialStage(), humanLegal = emptyList(), thinkingSeat = null))
                }
            }
            _events.tryEmit(GameEvent.SeatReplaced(seat, byBot = newPlayer is BotSeatPlayer))
            true
        }
        if (!accepted) return false
        (old as? HumanPlayer)?.abandon()
        if (humanSeats().isEmpty() || pacing == PacingPolicy.FREE) {
            // Sem humano na tela local, ninguém mais vai confirmar encenação nem a próxima partida: libera as esperas.
            if (awaitingPresentation.getAndSet(null) != null) presentationSignal.trySend(Unit)
            if (humanSeats().isEmpty() && awaitingNextRound.compareAndSet(true, false)) nextRoundSignal.trySend(Unit)
        }
        return true
    }

    /** §16.9 troca o jogador do [seat] por um robô [bot] (com o [random] contado, se o jogo precisa retomar o sorteio dele). */
    fun replaceWithBot(seat: Seat, bot: BotPlayer, random: CountingRandom? = null): Boolean =
        replacePlayer(seat, BotSeatPlayer(bot, random))

    private fun publish(stage: Stage, thinkingSeat: Seat? = null) {
        synchronized(lock) { publishLocked(stage, thinkingSeat = thinkingSeat) }
    }

    /** Publica o snapshot; chamar só com a trava. Depois da desistência, o fim de jogo nunca é sobrescrito. */
    private fun publishLocked(
        stage: Stage,
        humanSeat: Seat? = null,
        humanLegal: List<Action> = emptyList(),
        thinkingSeat: Seat? = null,
        requestId: Long = 0,
    ) {
        if (resigned) return
        // Só o humano da vez recebe as ações legais e o pedido; as demais vistas são só de quem observa.
        seatStates.forEach { (index, flow) ->
            val owner = index == humanSeat?.index
            setSeatLocked(index, flow, snapshot(Seat(index), stage, if (owner) humanLegal else emptyList(), thinkingSeat, if (owner) requestId else 0))
        }
    }

    private fun snapshot(seat: Seat, stage: Stage, humanLegal: List<Action>, thinkingSeat: Seat?, requestId: Long = 0): GameSnapshot {
        val current = match
        return GameSnapshot(
            config = config,
            viewerSeat = seat,
            view = current.currentRound.viewFor(seat, current.rules),
            roundNumber = current.roundNumber,
            totals = current.totals,
            history = current.history,
            stage = stage,
            winner = resignedWinner ?: current.winner,
            humanLegal = humanLegal,
            thinkingSeat = thinkingSeat?.takeIf { it != seat },
            turnEvents = turnEvents,
            turnRedThrees = turnReds.current,
            turnRedThreeAfter = turnReds.currentAfter,
            humanRequestId = requestId,
            resigned = resignedWinner != null,
        )
    }

    companion object {
        /** Pausa padrão antes de cada jogada de bot. */
        const val DEFAULT_BOT_DELAY_MILLIS: Long = 700

        /** Folga da espera pelo aviso da interface de que a distribuição terminou (rede de segurança). */
        private const val DEAL_SIGNAL_GRACE_MILLIS: Long = 4_000

        /** Eventos que cabem em [events] sem o consumidor ler. */
        private const val EVENT_BUFFER: Int = 512

        /** Vistas que cabem em [updatesOf] sem o consumidor ler. */
        private const val SEAT_UPDATES_BUFFER: Int = 4096

        /**
         * Jogadores de um jogo do app: o [human] no [humanSeat] e bots da dificuldade da [config] nos demais
         * assentos, cada um com `Random(botSeed(gameSeed, assento))`. [botFactory] permite trocar os bots nos
         * testes.
         */
        fun playersFor(
            config: GameConfig,
            gameSeed: Long,
            human: HumanPlayer,
            humanSeat: Seat = Seat(0),
            botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
            /** Sorteios já feitos por assento (jogo salvo): cada bot continua do ponto em que parou. */
            randomCalls: List<Long> = emptyList(),
        ): List<SeatPlayer> = playersForTable(config, gameSeed, mapOf(humanSeat to human), botFactory, randomCalls)

        /** Como [playersFor], com um humano em cada assento de [humans] (§16, multijogador) e bots nos demais. */
        fun playersForTable(
            config: GameConfig,
            gameSeed: Long,
            humans: Map<Seat, HumanPlayer>,
            botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
            randomCalls: List<Long> = emptyList(),
        ): List<SeatPlayer> = config.mode.seats.map { seat ->
            humans[seat] ?: run {
                val random = CountingRandom(botSeed(gameSeed, seat.index), randomCalls.getOrElse(seat.index) { 0L })
                BotSeatPlayer(botFactory(seat, random), random)
            }
        }
    }
}

/** Jogo salvo a retomar: o `Match` e os eventos públicos da partida atual, em ordem. */
data class RestoredGame(
    val match: Match,
    val events: List<PublicEvent>,
    val botRandomCalls: List<Long> = emptyList(),
    /** Nome do jogador usado no sorteio dos personagens (§14.5); `null` = jogo salvo antigo (vale o do perfil). */
    val playerName: String? = null,
)

/** O que salvar: o jogo completo e os eventos públicos da partida atual (para reconstruir a memória dos bots). */
data class SaveSnapshot(val match: Match, val events: List<PublicEvent>, val botRandomCalls: List<Long> = emptyList())

