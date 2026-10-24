package com.gtranca.multiplayer

import com.gtranca.engine.Action
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.RuleError
import com.gtranca.game.GameSnapshot
import com.gtranca.game.Stage
import com.gtranca.game.TableDriver
import com.gtranca.ui.game.GameViewModel
import com.gtranca.ui.game.TableAnimations
import com.gtranca.net.RejectReason
import com.gtranca.net.SeatView
import com.gtranca.net.TableRules
import com.gtranca.net.TableStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A mesa multijogador para a tela do jogo (§16.6): a vista do assento deste aparelho, seja ele o anfitrião (o
 * controlador) ou um convidado (a [SeatView] recebida), e o envio das jogadas. Segue o papel atual da [table]: na
 * sucessão (§16.10) a tela continua a mesma. Nunca valida regra: as jogadas são as legais recebidas.
 *
 * Ritmo próprio (§16.6): as jogadas dos outros chegam em rajada (o anfitrião não espera ninguém). Cada vista entra numa
 * fila ([SnapshotPacer]) e é mostrada uma de cada vez, em ordem, com a pausa antes de cada jogada de outro assento e as
 * mesmas esperas de encenação do jogo local (distribuição, trocas de 3 vermelho). A vez deste jogador só aparece depois
 * de a fila chegar até ela.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerDriver private constructor(
    private val table: MultiplayerTable,
    private val scope: CoroutineScope,
    paceMillis: () -> Long,
    dealWaitMillis: (() -> Long)?,
    presentationWaitMillis: (() -> Long)?,
    initial: GameSnapshot,
) : TableDriver {

    /** Última vista mostrada (como chegou, antes de [answered] e da desistência). */
    @Volatile
    private var shown: GameSnapshot = initial

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

    private val lock = Any()

    private val pacer = SnapshotPacer(
        paceMillis = paceMillis,
        dealWaitMillis = dealWaitMillis ?: { if (paceMillis() > 0) (DEAL_MILLIS * dealDelayScale).toLong() + WAIT_GRACE_MILLIS else 0 },
        presentationWaitMillis = presentationWaitMillis ?: { if (paceMillis() > 0) PRESENTATION_WAIT_MILLIS else 0 },
        publish = { snapshot ->
            shown = snapshot
            refresh()
        },
    )

    /** A vista na tela: a última mostrada, sem as jogadas do pedido já respondido e, se desistiu, o fim de jogo. */
    private fun refresh() {
        synchronized(lock) {
            var snapshot = shown
            val done = answered.value
            // Só o convidado responde por rede; no anfitrião (inclusive depois de assumir, §16.10) vale o controlador.
            if (table.role.value is MpRole.Guesting && done != 0L && snapshot.humanRequestId == done && snapshot.humanLegal.isNotEmpty()) snapshot = snapshot.copy(humanLegal = emptyList())
            if (table.resigned.value) snapshot = asResigned(snapshot)
            if (snapshot != _state.value) _state.value = snapshot
        }
    }

    init {
        scope.launch { pacer.run(initial) }
        scope.launch { for (snapshot in table.feed) pacer.offer(snapshot) }
        scope.launch { table.resigned.collect { refresh() } }
        scope.launch { answered.collect { refresh() } }
        // §16.10 na troca de papel o pedido respondido era de outra numeração (do anfitrião anterior).
        scope.launch { table.role.collect { answered.value = 0; refresh() } }
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

    /** §16.9 quem desistiu vai ao fim de jogo como derrota por desistência; o jogo dos outros segue. */
    private fun asResigned(snapshot: GameSnapshot): GameSnapshot = snapshot.copy(
        stage = Stage.GAME_OVER,
        resigned = true,
        winner = snapshot.view.mode.sides.first { it != snapshot.viewerSide },
        humanLegal = emptyList(),
        humanRequestId = 0,
    )

    override fun awaitingHuman(): Boolean {
        // A vez vale só quando a fila chegou até ela (a vista na tela), nunca pela última recebida.
        val snapshot = _state.value
        if (snapshot.humanRequestId <= 0 || snapshot.humanLegal.isEmpty()) return false
        return when (val role = table.role.value) {
            is MpRole.Hosting -> role.host?.let { it.controller.stateOf(it.hostSeat).value.humanRequestId == snapshot.humanRequestId } ?: false
            is MpRole.Guesting -> snapshot.humanRequestId != answered.value
            null -> false
        }
    }

    override fun submit(action: Action, requestId: Long): Boolean = when (val role = table.role.value) {
        is MpRole.Hosting -> role.host?.let { it.controller.submit(it.hostSeat, action, requestId) } ?: false
        is MpRole.Guesting -> {
            if (requestId == 0L || requestId == answered.value) {
                false
            } else {
                answered.value = requestId
                refresh()
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

    override fun presentationDone(roundNumber: Int, logSize: Int) = pacer.presentationDone(roundNumber, logSize)

    override fun dealAnimationDone(roundNumber: Int) = pacer.dealAnimationDone(roundNumber)

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
        /** Duração base da distribuição na tela (a mesma conta do jogo local, 4 assentos). */
        private const val DEAL_MILLIS: Long = (4 * 11 + 2) * TableAnimations.DEAL_STAGGER_MILLIS + GameViewModel.DEFAULT_ANIMATION_MILLIS

        /** Folga das esperas pela tela (rede de segurança, como no jogo local). */
        private const val WAIT_GRACE_MILLIS: Long = 4_000

        /** Limite da espera pela encenação das trocas de 3 vermelho (a própria troca espera o toque do jogador). */
        private const val PRESENTATION_WAIT_MILLIS: Long = 20_000

        /**
         * Espera a primeira vista do assento deste aparelho e cria o condutor da tela. [paceMillis] é a pausa antes de cada
         * jogada de outro assento; as esperas pela tela ([dealWaitMillis], [presentationWaitMillis]) têm o padrão do jogo
         * local quando há pausa, e nenhuma sem pausa.
         */
        suspend fun create(
            table: MultiplayerTable,
            scope: CoroutineScope,
            dealWaitMillis: (() -> Long)? = null,
            presentationWaitMillis: (() -> Long)? = null,
            paceMillis: () -> Long,
        ): MultiplayerDriver {
            // A primeira vista da fila (a distribuição, se o jogo acabou de começar; no reingresso, a atual).
            val first = table.feed.receive()
            return MultiplayerDriver(table, scope, paceMillis, dealWaitMillis, presentationWaitMillis, first)
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
    // Uma lista por assento sempre (a tela indexa por assento): uma vista sem elas não pode fechar o app.
    turnEvents = List(mode.seatCount) { turnEvents.getOrNull(it).orEmpty() },
    humanRequestId = requestId,
    turnRedThrees = List(mode.seatCount) { turnRedThrees.getOrNull(it).orEmpty() },
    turnRedThreeAfter = List(mode.seatCount) { turnRedThreeAfter.getOrNull(it).orEmpty() },
)
