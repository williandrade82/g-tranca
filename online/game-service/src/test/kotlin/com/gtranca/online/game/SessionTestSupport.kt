package com.gtranca.online.game

import com.gtranca.ai.BotPlayer
import com.gtranca.ai.Difficulty
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.RuleSet
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.BotProfile
import com.gtranca.online.contracts.GameError
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.PlayAction
import com.gtranca.online.contracts.Rejected
import com.gtranca.online.contracts.Replaced
import com.gtranca.online.contracts.Resign
import com.gtranca.online.contracts.SeatSpec
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.ServerMessage
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.YouResigned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

val FAST = GameConfig(internalSecret = TEST_SECRET, botDelayMs = 0, roundPauseMs = 0, retainAfterEndMs = 60_000)

fun humans2(): List<SeatSpec> = listOf(SeatSpec.Human("ga", "Ana"), SeatSpec.Human("gb", "Beto"))

fun duplasSeats(): List<SeatSpec> = listOf(
    SeatSpec.Human("ga", "Ana"),
    SeatSpec.Robot(BotProfile.JOVEM, BotLevel.FACIL),
    SeatSpec.Human("gb", "Beto"),
    SeatSpec.Robot(BotProfile.SOBRIO, BotLevel.FACIL),
)

fun rulesOf(mode: TableMode, target: Int = 300) = TableRules(mode = mode, rulesJson = "{}", targetScore = target)

/** Sessao em andamento num escopo de teste; [stop] encerra. */
class RunningSession(val session: MatchSession, private val scope: CoroutineScope, private val job: Job) {
    fun stop() {
        session.shutdown()
        job.cancel()
    }
}

fun startSession(
    mode: TableMode,
    seats: List<SeatSpec>,
    target: Int = 300,
    seed: Long = 42,
    config: GameConfig = FAST,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
): RunningSession {
    val s = MatchSession("ABC234", rulesOf(mode, target), seats, seed, config)
    val job = scope.launch { s.run() }
    return RunningSession(s, scope, job)
}

/** Cliente simulado: joga como um humano, sempre pelas `legal` da vista recebida (EasyBot decide). */
open class ClientBrain(val guestId: String, seed: Long = guestId.hashCode().toLong()) {
    var seat = -1
    var seq = 0L
    var view: OnlineSeatView? = null
    var welcome: Welcome? = null
    val received = mutableListOf<ServerMessage>()
    val rejections = mutableListOf<Rejected>()
    val errors = mutableListOf<GameError>()
    var replaced = false
    var resigned = false
    private val bot: BotPlayer = createBot(Difficulty.FACIL, Random(seed))
    private var lastRound = 0
    var actionsSent = 0

    /** Registra uma mensagem. Devolve a acao que o jogador faria (ou nulo). */
    fun absorb(m: ServerMessage): Action? {
        received += m
        when (m) {
            is Welcome -> {
                welcome = m
                seat = m.you.index
                seq = maxOf(seq, m.lastSeq)
            }
            is SeatUpdate -> {
                view = m.view
                if (m.view.stage == TableStage.PLAYING && m.view.roundNumber != lastRound) {
                    bot.onNewRound()
                    lastRound = m.view.roundNumber
                }
                m.event?.let(bot::observe)
                if (m.view.stage == TableStage.PLAYING && m.view.legal.isNotEmpty()) {
                    return bot.chooseAction(m.view.toPlayerView(welcome!!.ruleSet), m.view.legal)
                }
            }
            is Rejected -> rejections += m
            is GameError -> errors += m
            is Replaced -> replaced = true
            is YouResigned -> resigned = true
            else -> Unit
        }
        return null
    }

}

/** Cliente em memoria: fala direto com a sessao (sem WebSocket). */
class TestPlayer(val session: MatchSession, guestId: String, seed: Long = guestId.hashCode().toLong()) : ClientBrain(guestId, seed) {
    var link = PlayerLink()

    suspend fun attach(): AttachResult {
        link = PlayerLink()
        val r = session.attach(guestId, link)
        if (r is AttachResult.Ok) seat = r.seat
        return r
    }

    suspend fun detach() = session.detach(seat, link)

    suspend fun send(action: Action, stateVersion: Long = view!!.version, withSeq: Long = ++seq) =
        session.submit(seat, link, PlayAction(withSeq, stateVersion, action))

    suspend fun resign() = session.submit(seat, link, Resign(++seq))

    /** Le o que ja chegou (sem esperar). */
    fun drain(): List<ServerMessage> {
        val out = mutableListOf<ServerMessage>()
        while (true) {
            val m = link.messages.tryReceive().getOrNull() ?: break
            absorb(m)
            out += m
        }
        return out
    }

    /** Joga ate o fim do jogo (ou ate o canal fechar). */
    suspend fun playToEnd(timeoutMs: Long = 120_000) {
        withTimeout(timeoutMs) {
            for (m in link.messages) {
                val act = absorb(m)
                if (act != null) {
                    actionsSent++
                    send(act)
                }
                if (view?.stage == TableStage.GAME_OVER) return@withTimeout
            }
        }
    }
}
