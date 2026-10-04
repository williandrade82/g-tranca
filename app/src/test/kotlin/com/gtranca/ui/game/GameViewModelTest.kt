package com.gtranca.ui.game

import com.gtranca.ai.Difficulty
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.game.GameConfig
import com.gtranca.game.HandOrder
import com.gtranca.game.HandSort
import com.gtranca.game.HumanTurnResolver
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.RedThreeSwapSource
import com.gtranca.game.classCounts
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newGame(
        seed: Long,
        target: Int = 3000,
        mode: GameMode = GameMode.INDIVIDUAL,
        botDelayMillis: Long = 0,
        settleReveals: Boolean = true,
        swapMillis: Long = 0,
    ): GameViewModel {
        val vm = GameViewModel(
            GameConfig(mode, Difficulty.FACIL, target),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = botDelayMillis,
            swapAnimationMillis = swapMillis,
            animationMillis = 0,
        )
        advanceUntilIdle()
        if (settleReveals) settle(vm)
        return vm
    }

    /** Confirma as encenações de 3 vermelho pendentes (elas bloqueiam a mesa). */
    private fun TestScope.settle(vm: GameViewModel) {
        while (vm.uiState.value.reveal != null) {
            vm.onRevealConfirmed()
            advanceUntilIdle()
        }
    }

    private fun GameViewModel.select(cards: List<Card>) = cards.forEach(::onCardClick)

    /** Cartas físicas da mão com as classes de [wanted], preferindo a 2ª cópia (deck 1) quando houver. */
    private fun GameUiState.physical(wanted: List<Card>): List<Card> {
        val available = hand.sortedByDescending { it.deck }.toMutableList()
        return wanted.map { w -> available.first { it.cardClass == w.cardClass }.also { available.remove(it) } }
    }

    /** Uma vez do humano: compra e descarta a primeira carta descartável (sem confirmar encenações). */
    private fun TestScope.drawAndDiscard(vm: GameViewModel) {
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        val state = vm.uiState.value
        if (!state.playing) return
        val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(state.physical(listOf(discard.card)))
        vm.onDiscard()
        advanceUntilIdle()
    }

    @Test
    fun `§4_3 comeca na vez do humano com a mao na ordem personalizada e so a compra habilitada`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        val state = vm.uiState.value
        state.isHumanTurn shouldBe true
        state.awaitingDraw shouldBe true
        state.canDraw shouldBe true
        state.canMeld shouldBe false
        state.sort shouldBe HandSort.CUSTOM
        state.hand shouldBe HandOrder.sort(state.snapshot.view.hand, HandSort.CUSTOM)
        state.customHand shouldBe HandOrder.custom(state.snapshot.view.hand)
        vm.onSortChange(HandSort.BY_RANK)
        advanceUntilIdle()
        vm.uiState.value.sort shouldBe HandSort.BY_RANK
        vm.uiState.value.customHand.shouldBeNull()
        vm.uiState.value.hand.map { it.rank }.shouldBeSortedWith(compareBy { it.ordinal })
    }

    @Test
    fun `comprar, recusa explicada, baixar com a outra copia e descartar tocando no lixo`() = runTest(dispatcher) {
        // Procura uma semente em que o humano possa baixar depois de comprar.
        var vm: GameViewModel? = null
        var meld: Action.CreateMeld? = null
        for (seed in 1L..60L) {
            val candidate = newGame(seed)
            candidate.onDraw()
            advanceUntilIdle()
            settle(candidate)
            val found = candidate.uiState.value.snapshot.humanLegal.filterIsInstance<Action.CreateMeld>().firstOrNull()
            if (candidate.uiState.value.playing && found != null) {
                vm = candidate
                meld = found
                break
            }
        }
        vm.shouldNotBeNull()
        meld.shouldNotBeNull()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING

        // Seleção inválida: o motivo vem do motor.
        vm.select(vm.uiState.value.physical(meld.cards.take(1)))
        vm.onCreateMeld()
        advanceUntilIdle()
        vm.uiState.value.message shouldBe UiMessage.Rejected(MeldError.TOO_FEW_CARDS)
        vm.onClearSelection()

        // Seleção válida (com a 2ª cópia quando houver) vira a ação de legal.
        val selection = vm.uiState.value.physical(meld.cards)
        vm.select(selection)
        advanceUntilIdle()
        vm.uiState.value.selected shouldContainExactly selection
        vm.onCreateMeld()
        advanceUntilIdle()
        val afterMeld = vm.uiState.value
        afterMeld.snapshot.view.tables[afterMeld.snapshot.viewerSide.index].melds.size shouldBe 1
        afterMeld.selected.shouldBeEmpty()

        // §8 tocar no lixo com 2 cartas selecionadas pede uma só.
        vm.select(afterMeld.hand.take(2))
        vm.onDiscardPileClick()
        advanceUntilIdle()
        vm.uiState.value.message shouldBe UiMessage.SelectOneCardToDiscard
        vm.onClearSelection()

        // Com uma carta (e o Discard dela em legal), tocar no lixo descarta.
        val discard = vm.uiState.value.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(vm.uiState.value.physical(listOf(discard.card)))
        vm.onDiscardPileClick()
        advanceUntilIdle()
        // A vez do humano teve exatamente compra, baixa e descarte (este pelo toque no lixo).
        val ownTurn = vm.uiState.value.snapshot.turnEvents[0].map { it.action }
        ownTurn.size shouldBe 3
        ownTurn[0] shouldBe Action.DrawFromStock
        (ownTurn[1] is Action.CreateMeld) shouldBe true
        ownTurn[2].shouldBeDiscardOf(discard.card)
    }

    private fun Action.shouldBeDiscardOf(card: Card) {
        (this as Action.Discard).card.cardClass shouldBe card.cardClass
    }

    @Test
    fun `destaque por copias vem de legalActions e recusa da compra so quando legal`() = runTest(dispatcher) {
        val vm = newGame(seed = 7)
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        val state = vm.uiState.value
        state.highlighted shouldBe HumanTurnResolver.highlightedCards(state.hand, state.snapshot.humanLegal, emptyList())
        vm.onDeclineDraw()
        advanceUntilIdle()
        vm.uiState.value.confirmDecline shouldBe false
    }

    @Test
    fun `§4_3 carta comprada fica destacada como nova ate a proxima jogada`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        val before = vm.uiState.value.snapshot.view.hand.toSet()
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        val state = vm.uiState.value
        val gained = state.snapshot.view.hand.toSet() - before
        gained.shouldNotBeEmpty()
        state.newCards shouldBe gained
        // Na próxima jogada (descarte), o destaque some.
        val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(state.physical(listOf(discard.card)))
        vm.onDiscard()
        advanceUntilIdle()
        vm.uiState.value.newCards.shouldBeEmpty()
    }

    @Test
    fun `§5_3 e §5_5 pegar lixo so habilitado com TakeDiscardPile em legal`() = runTest(dispatcher) {
        // Joga várias vezes em várias sementes; em toda etapa de compra o botão segue legalActions, e encontra ao
        // menos um lixo travado por 3 preto ou vazio.
        var blockedSeen = false
        for (seed in 1L..20L) {
            val vm = newGame(seed)
            repeat(15) {
                val state = vm.uiState.value
                if (state.awaitingDraw) {
                    state.canTakeDiscardPile shouldBe state.snapshot.humanLegal.any { it is Action.TakeDiscardPile }
                    val top = state.snapshot.view.discardTop
                    if (top == null || top.isBlackThree) {
                        state.canTakeDiscardPile shouldBe false
                        blockedSeen = true
                    }
                    drawAndDiscard(vm)
                }
            }
            if (blockedSeen) break
        }
        blockedSeen shouldBe true
    }

    @Test
    fun `§6_5 3 vermelho comprado pelo humano e encenado antes da reposicao aparecer`() = runTest(dispatcher) {
        var found: Pair<GameViewModel, RedThreeReveal>? = null
        seeds@ for (seed in 1L..40L) {
            val vm = newGame(seed)
            repeat(25) {
                if (vm.uiState.value.snapshot.stage != Stage.PLAYING || !vm.uiState.value.awaitingDraw) return@repeat
                vm.onDraw()
                advanceUntilIdle()
                val reveal = vm.uiState.value.reveal
                if (reveal != null && !reveal.notice.atDeal) {
                    found = vm to reveal
                    break@seeds
                }
                settle(vm)
                val state = vm.uiState.value
                if (state.playing) {
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card)))
                    vm.onDiscard()
                    advanceUntilIdle()
                }
            }
        }
        val (vm, first) = found.shouldNotBeNull()
        first.notice.seat shouldBe Seat(0)
        val own = vm.uiState.value.snapshot.viewerSide.index
        // Uma encenação por 3 vermelho (reposição em cadeia = várias); a reposição aparece só depois da última.
        val hiddenSeen = mutableSetOf<Card>()
        var current: RedThreeReveal? = first
        while (current != null) {
            val state = vm.uiState.value
            current.notice.cards.all { it.isRedThree } shouldBe true
            // Durante a encenação: a mesa ainda não mostra esse 3 vermelho, a mão não mostra a reposição e nada se joga.
            state.redThrees[own].any { it in current.notice.cards } shouldBe false
            state.hand.any { it in current.hiddenHandCards } shouldBe false
            state.isHumanTurn shouldBe false
            vm.onCreateMeld()
            advanceUntilIdle()
            vm.uiState.value.message.shouldBeNull()
            hiddenSeen += current.hiddenHandCards
            // "Baixar 3 vermelho": ele vai à mesa.
            vm.onRevealConfirmed()
            advanceUntilIdle()
            vm.uiState.value.redThrees[own] shouldContainAll current.notice.cards
            current = vm.uiState.value.reveal
        }
        hiddenSeen.shouldNotBeEmpty()
        val after = vm.uiState.value
        after.hand shouldContainAll hiddenSeen
        after.newCards shouldContainAll hiddenSeen
        after.isHumanTurn shouldBe true
    }

    @Test
    fun `§3_5 trocas da distribuicao encenadas uma a uma na ordem do registro, antes da 1a jogada`() = runTest(dispatcher) {
        var dealChecked = false
        var playBanner: RedThreeNotice? = null
        for (seed in 1L..60L) {
            // Sem avançar o tempo antes de começar a observar (as animações dos bots duram 1 s cada).
            val vm = GameViewModel(
                GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
                gameSeed = seed,
                computeDispatcher = dispatcher,
                botDelayMillis = 0,
                swapAnimationMillis = 1_000,
                animationMillis = 0,
            )
            val presented = mutableListOf<RedThreeNotice>()
            backgroundScope.launch {
                vm.uiState.collect { state ->
                    val head = state.reveal?.notice ?: state.banner
                    if (head != null && presented.none { it.id == head.id }) presented += head
                }
            }
            advanceUntilIdle()
            val deal = vm.uiState.value.snapshot.view.redThreeLog.filter { it.atDeal }
            val ownDeal = vm.uiState.value.reveal?.notice?.takeIf { it.atDeal }
            if (ownDeal != null && deal.size >= 2 && !dealChecked) {
                // O controlador espera a encenação: ninguém jogou ainda.
                vm.uiState.value.snapshot.turnEvents.all { it.isEmpty() } shouldBe true
                vm.uiState.value.snapshot.thinkingSeat.shouldBeNull()
                ownDeal.seat shouldBe Seat(0)
                settle(vm)
                // Todas as trocas da distribuição foram mostradas, uma a uma, na ordem do registro, com quem trocou.
                presented.filter { it.atDeal }.map { it.seat to it.cards.single() } shouldBe deal.map { it.seat to it.card }
                dealChecked = true
            } else {
                settle(vm)
            }
            repeat(10) {
                if (vm.uiState.value.awaitingDraw) drawAndDiscard(vm)
                settle(vm)
            }
            presented.firstOrNull { !it.atDeal && it.seat != Seat(0) }?.let { playBanner = it }
            if (dealChecked && playBanner != null) break
        }
        dealChecked shouldBe true
        // Troca de outro assento durante o jogo: aviso com quem trocou (a reposição dele não é revelada).
        playBanner.shouldNotBeNull().cards.single().isRedThree shouldBe true
    }

    @Test
    fun `§11 §12 §13 fim de partida - anuncio, pontos e proxima partida, ou anuncio do jogo e tela final`() = runTest(dispatcher) {
        val vm = newGame(seed = 9, target = 1)
        var guard = 0
        while (vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 2_000) {
            settle(vm)
            val state = vm.uiState.value
            when {
                state.awaitingDraw -> if (state.canDraw) vm.onDraw() else vm.onTakeDiscardPile()
                state.playing -> {
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card)))
                    vm.onDiscard()
                }
            }
            advanceUntilIdle()
        }
        val ended = vm.uiState.value
        ended.snapshot.history.size shouldBe 1
        // §11 primeiro o anúncio do resultado, depois os pontos (§12).
        ended.endScreen shouldBe EndScreen.ANNOUNCE_ROUND
        vm.onEndNext()
        advanceUntilIdle()
        vm.uiState.value.endScreen shouldBe EndScreen.ROUND_POINTS
        when (ended.snapshot.stage) {
            Stage.ROUND_OVER -> {
                vm.onNextRound()
                advanceUntilIdle()
                vm.uiState.value.snapshot.roundNumber shouldBe 2
                vm.uiState.value.endScreen.shouldBeNull()
            }
            else -> {
                ended.snapshot.stage shouldBe Stage.GAME_OVER
                vm.onEndNext()
                advanceUntilIdle()
                vm.uiState.value.endScreen shouldBe EndScreen.ANNOUNCE_GAME
                vm.onEndNext()
                advanceUntilIdle()
                vm.uiState.value.endScreen shouldBe EndScreen.FINAL
            }
        }
    }

    @Test
    fun `§13_1 desistir pede confirmacao, cancelar nao muda nada e confirmar vai ao fim com o outro lado vencedor`() = runTest(dispatcher) {
        val vm = newGame(seed = 5, mode = GameMode.DUPLAS)
        val before = vm.uiState.value
        vm.onResign()
        advanceUntilIdle()
        vm.uiState.value.confirmResign shouldBe true
        vm.onDismissResign()
        advanceUntilIdle()
        vm.uiState.value shouldBe before

        vm.onResign()
        vm.onConfirmResign()
        advanceUntilIdle()
        val after = vm.uiState.value
        after.snapshot.stage shouldBe Stage.GAME_OVER
        after.snapshot.resigned shouldBe true
        after.snapshot.winner shouldBe Side(1)
        after.snapshot.totals shouldBe before.snapshot.totals
        after.endScreen shouldBe EndScreen.FINAL
        after.confirmResign shouldBe false
    }

    @Test
    fun `duplas - humano no assento 0 ve so a propria mao e a jogada dos tres bots`() = runTest(dispatcher) {
        val vm = newGame(seed = 4, mode = GameMode.DUPLAS)
        val first = vm.uiState.value
        first.isHumanTurn shouldBe true
        first.snapshot.viewerSeat shouldBe Seat(0)
        val view = first.snapshot.view
        view.handSizes.size shouldBe 4
        // §1.1 o parceiro (assento 2) é do mesmo lado; a mão dele nunca chega à interface, só o tamanho.
        view.mode.sideOf(Seat(2)) shouldBe view.side
        view.hand.size shouldBe view.handSizes[0]
        first.hand.size shouldBe view.handSizes[0]

        drawAndDiscard(vm)
        settle(vm)
        val after = vm.uiState.value
        // A semente 4 mantém a partida em andamento: a vez deu a volta e os três bots jogaram (§4.2, anti-horário).
        after.snapshot.stage shouldBe Stage.PLAYING
        after.isHumanTurn shouldBe true
        listOf(1, 2, 3).forEach { after.snapshot.turnEvents[it].shouldNotBeEmpty() }
    }

    @Test
    fun `§4_2 duplas - a vez passa a direita, ao parceiro e a esquerda com a pausa entre bots`() = runTest(dispatcher) {
        val vm = newGame(seed = 4, mode = GameMode.DUPLAS, botDelayMillis = 700)
        vm.uiState.value.isHumanTurn shouldBe true
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        val drawn = vm.uiState.value
        val discard = drawn.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
        vm.select(drawn.physical(listOf(discard.card)))
        vm.onDiscard()
        runCurrent()
        val thinkingOrder = mutableListOf<Seat>()
        var guard = 0
        while (!vm.uiState.value.isHumanTurn && vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 500) {
            vm.uiState.value.snapshot.thinkingSeat?.let { if (thinkingOrder.lastOrNull() != it) thinkingOrder += it }
            advanceTimeBy(100)
            runCurrent()
            settle(vm)
        }
        vm.uiState.value.snapshot.stage shouldBe Stage.PLAYING
        thinkingOrder shouldContainExactly listOf(Seat(1), Seat(2), Seat(3))
    }

    @Test
    fun `§4_3 toque duplo - o segundo toque nao mostra motivo de outra situacao`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        vm.uiState.value.awaitingDraw shouldBe true
        vm.select(vm.uiState.value.hand.take(3))
        vm.onDraw()
        // Antes de o controlador retomar, um toque em "Baixar" (que daria "compre primeiro") é ignorado.
        vm.onCreateMeld()
        vm.onDraw()
        vm.uiState.value.message shouldBe null
        advanceUntilIdle()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING
        vm.uiState.value.message shouldBe null
    }

    /**
     * Fonte de trocas falsa: no 1º snapshot, uma troca de outro assento seguida de uma do humano (§6.5), nessa
     * ordem; depois, nenhuma. Simula a fila com a faixa de um bot à frente da encenação do humano.
     */
    private val botThenOwnSwap = RedThreeSwapSource { previous, current ->
        if (previous != null) {
            emptyList()
        } else {
            listOf(
                RedThreeNotice(1, Side(1), Seat(1), listOf(Card.parse("3H")), atDeal = false),
                RedThreeNotice(2, Side(0), Seat(0), listOf(Card.parse("3D")), atDeal = false),
            )
        }
    }

    private fun TestScope.gameWithSwaps(seed: Long, source: RedThreeSwapSource, swapMillis: Long): GameViewModel {
        val vm = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            redThreeSource = source,
            swapAnimationMillis = swapMillis,
            animationMillis = 0,
        )
        // Sem avançar o relógio: a faixa do bot fica na tela (dura swapMillis).
        runCurrent()
        return vm
    }

    @Test
    fun `§6_5 troca do humano atras da faixa de um bot ainda bloqueia a mesa ate o Baixar`() = runTest(dispatcher) {
        // Procura uma semente em que o humano começa (para haver jogadas legais a bloquear).
        var vm: GameViewModel? = null
        for (seed in 1L..40L) {
            val candidate = gameWithSwaps(seed, botThenOwnSwap, swapMillis = 5_000)
            if (candidate.uiState.value.snapshot.isHumanTurn) {
                vm = candidate
                break
            }
        }
        vm.shouldNotBeNull()
        val withBanner = vm.uiState.value
        withBanner.banner?.seat shouldBe Seat(1)
        withBanner.reveal.shouldBeNull()
        // A troca do humano está na fila, atrás da faixa: nada se joga, nada se destaca.
        withBanner.ownSwapPending shouldBe true
        withBanner.isHumanTurn shouldBe false
        withBanner.canDraw shouldBe false
        withBanner.highlighted.shouldBeEmpty()
        vm.onDraw()
        runCurrent()
        vm.uiState.value.snapshot.view.phase shouldBe Phase.AWAITING_DRAW
        vm.uiState.value.snapshot.turnEvents[0].shouldBeEmpty()

        // Passa a faixa: agora a encenação do humano, ainda bloqueando.
        advanceTimeBy(5_001)
        runCurrent()
        vm.uiState.value.reveal?.notice?.seat shouldBe Seat(0)
        vm.uiState.value.isHumanTurn shouldBe false
        vm.onRevealConfirmed()
        runCurrent()
        vm.uiState.value.ownSwapPending shouldBe false
        vm.uiState.value.isHumanTurn shouldBe true
        vm.uiState.value.canDraw shouldBe true
    }

    @Test
    fun `§13_1 desistir durante a encenacao do 3 vermelho vai ao fim de jogo`() = runTest(dispatcher) {
        val vm = gameWithSwaps(seed = 3, source = botThenOwnSwap, swapMillis = 0)
        advanceUntilIdle()
        vm.uiState.value.reveal.shouldNotBeNull()
        vm.onResign()
        runCurrent()
        vm.uiState.value.confirmResign shouldBe true
        vm.onConfirmResign()
        advanceUntilIdle()
        vm.uiState.value.endScreen shouldBe EndScreen.FINAL
        vm.uiState.value.snapshot.resigned shouldBe true
        vm.uiState.value.reveal.shouldBeNull()
    }

    @Test
    fun `§13_1 desistir no fim de partida e o dialogo nao reaparece na partida seguinte`() = runTest(dispatcher) {
        val vm = newGame(seed = 9, target = 1_000_000)
        var guard = 0
        while (vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 2_000) {
            settle(vm)
            val state = vm.uiState.value
            when {
                state.awaitingDraw -> if (state.canDraw) vm.onDraw() else vm.onTakeDiscardPile()
                state.playing -> {
                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card)))
                    vm.onDiscard()
                }
            }
            advanceUntilIdle()
        }
        vm.uiState.value.snapshot.stage shouldBe Stage.ROUND_OVER
        // Abre o diálogo e, sem responder, segue para a próxima partida: ele não volta sozinho.
        vm.onResign()
        runCurrent()
        vm.uiState.value.confirmResign shouldBe true
        vm.onNextRound()
        advanceUntilIdle()
        vm.uiState.value.snapshot.roundNumber shouldBe 2
        vm.uiState.value.confirmResign shouldBe false
        // Desistir no fim de partida também vale (§13.1 "a qualquer momento").
        settle(vm)
        vm.onResign()
        vm.onConfirmResign()
        advanceUntilIdle()
        vm.uiState.value.endScreen shouldBe EndScreen.FINAL
    }

    @Test
    fun `§8 o lixo so acende para descarte com Discard da carta selecionada em legal`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        val card = vm.uiState.value.hand.first()
        vm.select(listOf(card))
        advanceUntilIdle()
        val state = vm.uiState.value
        state.canDiscardSelected shouldBe state.snapshot.humanLegal.any {
            it is Action.Discard && it.card.cardClass == card.cardClass
        }
        state.canDiscardSelected shouldBe true
        // Sem o Discard dessa carta em legal, o lixo não acende, mesmo com uma carta selecionada.
        val withoutDiscard = state.copy(
            snapshot = state.snapshot.copy(
                humanLegal = state.snapshot.humanLegal.filterNot { it is Action.Discard && it.card.cardClass == card.cardClass },
            ),
        )
        withoutDiscard.canDiscard shouldBe true
        withoutDiscard.canDiscardSelected shouldBe false
    }

    @Test
    fun `§5_1 escolha de plano do lixo e enviada com o pedido em que foi resolvida`() = runTest(dispatcher) {
        // Procura uma situação com mais de um plano para as mesmas cartas da mão.
        var chosen = false
        seeds@ for (seed in 1L..150L) {
            val vm = newGame(seed)
            repeat(40) {
                val state = vm.uiState.value
                if (state.snapshot.stage != Stage.PLAYING) return@repeat
                if (state.awaitingDraw) {
                    val groups = state.snapshot.humanLegal.filterIsInstance<Action.TakeDiscardPile>()
                        .groupBy { HumanTurnResolver.handCardsOf(it).classCounts() }
                    val multi = groups.values.firstOrNull { it.size > 1 }
                    if (multi != null) {
                        val requestId = state.snapshot.humanRequestId
                        vm.select(state.physical(HumanTurnResolver.handCardsOf(multi.first())))
                        vm.onTakeDiscardPile()
                        advanceUntilIdle()
                        val options = vm.uiState.value.planChoice.shouldNotBeNull()
                        options.size shouldBe multi.size
                        vm.onPlanChosen(options.last())
                        advanceUntilIdle()
                        val after = vm.uiState.value
                        after.planChoice.shouldBeNull()
                        after.snapshot.turnEvents[0].first().action shouldBe options.last()
                        (after.snapshot.humanRequestId != requestId) shouldBe true
                        chosen = true
                        break@seeds
                    }
                }
                playMelding(vm)
            }
        }
        chosen shouldBe true
    }

    /** Uma vez do humano que baixa o que puder (para haver jogos do lado na mesa) e descarta. */
    private fun TestScope.playMelding(vm: GameViewModel) {
        if (!vm.uiState.value.awaitingDraw) return
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        var guard = 0
        while (vm.uiState.value.playing && guard++ < 10) {
            val state = vm.uiState.value
            val meld = state.snapshot.humanLegal.filterIsInstance<Action.CreateMeld>().firstOrNull()
                ?.takeIf { state.hand.size - it.cards.size >= 2 }
            if (meld != null) {
                vm.select(state.physical(meld.cards))
                vm.onCreateMeld()
            } else {
                val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                vm.select(state.physical(listOf(discard.card)))
                vm.onDiscard()
            }
            advanceUntilIdle()
            settle(vm)
        }
    }

    @Test
    fun `§3_5 reposicoes da distribuicao do humano ficam ocultas ate o Baixar e depois aparecem como novas`() = runTest(dispatcher) {
        var checked = false
        for (seed in 1L..80L) {
            val vm = newGame(seed, mode = GameMode.DUPLAS, settleReveals = false)
            val replacements = vm.uiState.value.snapshot.view.ownDealReplacements
            val reveal = vm.uiState.value.reveal
            if (reveal == null || !reveal.notice.atDeal || replacements.isEmpty()) continue
            // Durante a encenação (de todas as trocas do humano), a mão não mostra as reposições e a mesa está bloqueada.
            var guard = 0
            while (vm.uiState.value.reveal != null && guard++ < 8) {
                val state = vm.uiState.value
                state.hand.any { it in replacements } shouldBe false
                state.isHumanTurn shouldBe false
                vm.onRevealConfirmed()
                advanceUntilIdle()
            }
            val after = vm.uiState.value
            after.reveal.shouldBeNull()
            after.hand shouldContainAll replacements
            after.newCards shouldContainAll replacements
            checked = true
            break
        }
        checked shouldBe true
    }

    @Test
    fun `§9_4 3 vermelho que veio no morto pego pelo humano - nada oculto e as cartas do morto aparecem como novas`() = runTest(dispatcher) {
        var checked = false
        seeds@ for (seed in 1L..200L) {
            val vm = newGame(seed, target = 1_000_000)
            repeat(60) {
                val before = vm.uiState.value
                if (before.snapshot.stage != Stage.PLAYING) return@repeat
                if (!before.awaitingDraw) {
                    settle(vm)
                    return@repeat
                }
                val mortoBefore = before.snapshot.view.mortoStatus
                playMeldingNoSettle(vm)
                val after = vm.uiState.value
                val tookMorto = after.snapshot.view.mortoStatus.indices.any { i ->
                    after.snapshot.view.mortoStatus[i] == com.gtranca.engine.model.MortoStatus.Taken(Side(0)) &&
                        mortoBefore[i] != com.gtranca.engine.model.MortoStatus.Taken(Side(0))
                }
                val reveal = after.reveal
                if (tookMorto && reveal != null && !reveal.notice.atDeal) {
                    // As cartas do morto não ficam ocultas como "reposição" (§9.4).
                    reveal.hiddenHandCards.shouldBeEmpty()
                    (after.newCards.size >= 2) shouldBe true
                    after.hand.containsAll(after.newCards) shouldBe true
                    checked = true
                    break@seeds
                }
                settle(vm)
            }
        }
        checked shouldBe true
    }

    /** Uma vez do humano que baixa o que puder e descarta, sem confirmar a encenação final. */
    private fun TestScope.playMeldingNoSettle(vm: GameViewModel) {
        vm.onDraw()
        advanceUntilIdle()
        settle(vm)
        var guard = 0
        while (vm.uiState.value.playing && guard++ < 12) {
            val state = vm.uiState.value
            val legal = state.snapshot.humanLegal
            val meld = legal.filterIsInstance<Action.CreateMeld>().firstOrNull()
                ?: legal.filterIsInstance<Action.AddToMeld>().firstOrNull()
            val cards = when (meld) {
                is Action.CreateMeld -> meld.cards
                is Action.AddToMeld -> meld.cards
                else -> null
            }
            if (cards != null) {
                vm.select(state.physical(cards))
                if (meld is Action.AddToMeld) vm.onAddToMeld(meld.meldId) else vm.onCreateMeld()
            } else {
                val discard = legal.filterIsInstance<Action.Discard>().first()
                vm.select(state.physical(listOf(discard.card)))
                vm.onDiscard()
            }
            advanceUntilIdle()
            if (vm.uiState.value.reveal != null) return
        }
    }

    @Test
    fun `§5_1 tocar no lixo sem selecao abre a escolha com todos os planos e nada e aplicado ate escolher`() = runTest(dispatcher) {
        var checked = false
        seeds@ for (seed in 1L..120L) {
            val vm = newGame(seed, target = 1_000_000)
            repeat(30) {
                val state = vm.uiState.value
                if (state.snapshot.stage != Stage.PLAYING) return@repeat
                if (state.awaitingDraw && state.canTakeDiscardPile) {
                    val plans = state.snapshot.humanLegal.filterIsInstance<Action.TakeDiscardPile>()
                    vm.onClearSelection()
                    vm.onDiscardPileClick()
                    advanceUntilIdle()
                    val choice = vm.uiState.value.planChoice.shouldNotBeNull()
                    // Todos os planos, mesmo que haja um só; nada aplicado ainda.
                    choice.toSet() shouldBe plans.toSet()
                    vm.uiState.value.snapshot.view.phase shouldBe Phase.AWAITING_DRAW
                    vm.uiState.value.snapshot.turnEvents[0].none { it.action is Action.TakeDiscardPile } shouldBe true
                    // O botão "Pegar lixo" segue a mesma regra.
                    vm.onDismissPlanChoice()
                    vm.onTakeDiscardPile()
                    advanceUntilIdle()
                    vm.uiState.value.planChoice.shouldNotBeNull().toSet() shouldBe plans.toSet()
                    // Escolher um plano aplica exatamente esse plano.
                    val chosen = choice.last()
                    vm.onPlanChosen(chosen)
                    advanceUntilIdle()
                    settle(vm)
                    vm.uiState.value.snapshot.turnEvents[0].first().action shouldBe chosen
                    checked = true
                    break@seeds
                }
                drawAndDiscard(vm)
                settle(vm)
            }
        }
        checked shouldBe true
    }
}

