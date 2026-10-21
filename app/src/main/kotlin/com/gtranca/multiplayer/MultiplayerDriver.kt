package com.gtranca.multiplayer

import com.gtranca.engine.Action
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.RuleError
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.TableDriver
import com.gtranca.net.GuestSession
import com.gtranca.net.RejectReason
import com.gtranca.net.SeatView
import com.gtranca.net.TableRules
import com.gtranca.net.TableStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A mesa multijogador para a tela do jogo (§16.6): a vista do assento deste aparelho, seja ele o anfitrião (o
 * controlador) ou um convidado (a [SeatView] recebida), e o envio das jogadas. Segue o papel atual da [table]: na
 * sucessão (§16.10) a tela continua a mesma. Nunca valida regra: as jogadas são as legais recebidas.
 *
 * Ritmo próprio (§16.6): as jogadas dos outros chegam em rajada (o anfitrião não espera ninguém); aqui cada uma é
 * mostrada com [paceMillis] de intervalo, para o jogador acompanhar na velocidade dele.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerDriver private constructor(
    private val table: MultiplayerTable,
    private val scope: CoroutineScope,
    private val paceMillis: () -> Long,
    initial: GameSnapshot,
) : TableDriver {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<GameSnapshot> = _state.asStateFlow()

    /**
     * Pedido já respondido pelo convidado (não reenviar por toque duplo). Uma recusa "não é sua vez" desse pedido (logo
     * depois da troca de anfitrião, §16.10) o libera: quando a vista chega com o mesmo número, o jogador joga de novo.
     */
    private val answered = MutableStateFlow(0L)

    private val _rejections = MutableSharedFlow<RuleError?>(extraBufferCapacity = 16)
    override val rejections: Flow<RuleError?> = _rejections

    override var dealDelayScale: Double = 1.0

    init {
        val source = table.role.flatMapLatest { role ->
            when (role) {
                is MpRole.Hosting -> role.host?.controller?.state ?: emptyFlow()
                is MpRole.Guesting -> guestSnapshots(role.session)
                null -> emptyFlow()
            }
        }
        val withResign = combine(source, table.resigned) { snapshot, resigned -> if (resigned) asResigned(snapshot) else snapshot }
        scope.launch {
            withResign.buffer(Channel.UNLIMITED).collect { next ->
                val previous = _state.value
                if (othersPlayed(previous, next)) paceMillis().takeIf { it > 0 }?.let { delay(it) }
                _state.value = next
            }
        }
        table.role.flatMapLatest { role ->
            (role as? MpRole.Guesting)?.session?.rejections ?: emptyFlow()
        }.onEach { rejection ->
            if (rejection.requestId != answered.value) return@onEach
            when (rejection.reason) {
                // §16.10 o pedido chegou ao anfitrião novo antes de ele abri-lo: libera o mesmo pedido.
                RejectReason.NOT_YOUR_TURN, RejectReason.NOT_PLAYING -> answered.value = 0
                RejectReason.ILLEGAL -> {
                    answered.value = 0
                    _rejections.emit(ruleErrorOf(rejection.ruleError))
                }
                RejectReason.STALE_REQUEST -> Unit
            }
        }.launchIn(scope)
    }

    private fun guestSnapshots(guest: GuestSession): Flow<GameSnapshot> =
        combine(guest.view.filterNotNull(), guest.rules.filterNotNull(), answered) { view, rules, done -> view.toSnapshot(rules, done) }

    /** §16.9 quem desistiu vai ao fim de jogo como derrota por desistência; o jogo dos outros segue. */
    private fun asResigned(snapshot: GameSnapshot): GameSnapshot = snapshot.copy(
        stage = Stage.GAME_OVER,
        resigned = true,
        winner = snapshot.view.mode.sides.first { it != snapshot.viewerSide },
        humanLegal = emptyList(),
        humanRequestId = 0,
    )

    /** Outro assento jogou desde a vista anterior (na mesma partida): pausa para acompanhar. */
    private fun othersPlayed(previous: GameSnapshot, next: GameSnapshot): Boolean {
        if (previous.stage != Stage.PLAYING || next.stage != Stage.PLAYING || previous.roundNumber != next.roundNumber) return false
        val viewer = next.viewerSeat.index
        return next.turnEvents.indices.any { it != viewer && next.turnEvents[it] != previous.turnEvents.getOrNull(it) }
    }

    override fun awaitingHuman(): Boolean = when (val role = table.role.value) {
        is MpRole.Hosting -> role.host?.let { it.controller.stateOf(it.hostSeat).value.humanRequestId > 0 } ?: false
        is MpRole.Guesting -> role.session.view.value?.let { it.requestId > 0 && it.requestId != answered.value && it.legal.isNotEmpty() } ?: false
        null -> false
    }

    override fun submit(action: Action, requestId: Long): Boolean = when (val role = table.role.value) {
        is MpRole.Hosting -> role.host?.let { it.controller.submit(it.hostSeat, action, requestId) } ?: false
        is MpRole.Guesting -> {
            if (requestId == 0L || requestId == answered.value) {
                false
            } else {
                answered.value = requestId
                scope.launch { role.session.propose(action, requestId) }
                true
            }
        }
        null -> false
    }

    override fun explain(action: Action, requestId: Long): RuleError? = when (val role = table.role.value) {
        is MpRole.Hosting -> role.host?.let { it.controller.explain(it.hostSeat, action, requestId) }
        else -> null
    }

    override fun continueToNextRound() {
        when (val role = table.role.value) {
            is MpRole.Hosting -> role.host?.continueRound()
            is MpRole.Guesting -> scope.launch { role.session.advance() }
            null -> Unit
        }
    }

    override fun resign() {
        scope.launch { table.resign() }
    }

    override suspend fun run() = awaitCancellation()

    companion object {
        /** Espera a primeira vista do assento deste aparelho e cria o condutor da tela. */
        suspend fun create(table: MultiplayerTable, scope: CoroutineScope, paceMillis: () -> Long): MultiplayerDriver {
            val first = table.role.flatMapLatest { role ->
                when (role) {
                    is MpRole.Hosting -> role.host?.controller?.state ?: emptyFlow()
                    is MpRole.Guesting -> combine(role.session.view.filterNotNull(), role.session.rules.filterNotNull()) { v, r -> v.toSnapshot(r, 0) }
                    null -> emptyFlow()
                }
            }.first()
            return MultiplayerDriver(table, scope, paceMillis, first)
        }

        /** Nome do erro do motor recebido na recusa → o erro (para o texto em pt-BR); desconhecido = `null`. */
        fun ruleErrorOf(name: String?): RuleError? = name?.let { n ->
            ActionError.entries.firstOrNull { it.name == n } ?: MeldError.entries.firstOrNull { it.name == n }
        }
    }
}

/** §16.4 a vista recebida no formato da tela (a mesma do jogo local); [answered] = pedido já respondido. */
fun SeatView.toSnapshot(rules: TableRules, answered: Long): GameSnapshot = GameSnapshot(
    config = MultiplayerTable.configOf(rules),
    viewerSeat = seat,
    view = toPlayerView(rules.rules),
    roundNumber = roundNumber,
    totals = totals,
    history = history.orEmpty(),
    stage = when (stage) {
        TableStage.PLAYING -> Stage.PLAYING
        TableStage.ROUND_OVER -> Stage.ROUND_OVER
        TableStage.GAME_OVER -> Stage.GAME_OVER
    },
    winner = winner,
    humanLegal = if (requestId != 0L && requestId == answered) emptyList() else legal,
    thinkingSeat = thinkingSeat,
    turnEvents = turnEvents,
    humanRequestId = requestId,
    turnRedThrees = turnRedThrees,
)
