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
import com.gtranca.engine.model.Suit
import com.gtranca.game.CardClass
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.HumanPlayer
import com.gtranca.game.HumanTurnResolver
import com.gtranca.game.PlayIntent
import com.gtranca.game.Resolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Ordem da mão na tela. */
enum class HandSort { BY_SUIT, BY_RANK }

/** Mensagem para o humano (o texto em pt-BR é escolhido na camada de UI). */
sealed interface UiMessage {
    /** Jogada recusada pelo motor; [error] `null` = motivo desconhecido. */
    data class Rejected(val error: RuleError?) : UiMessage

    /** Para descartar, selecione exatamente uma carta. */
    data object SelectOneCardToDiscard : UiMessage
}

/**
 * Estado da tela de jogo. Derivado do [GameSnapshot] (vista do humano) e do estado local da tela; os
 * controles e o destaque vêm de `legalActions` ([GameSnapshot.humanLegal]).
 */
data class GameUiState(
    val snapshot: GameSnapshot,
    val hand: List<Card>,
    val selected: List<Card>,
    val highlighted: Set<CardClass>,
    val sort: HandSort,
    val planChoice: List<Action.TakeDiscardPile>?,
    val confirmDecline: Boolean,
    val message: UiMessage?,
    val showFinalResult: Boolean,
) {
    val isHumanTurn: Boolean get() = snapshot.isHumanTurn
    private val legal: List<Action> get() = snapshot.humanLegal
    val awaitingDraw: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW
    val playing: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.PLAYING
    val canDraw: Boolean get() = Action.DrawFromStock in legal
    val canTakeDiscardPile: Boolean get() = legal.any { it is Action.TakeDiscardPile }
    val canDecline: Boolean get() = Action.DeclineDraw in legal
    val canMeld: Boolean get() = playing && selected.isNotEmpty()
    val canDiscard: Boolean get() = playing && selected.size == 1
}

/**
 * ViewModel da partida: cria o [GameController] (humano no assento 0, bots da dificuldade escolhida),
 * roda o laço no [viewModelScope] e traduz os gestos da tela em ações de `legalActions`
 * ([HumanTurnResolver]).
 */
class GameViewModel(
    config: GameConfig,
    gameSeed: Long = Random.nextLong(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    botDelayMillis: Long = GameController.DEFAULT_BOT_DELAY_MILLIS,
    botFactory: (Seat, Random) -> BotPlayer = { _, random -> createBot(config.difficulty, random) },
) : ViewModel(), TableEvents {

    private data class LocalState(
        val selected: List<Card> = emptyList(),
        val sort: HandSort = HandSort.BY_SUIT,
        val planChoice: List<Action.TakeDiscardPile>? = null,
        val confirmDecline: Boolean = false,
        val message: UiMessage? = null,
        val showFinalResult: Boolean = false,
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
    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<GameUiState> = combine(controller.state, local, ::build)
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(controller.state.value, local.value))

    init {
        viewModelScope.launch { controller.run() }
    }

    private fun build(snapshot: GameSnapshot, state: LocalState): GameUiState {
        val hand = sortHand(snapshot.view.hand, state.sort)
        val selected = state.selected.filter { it in snapshot.view.hand }
        return GameUiState(
            snapshot = snapshot,
            hand = hand,
            selected = selected,
            highlighted = if (snapshot.isHumanTurn) HumanTurnResolver.highlightedClasses(snapshot.humanLegal, selected) else emptySet(),
            sort = state.sort,
            planChoice = state.planChoice.takeIf { snapshot.isHumanTurn },
            confirmDecline = state.confirmDecline && snapshot.isHumanTurn,
            message = state.message,
            showFinalResult = state.showFinalResult,
        )
    }

    // ---------- gestos ----------

    override fun onCardClick(card: Card) = local.update { state ->
        val selected = state.selected.filter { it in controller.state.value.view.hand }
        state.copy(selected = if (card in selected) selected - card else selected + card, message = null)
    }

    override fun onClearSelection() = local.update { it.copy(selected = emptyList()) }

    override fun onToggleSort() = local.update {
        it.copy(sort = if (it.sort == HandSort.BY_SUIT) HandSort.BY_RANK else HandSort.BY_SUIT)
    }

    override fun onDraw() = play(PlayIntent.Draw)

    override fun onTakeDiscardPile() = play(PlayIntent.TakeDiscardPile)

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
        submit(action)
    }

    override fun onDismissPlanChoice() = local.update { it.copy(planChoice = null) }

    override fun onMessageShown() = local.update { it.copy(message = null) }

    fun onNextRound() {
        controller.continueToNextRound()
    }

    fun onShowFinalResult() = local.update { it.copy(showFinalResult = true) }

    private fun play(intent: PlayIntent) {
        // Lê as fontes (e não o uiState derivado, que só é recalculado na próxima coleta).
        val snapshot = controller.state.value
        if (!snapshot.isHumanTurn) return
        val selected = local.value.selected.filter { it in snapshot.view.hand }
        val resolution = HumanTurnResolver.resolve(intent, selected, snapshot.humanLegal, controller::explain)
        when (resolution) {
            is Resolution.Play -> submit(resolution.action)
            is Resolution.ChoosePlan -> local.update { it.copy(planChoice = resolution.options, message = null) }
            is Resolution.Rejected -> local.update { it.copy(message = UiMessage.Rejected(resolution.reason)) }
            Resolution.SelectOneCardToDiscard -> local.update { it.copy(message = UiMessage.SelectOneCardToDiscard) }
        }
    }

    private fun submit(action: Action) {
        if (human.submit(action)) local.update { it.copy(selected = emptyList(), message = null) }
    }

    companion object {
        /** Ordem dos naipes na mão: cores alternadas. */
        private val SUIT_ORDER = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)

        fun sortHand(hand: List<Card>, sort: HandSort): List<Card> {
            val bySuit = compareBy<Card> { SUIT_ORDER.indexOf(it.suit) }
            val byRank = compareBy<Card> { it.rank.ordinal }
            val comparator = when (sort) {
                HandSort.BY_SUIT -> bySuit.then(byRank)
                HandSort.BY_RANK -> byRank.then(bySuit)
            }
            return hand.sortedWith(comparator.thenBy { it.deck })
        }
    }
}
