package com.gtranca.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.BotPlayer
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Seat
import com.gtranca.game.CustomHand
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.HandOrder
import com.gtranca.game.HandSort
import com.gtranca.game.HumanPlayer
import com.gtranca.game.HumanTurnResolver
import com.gtranca.game.PlayIntent
import com.gtranca.game.LogRedThreeSource
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.RedThreeSwapSource
import com.gtranca.game.Resolution
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import com.gtranca.game.WriteQueue
import com.gtranca.game.toSaved
import com.gtranca.game.savedGameOf
import com.gtranca.game.SaveSnapshot
import com.gtranca.game.RestoredGame
import com.gtranca.game.GamePersistence
import com.gtranca.data.GameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Mensagem para o humano (o texto em pt-BR é escolhido na camada de UI). */
sealed interface UiMessage {
    /** Jogada recusada pelo motor; [error] `null` = motivo desconhecido. */
    data class Rejected(val error: RuleError?) : UiMessage

    /** Para descartar, selecione exatamente uma carta. */
    data object SelectOneCardToDiscard : UiMessage
}

/**
 * Encenação de 3 vermelho(s) que entraram na mesa do lado do humano (§6.5): por ação dele ou na distribuição. O
 * motor já os baixou e repôs; até o humano tocar em "Baixar", a mesa não mostra esses 3 vermelhos e a mão não
 * mostra as cartas [hiddenHandCards] (reposição/morto) recebidas na mesma ação.
 */
data class RedThreeReveal(val notice: RedThreeNotice, val hiddenHandCards: Set<Card>)

/** Telas depois de uma partida: anúncio do resultado, pontos (§12), anúncio do fim do jogo e tela final (§13). */
enum class EndScreen { ANNOUNCE_ROUND, ROUND_POINTS, ANNOUNCE_GAME, FINAL }

/**
 * Estado da tela de jogo. Derivado do [GameSnapshot] (vista do humano) e do estado local da tela; os
 * controles e o destaque vêm de `legalActions` ([GameSnapshot.humanLegal]).
 *
 * @property hand mão na ordem escolhida, sem as cartas ainda escondidas por uma encenação de 3 vermelho.
 * @property customHand blocos da ordem Personalizada (coluna de 3 pretos e coringas + resto), ou `null`.
 * @property highlighted cartas físicas que participam de jogada (por contagem de cópias).
 * @property newCards cartas que acabaram de entrar na mão (compra, reposição, morto, lixo), até a próxima jogada.
 * @property redThrees 3 vermelhos na mesa de cada lado, sem os que ainda estão sendo encenados.
 * @property reveal troca de 3 vermelho do próprio humano sendo encenada (bloqueia a mesa até "Baixar 3 vermelho").
 * @property banner troca de 3 vermelho de outro assento sendo mostrada (some sozinha após a duração da animação).
 */
data class GameUiState(
    val snapshot: GameSnapshot,
    val hand: List<Card>,
    val customHand: CustomHand?,
    val selected: List<Card>,
    val highlighted: Set<Card>,
    val newCards: Set<Card>,
    val sort: HandSort,
    val redThrees: List<List<Card>>,
    val reveal: RedThreeReveal?,
    /** §6.5 há troca de 3 vermelho do humano ainda não confirmada ("Baixar 3 vermelho"): a mesa fica bloqueada. */
    val ownSwapPending: Boolean,
    val banner: RedThreeNotice?,
    val planChoice: List<Action.TakeDiscardPile>?,
    val confirmDecline: Boolean,
    val confirmResign: Boolean,
    val message: UiMessage?,
    val endScreen: EndScreen?,
    /** Cartas voando agora (animações da mesa); vazio com animações desligadas. */
    val flights: List<CardFlight> = emptyList(),
    /** §7 conjuntos que acabaram de virar canastra (destaque curto). */
    val canastaFlashes: List<CanastaFlash> = emptyList(),
    /**
     * Duração efetiva de cada voo, já multiplicada pela escala de animação do sistema (0 = sem animação: com
     * "remover animações", nenhum voo é gerado).
     */
    val animationMillis: Long = 0,
) {
    val isHumanTurn: Boolean get() = snapshot.isHumanTurn && !ownSwapPending
    private val legal: List<Action> get() = if (ownSwapPending) emptyList() else snapshot.humanLegal
    val awaitingDraw: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW
    val playing: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.PLAYING
    val canDraw: Boolean get() = Action.DrawFromStock in legal

    /** §5.3/§5.5 só com algum plano de pegar o lixo em `legalActions`. */
    val canTakeDiscardPile: Boolean get() = legal.any { it is Action.TakeDiscardPile }
    val canDecline: Boolean get() = Action.DeclineDraw in legal
    val canMeld: Boolean get() = playing && selected.isNotEmpty()
    val canDiscard: Boolean get() = playing && selected.size == 1

    /** A carta selecionada pode ser descartada agora (há um `Discard` dela em `legalActions`). */
    val canDiscardSelected: Boolean
        get() = canDiscard && legal.any { it is Action.Discard && it.card.cardClass == selected.single().cardClass }
}

/**
 * ViewModel da partida: cria o [GameController] (humano no assento 0, bots da dificuldade escolhida),
 * roda o laço no [viewModelScope] e traduz os gestos da tela em ações de `legalActions`
 * ([HumanTurnResolver]). Cada snapshot do controlador é absorvido num único passo ([absorb]), que deriva as
 * cartas novas e as encenações de 3 vermelho de forma consistente com o que a tela mostra.
 */
class GameViewModel(
    config: GameConfig,
    gameSeed: Long = Random.nextLong(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    botDelayMillis: Long = GameController.DEFAULT_BOT_DELAY_MILLIS,
    botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
    private val redThreeSource: RedThreeSwapSource = LogRedThreeSource,
    /** Duração da animação automática de troca de 3 vermelho de outro assento (0 nos testes). */
    private val swapAnimationMillis: Long = DEFAULT_SWAP_ANIMATION_MILLIS,
    /**
     * Duração das animações da mesa (compra, descarte, lixo, baixas, 3 vermelho, canastra); 0 desliga (testes).
     * São só da interface: o laço do controlador não espera por elas.
     */
    private val animationMillis: Long = DEFAULT_ANIMATION_MILLIS,
    /** Jogo salvo a retomar (então [config] e [gameSeed] devem ser os dele), ou `null` para um jogo novo. */
    restored: RestoredGame? = null,
    /** Identificador do jogo (estatísticas contam cada jogo uma vez); o do jogo salvo, ao retomar. */
    private val gameId: String = UUID.randomUUID().toString(),
    /** Onde gravar o jogo, a estatística e as preferências; `null` = não grava (testes). */
    private val persistence: GamePersistence? = null,
    /** Ordem da mão inicial (a preferida do jogador). */
    initialSort: HandSort = HandSort.CUSTOM,
    /** Fila das gravações (fora da thread principal e do laço do jogo; sobrevive ao fim desta tela). */
    private val writes: WriteQueue = WriteQueue.app,
) : ViewModel(), TableEvents {

    private val config = config
    private val gameSeed = gameSeed

    /** O fim do jogo já foi para a fila: nenhuma gravação depois dele (recriaria o salvo apagado). */
    private val finishSent = AtomicBoolean(false)

    /** Grava a foto do jogo (na fila, em ordem; falha só é registrada). */
    private fun enqueueSave(snapshot: SaveSnapshot) {
        val store = persistence ?: return
        if (finishSent.get()) return
        writes.enqueue { store.save(savedGameOf(gameId, config, gameSeed, snapshot)) }
    }

    private data class Local(
        val snapshot: GameSnapshot,
        val selected: List<Card> = emptyList(),
        val sort: HandSort = HandSort.CUSTOM,
        val newCards: Set<Card> = emptySet(),
        /** Trocas de 3 vermelho a encenar, em ordem; a primeira está na tela. */
        val swaps: List<RedThreeNotice> = emptyList(),
        /** Cartas da mão escondidas até a troca do humano ([RedThreeNotice.id]) ser confirmada. */
        val hidden: Map<Long, Set<Card>> = emptyMap(),
        val flights: List<CardFlight> = emptyList(),
        val flashes: List<CanastaFlash> = emptyList(),
        /** Escala de duração das animações do sistema (`ANIMATOR_DURATION_SCALE`; 0 = desligadas). */
        val animationScale: Float = 1f,
        /** §9.4 trocas de 3 vermelho que vieram no morto (id da troca → morto). */
        val mortoSwaps: Map<Long, Int> = emptyMap(),
        val planChoice: List<Action.TakeDiscardPile>? = null,
        /** Pedido ([GameSnapshot.humanRequestId]) em que os planos de [planChoice] foram resolvidos. */
        val planRequestId: Long = 0,
        val confirmDecline: Boolean = false,
        val confirmResign: Boolean = false,
        val message: UiMessage? = null,
        val endStep: Int = 0,
    )

    private val human = HumanPlayer()
    private val humanSeat = Seat(0)
    private val controller = GameController(
        config = config,
        gameSeed = gameSeed,
        players = GameController.playersFor(config, gameSeed, human, humanSeat, botFactory),
        viewerSeat = humanSeat,
        computeDispatcher = computeDispatcher,
        botDelayMillis = botDelayMillis,
        restored = restored,
        // Só repassa a foto; serializar e gravar fica com o consumidor em IO (não atrasa o laço).
        onSave = if (persistence == null) null else ::enqueueSave,
    )
    private val animationIds = AtomicLong(0)
    private val local = MutableStateFlow(
        controller.state.value.let { initial ->
            // Retomada: o registro de 3 vermelhos já aconteceu (nada se reencena) e, se o jogo parou no fim de uma
            // partida, volta à tela de pontos dela.
            val base = Local(
                initial,
                sort = initialSort,
                endStep = if (restored != null && initial.stage == Stage.ROUND_OVER) 1 else 0,
            )
            absorb(base, initial, previous = if (restored != null) initial else null)
        },
    )

    val uiState: StateFlow<GameUiState> = local.map(::build)
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(local.value))

    init {
        viewModelScope.launch {
            controller.state.collect { snapshot ->
                local.update { absorb(it, snapshot, it.snapshot) }
                // §13/§13.1 fim do jogo: estatística (uma vez) e o jogo salvo apagado.
                if (snapshot.stage == Stage.GAME_OVER && persistence != null && finishSent.compareAndSet(false, true)) {
                    val result = when {
                        snapshot.resigned -> GameResult.RESIGNATION
                        snapshot.winner == snapshot.viewerSide -> GameResult.WIN
                        else -> GameResult.LOSS
                    }
                    // Na fila durável: sair da tela não interrompe o fim (apagar o salvo e contar o jogo).
                    writes.enqueue { persistence.finish(gameId, config.toSaved(), result) }
                }
            }
        }
        // Jogo novo: salvo desde o início (se o processo morrer antes da 1ª ação, o jogo continua de onde estava).
        if (restored == null) enqueueSave(controller.saveSnapshot())
        // Trocas de outros assentos: animação automática, uma de cada vez.
        viewModelScope.launch {
            local.map { state -> state.swaps.firstOrNull()?.takeIf { !isOwn(it, state.snapshot) } }
                .distinctUntilChanged()
                .collectLatest { head ->
                    if (head != null) {
                        delay(swapAnimationMillis)
                        local.update { state ->
                            if (state.swaps.firstOrNull()?.id == head.id) {
                                state.copy(swaps = state.swaps.drop(1), flights = state.flights + swapFlights(head, state))
                            } else {
                                state
                            }
                        }
                    }
                }
        }
        // Cada voo/destaque some sozinho depois da sua duração (o desenho também para; isto só limpa o estado).
        viewModelScope.launch {
            val scheduled = mutableSetOf<Long>()
            local.map { state -> state.flights.map { it.id to it.delayMillis } + state.flashes.map { it.id to 0L } }
                .distinctUntilChanged()
                .collect { items ->
                    items.filter { (id, _) -> scheduled.add(id) }.forEach { (id, delayMillis) ->
                        launch {
                            // O desenho dura no máximo 2 × a duração efetiva (pulso: sobe e desce) após o atraso.
                            delay(delayMillis + effectiveMillis(local.value) * 2 + EXPIRY_MARGIN_MILLIS)
                            local.update { state ->
                                state.copy(flights = state.flights.filterNot { it.id == id }, flashes = state.flashes.filterNot { it.id == id })
                            }
                            scheduled.remove(id)
                        }
                    }
                }
        }
        // §3.5/§6.5 encenadas todas as trocas de 3 vermelho já vistas (fila vazia), libera a próxima ação do controlador.
        // A confirmação leva o tamanho do registro visto: só vale para a espera exata (as trocas que acabamos de mostrar).
        viewModelScope.launch {
            local.collect { state ->
                val snapshot = state.snapshot
                if (snapshot.stage == Stage.PLAYING && state.swaps.isEmpty() && snapshot.view.redThreeLog.isNotEmpty()) {
                    controller.presentationDone(snapshot.roundNumber, snapshot.view.redThreeLog.size)
                }
            }
        }
        viewModelScope.launch { controller.run() }
    }

    /** A troca é do próprio humano: encenação com o botão "Baixar 3 vermelho". */
    private fun isOwn(notice: RedThreeNotice, snapshot: GameSnapshot): Boolean = notice.seat == snapshot.viewerSeat

    // ---------- derivação ----------

    /**
     * Incorpora um novo snapshot ([previous] = o último já incorporado, `null` no início): cartas novas na mão,
     * trocas de 3 vermelho a encenar ([redThreeSource]) e a etapa das telas finais.
     */
    private fun absorb(state: Local, snapshot: GameSnapshot, previous: GameSnapshot?): Local {
        val old = state.snapshot
        val sameRound = old.roundNumber == snapshot.roundNumber
        val hand = snapshot.view.hand.toSet()
        // §5.2/§6.5/§9 cartas que entraram na mão desde o último snapshot visto (diferença de multiconjunto: as
        // cartas físicas são únicas).
        val gained = if (sameRound && previous != null) hand - old.view.hand.toSet() else emptySet()
        var newCards = if (sameRound) (state.newCards intersect hand) + gained else emptySet()

        val fresh = redThreeSource.newSwaps(previous, snapshot)
        val swaps = (if (sameRound) state.swaps else emptyList()) + fresh
        var hidden = if (sameRound) state.hidden else emptyMap()
        // A reposição (ou o morto) da troca do próprio humano só aparece depois do "Baixar 3 vermelho" da última
        // troca dele nesta ação.
        // §9.4 3 vermelhos que vieram no morto pego agora: as cartas do morto aparecem como novas (nada oculto),
        // e a troca anima do morto à área de 3 vermelhos.
        val mortoTaken = if (sameRound) {
            snapshot.view.mortoStatus.indices.firstOrNull { i ->
                snapshot.view.mortoStatus[i] is MortoStatus.Taken && old.view.mortoStatus.getOrNull(i) !is MortoStatus.Taken
            }
        } else {
            null
        }
        val takenBy = mortoTaken?.let { (snapshot.view.mortoStatus[it] as MortoStatus.Taken).side }
        val fromMorto = fresh.filter { !it.atDeal && takenBy != null && it.side == takenBy }
        val mortoSwaps = (if (sameRound) state.mortoSwaps else emptyMap()) + fromMorto.associate { it.id to mortoTaken!! }
        fresh.lastOrNull { !it.atDeal && it.seat == snapshot.viewerSeat && it !in fromMorto }
            ?.let { hidden = hidden + (it.id to gained) }
        // §3.5 na distribuição, as reposições do humano (informação privada da vista dele) ficam ocultas até a
        // última troca dele ser confirmada, e então aparecem como novas. Não se casa reposição com troca: numa
        // reposição em cadeia a carta intermediária era outro 3 vermelho, que não está mais na mão.
        fresh.lastOrNull { it.atDeal && it.seat == snapshot.viewerSeat }?.let { last ->
            val replacements = snapshot.view.ownDealReplacements.toSet()
            hidden = hidden + (last.id to replacements)
            newCards = newCards + replacements
        }
        // Animações da mesa a partir das diferenças com o último snapshot incorporado.
        val (newFlights, newFlashes) = if (effectiveMillis(state) > 0 && previous != null && snapshot.stage == Stage.PLAYING) {
            TableAnimations.derive(old, snapshot, hidden.values.flatten().toSet(), animationIds::incrementAndGet)
                .let { (flights, flashes) -> flights.map { it.scaled(state.animationScale) } to flashes }
        } else {
            emptyList<CardFlight>() to emptyList()
        }
        val endStep = if (snapshot.stage == Stage.PLAYING || old.history.size != snapshot.history.size) 0 else state.endStep
        val playing = snapshot.stage == Stage.PLAYING
        if (!playing) newCards = emptySet()
        return state.copy(
            snapshot = snapshot,
            selected = state.selected.filter { it in hand },
            newCards = newCards,
            swaps = if (playing) swaps else emptyList(),
            mortoSwaps = if (playing) mortoSwaps.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            flights = if (playing && sameRound) state.flights + newFlights else emptyList(),
            flashes = if (playing && sameRound) state.flashes + newFlashes else emptyList(),
            hidden = if (playing) hidden.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            planChoice = state.planChoice.takeIf { snapshot.isHumanTurn },
            confirmDecline = state.confirmDecline && snapshot.isHumanTurn,
            confirmResign = state.confirmResign && snapshot.stage == old.stage && sameRound,
            endStep = endStep,
        )
    }

    private fun build(state: Local): GameUiState {
        val snapshot = state.snapshot
        val head = state.swaps.firstOrNull()
        val reveal = head?.takeIf { isOwn(it, snapshot) }?.let { RedThreeReveal(it, state.hidden[it.id].orEmpty()) }
        val hidden = state.hidden.values.flatten().toSet()
        // Os 3 vermelhos ainda não encenados não aparecem na mesa (o atual entra quando a encenação termina).
        val staged = state.swaps.flatMap { it.cards }.toSet()
        val visibleHand = snapshot.view.hand.filter { it !in hidden }
        val hand = HandOrder.sort(visibleHand, state.sort)
        val ownSwapPending = state.swaps.any { isOwn(it, snapshot) }
        val legal = if (ownSwapPending) emptyList() else snapshot.humanLegal
        return GameUiState(
            snapshot = snapshot,
            hand = hand,
            customHand = if (state.sort == HandSort.CUSTOM) HandOrder.custom(visibleHand) else null,
            selected = state.selected.filter { it in visibleHand },
            highlighted = HumanTurnResolver.highlightedCards(hand, legal, state.selected),
            newCards = state.newCards - hidden,
            sort = state.sort,
            redThrees = snapshot.view.redThrees.map { side -> side.filter { it !in staged } },
            reveal = reveal,
            ownSwapPending = ownSwapPending,
            banner = head?.takeIf { reveal == null },
            planChoice = state.planChoice,
            confirmDecline = state.confirmDecline,
            confirmResign = state.confirmResign,
            message = state.message,
            endScreen = endScreen(snapshot, state.endStep),
            flights = state.flights,
            canastaFlashes = state.flashes,
            animationMillis = effectiveMillis(state),
        )
    }

    private fun endScreen(snapshot: GameSnapshot, step: Int): EndScreen? = when {
        snapshot.stage == Stage.PLAYING -> null
        snapshot.resigned -> EndScreen.FINAL
        snapshot.stage == Stage.ROUND_OVER -> if (step == 0) EndScreen.ANNOUNCE_ROUND else EndScreen.ROUND_POINTS
        else -> EndScreen.entries[step.coerceAtMost(EndScreen.entries.lastIndex)]
    }

    // ---------- gestos ----------

    override fun onCardClick(card: Card) = local.update { state ->
        val selected = state.selected.filter { it in state.snapshot.view.hand }
        state.copy(selected = if (card in selected) selected - card else selected + card, message = null)
    }

    override fun onClearSelection() = local.update { it.copy(selected = emptyList()) }

    override fun onSortChange(sort: HandSort) {
        local.update { it.copy(sort = sort) }
        persistence?.let { store -> writes.enqueue { store.saveHandSort(sort.name) } }
    }

    override fun onDraw() = play(PlayIntent.Draw)

    override fun onTakeDiscardPile() = play(PlayIntent.TakeDiscardPile)

    /** Toque no lixo: antes de comprar, pegar o lixo; depois, descartar a carta selecionada (§4.3, §8). */
    override fun onDiscardPileClick() {
        when (controller.state.value.view.phase) {
            Phase.AWAITING_DRAW -> play(PlayIntent.TakeDiscardPile)
            Phase.PLAYING -> play(PlayIntent.Discard)
            Phase.FINISHED -> Unit
        }
    }

    override fun onCreateMeld() = play(PlayIntent.CreateMeld)

    override fun onAddToMeld(meldId: MeldId) = play(PlayIntent.AddToMeld(meldId))

    override fun onDiscard() = play(PlayIntent.Discard)

    /** §10.2 recusar a compra encerra a partida sem vencedor: pede confirmação. */
    override fun onDeclineDraw() {
        if (Action.DeclineDraw in controller.state.value.humanLegal) local.update { it.copy(confirmDecline = true) }
    }

    override fun onConfirmDecline() {
        local.update { it.copy(confirmDecline = false) }
        play(PlayIntent.DeclineDraw)
    }

    override fun onDismissDecline() = local.update { it.copy(confirmDecline = false) }

    /** Escolha entre planos de pegar o lixo (todos vindos de `legal`). */
    override fun onPlanChosen(action: Action.TakeDiscardPile) {
        val requestId = local.value.planRequestId
        local.update { it.copy(planChoice = null) }
        submit(action, requestId)
    }

    override fun onDismissPlanChoice() = local.update { it.copy(planChoice = null) }

    override fun onMessageShown() = local.update { it.copy(message = null) }

    /** "Baixar 3 vermelho": encerra a encenação; o 3 vermelho aparece na mesa e a reposição, como nova. */
    override fun onRevealConfirmed() = local.update { state ->
        val head = state.swaps.firstOrNull()?.takeIf { isOwn(it, state.snapshot) } ?: return@update state
        state.copy(
            swaps = state.swaps.drop(1),
            hidden = state.hidden - head.id,
            flights = state.flights + swapFlights(head, state),
        )
    }

    /** §13.1 desistir pede confirmação; cancelar volta como se nada tivesse acontecido. */
    override fun onResign() = local.update { it.copy(confirmResign = true) }

    override fun onDismissResign() = local.update { it.copy(confirmResign = false) }

    override fun onConfirmResign() {
        local.update { it.copy(confirmResign = false) }
        controller.resign()
    }

    fun onNextRound() {
        controller.continueToNextRound()
    }

    /** Avança entre as telas de fim de partida/jogo (anúncio → pontos → anúncio do jogo → final). */
    fun onEndNext() = local.update { it.copy(endStep = it.endStep + 1) }

    private fun play(intent: PlayIntent) {
        // Toque atrasado (ex.: toque duplo): se o controlador não espera mais o humano (a jogada anterior já foi
        // enviada, a vez mudou), ignora em vez de mostrar um motivo de outra situação. Durante a encenação de 3
        // vermelho a mesa está bloqueada.
        val snapshot = controller.state.value
        val ownSwapPending = local.value.let { l -> l.swaps.any { isOwn(it, l.snapshot) } }
        if (!snapshot.isHumanTurn || !human.waiting.value || ownSwapPending) return
        val requestId = snapshot.humanRequestId
        val selected = local.value.selected.filter { it in snapshot.view.hand }
        val resolution = HumanTurnResolver.resolve(intent, selected, snapshot.humanLegal) { controller.explain(it, requestId) }
        if (resolution !is Resolution.Play && controller.state.value !== snapshot) return
        when (resolution) {
            is Resolution.Play -> submit(resolution.action, requestId)
            is Resolution.ChoosePlan ->
                local.update { it.copy(planChoice = resolution.options, planRequestId = requestId, message = null) }
            is Resolution.Rejected -> local.update { it.copy(message = UiMessage.Rejected(resolution.reason)) }
            Resolution.SelectOneCardToDiscard -> local.update { it.copy(message = UiMessage.SelectOneCardToDiscard) }
        }
    }

    /** Escala de animação do sistema mudou (a tela observa `ANIMATOR_DURATION_SCALE`); 0 desliga os voos. */
    fun onAnimationScaleChanged(scale: Float) = local.update { state ->
        if (scale == state.animationScale) {
            state
        } else {
            state.copy(animationScale = scale, flights = if (scale <= 0f) emptyList() else state.flights, flashes = if (scale <= 0f) emptyList() else state.flashes)
        }
    }

    /** Duração efetiva de cada animação (configurada × escala do sistema). */
    private fun effectiveMillis(state: Local): Long = (animationMillis * state.animationScale).toLong().coerceAtLeast(0)

    private fun CardFlight.scaled(scale: Float): CardFlight = copy(delayMillis = (delayMillis * scale).toLong())

    /**
     * §6.5 voos da troca encenada [notice] (que está saindo da fila de [state]): o 3 vermelho vai à área do lado e a
     * reposição chega do monte. Do humano: numa cadeia (mesma compra), a reposição de uma troca é o 3 vermelho da troca
     * seguinte, e a da última é a carta revelada agora; vindo do morto (§9.4), sem reposição do monte. De outro assento,
     * a reposição chega virada.
     */
    private fun swapFlights(notice: RedThreeNotice, state: Local): List<CardFlight> {
        if (effectiveMillis(state) <= 0) return emptyList()
        val viewer = state.snapshot.viewerSeat
        val fromMorto = state.mortoSwaps[notice.id]
        val replacement = when {
            fromMorto != null -> TableAnimations.Replacement.None
            notice.seat != viewer -> TableAnimations.Replacement.FaceDown
            notice.id in state.hidden -> TableAnimations.Replacement.Revealed(state.hidden.getValue(notice.id).toList())
            else -> {
                val next = state.swaps.getOrNull(1)
                if (!notice.atDeal && next != null && next.seat == viewer && !next.atDeal && next.id !in state.mortoSwaps) {
                    TableAnimations.Replacement.Revealed(next.cards)
                } else {
                    TableAnimations.Replacement.None
                }
            }
        }
        return TableAnimations.redThreeSwap(notice, viewer, replacement, animationIds::incrementAndGet, fromMorto)
            .map { it.scaled(state.animationScale) }
    }

    companion object {
        /** Folga para a limpeza de uma animação depois do fim do desenho. */
        private const val EXPIRY_MARGIN_MILLIS: Long = 150

        /** Duração padrão de cada animação da mesa (300–600 ms). */
        const val DEFAULT_ANIMATION_MILLIS: Long = 450

        /** Duração padrão da animação de troca de 3 vermelho de outro assento. */
        const val DEFAULT_SWAP_ANIMATION_MILLIS: Long = 1_800
    }

    private fun submit(action: Action, requestId: Long) {
        if (controller.submit(action, requestId)) {
            local.update { it.copy(selected = emptyList(), newCards = emptySet(), message = null) }
        }
    }
}
