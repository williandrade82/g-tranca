package com.gtranca.online.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PlayStyle
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.RejectReason
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.Replaced
import com.gtranca.online.contracts.Resign
import com.gtranca.online.contracts.SeatChange
import com.gtranca.online.contracts.SeatInfo
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.SeatsChanged
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** Estilo de jogo de cada perfil de robo (§14.5); so muda preferencias entre jogadas legais. */
internal fun BotProfile.style(): PlayStyle = when (this) {
    BotProfile.JOVEM -> PlayStyle.SAGAZ
    BotProfile.SOBRIO -> PlayStyle.SOBRIO
    BotProfile.ALTERNATIVO -> PlayStyle.OUSADO
    BotProfile.ATLETA -> PlayStyle.AGRESSIVO
}

internal fun BotLevel.difficulty(): Difficulty = when (this) {
    BotLevel.FACIL -> Difficulty.FACIL
    BotLevel.MEDIO -> Difficulty.MEDIO
    BotLevel.DIFICIL -> Difficulty.DIFICIL
}

/** Resultado de [MatchSession.attach]. */
sealed interface AttachResult {
    data class Ok(val seat: Int) : AttachResult

    /** O jogador desistiu: um robo assumiu o assento (§18.4). */
    data object Abandoned : AttachResult

    /** A sessao ja terminou. */
    data object Closed : AttachResult
}

/** Registro do que aconteceu com um assento humano (para a punicao/aposta da fase 2). */
data class ResignRecord(val seat: Int, val guestId: String, val round: Int)

/** Fotografia do estado para os testes (nunca sai do servidor). */
internal data class Inspection(
    val match: Match,
    val stage: TableStage,
    val version: Long,
    val refusals: List<Int>,
    val lastSeq: List<Long>,
    val resignations: List<ResignRecord>,
)

/**
 * Uma partida autoritativa (§18.3): guarda o `Match` completo, valida cada `Action` com `legalActions`, aplica com
 * `apply` e envia a cada humano so a VISTA DO ASSENTO. Tudo roda num unico laco (ator): um comando por vez, sem
 * travas. Robos jogam o seu assento depois de [GameConfig.botDelayMs], sempre com o `PlayerView`.
 *
 * Nao ha economia nem relogio de jogada aqui (etapa 1.3b): queda de conexao NAO e abandono; no tempo indeterminado a
 * vez de quem caiu espera. So [Resign] troca o humano por um robo.
 */
class MatchSession(
    val tableId: String,
    private val table: TableRules,
    specs: List<SeatSpec>,
    seed: Long,
    private val config: GameConfig,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    private sealed interface Slot {
        class Human(val guestId: String, val name: String) : Slot
        class Robot(val profile: BotProfile, val level: BotLevel, val replacedGuestId: String? = null) : Slot
    }

    private sealed interface Cmd {
        class Attach(val guestId: String, val link: PlayerLink, val reply: CompletableDeferred<AttachResult>) : Cmd
        class Detach(val seat: Int, val link: PlayerLink) : Cmd
        class Incoming(val seat: Int, val link: PlayerLink, val message: ClientMessage) : Cmd
        class Inspect(val reply: CompletableDeferred<Inspection>) : Cmd
        data object Shutdown : Cmd
    }

    private val commands = Channel<Cmd>(capacity = 1024)

    /** Assento de cada humano (imutavel: quem entra e quem sai nao muda; so o tipo do assento). */
    private val humanSeats: Map<String, Int> = specs.mapIndexedNotNull { i, s ->
        (s as? SeatSpec.Human)?.let { it.guestId to i }
    }.toMap()

    private val slots: MutableList<Slot> = specs.map {
        when (it) {
            is SeatSpec.Human -> Slot.Human(it.guestId, it.name)
            is SeatSpec.Robot -> Slot.Robot(it.profile, it.level)
        }
    }.toMutableList()

    private val ruleSet = table.ruleSetOrThrow()
    private var match: Match = startMatch(table.mode.toEngine(), table.targetScore, Random(seed), ruleSet)
    private val rules get() = match.rules
    private val seatCount = match.mode.seatCount
    private val bots: Array<BotPlayer?> = Array(seatCount) { null }
    private val links: Array<PlayerLink?> = Array(seatCount) { null }
    private val lastSeq = LongArray(seatCount)
    private val refusals = IntArray(seatCount)
    private val turnEvents: Array<List<PublicEvent>> = Array(seatCount) { emptyList() }
    private val resignations = mutableListOf<ResignRecord>()
    private var version = 1L
    private var stage = TableStage.PLAYING
    private var autoAt: Long? = null
    private var lastActivity = now()
    private var endedAt: Long? = null

    @Volatile
    private var finished = false
    val isFinished: Boolean get() = finished

    /** Para o registro rejeitar o mesmo guestId duplicado e o endpoint saber o assento antes de abrir o canal. */
    fun seatOf(guestId: String): Int? = humanSeats[guestId]

    init {
        require(slots.size == seatCount) { "Assentos (${slots.size}) diferentes do modo ($seatCount)" }
        for (i in 0 until seatCount) (slots[i] as? Slot.Robot)?.let { bots[i] = newBot(i, it.profile, it.level) }
        scheduleAuto()
    }

    private fun newBot(seat: Int, profile: BotProfile, level: BotLevel): BotPlayer =
        createBot(level.difficulty(), Random(MatchRunner.botSeed(match.seed, seat)), config.hardConfig, profile.style())

    // ------------------------------------------------------------------------------------------------ API

    suspend fun attach(guestId: String, link: PlayerLink): AttachResult {
        if (finished) return AttachResult.Closed
        val reply = CompletableDeferred<AttachResult>()
        if (commands.trySend(Cmd.Attach(guestId, link, reply)).isFailure) return AttachResult.Closed
        return reply.await()
    }

    suspend fun detach(seat: Int, link: PlayerLink) {
        commands.trySend(Cmd.Detach(seat, link))
    }

    suspend fun submit(seat: Int, link: PlayerLink, message: ClientMessage) {
        commands.send(Cmd.Incoming(seat, link, message))
    }

    internal suspend fun inspect(): Inspection {
        val reply = CompletableDeferred<Inspection>()
        commands.send(Cmd.Inspect(reply))
        return reply.await()
    }

    fun shutdown() {
        commands.trySend(Cmd.Shutdown)
    }

    /** Laco do ator; termina quando o jogo acaba e o tempo de retencao passa, ou por inatividade. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        try {
            while (!finished) {
                val cmd: Cmd? = select {
                    commands.onReceive { it }
                    onTimeout(waitMillis()) { null }
                }
                if (cmd != null) handle(cmd) else autoStep()
            }
        } finally {
            finished = true
            links.forEach { it?.close(1001, "Partida encerrada") }
            // Quem ainda tenta falar com a sessao recebe Closed em vez de ficar esperando.
            commands.close()
            for (c in commands) if (c is Cmd.Attach) c.reply.complete(AttachResult.Closed)
        }
    }

    // ------------------------------------------------------------------------------------------------ comandos

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Attach -> cmd.reply.complete(onAttach(cmd.guestId, cmd.link))
            is Cmd.Detach -> onDetach(cmd.seat, cmd.link)
            is Cmd.Incoming -> onIncoming(cmd.seat, cmd.link, cmd.message)
            is Cmd.Inspect -> cmd.reply.complete(
                Inspection(match, stage, version, refusals.toList(), lastSeq.toList(), resignations.toList()),
            )
            Cmd.Shutdown -> finished = true
        }
    }

    private fun onAttach(guestId: String, link: PlayerLink): AttachResult {
        val seat = humanSeats[guestId] ?: return AttachResult.Closed
        if (slots[seat] !is Slot.Human) return AttachResult.Abandoned
        lastActivity = now()
        links[seat]?.let { old ->
            if (old !== link) {
                // §18.4: a nova conexao assume e a antiga e fechada.
                send(seat, old, Replaced("Você voltou à mesa por outra conexão; esta foi encerrada."))
                old.close(CLOSE_REPLACED, "Substituida")
            }
        }
        links[seat] = link
        send(
            seat, link,
            Welcome(
                protocolVersion = ONLINE_PROTOCOL_VERSION, tableId = tableId, you = Seat(seat), table = table,
                ruleSet = rules, seats = seatInfos(), lastSeq = lastSeq[seat],
            ),
        )
        send(seat, link, SeatUpdate(null, viewOf(seat, includeHistory = true)))
        broadcastSeats(seat, SeatChange.CONNECTED)
        return AttachResult.Ok(seat)
    }

    private fun onDetach(seat: Int, link: PlayerLink) {
        if (links[seat] !== link) return // conexao antiga ja substituida
        links[seat] = null
        link.close(1000, "Conexao encerrada")
        if (slots[seat] is Slot.Human) broadcastSeats(seat, SeatChange.DISCONNECTED)
    }

    private fun onIncoming(seat: Int, link: PlayerLink, message: ClientMessage) {
        if (links[seat] !== link) return // mensagem de conexao que ja foi substituida
        if (slots[seat] !is Slot.Human) return
        lastActivity = now()
        when (message) {
            is PlayAction -> onPlay(seat, link, message)
            is Resign -> {
                if (message.seq <= lastSeq[seat]) return
                lastSeq[seat] = message.seq
                onResign(seat, link)
            }
        }
    }

    private fun onPlay(seat: Int, link: PlayerLink, msg: PlayAction) {
        // Idempotencia: duplicada ou atrasada e descartada sem efeito e sem contar como recusa.
        if (msg.seq <= lastSeq[seat]) return
        lastSeq[seat] = msg.seq

        fun reject(reason: RejectReason, text: String) {
            refusals[seat]++
            log.info("Jogada recusada table={} seat={} reason={} total={}", tableId, seat, reason, refusals[seat])
            send(seat, link, Rejected(msg.seq, reason, text, refusals[seat]))
        }

        if (stage != TableStage.PLAYING || match.currentRound.phase == Phase.FINISHED) {
            return reject(RejectReason.NOT_PLAYING, "Não há partida em andamento agora.")
        }
        val round = match.currentRound
        if (round.currentSeat.index != seat) return reject(RejectReason.NOT_YOUR_TURN, "Não é a sua vez.")
        if (msg.stateVersion != version) {
            return reject(RejectReason.STALE_STATE, "A mesa mudou; use a vista mais recente.")
        }
        val legal = RoundEngine.legalActions(round, Seat(seat), rules)
        if (msg.action !in legal) {
            val why = (RoundEngine.validate(round, Seat(seat), msg.action, rules) as? RuleResult.Failure)?.error
            return reject(RejectReason.ILLEGAL, "Jogada não permitida agora" + (why?.let { " ($it)" } ?: "") + ".")
        }
        applyAction(Seat(seat), msg.action)
    }

    /** Desistir (§13.1, §18.4): um robo assume o assento e o jogo segue. Aposta/punicao: fase 2 (so registra). */
    private fun onResign(seat: Int, link: PlayerLink) {
        val human = slots[seat] as? Slot.Human ?: return
        if (stage == TableStage.GAME_OVER) return
        val used = slots.filterIsInstance<Slot.Robot>().map { it.profile }.toSet()
        val profile = BotProfile.entries.firstOrNull { it !in used } ?: BotProfile.entries[seat % BotProfile.entries.size]
        slots[seat] = Slot.Robot(profile, table.robotLevel, human.guestId)
        bots[seat] = newBot(seat, profile, table.robotLevel)
        resignations += ResignRecord(seat, human.guestId, match.roundNumber)
        log.info("Desistencia table={} seat={} round={}", tableId, seat, match.roundNumber)
        send(seat, link, YouResigned("Você desistiu; um robô assumiu o seu lugar."))
        link.close(1000, "Desistencia")
        links[seat] = null
        version++
        broadcastSeats(seat, SeatChange.RESIGNED)
        broadcastUpdate(null)
        scheduleAuto()
    }

    // ------------------------------------------------------------------------------------------------ jogo

    private fun applyAction(seat: Seat, action: Action) {
        val round = match.currentRound
        val event = PublicEvent.of(round.discardPile, seat, action)
        match = match.play(seat, action)
        turnEvents[seat.index] = if (event.startsTurn()) listOf(event) else turnEvents[seat.index] + event
        bots.forEach { it?.observe(event) }
        version++
        lastActivity = now()
        if (match.currentRound.phase == Phase.FINISHED) {
            match = match.finishRound() // §12 pontua; §13 verifica o vencedor
            stage = if (match.isOver) TableStage.GAME_OVER else TableStage.ROUND_OVER
            if (stage == TableStage.GAME_OVER) endedAt = now()
        }
        broadcastUpdate(event, includeHistory = stage != TableStage.PLAYING)
        scheduleAuto()
    }

    private fun nextRound() {
        match = match.startNextRound() // §4.1 o jogador seguinte inicia
        turnEvents.fill(emptyList())
        bots.forEach { it?.onNewRound() }
        stage = TableStage.PLAYING
        version++
        lastActivity = now()
        broadcastUpdate(null)
        scheduleAuto()
    }

    private suspend fun botMove() {
        val round = match.currentRound
        val seat = round.currentSeat
        val bot = bots[seat.index] ?: return
        val legal = RoundEngine.legalActions(round, seat, rules)
        if (legal.isEmpty()) {
            log.error("Sem jogadas legais table={} seat={}", tableId, seat.index)
            fail("Partida sem jogadas legais.")
            return
        }
        val view = round.viewFor(seat, rules)
        val chosen = try {
            withContext(Dispatchers.Default) { bot.chooseAction(view, legal) }
        } catch (e: Exception) {
            log.error("Robo falhou table={} seat={}: {}", tableId, seat.index, e.toString())
            null
        }
        applyAction(seat, chosen?.takeIf { it in legal } ?: legal.first())
    }

    private fun fail(message: String) {
        links.forEachIndexed { i, l -> if (l != null) send(i, l, GameError(ErrorCode.PARTIDA_INDISPONIVEL, message)) }
        finished = true
    }

    // ------------------------------------------------------------------------------------------------ tempo

    private fun botDelay(): Long = if (humansActive()) config.botDelayMs else 0

    /** Ha humano que ainda joga (nao desistiu)? Os humanos sao sempre `Slot.Human` enquanto jogam. */
    private fun humansActive(): Boolean = slots.any { it is Slot.Human }

    private fun scheduleAuto() {
        autoAt = when (stage) {
            TableStage.ROUND_OVER -> now() + (if (humansActive()) config.roundPauseMs else 0)
            TableStage.GAME_OVER -> (endedAt ?: now()) + config.retainAfterEndMs
            TableStage.PLAYING ->
                if (slots[match.currentRound.currentSeat.index] is Slot.Robot) now() + botDelay() else null
        }
    }

    private fun waitMillis(): Long {
        val idleLeft = lastActivity + config.idleTtlMs - now()
        val auto = autoAt?.let { it - now() } ?: idleLeft
        return maxOf(0L, minOf(idleLeft, auto))
    }

    private suspend fun autoStep() {
        val due = autoAt?.let { now() >= it } == true
        when {
            due && stage == TableStage.GAME_OVER -> finished = true
            due && stage == TableStage.ROUND_OVER -> nextRound()
            due && stage == TableStage.PLAYING -> botMove()
            now() - lastActivity >= config.idleTtlMs -> {
                log.info("Sessao inativa descartada table={}", tableId)
                finished = true
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ vistas

    /** Gancho de teste: recebe cada mensagem no instante do envio, junto do `Match` real naquele momento. */
    internal var tap: ((seat: Int, message: ServerMessage, match: Match) -> Unit)? = null

    private fun send(seat: Int, link: PlayerLink, message: ServerMessage) {
        tap?.invoke(seat, message, match)
        link.offer(message)
    }

    private fun seatInfos(): List<SeatInfo> = slots.mapIndexed { i, s ->
        when (s) {
            is Slot.Human -> SeatInfo(i, SeatKind.HUMANO, s.name, connected = links[i] != null)
            is Slot.Robot -> SeatInfo(
                i, SeatKind.ROBO, robotName(s.profile), s.profile, s.level, connected = true,
                resigned = s.replacedGuestId != null,
            )
        }
    }

    private fun broadcastSeats(seat: Int, change: SeatChange) {
        val msg = SeatsChanged(seatInfos(), seat, change)
        links.forEachIndexed { i, l -> if (l != null && (i != seat || change != SeatChange.RESIGNED)) send(i, l, msg) }
    }

    /** Cada humano recebe a vista do SEU assento (§18.3). Nunca o `Match`/`RoundState`. */
    private fun broadcastUpdate(event: PublicEvent?, includeHistory: Boolean = false) {
        for (i in 0 until seatCount) links[i]?.let { send(i, it, SeatUpdate(event, viewOf(i, includeHistory))) }
    }

    internal fun viewOf(seat: Int, includeHistory: Boolean): OnlineSeatView {
        val round = match.currentRound
        val pv = round.viewFor(Seat(seat), rules)
        val current = round.currentSeat
        return OnlineSeatView(
            version = version, roundNumber = match.roundNumber, stage = stage, totals = match.totals,
            winner = match.winner, history = if (includeHistory) match.history else null,
            mode = pv.mode, seat = pv.seat, side = pv.side, currentSeat = pv.currentSeat, firstSeat = pv.firstSeat,
            phase = pv.phase, result = pv.result, hand = pv.hand, handSizes = pv.handSizes, discardPile = pv.discardPile,
            stockSize = pv.stockSize, mortoStatus = pv.mortoStatus, mortoSizes = pv.mortoSizes,
            redThrees = pv.redThrees, tables = pv.tables, redThreeLog = pv.redThreeLog, turnsBegun = pv.turnsBegun,
            unsettledMortoSeats = pv.unsettledMortoSeats, turnEvents = turnEvents.toList(),
            thinkingSeat = current.takeIf { stage == TableStage.PLAYING && slots[it.index] is Slot.Robot },
            legal = if (stage == TableStage.PLAYING && current.index == seat) {
                RoundEngine.legalActions(round, Seat(seat), rules)
            } else {
                emptyList()
            },
        )
    }

    companion object {
        /** Codigo de fechamento proprio: a conexao foi substituida por outra do mesmo jogador. */
        const val CLOSE_REPLACED = 4001

        fun robotName(profile: BotProfile): String = when (profile) {
            BotProfile.JOVEM -> "Bolt"
            BotProfile.SOBRIO -> "Domino"
            BotProfile.ALTERNATIVO -> "Zen"
            BotProfile.ATLETA -> "Turbo"
        }
    }
}

/** A acao abre a vez do jogador (compra, recusa do lixo ou lixo): igual ao `GameController` do app. */
internal fun PublicEvent.startsTurn(): Boolean =
    action == Action.DrawFromStock || action == Action.DeclineDraw || action is Action.TakeDiscardPile
