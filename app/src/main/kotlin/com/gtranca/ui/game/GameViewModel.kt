package com.gtranca.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.BotPlayer
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldId
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
import kotlinx.coroutines.CoroutineDispatcher
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
    val banner: RedThreeNotice?,
    val planChoice: List<Action.TakeDiscardPile>?,
    val confirmDecline: Boolean,
    val confirmResign: Boolean,
    val message: UiMessage?,
    val endScreen: EndScreen?,
) {
    val isHumanTurn: Boolean get() = snapshot.isHumanTurn && reveal == null
    private val legal: List<Action> get() = if (reveal == null) snapshot.humanLegal else emptyList()
    val awaitingDraw: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW
    val playing: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.PLAYING
    val canDraw: Boolean get() = Action.DrawFromStock in legal

    /** §5.3/§5.5 só com algum plano de pegar o lixo em `legalActions`. */
    val canTakeDiscardPile: Boolean get() = legal.any { it is Action.TakeDiscardPile }
    val canDecline: Boolean get() = Action.DeclineDraw in legal
    val canMeld: Boolean get() = playing && selected.isNotEmpty()
    val canDiscard: Boolean get() = playing && selected.size == 1
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
) : ViewModel(), TableEvents {

    private data class Local(
        val snapshot: GameSnapshot,
        val selected: List<Card> = emptyList(),
        val sort: HandSort = HandSort.CUSTOM,
        val newCards: Set<Card> = emptySet(),
        /** Trocas de 3 vermelho a encenar, em ordem; a primeira está na tela. */
        val swaps: List<RedThreeNotice> = emptyList(),
        /** Cartas da mão escondidas até a troca do humano ([RedThreeNotice.id]) ser confirmada. */
        val hidden: Map<Long, Set<Card>> = emptyMap(),
        val planChoice: List<Action.TakeDiscardPile>? = null,
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
    )
    private val local = MutableStateFlow(controller.state.value.let { absorb(Local(it), it, previous = null) })

    val uiState: StateFlow<GameUiState> = local.map(::build)
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(local.value))

    init {
        viewModelScope.launch { controller.state.collect { snapshot -> local.update { absorb(it, snapshot, it.snapshot) } } }
        // Trocas de outros assentos: animação automática, uma de cada vez.
        viewModelScope.launch {
            local.map { state -> state.swaps.firstOrNull()?.takeIf { !isOwn(it, state.snapshot) } }
                .distinctUntilChanged()
                .collectLatest { head ->
                    if (head != null) {
                        delay(swapAnimationMillis)
                        local.update { state ->
                            if (state.swaps.firstOrNull()?.id == head.id) state.copy(swaps = state.swaps.drop(1)) else state
                        }
                    }
                }
        }
        // §3.5 encenadas as trocas da distribuição, libera a 1ª jogada da partida.
        viewModelScope.launch {
            local.collect { state ->
                val snapshot = state.snapshot
                if (snapshot.stage == Stage.PLAYING && state.swaps.none { it.atDeal } && snapshot.view.redThreeLog.any { it.atDeal }) {
                    controller.dealPresentationDone(snapshot.roundNumber)
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
        fresh.lastOrNull { !it.atDeal && it.seat == snapshot.viewerSeat }?.let { hidden = hidden + (it.id to gained) }
        val endStep = if (snapshot.stage == Stage.PLAYING || old.history.size != snapshot.history.size) 0 else state.endStep
        val playing = snapshot.stage == Stage.PLAYING
        if (!playing) newCards = emptySet()
        return state.copy(
            snapshot = snapshot,
            selected = state.selected.filter { it in hand },
            newCards = newCards,
            swaps = if (playing) swaps else emptyList(),
            hidden = if (playing) hidden.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            planChoice = state.planChoice.takeIf { snapshot.isHumanTurn },
            confirmDecline = state.confirmDecline && snapshot.isHumanTurn,
            confirmResign = state.confirmResign && snapshot.stage != Stage.GAME_OVER,
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
        val legal = if (reveal == null) snapshot.humanLegal else emptyList()
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
            banner = head?.takeIf { reveal == null },
            planChoice = state.planChoice,
            confirmDecline = state.confirmDecline,
            confirmResign = state.confirmResign,
            message = state.message,
            endScreen = endScreen(snapshot, state.endStep),
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

    override fun onSortChange(sort: HandSort) = local.update { it.copy(sort = sort) }

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
        local.update { it.copy(planChoice = null) }
        submit(action, controller.state.value.humanRequestId)
    }

    override fun onDismissPlanChoice() = local.update { it.copy(planChoice = null) }

    override fun onMessageShown() = local.update { it.copy(message = null) }

    /** "Baixar 3 vermelho": encerra a encenação; o 3 vermelho aparece na mesa e a reposição, como nova. */
    override fun onRevealConfirmed() = local.update { state ->
        val head = state.swaps.firstOrNull()?.takeIf { isOwn(it, state.snapshot) } ?: return@update state
        state.copy(swaps = state.swaps.drop(1), hidden = state.hidden - head.id)
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
        val ownSwapShowing = local.value.let { l -> l.swaps.firstOrNull()?.let { isOwn(it, l.snapshot) } == true }
        if (!snapshot.isHumanTurn || !human.waiting.value || ownSwapShowing) return
        val requestId = snapshot.humanRequestId
        val selected = local.value.selected.filter { it in snapshot.view.hand }
        val resolution = HumanTurnResolver.resolve(intent, selected, snapshot.humanLegal) { controller.explain(it, requestId) }
        if (resolution !is Resolution.Play && controller.state.value !== snapshot) return
        when (resolution) {
            is Resolution.Play -> submit(resolution.action, requestId)
            is Resolution.ChoosePlan -> local.update { it.copy(planChoice = resolution.options, message = null) }
            is Resolution.Rejected -> local.update { it.copy(message = UiMessage.Rejected(resolution.reason)) }
            Resolution.SelectOneCardToDiscard -> local.update { it.copy(message = UiMessage.SelectOneCardToDiscard) }
        }
    }

    companion object {
        /** Duração padrão da animação de troca de 3 vermelho de outro assento. */
        const val DEFAULT_SWAP_ANIMATION_MILLIS: Long = 1_800
    }

    private fun submit(action: Action, requestId: Long) {
        if (controller.submit(action, requestId)) {
            local.update { it.copy(selected = emptyList(), newCards = emptySet(), message = null) }
        }
    }
}
