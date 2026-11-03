package com.gtranca.online.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.PlayStyle
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.Loadout
import com.gtranca.engine.PublicEvent
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.TableBoosters
import com.gtranca.engine.finishRound
import com.gtranca.engine.model.Match
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.play
import com.gtranca.engine.startMatch
import com.gtranca.engine.startNextRound
import com.gtranca.engine.viewFor
import com.gtranca.online.contracts.AbandonReason
import com.gtranca.online.contracts.BoosterConfirmFailed
import com.gtranca.online.contracts.BoosterPrompt
import com.gtranca.online.contracts.BoostersConfirmed
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.ClientMessage
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.GameEndReason
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.Punicao
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
import com.gtranca.online.contracts.SelectBoosters
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableClosed
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.TempoDeJogada
import com.gtranca.online.contracts.TurnClock
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

/** Resultado de [MatchSession.closeByHost]. */
sealed interface CloseResult {
    /** A mesa foi encerrada agora (§18.4). */
    data object Closed : CloseResult

    /** O jogo ja tinha terminado: nada a fazer. */
    data object AlreadyOver : CloseResult

    /** O jogador informado nao e humano desta partida. */
    data object NotInMatch : CloseResult
}

/** Compatibilidade com a etapa 1.3b: quem foi trocado por robo (derivado de [AbandonEvent]). */
data class ResignRecord(val seat: Int, val guestId: String, val round: Int)

/**
 * Abandono de um assento humano (§18.4), registrado para a economia da fase 2 (ainda SEM mexer em moedas).
 * [elapsedMs] conta desde o inicio da 1a partida (criacao da sessao); [withinGrace] = dentro dos primeiros 60 s, quando a
 * aposta e devolvida mesmo com `PERDE_APOSTA`. [betRefunded] ja aplica a punicao da mesa a este abandono.
 * Contem o guestId: fica so na memoria do servidor, NUNCA vai ao log nem ao cliente (o toString o omite).
 */
data class AbandonEvent(
    val seat: Int,
    val guestId: String,
    val reason: AbandonReason,
    val round: Int,
    val atEpochMs: Long,
    val elapsedMs: Long,
    val punicao: Punicao,
    val bet: Int,
    val withinGrace: Boolean,
    val betRefunded: Boolean,
) {
    val secondsSinceStart: Long get() = elapsedMs / 1000

    override fun toString(): String =
        "AbandonEvent(seat=$seat, reason=$reason, round=$round, elapsedMs=$elapsedMs, punicao=$punicao, " +
            "withinGrace=$withinGrace, betRefunded=$betRefunded)"
}

/** Como e quando o jogo acabou; [othersRefunded]: os demais humanos recebem a aposta de volta (mesa encerrada, §18.4). */
data class MatchEnd(
    val reason: GameEndReason,
    val atEpochMs: Long,
    val elapsedMs: Long,
    val othersRefunded: Boolean,
    /** Todos os abandonos do jogo, para a liquidacao da economia (fase 2). */
    val abandonments: List<AbandonEvent> = emptyList(),
    /** Indice do lado vencedor (§13, §13.1); `null` = ninguem venceu (mesa encerrada, destruida, erro). */
    val winnerSide: Int? = null,
    /** Lado (indice) de cada assento, na ordem dos assentos. */
    val seatSides: List<Int> = emptyList(),
    /** Assento de origem de cada humano (guestId -> assento). Fica so na memoria do servidor. */
    val humanSeats: Map<String, Int> = emptyMap(),
) {
    override fun toString(): String =
        "MatchEnd(reason=$reason, elapsedMs=$elapsedMs, winnerSide=$winnerSide, abandonments=${abandonments.size})"
}

/** Fotografia do estado para os testes (nunca sai do servidor). */
internal data class Inspection(
    val match: Match,
    val stage: TableStage,
    val version: Long,
    val refusals: List<Int>,
    val lastSeq: List<Long>,
    val abandonments: List<AbandonEvent>,
    val end: MatchEnd?,
    /** Tempo que resta da vez de um humano (`null` = sem relogio agora). */
    val turnLeftMs: Long?,
    val winner: Side?,
) {
    val resignations: List<ResignRecord>
        get() = abandonments.filter { it.reason == AbandonReason.DESISTIU || it.reason == AbandonReason.TEMPO }
            .map { ResignRecord(it.seat, it.guestId, it.round) }
}

/**
 * Uma partida autoritativa (§18.3): guarda o `Match` completo, valida cada `Action` com `legalActions`, aplica com
 * `apply` e envia a cada humano so a VISTA DO ASSENTO. Tudo roda num unico laco (ator): um comando por vez, sem
 * travas. Robos jogam o seu assento depois de [GameConfig.botDelayMs], sempre com o `PlayerView`.
 *
 * Relogio de jogada (§18.4, etapa 1.3c): com tempo definido a VEZ do humano conta (comprar, baixar e descartar) e a queda de
 * conexao NAO a para; esgotar = abandono (nunca se joga por ele). Sem tempo (indeterminado) a mesa espera. Abandono
 * (desistir/tempo): um robo assume o assento, exceto se nao resta humano (ou no 1x1): o jogo termina na hora (§18.4). Sem economia
 * ainda: cada abandono vira um [AbandonEvent]. O relogio e INJETADO ([now], monotonico em ms) e os prazos disparam antes
 * de qualquer comando, entao os testes avancam o relogio sem dormir.
 */
class MatchSession(
    val tableId: String,
    private val table: TableRules,
    specs: List<SeatSpec>,
    seed: Long,
    private val config: GameConfig,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Chamado UMA vez quando o jogo termina ou e encerrado (dentro do ator: nao pode bloquear nem fazer rede). */
    private val onEnded: (MatchEnd) -> Unit = {},
    /** Quem esta conectado e quem abandonou, a cada mudanca (§18.2: o lobby faz a sucessao do anfitriao). */
    private val onPresence: (connected: Set<String>, abandoned: Set<String>) -> Unit = { _, _ -> },
    /** §17.3/§18.3: inventario online dos boosters (§18.5). `null` = sem economia: o seletor nem aparece (boosters desligados). */
    private val economy: EconomyClient? = null,
    /** §17.3 prazo do seletor, a partir de quando ele aparece a cada assento (30 s). */
    private val boosterDeadlineMs: Long = 30_000L,
    /** Para o debito assincrono do seletor (nunca bloqueia o ator): o resultado volta por [Cmd.BoosterResolved]/[Cmd.BoosterFailed]. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * §17.3: id usado na chave de idempotencia do debito de boosters na economia. NUNCA o [tableId] puro: a mesma mesa
     * hospeda jogos sucessivos (§18.2, "mesa que esvazia volta a ser aberta") e reaproveitar o id da mesa faria um jogo
     * novo encontrar a chave `boosters-<id>-<assento>-<booster>` de um jogo ANTERIOR ja aplicada, pulando o debito de
     * boosters de novo "de graca". Por padrao usa [tableId] mesmo assim (testes isolados, mesa nunca repetida); quem
     * cria sessoes de verdade ([MatchRegistry]) passa um id unico por jogo, como ja faz com o [betId] das apostas.
     */
    private val boosterMatchId: String = tableId,
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
        class Close(val hostGuestId: String, val reply: CompletableDeferred<CloseResult>) : Cmd
        /** §18.7: o operador (console de gerenciamento) encerra a mesa travada, sem punir ninguem. */
        class CloseByOperator(val reason: String?, val reply: CompletableDeferred<CloseResult>) : Cmd
        /** §17.3: a economia respondeu ao debito do seletor do [seat] com os boosters [applied] (vazio = nenhum debitado). */
        class BoosterResolved(val seat: Int, val applied: List<String>) : Cmd
        /** §17.3: o debito do [seat] falhou (rede/economia fora); nao resolve nada, o cliente pode mandar [SelectBoosters] de novo. */
        class BoosterFailed(val seat: Int) : Cmd
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
    private val abandonments = mutableListOf<AbandonEvent>()

    // --- seletor de boosters online (§17.3/§18.3): so ativo com [economy] configurado.
    private var tableBoosters = TableBoosters()
    private val boosterPending = BooleanArray(seatCount)
    private val boosterDeadlineAt = arrayOfNulls<Long>(seatCount)

    /**
     * §17.3: o ultimo rascunho SANITIZADO recebido do assento (rascunho ou tentativa de confirmar, mesmo que o debito
     * falhe); `null` = nunca mandou nada. "Vale o que estiver selecionado na tela": o prazo do servidor usa isto, nunca
     * confirma vazio so porque a mensagem final nao chegou a tempo.
     */
    private val boosterDraft = arrayOfNulls<List<String>>(seatCount)

    /** §17.3: tentativas de debito feitas pelo PRAZO do servidor (nunca pelo reenvio manual do jogador); limitado por [BOOSTER_MAX_DEADLINE_ATTEMPTS]. */
    private val boosterDeadlineAttempts = IntArray(seatCount)

    /**
     * §17.3: a selecao TRAVADA na primeira tentativa de confirmar (`final = true`, do jogador ou do prazo); `null` =
     * ainda nenhuma tentativa. Depois disso o que o cliente manda deixa de importar: so se tenta de novo (manual ou
     * pelo prazo) a MESMA lista, com a MESMA chave de idempotencia — nunca uma troca de selecao depois de uma
     * tentativa, que poderia debitar dois boosters diferentes (um "silenciosamente", se a resposta da 1a tentativa se
     * perdeu mas o debito foi aplicado) e so um deles chegar a valer no jogo.
     */
    private val boosterFrozen = arrayOfNulls<List<String>>(seatCount)

    /**
     * §17.3/§17.4: enquanto o assento esta conectado mas ainda nao confirmou, as vistas que iriam para ele ficam
     * nesta fila (como no multijogador local) em vez de se perderem; ao confirmar, ele as ve em ordem — "as
     * animacoes de distribuicao e os descartes ja feitos" — antes da vista fresca de agora.
     */
    private val boosterCatchUp: Array<MutableList<SeatUpdate>> = Array(seatCount) { mutableListOf() }

    private var version = 1L
    private var stage = TableStage.PLAYING
    private var autoAt: Long? = null
    private var endedAt: Long? = null
    /** Inicio do jogo para a janela dos 60 s (§18.4): criacao da sessao, refeito na 1a conexao humana. */
    private var startedAt = now()
    private var everConnected = false

    /** 1x1 entre humanos: quem abandonou (continua `Slot.Human`, mas nao volta) e o vencedor declarado (§13.1). */
    private val forfeited = mutableSetOf<Int>()
    private var forcedWinner: Side? = null
    private var end: MatchEnd? = null

    /** Vez em contagem (§18.4): o assento da vez e quando ela comecou. */
    private var turnSeat: Int? = null
    private var turnStartedAt = 0L

    /** Desde quando nenhum humano esta conectado (`null` = ha algum, ou nao se aplica); base da regra "todos desconectados". */
    private var emptySince: Long? = null

    @Volatile
    private var finished = false
    val isFinished: Boolean get() = finished

    /** Para o registro rejeitar o mesmo guestId duplicado e o endpoint saber o assento antes de abrir o canal. */
    fun seatOf(guestId: String): Int? = humanSeats[guestId]

    init {
        require(slots.size == seatCount) { "Assentos (${slots.size}) diferentes do modo ($seatCount)" }
        for (i in 0 until seatCount) (slots[i] as? Slot.Robot)?.let { bots[i] = newBot(i, it.profile, it.level) }
        syncTurn()
        refreshPresence()
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

    /**
     * O anfitriao da mesa encerra o jogo em andamento (§18.4): conta como abandono DELE, nenhum robo assume, todos os
     * clientes recebem [TableClosed] e as conexoes fecham. O lobby e quem decide quem e o anfitriao.
     */
    suspend fun closeByHost(hostGuestId: String): CloseResult {
        if (finished) return CloseResult.AlreadyOver
        val reply = CompletableDeferred<CloseResult>()
        if (commands.trySend(Cmd.Close(hostGuestId, reply)).isFailure) return CloseResult.AlreadyOver
        return reply.await()
    }

    /**
     * §18.4/§18.7: o OPERADOR (console de gerenciamento, via lobby-service) encerra a mesa travada - diferente de
     * [closeByHost]: ninguem e punido, todos os humanos recebem a aposta de volta, aviso honesto aos conectados.
     * Nunca exige nem confere quem esta sentado (quem chama nao e um jogador).
     */
    suspend fun closeByOperator(reason: String? = null): CloseResult {
        if (finished) return CloseResult.AlreadyOver
        val reply = CompletableDeferred<CloseResult>()
        if (commands.trySend(Cmd.CloseByOperator(reason, reply)).isFailure) return CloseResult.AlreadyOver
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
                // Prazos primeiro: uma jogada que chega depois do fim da vez ja encontra o abandono feito (§18.4).
                fireDueTimers()
                if (cmd != null) handle(cmd) else if (!finished) autoStep()
            }
        } finally {
            finished = true
            links.forEach { it?.close(1001, "Partida encerrada") }
            // Quem ainda tenta falar com a sessao recebe Closed em vez de ficar esperando.
            commands.close()
            for (c in commands) {
                if (c is Cmd.Attach) c.reply.complete(AttachResult.Closed)
                if (c is Cmd.Close) c.reply.complete(CloseResult.AlreadyOver)
                if (c is Cmd.CloseByOperator) c.reply.complete(CloseResult.AlreadyOver)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ comandos

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Attach -> cmd.reply.complete(onAttach(cmd.guestId, cmd.link))
            is Cmd.Detach -> onDetach(cmd.seat, cmd.link)
            is Cmd.Incoming -> onIncoming(cmd.seat, cmd.link, cmd.message)
            is Cmd.Inspect -> cmd.reply.complete(
                Inspection(
                    match, stage, version, refusals.toList(), lastSeq.toList(), abandonments.toList(), end,
                    turnDeadline()?.let { maxOf(0L, it - now()) }, forcedWinner ?: match.winner,
                ),
            )
            is Cmd.Close -> cmd.reply.complete(onClose(cmd.hostGuestId))
            is Cmd.CloseByOperator -> cmd.reply.complete(onCloseByOperator(cmd.reason))
            is Cmd.BoosterResolved -> { boosterPending[cmd.seat] = false; confirmBoosters(cmd.seat, cmd.applied) }
            is Cmd.BoosterFailed -> {
                boosterPending[cmd.seat] = false
                // §17.3: o jogo acabou enquanto o debito estava em andamento - ninguem espera mais, resolve ja.
                if (stage == TableStage.GAME_OVER) {
                    confirmBoosters(cmd.seat, emptyList())
                } else {
                    links[cmd.seat]?.let { send(cmd.seat, it, BoosterConfirmFailed("Não foi possível confirmar os boosters agora. Tente de novo.")) }
                }
            }
            Cmd.Shutdown -> finished = true
        }
    }

    private fun onAttach(guestId: String, link: PlayerLink): AttachResult {
        if (finished) return AttachResult.Closed
        val seat = humanSeats[guestId] ?: return AttachResult.Closed
        if (slots[seat] !is Slot.Human || seat in forfeited) return AttachResult.Abandoned
        links[seat]?.let { old ->
            if (old !== link) {
                // §18.4: a nova conexao assume e a antiga e fechada.
                send(seat, old, Replaced("Você voltou à mesa por outra conexão; esta foi encerrada."))
                old.close(CLOSE_REPLACED, "Substituida")
            }
        }
        links[seat] = link
        if (!everConnected) {
            everConnected = true
            startedAt = now() // §18.4: os 60 s contam desde o inicio da 1a partida entregue aos jogadores
        }
        refreshPresence()
        send(
            seat, link,
            Welcome(
                protocolVersion = ONLINE_PROTOCOL_VERSION, tableId = tableId, you = Seat(seat), table = table,
                ruleSet = rules, seats = seatInfos(), lastSeq = lastSeq[seat],
            ),
        )
        // §17.3 "sem ver a distribuicao das cartas": a mao (SeatUpdate) so vai depois de confirmar os boosters.
        // O jogo ja acabado (§18.4, a sessao fica retida para o placar) nunca mostra o seletor: `endGame` ja resolveu
        // todo assento sem debito em andamento (confirma vazio); aqui so sobra um debito de um Seguir de verdade
        // ainda em voo (resolve sozinho quando a economia responder) ou o raro caso de nunca ter sido resolvido.
        if (stage == TableStage.GAME_OVER) {
            when {
                isBoosterConfirmed(seat) -> {
                    if (economy != null) send(seat, link, BoostersConfirmed(tableBoosters.of(seat).boosters))
                    send(seat, link, SeatUpdate(null, viewOf(seat, includeHistory = true)))
                }
                boosterPending[seat] -> Unit
                else -> confirmBoosters(seat, emptyList()) // manda tudo: BoostersConfirmed + fila guardada + vista fresca
            }
        } else if (isBoosterConfirmed(seat)) {
            if (economy != null) send(seat, link, BoostersConfirmed(tableBoosters.of(seat).boosters))
            send(seat, link, SeatUpdate(null, viewOf(seat, includeHistory = true)))
        } else {
            armBoosterDeadlineIfNeeded(seat)
            // §17.3/§17.4: a fila recomeca aqui (reconectar perde o que ficou de antes, como o resto da mesa perde ao
            // cair); a 1a vista guardada e a de AGORA, para as seguintes (enquanto ele escolhe) virem em sequencia
            // depois do Seguir, como "as animacoes de distribuicao e os descartes ja feitos".
            boosterCatchUp[seat] = mutableListOf(SeatUpdate(null, viewOf(seat, includeHistory = true)))
            send(seat, link, BoosterPrompt(table.skillBoostersAllowed, maxOf(0L, boosterDeadlineAt[seat]!! - now())))
        }
        broadcastSeats(seat, SeatChange.CONNECTED)
        return AttachResult.Ok(seat)
    }

    private fun onDetach(seat: Int, link: PlayerLink) {
        if (links[seat] !== link) return // conexao antiga ja substituida
        links[seat] = null
        link.close(1000, "Conexao encerrada")
        refreshPresence() // queda NAO para o relogio da vez (§18.4)
        if (slots[seat] is Slot.Human) broadcastSeats(seat, SeatChange.DISCONNECTED)
        destroyIfNobodyConnected() // §18.4 todos desconectados: percebido aqui, dentro do ator (sem corrida com o laco)
    }

    private fun onIncoming(seat: Int, link: PlayerLink, message: ClientMessage) {
        if (links[seat] !== link) return // mensagem de conexao que ja foi substituida
        if (slots[seat] !is Slot.Human) return
        when (message) {
            is PlayAction -> onPlay(seat, link, message)
            is Resign -> {
                if (message.seq <= lastSeq[seat]) return
                lastSeq[seat] = message.seq
                abandon(seat, AbandonReason.DESISTIU)
            }
            is SelectBoosters -> onSelectBoosters(seat, message)
        }
    }

    private fun onPlay(seat: Int, link: PlayerLink, msg: PlayAction) {
        // Idempotencia: duplicada ou atrasada e descartada sem efeito e sem contar como recusa.
        if (msg.seq <= lastSeq[seat]) return
        lastSeq[seat] = msg.seq

        fun reject(reason: RejectReason, text: String, ruleError: String? = null) {
            refusals[seat]++
            log.info("Jogada recusada table={} seat={} reason={} total={}", tableId, seat, reason, refusals[seat])
            send(seat, link, Rejected(msg.seq, reason, text, refusals[seat], ruleError))
        }

        if (stage != TableStage.PLAYING || match.currentRound.phase == Phase.FINISHED) {
            return reject(RejectReason.NOT_PLAYING, "Não há partida em andamento agora.")
        }
        // §17.3: nunca aplica uma jogada de quem ainda nao confirmou os boosters (defesa: o cliente nunca tem `legal`
        // antes de confirmar, mas o servidor nao confia nisso).
        if (!isBoosterConfirmed(seat)) return reject(RejectReason.BOOSTERS_PENDING, "Confirme os boosters antes de jogar.")
        val round = match.currentRound
        if (round.currentSeat.index != seat) return reject(RejectReason.NOT_YOUR_TURN, "Não é a sua vez.")
        if (msg.stateVersion != version) {
            return reject(RejectReason.STALE_STATE, "A mesa mudou; use a vista mais recente.")
        }
        val legal = RoundEngine.legalActions(round, Seat(seat), rules)
        if (msg.action !in legal) {
            val why = (RoundEngine.validate(round, Seat(seat), msg.action, rules) as? RuleResult.Failure)?.error
            return reject(RejectReason.ILLEGAL, "Jogada não permitida agora" + (why?.let { " ($it)" } ?: "") + ".", (why as? Enum<*>)?.name)
        }
        applyAction(Seat(seat), msg.action)
    }

    // ------------------------------------------------------------------------------------------------ boosters (§17.3/§18.3)

    /** Sem economia configurada, o seletor nem existe: todo assento vale como "confirmado" (sem boosters, como antes desta fase). */
    private fun isBoosterConfirmed(seat: Int): Boolean = economy == null || tableBoosters.of(seat).round != 0

    private fun armBoosterDeadlineIfNeeded(seat: Int) {
        if (isBoosterConfirmed(seat) || slots[seat] !is Slot.Human) return
        if (boosterDeadlineAt[seat] == null) boosterDeadlineAt[seat] = now() + boosterDeadlineMs
    }

    /**
     * Tira do que o cliente mandou: ids fora do catalogo, b3 (mesas online nao retransmitem reacoes ainda, telas.md
     * 3.19 Online), boosters de skill se a mesa nao os permite (§17.9) e o excedente de [Loadout.MAX]. Nunca confia no
     * cliente: a economia confere de novo o estoque antes de debitar.
     */
    private fun sanitizeBoosterSelection(ids: List<String>): List<String> {
        val catalogIds = BoosterCatalog.DEFAULT.map { it.id }.toSet()
        return ids.filter { it in catalogIds }
            .filterNot { it == BoosterCatalog.REACTIONS }
            .filter { table.skillBoostersAllowed || !BoosterCatalog.isSkill(it) }
            .distinct()
            .take(Loadout.MAX)
    }

    /**
     * §17.3: rascunho (so grava, §17.3 "vale o que estiver selecionado na tela") ou Seguir (debita). Nunca confia no
     * cliente: sanitiza de novo antes de gravar ou debitar, mesmo que o rascunho ja tivesse vindo filtrado.
     */
    private fun onSelectBoosters(seat: Int, msg: SelectBoosters) {
        if (economy == null) return
        if (isBoosterConfirmed(seat)) return
        if (slots[seat] !is Slot.Human) return
        val frozen = boosterFrozen[seat]
        if (frozen != null) {
            // Ja tentou confirmar uma vez (§17.3): a selecao esta travada, nunca troca (o debito pode ja ter sido
            // aplicado sem a resposta chegar). So vale tentar de novo a MESMA lista, com a MESMA chave.
            if (msg.final && !boosterPending[seat]) beginBoosterConfirm(seat, frozen)
            return
        }
        val sanitized = sanitizeBoosterSelection(msg.boosters)
        boosterDraft[seat] = sanitized
        if (!msg.final || boosterPending[seat]) return
        boosterFrozen[seat] = sanitized
        beginBoosterConfirm(seat, sanitized)
    }

    /** Debita [sanitized] (ou confirma direto, se vazio) de forma assincrona; nunca bloqueia o ator. */
    private fun beginBoosterConfirm(seat: Int, sanitized: List<String>) {
        // §17.3: nunca comeca um debito NOVO depois do jogo acabar (o assento abandonou ou o jogo terminou por
        // outro motivo enquanto este assento ainda escolhia os boosters) - resolve ja, sem boosters, em vez de
        // deixar quem chamou (ex.: um Seguir manual) sem resposta nenhuma. Um debito ja em andamento de uma
        // confirmacao de verdade (Seguir antes do fim) continua e completa normalmente: desistir nao devolve o
        // que ja foi debitado (§17.3), mas tambem nunca inicia uma cobranca nova para um jogo que ja acabou.
        if (seat in forfeited) return
        if (stage == TableStage.GAME_OVER) {
            confirmBoosters(seat, emptyList())
            return
        }
        if (sanitized.isEmpty()) {
            confirmBoosters(seat, emptyList())
            return
        }
        val human = slots[seat] as? Slot.Human ?: return
        val eco = economy ?: return
        boosterPending[seat] = true
        val tid = boosterMatchId
        val guestId = human.guestId
        scope.launch {
            val applied = try {
                eco.debitBoosters(tid, seat, guestId, sanitized)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Debito de boosters falhou table={} seat={}: {}", tid, seat, e.toString())
                commands.trySend(Cmd.BoosterFailed(seat))
                return@launch
            }
            commands.trySend(Cmd.BoosterResolved(seat, applied.toList()))
        }
    }

    /** §17.3: confirma (ja debitados) os boosters do assento; so chamada apos a economia responder ou por tempo esgotado. */
    private fun confirmBoosters(seat: Int, applied: List<String>) {
        if (isBoosterConfirmed(seat)) return
        tableBoosters = tableBoosters.confirm(seat, applied, round = 1)
        boosterPending[seat] = false
        boosterDeadlineAt[seat] = null
        // O relogio normal da vez (§18.4) comeca do zero agora que a mao foi revelada (nao enquanto esperava o seletor).
        if (turnSeat == seat) turnStartedAt = now()
        val catchUp = boosterCatchUp[seat]
        boosterCatchUp[seat] = mutableListOf()
        links[seat]?.let { link ->
            send(seat, link, BoostersConfirmed(applied))
            // §17.3/§17.4: "as animacoes de distribuicao e os descartes ja feitos" - a fila inteira, em ordem, antes
            // da vista fresca de agora (a unica com o relogio da vez reiniciado).
            catchUp.forEach { send(seat, link, it) }
            send(seat, link, SeatUpdate(null, viewOf(seat, includeHistory = true)))
        }
        broadcastSeats(seat, SeatChange.BOOSTERS_CHOSEN)
    }

    /** Quanto tempo se passou desde o inicio da 1a partida (a sessao e criada ao iniciar o jogo). */
    private fun elapsedMs(): Long = now() - startedAt

    private fun recordAbandon(seat: Int, guestId: String, reason: AbandonReason): AbandonEvent {
        val elapsed = elapsedMs()
        val within = elapsed < config.abandonGraceMs // §18.4: primeiros 60 s do jogo (0 a 59,999 s)
        val event = AbandonEvent(
            seat = seat, guestId = guestId, reason = reason, round = match.roundNumber, atEpochMs = wallClock(),
            elapsedMs = elapsed, punicao = table.punicao, bet = table.bet, withinGrace = within,
            // §18.4 todos desconectados: a mesa é destruída e todos recebem a aposta de volta, a qualquer momento.
            betRefunded = table.punicao == Punicao.SEM_PUNICAO || within || reason == AbandonReason.TODOS_DESCONECTADOS,
        )
        abandonments += event
        log.info("Abandono table={} {}", tableId, event) // o toString nao traz o guestId
        return event
    }

    /**
     * Abandono voluntario ou por tempo (§13.1, §18.4): um robo assume o assento e o jogo segue, MAS se for um 1x1 ou nao
     * restar humano o jogo termina na hora (o outro lado vence, sem robo). Sem volta. Aposta/punicao: so registrado (fase 2).
     */
    private fun abandon(seat: Int, reason: AbandonReason) {
        val human = slots[seat] as? Slot.Human ?: return
        if (stage == TableStage.GAME_OVER) return
        recordAbandon(seat, human.guestId, reason)
        // §17.3: quem abandona sem ter confirmado os boosters nunca e debitado por isso (nada foi escolhido de
        // verdade); tambem evita tentativas do prazo nesse assento depois que ele ja saiu (ou virou robo).
        if (!isBoosterConfirmed(seat)) {
            boosterDeadlineAt[seat] = null
            boosterCatchUp[seat] = mutableListOf()
        }
        val link = links[seat]
        val text = if (reason == AbandonReason.TEMPO) {
            "O tempo da sua vez esgotou; você abandonou a mesa."
        } else {
            "Você desistiu."
        }
        // §18.4 "Nunca se joga sem humano": 1x1 (humano x humano ou humano x robo) ou o ultimo humano de uma mesa maior.
        val othersPlaying = slots.indices.any { it != seat && slots[it] is Slot.Human && it !in forfeited }
        links[seat] = null
        if (table.mode == TableMode.INDIVIDUAL || !othersPlaying) {
            // §13.1: o lado de quem abandonou perde e o outro vence; a partida em andamento nao e pontuada. Nenhum robo assume.
            val versus = if (slots.indices.any { it != seat && slots[it] is Slot.Human }) "O outro jogador venceu." else "O jogo terminou: você perdeu."
            forfeited += seat
            val loser = match.mode.sideOf(Seat(seat))
            forcedWinner = match.mode.sides.first { it != loser }
            stage = TableStage.GAME_OVER
            endedAt = now()
            syncTurn()
            version++
            link?.let {
                send(seat, it, YouResigned("$text $versus"))
                it.close(1000, "Abandono")
            }
            refreshPresence()
            broadcastSeats(seat, SeatChange.RESIGNED, reason)
            broadcastUpdate(null, includeHistory = true)
            endGame(GameEndReason.ABANDONO)
            scheduleAuto()
            return
        }
        val used = slots.filterIsInstance<Slot.Robot>().map { it.profile }.toSet()
        val profile = BotProfile.entries.firstOrNull { it !in used } ?: BotProfile.entries[seat % BotProfile.entries.size]
        slots[seat] = Slot.Robot(profile, table.robotLevel, human.guestId)
        bots[seat] = newBot(seat, profile, table.robotLevel)
        link?.let {
            send(seat, it, YouResigned("$text Um robô assumiu o seu lugar."))
            it.close(1000, "Abandono")
        }
        version++
        refreshPresence()
        broadcastSeats(seat, SeatChange.RESIGNED, reason)
        broadcastUpdate(null)
        scheduleAuto()
        destroyIfNobodyConnected() // sobraram so humanos desconectados
    }

    /** O anfitriao encerra a mesa em jogo (§18.4): abandono dele, sem robo; os demais recebem a aposta de volta. */
    private fun onClose(hostGuestId: String): CloseResult {
        val seat = humanSeats[hostGuestId] ?: return CloseResult.NotInMatch
        if (stage == TableStage.GAME_OVER) return CloseResult.AlreadyOver
        // Se o anfitriao ja tinha abandonado (um robo esta no lugar dele), nao ha um segundo abandono.
        if (slots[seat] is Slot.Human) recordAbandon(seat, hostGuestId, AbandonReason.ANFITRIAO_ENCERROU)
        links.forEachIndexed { i, l ->
            if (l != null) {
                send(i, l, TableClosed("O anfitrião encerrou a mesa."))
                l.close(1000, "Mesa encerrada")
                links[i] = null
            }
        }
        endGame(GameEndReason.ANFITRIAO_ENCERROU)
        finished = true
        return CloseResult.Closed
    }

    /**
     * §18.4/§18.7 (decisao de 03/11/2026): o OPERADOR encerra a mesa travada - NUNCA registra abandono de
     * ninguem (diferente de [onClose]: aqui nao ha um jogador "culpado"), a aposta de todos os humanos volta
     * ([settleBets] cuida disso sozinho: sem [AbandonEvent] e sem [GameEndReason.PONTUACAO]/[GameEndReason.ABANDONO],
     * o resultado padrao e devolver) e o aviso aos conectados e honesto.
     */
    private fun onCloseByOperator(reason: String?): CloseResult {
        if (stage == TableStage.GAME_OVER) return CloseResult.AlreadyOver
        links.forEachIndexed { i, l ->
            if (l != null) {
                send(i, l, TableClosed("A mesa foi encerrada pelo suporte."))
                l.close(1000, "Mesa encerrada pelo suporte")
                links[i] = null
            }
        }
        log.info("Mesa encerrada pelo operador table={} motivo={}", tableId, reason ?: "-")
        endGame(GameEndReason.ENCERRADO_PELO_OPERADOR)
        finished = true
        return CloseResult.Closed
    }

    /** Registra o fim UMA vez e avisa quem cuida disso (lobby), sem bloquear o ator. */
    private fun endGame(reason: GameEndReason) {
        if (end != null) return
        val e = MatchEnd(
            reason, wallClock(), elapsedMs(),
            othersRefunded = reason == GameEndReason.ANFITRIAO_ENCERROU || reason == GameEndReason.ENCERRADO_PELO_OPERADOR,
            abandonments = abandonments.toList(),
            winnerSide = if (reason == GameEndReason.PONTUACAO || reason == GameEndReason.ABANDONO) (forcedWinner ?: match.winner)?.index else null,
            seatSides = slots.indices.map { match.mode.sideOf(Seat(it)).index },
            humanSeats = humanSeats,
        )
        end = e
        // §17.3: ninguem fica esperando o seletor depois que o jogo acaba — nem o prazo do servidor, nem um debito
        // novo; resolve ja, sem boosters, mandando BoostersConfirmed + o fim de jogo (quem tem um debito em
        // andamento de um Seguir de verdade resolve normalmente quando a economia responder).
        for (i in 0 until seatCount) {
            if (slots[i] is Slot.Human && !isBoosterConfirmed(i) && !boosterPending[i]) confirmBoosters(i, emptyList())
        }
        log.info("Jogo encerrado table={} reason={} elapsedMs={}", tableId, reason, e.elapsedMs)
        try {
            onEnded(e)
        } catch (ex: Exception) {
            log.warn("Aviso de fim de jogo falhou table={}: {}", tableId, ex.toString())
        }
    }

    // ------------------------------------------------------------------------------------------------ jogo

    private fun applyAction(seat: Seat, action: Action) {
        val round = match.currentRound
        val event = PublicEvent.of(round.discardPile, seat, action)
        match = match.play(seat, action)
        turnEvents[seat.index] = if (event.startsTurn()) listOf(event) else turnEvents[seat.index] + event
        bots.forEach { it?.observe(event) }
        version++
        if (match.currentRound.phase == Phase.FINISHED) {
            match = match.finishRound() // §12 pontua; §13 verifica o vencedor
            stage = if (match.isOver) TableStage.GAME_OVER else TableStage.ROUND_OVER
            if (stage == TableStage.GAME_OVER) endedAt = now()
        }
        syncTurn()
        broadcastUpdate(event, includeHistory = stage != TableStage.PLAYING)
        if (stage == TableStage.GAME_OVER) {
            refreshPresence()
            endGame(GameEndReason.PONTUACAO)
        }
        scheduleAuto()
    }

    private fun nextRound() {
        match = match.startNextRound() // §4.1 o jogador seguinte inicia
        turnEvents.fill(emptyList())
        bots.forEach { it?.onNewRound() }
        stage = TableStage.PLAYING
        version++
        syncTurn()
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
        endGame(GameEndReason.ERRO)
        finished = true
    }

    // ------------------------------------------------------------------------------------------------ tempo

    private fun botDelay(): Long = config.botDelayMs // nunca ha jogo so de robos: o jogo termina quando o ultimo humano sai

    private fun scheduleAuto() {
        autoAt = when (stage) {
            TableStage.ROUND_OVER -> now() + config.roundPauseMs
            TableStage.GAME_OVER -> (endedAt ?: now()) + config.retainAfterEndMs
            TableStage.PLAYING ->
                if (slots[match.currentRound.currentSeat.index] is Slot.Robot) now() + botDelay() else null
        }
    }

    private fun waitMillis(): Long {
        val boosterWaits = boosterDeadlineAt.indices.mapNotNull { i -> boosterDeadlineAt[i]?.takeIf { !isBoosterConfirmed(i) }?.let { it - now() } }
        val waits = listOfNotNull(
            autoAt?.let { it - now() },
            turnDeadline()?.let { it - now() },
            emptySince?.let { it + presenceGraceMs() - now() },
        ) + boosterWaits
        return maxOf(0L, waits.minOrNull() ?: IDLE_POLL_MS)
    }

    private suspend fun autoStep() {
        val due = autoAt?.let { now() >= it } == true
        when {
            due && stage == TableStage.GAME_OVER -> finished = true
            due && stage == TableStage.ROUND_OVER -> nextRound()
            due && stage == TableStage.PLAYING -> botMove()
        }
    }

    /**
     * Prazos que correm sozinhos: o seletor de boosters (§17.3, antes do relogio normal), fim da vez de um humano
     * (§18.4) e a folga de "todos desconectados" (padrao 0).
     */
    private fun fireDueTimers() {
        if (finished) return
        for (seat in boosterDeadlineAt.indices) fireBoosterDeadline(seat)
        val deadline = turnDeadline()
        val seat = turnSeat
        if (deadline != null && seat != null && now() >= deadline) abandon(seat, AbandonReason.TEMPO)
        destroyIfNobodyConnected()
    }

    /**
     * §17.3: o prazo do SERVIDOR (nunca o do aparelho) decide quando o assento ainda nao confirmou. "Vale o que
     * estiver selecionado na tela": usa o ULTIMO [boosterDraft] recebido (rascunho ou tentativa), nunca confirma vazio
     * so porque o `final = true` nao chegou a tempo. Se o debito falhar (rede, economia fora do ar), tenta de novo com
     * recuo, ate [BOOSTER_MAX_DEADLINE_ATTEMPTS]; esgotadas as tentativas, confirma vazio (risco residual registrado
     * em `docs/seguranca-pendencias.md`: se a economia tiver aplicado o debito mas a resposta se perdeu em TODAS as
     * tentativas, o jogador fica sem os boosters que pagou). O reenvio MANUAL do jogador (`SelectBoosters` de novo
     * depois de um [BoosterConfirmFailed]) nao passa por este limite.
     */
    private fun fireBoosterDeadline(seat: Int) {
        val at = boosterDeadlineAt[seat] ?: return
        if (boosterPending[seat] || isBoosterConfirmed(seat) || now() < at) return
        // §17.3: o jogo ja acabou (ex.: o OUTRO lado abandonou enquanto este assento ainda escolhia) - nada a
        // tentar, nem a avisar: resolve ja, sem o log de "esgotou as tentativas" (nao e uma falha de rede real).
        if (stage == TableStage.GAME_OVER) {
            confirmBoosters(seat, emptyList())
            return
        }
        // §17.3: uma vez que o prazo tenta confirmar, a selecao tambem trava (mesma regra do Seguir manual) - nunca
        // troca por um rascunho novo que chegue depois de uma tentativa (que pode ja ter debitado sem a resposta vir).
        val toTry = boosterFrozen[seat] ?: boosterDraft[seat]
        if (toTry == null || boosterDeadlineAttempts[seat] >= BOOSTER_MAX_DEADLINE_ATTEMPTS) {
            if (toTry != null) log.warn("Prazo do seletor esgotou as tentativas table={} seat={}: segue sem boosters", tableId, seat)
            confirmBoosters(seat, emptyList())
            return
        }
        if (boosterFrozen[seat] == null) boosterFrozen[seat] = toTry
        boosterDeadlineAttempts[seat]++
        boosterDeadlineAt[seat] = now() + BOOSTER_RETRY_BACKOFF_MS // evita tentativa em loop se este debito tambem falhar
        beginBoosterConfirm(seat, toTry)
    }

    // --- relogio da vez (§18.4)

    private fun turnLimitMs(): Long? = when (table.tempoDeJogada) {
        TempoDeJogada.INDETERMINADO -> null
        TempoDeJogada.UM_MINUTO -> 60_000L
        TempoDeJogada.CINCO_MINUTOS -> 300_000L
        TempoDeJogada.QUINZE_MINUTOS -> 900_000L
    }

    /** A vez recomeca so quando muda o assento da vez ou comeca partida nova (comprar, baixar e descartar sao UMA vez). */
    private fun syncTurn() {
        if (stage != TableStage.PLAYING) {
            turnSeat = null
            return
        }
        val current = match.currentRound.currentSeat.index
        if (turnSeat != current) {
            turnSeat = current
            turnStartedAt = now()
        }
    }

    /**
     * Fim da vez do humano da vez; `null` sem tempo definido, entre partidas, na vez de robo, acabado, ou ainda
     * escolhendo boosters. §17.3: enquanto o assento nao confirmou os boosters MAS ja viu o seletor (um prazo foi
     * armado em [onAttach]), o relogio NORMAL nao conta — o prazo do seletor e quem limita a espera, nao o da mesa.
     * Um assento que nunca conectou (prazo nunca armado: o seletor nunca apareceu a ele) segue pela regra de sempre
     * ("a vez continua contando", §18.4): sem isso, um humano que nunca entra travaria a mesa para sempre numa mesa
     * com tempo definido, sem nenhum prazo correndo para destravar.
     */
    private fun turnDeadline(): Long? {
        val limit = turnLimitMs() ?: return null
        val seat = turnSeat ?: return null
        if (stage != TableStage.PLAYING || slots[seat] !is Slot.Human) return null
        if (!isBoosterConfirmed(seat) && boosterDeadlineAt[seat] != null) return null
        return turnStartedAt + limit
    }

    /** Folga entre "nenhum humano conectado" e a destruicao: antes da 1a conexao e o prazo de entrada, depois a folga tecnica. */
    private fun presenceGraceMs(): Long = if (everConnected) config.disconnectGraceMs else config.firstConnectMs

    /**
     * §18.4 "Todos desconectados": nenhum humano que ainda joga esta conectado (apos a 1a conexao, ou passado o prazo da
     * 1a conexao) = a mesa e destruida na hora: abandono registrado por assento (sem mexer em moedas), sem robo, sem volta.
     * Nao vale com o jogo acabado (a sessao fica retida para o placar). Roda dentro do ator.
     */
    private fun destroyIfNobodyConnected() {
        if (finished || end != null || stage == TableStage.GAME_OVER) return
        val since = emptySince ?: return
        if (now() - since < presenceGraceMs()) return
        log.info("Mesa destruida: nenhum humano conectado table={} firstConnection={}", tableId, everConnected)
        slots.forEachIndexed { i, s -> if (s is Slot.Human && i !in forfeited) recordAbandon(i, s.guestId, AbandonReason.TODOS_DESCONECTADOS) }
        links.forEachIndexed { i, l -> l?.close(1001, "Mesa destruida"); links[i] = null }
        endGame(GameEndReason.TODOS_DESCONECTADOS)
        finished = true
    }

    private var lastPresence: Pair<Set<String>, Set<String>>? = null

    private fun refreshPresence() {
        val humans = slots.indices.filter { slots[it] is Slot.Human && it !in forfeited }
        val anyConnected = humans.any { links[it] != null }
        emptySince = if (humans.isEmpty() || anyConnected || stage == TableStage.GAME_OVER) null else (emptySince ?: now())
        val connected = humans.filter { links[it] != null }.map { (slots[it] as Slot.Human).guestId }.toSet()
        val abandoned = slots.indices.mapNotNull { i ->
            when (val s = slots[i]) {
                is Slot.Robot -> s.replacedGuestId
                is Slot.Human -> if (i in forfeited) s.guestId else null
            }
        }.toSet()
        val presence = connected to abandoned
        if (presence != lastPresence) {
            lastPresence = presence
            try {
                onPresence(connected, abandoned)
            } catch (ex: Exception) {
                log.warn("Aviso de presenca falhou table={}: {}", tableId, ex.toString())
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
            is Slot.Human -> SeatInfo(
                i, SeatKind.HUMANO, s.name, connected = links[i] != null, resigned = i in forfeited, guestId = s.guestId,
                choosingBoosters = !isBoosterConfirmed(i),
            )
            is Slot.Robot -> SeatInfo(
                i, SeatKind.ROBO, robotName(s.profile), s.profile, s.level, connected = true,
                resigned = s.replacedGuestId != null,
            )
        }
    }

    private fun broadcastSeats(seat: Int, change: SeatChange, reason: AbandonReason? = null) {
        val msg = SeatsChanged(seatInfos(), seat, change, reason)
        links.forEachIndexed { i, l -> if (l != null && (i != seat || change != SeatChange.RESIGNED)) send(i, l, msg) }
    }

    /**
     * Cada humano recebe a vista do SEU assento (§18.3). Nunca o `Match`/`RoundState`. §17.3: quem ainda nao
     * confirmou os boosters nao recebe nada aqui — a mao so chega pelo [SeatUpdate] explicito de [confirmBoosters].
     */
    private fun broadcastUpdate(event: PublicEvent?, includeHistory: Boolean = false) {
        for (i in 0 until seatCount) {
            if (isBoosterConfirmed(i)) {
                links[i]?.let { send(i, it, SeatUpdate(event, viewOf(i, includeHistory))) }
            } else if (links[i] != null) {
                // §17.3/§17.4: nao perde a jogada, so adia (a fila e entregue inteira ao confirmar, confirmBoosters).
                val buf = boosterCatchUp[i]
                buf += SeatUpdate(event, viewOf(i, includeHistory))
                if (buf.size > BOOSTER_CATCHUP_CAP) buf.removeAt(0)
            }
        }
    }

    internal fun viewOf(seat: Int, includeHistory: Boolean): OnlineSeatView {
        val round = match.currentRound
        val pv = round.viewFor(Seat(seat), rules)
        val current = round.currentSeat
        return OnlineSeatView(
            version = version, roundNumber = match.roundNumber, stage = stage, totals = match.totals,
            winner = forcedWinner ?: match.winner, history = if (includeHistory) match.history else null,
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
            turn = turnDeadline()?.let { TurnClock(Seat(turnSeat!!), turnLimitMs()!!, maxOf(0L, it - now())) },
            endReason = if (stage == TableStage.GAME_OVER) {
                if (forcedWinner != null) GameEndReason.ABANDONO else GameEndReason.PONTUACAO
            } else {
                null
            },
        )
    }

    companion object {
        /** Codigo de fechamento proprio: a conexao foi substituida por outra do mesmo jogador. */
        const val CLOSE_REPLACED = 4001

        /** Espera maxima do laco quando nada esta agendado (so reavalia; nao ha prazo correndo). */
        private const val IDLE_POLL_MS = 60_000L

        /** §17.3: quantas vezes o PRAZO do servidor tenta debitar o rascunho antes de desistir e confirmar vazio. */
        private const val BOOSTER_MAX_DEADLINE_ATTEMPTS = 3

        /** §17.3: espera entre uma tentativa do prazo e a proxima, se a anterior falhou (evita martelar a economia). */
        private const val BOOSTER_RETRY_BACKOFF_MS = 5_000L

        /** §17.3/§17.4: tamanho maximo da fila de vistas de quem ainda nao confirmou (a janela e de uns 45 s no maximo). */
        private const val BOOSTER_CATCHUP_CAP = 500

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
