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
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
        animationMillis: Long = 0,
    ): GameViewModel {
        val vm = GameViewModel(
            GameConfig(mode, Difficulty.FACIL, target),
            gameSeed = seed,
            computeDispatcher = dispatcher,
            botDelayMillis = botDelayMillis,
            swapAnimationMillis = swapMillis,
            animationMillis = animationMillis,
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
    fun `§14_1 cada assento tem um perfil - o do jogador no seu e os sorteados pela semente nos outros`() = runTest(dispatcher) {
        val individual = newGame(seed = 11)
        individual.personas.size shouldBe 2
        individual.personas[0].fullName shouldBe "Você"
        individual.uiState.value.personas shouldBe individual.personas
        val duplas = newGame(seed = 11, mode = GameMode.DUPLAS)
        duplas.personas.size shouldBe 4
        duplas.personas.drop(1).map { it.firstName }.toSet().size shouldBe 3
        // Mesmo jogo, mesma semente: as mesmas pessoas ("Continuar").
        newGame(seed = 11, mode = GameMode.DUPLAS).personas shouldBe duplas.personas
        newGame(seed = 12, mode = GameMode.DUPLAS).personas shouldNotBe duplas.personas
    }

    @Test
    fun `§14_4 o jogo da mesa escolhida joga com exatamente os personagens que a mesa mostrou`() = runTest(dispatcher) {
        for (seed in listOf(3L, 42L, 77L)) {
            for (table in com.gtranca.game.Tables.botTables(seed)) {
                val vm = GameViewModel(table.toConfig(), gameSeed = table.seed, computeDispatcher = dispatcher, botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0)
                advanceUntilIdle()
                // os assentos 1.. são os personagens da mesa, na ordem (o 0 é o jogador)
                vm.personas.drop(1) shouldBe table.players
            }
        }
    }

    @Test
    fun `§14_4 Continuar mostra os mesmos personagens mesmo que o perfil seja renomeado para o nome de um robô`() = runTest(dispatcher) {
        val original = newGame(seed = 11, mode = GameMode.DUPLAS)
        val colliding = original.personas[1].firstName
        // jogo salvo com o nome usado no sorteio ("" = perfil sem nome); o perfil agora tem o nome de um adversário
        val resumed = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
            gameSeed = 11,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            swapAnimationMillis = 0,
            animationMillis = 0,
            restored = com.gtranca.game.RestoredGame(
                com.gtranca.engine.startMatch(GameMode.DUPLAS, 3000, kotlin.random.Random(11)),
                emptyList(),
                emptyList(),
                playerName = "",
            ),
            playerProfile = com.gtranca.game.PlayerProfile(name = colliding),
        )
        advanceUntilIdle()
        resumed.personas.drop(1) shouldBe original.personas.drop(1)
        // jogo salvo antigo (sem o nome do jogador): os nomes dos robôs são fixos, então nada muda
        val old = GameViewModel(
            GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000),
            gameSeed = 11,
            computeDispatcher = dispatcher,
            botDelayMillis = 0,
            swapAnimationMillis = 0,
            animationMillis = 0,
            restored = com.gtranca.game.RestoredGame(com.gtranca.engine.startMatch(GameMode.DUPLAS, 3000, kotlin.random.Random(11)), emptyList()),
            playerProfile = com.gtranca.game.PlayerProfile(name = colliding),
        )
        advanceUntilIdle()
        old.personas.drop(1).map { it.character } shouldBe original.personas.drop(1).map { it.character }
        old.personas.drop(1).map { it.firstName } shouldBe original.personas.drop(1).map { it.firstName }
    }

    @Test
    fun `§6_5 nenhuma troca de 3 vermelho de outro assento aparece durante a vez do humano`() = runTest(dispatcher) {
        // Tempos reais (troca encenada em 1,8 s, voo de 450 ms), com o bot pensando 700 ms e sem pausa nenhuma (o caso do
        // morto indireto: a troca vem na mesma ação que passa a vez). A vez do humano só abre depois de todas as trocas dos
        // outros assentos terem sido mostradas e de os voos delas terem pousado.
        var humanTurns = 0
        for (botDelay in listOf(700L, 0L)) {
            for (mode in GameMode.entries) {
                for (seed in 1L..25L) {
                    val vm = newGame(seed, mode = mode, botDelayMillis = botDelay, swapMillis = 1_800, animationMillis = 450, settleReveals = false)
                    val firstSeen = mutableMapOf<Long, Long>()
                    var turns = 0
                    var guard = 0
                    while (turns < 12 && vm.uiState.value.snapshot.stage == Stage.PLAYING && guard++ < 4_000) {
                        val state = vm.uiState.value
                        val now = testScheduler.currentTime
                        state.flights.filter { it.to is AnimAnchor.RedThrees }.forEach { firstSeen.putIfAbsent(it.id, now) }
                        when {
                            state.reveal != null -> vm.onRevealConfirmed()
                            state.isHumanTurn -> {
                                // Na vez do humano nenhuma troca de outro assento está pendente nem em cena.
                                state.banner shouldBe null
                                state.redThrees shouldBe state.snapshot.view.redThrees
                                // Nem o voo de um 3 vermelho dos outros ao seu lugar (a troca encenada) ainda está no ar.
                                state.flights.filter { it.to is AnimAnchor.RedThrees && it.from != AnimAnchor.OwnHand }.forEach { flight ->
                                    val landed = firstSeen.getValue(flight.id) + flight.delayMillis + 450
                                    withClue("modo $mode, semente $seed, bot $botDelay ms: voo $flight pousa em $landed e já é $now") {
                                        (now >= landed - 100) shouldBe true // 100 ms: a granularidade da varredura do teste
                                    }
                                }
                                humanTurns++
                                turns++
                                if (state.awaitingDraw) vm.onDraw() else {
                                    val discard = state.snapshot.humanLegal.filterIsInstance<Action.Discard>().first()
                                    vm.select(state.physical(listOf(discard.card)))
                                    vm.onDiscard()
                                }
                            }
                        }
                        advanceTimeBy(100)
                        runCurrent()
                    }
                }
            }
        }
        withClue("vezes do humano: $humanTurns") { (humanTurns > 200) shouldBe true }
    }

    @Test
    fun `§4_3 comeca na vez do humano com a mao por naipe, 3 pretos e coringas separados, e so a compra habilitada`() = runTest(dispatcher) {
        val vm = newGame(seed = 3)
        val state = vm.uiState.value
        state.isHumanTurn shouldBe true
        state.awaitingDraw shouldBe true
        state.canDraw shouldBe true
        state.canMeld shouldBe false
        state.sort shouldBe HandSort.BY_SUIT
        state.customHand shouldBe HandOrder.split(state.snapshot.view.hand, HandSort.BY_SUIT)
        state.hand shouldBe state.customHand!!.all
        // Valor: a coluna separada continua e o resto muda de critério.
        vm.onSortChange(HandSort.BY_RANK)
        advanceUntilIdle()
        vm.uiState.value.sort shouldBe HandSort.BY_RANK
        vm.uiState.value.customHand shouldBe HandOrder.split(state.snapshot.view.hand, HandSort.BY_RANK)
        // Desligar a separação: a mão toda segue o critério, sem coluna.
        vm.onSpecialColumnToggle()
        advanceUntilIdle()
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
                if (reveal != null && !reveal.notice.atTurnStart) {
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
            // §6.5 cadeia: só o 3 vermelho desta troca aparece na mão; o seguinte chega depois, como reposição dele.
            state.hand.filter { it.isRedThree } shouldBe current.notice.cards
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
    fun `§3_5 distribuicao do humano inclui os seus 3 vermelhos e cada troca do inicio traz a sua reposicao`() = runTest(dispatcher) {
        var checked = 0
        for (seed in 1L..80L) {
            val vm = GameViewModel(
                GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000),
                gameSeed = seed,
                computeDispatcher = dispatcher,
                botDelayMillis = 0,
                swapAnimationMillis = 0,
                animationMillis = 450,
            )
            val own = vm.uiState.value.snapshot.view.redThreeLog.filter { it.seat == Seat(0) && it.atTurnStart }
            if (own.isEmpty()) continue
            // Sem avançar o tempo: ainda é a distribuição. Chegam as 11 cartas, com os 3 vermelhos entre elas.
            val dealt = vm.uiState.value.flights.filter { it.to == AnimAnchor.OwnHand }.mapNotNull { it.card }
            dealt.size shouldBe 11
            dealt shouldContainAll own.filter { !it.chained }.map { it.card } // os em cadeia vêm do monte, depois
            advanceUntilIdle()
            var confirmed = 0
            while (true) {
                val reveal = vm.uiState.value.reveal ?: break
                confirmed++
                // Uma reposição por troca (a última leva o que sobrar); o 3 vermelho vai à mesa e a reposição chega do monte.
                if (confirmed < own.size) (reveal.hiddenHandCards.size <= 1) shouldBe true
                val hadReplacement = reveal.hiddenHandCards.isNotEmpty()
                vm.onRevealConfirmed()
                runCurrent()
                if (hadReplacement) {
                    vm.uiState.value.flights.any { it.from == AnimAnchor.Stock && it.to == AnimAnchor.OwnHand } shouldBe true
                }
                advanceUntilIdle()
            }
            confirmed shouldBe own.size
            vm.uiState.value.isHumanTurn shouldBe true
            checked++
            if (checked >= 3) break
        }
        (checked > 0) shouldBe true
    }

    @Test
    fun `§3_5 simulacao - o inicio com 3 vermelho (inclusive em cadeia) fecha a mao do humano nas duas velocidades`() = runTest(dispatcher) {
        var withSwaps = 0
        var withChain = 0
        for (slow in listOf(false, true)) {
            for (mode in GameMode.entries) {
                for (seed in 1L..120L) {
                    val vm = GameViewModel(
                        GameConfig(mode, Difficulty.FACIL, 3000),
                        gameSeed = seed,
                        computeDispatcher = dispatcher,
                        botDelayMillis = 0,
                        swapAnimationMillis = 1_000,
                        animationMillis = 450,
                        slowAnimations = slow,
                    )
                    val log = vm.uiState.value.snapshot.view.redThreeLog
                    val own = log.filter { it.seat == Seat(0) && it.atTurnStart }
                    val dealtOwn = vm.uiState.value.flights.filter { it.to == AnimAnchor.OwnHand && it.from == AnimAnchor.Stock }
                    val handSize = vm.uiState.value.snapshot.view.hand.size
                    if (own.isNotEmpty()) {
                        // A distribuição dá 11 cartas: as da mão (menos as repostas, que chegam depois) e os 3 vermelhos originais;
                        // os 3 vermelhos em cadeia vieram do monte e nunca estiveram na mão dada.
                        val originals = own.count { !it.chained }
                        dealtOwn.size shouldBe handSize // as 11 cartas dadas
                        dealtOwn.mapNotNull { it.card }.toSet() shouldContainAll own.filter { !it.chained }.map { it.card }
                        dealtOwn.mapNotNull { it.card }.any { c -> own.filter { it.chained }.any { it.card == c } } shouldBe false
                        withSwaps++
                        if (own.any { it.chained }) withChain++
                    }
                    // Escala das animações: voos da distribuição escalonados e duração efetiva conforme a velocidade.
                    vm.uiState.value.animationMillis shouldBe (if (slow) 585L else 450L)
                    advanceUntilIdle()
                    if (own.isEmpty()) continue
                    var guard = 0
                    var confirmed = 0
                    val seen = mutableListOf<Card>()
                    while (guard++ < 50) {
                        val state = vm.uiState.value
                        val reveal = state.reveal ?: break
                        confirmed++
                        // Antes de cada confirmação a mão mostrada não repete carta nem esconde o 3 vermelho em questão.
                        state.hand.size shouldBe state.hand.toSet().size
                        state.hand shouldContainAll reveal.notice.cards
                        seen += reveal.notice.cards
                        vm.onRevealConfirmed()
                        advanceUntilIdle()
                    }
                    // Todas as trocas do início foram confirmadas; os 3 vermelhos da mão saem juntos (§3.5, uma troca só) e cada
                    // um em cadeia é uma troca seguinte; na ordem do registro.
                    confirmed shouldBe ((if (own.any { !it.chained }) 1 else 0) + own.count { it.chained })
                    seen shouldBe own.map { it.card }
                    val state = vm.uiState.value
                    if (own.isNotEmpty()) {
                        // Mão final: igual à do motor, com 11 cartas, e os 3 vermelhos baixados estão na mesa.
                        state.hand.toSet() shouldBe state.snapshot.view.hand.toSet()
                        state.hand.size shouldBe handSize
                        state.redThrees[state.snapshot.viewerSide.index] shouldContainAll own.map { it.card }
                    }
                    // O jogo nunca trava: a vez do humano chega.
                    withClue("modo $mode, semente $seed, lenta=$slow") {
                        (state.isHumanTurn || state.snapshot.stage != Stage.PLAYING) shouldBe true
                    }
                }
            }
        }
        withClue("jogos com 3 vermelho do humano no início: $withSwaps, com cadeia: $withChain") {
            (withSwaps > 20) shouldBe true
            (withChain > 0) shouldBe true
        }
    }

    @Test
    fun `velocidade lenta deixa as animacoes 30 por cento mais lentas e muda ao vivo`() = runTest(dispatcher) {
        val normal = newGame(seed = 5, animationMillis = 450)
        normal.uiState.value.animationMillis shouldBe 450L
        normal.onSpeedChanged(true)
        runCurrent()
        normal.uiState.value.animationMillis shouldBe 585L
        normal.onSpeedChanged(false)
        runCurrent()
        normal.uiState.value.animationMillis shouldBe 450L
        // Com as animações do sistema desligadas (escala 0) a velocidade não as religa.
        normal.onAnimationScaleChanged(0f)
        normal.onSpeedChanged(true)
        runCurrent()
        normal.uiState.value.animationMillis shouldBe 0L
        // Os atrasos dos voos da distribuição também crescem 30%.
        fun delays(slow: Boolean): List<Long> = GameViewModel(
            GameConfig(GameMode.INDIVIDUAL, Difficulty.FACIL, 3000), gameSeed = 7, computeDispatcher = dispatcher,
            botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 450, slowAnimations = slow,
        ).uiState.value.flights.map { it.delayMillis }
        val fast = delays(false)
        val slowed = delays(true)
        fast.size shouldBe slowed.size
        fast.zip(slowed).all { (a, b) -> b == (a * 1.3f).toLong() } shouldBe true
        (fast.max() > 0) shouldBe true
    }

    @Test
    fun `§3_5 trocas do inicio da vez encenadas uma a uma na ordem do registro, antes de o dono jogar`() = runTest(dispatcher) {
        var checked = false
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
            settle(vm)
            repeat(12) {
                if (vm.uiState.value.awaitingDraw) drawAndDiscard(vm)
                settle(vm)
            }
            val log = vm.uiState.value.snapshot.view.redThreeLog
            // Todas as trocas foram mostradas, uma a uma, na ordem do registro, com quem trocou e a carta.
            presented.map { it.seat to it.cards.single() } shouldBe log.map { it.seat to it.card }
            presented.map { it.atTurnStart } shouldBe log.map { it.atTurnStart }
            if (log.any { it.atTurnStart && it.seat == Seat(0) } && log.any { it.atTurnStart && it.seat != Seat(0) }) checked = true
            presented.firstOrNull { !it.atTurnStart && it.seat != Seat(0) }?.let { playBanner = it }
            if (checked && playBanner != null) break
        }
        checked shouldBe true
        // Troca de outro assento durante o jogo (compra): aviso com quem trocou (a reposição dele não é revelada).
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
                RedThreeNotice(1, Side(1), Seat(1), listOf(Card.parse("3H")), atTurnStart = false),
                RedThreeNotice(2, Side(0), Seat(0), listOf(Card.parse("3D")), atTurnStart = false),
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
    fun `§3_5 reposicoes do inicio da vez do humano ficam ocultas ate o Baixar e depois aparecem como novas`() = runTest(dispatcher) {
        var checked = false
        for (seed in 1L..80L) {
            val vm = newGame(seed, mode = GameMode.DUPLAS, settleReveals = false)
            val reveal = vm.uiState.value.reveal
            if (reveal == null || !reveal.notice.atTurnStart) continue
            val replacements = reveal.hiddenHandCards
            if (replacements.isEmpty()) continue
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

    /** Uma ação do humano sem avançar o tempo virtual além do necessário: confirma, compra, baixa o que der ou descarta. */
    private fun TestScope.humanStep(vm: GameViewModel) {
        val state = vm.uiState.value
        when {
            state.reveal != null -> vm.onRevealConfirmed()
            state.awaitingDraw -> vm.onDraw()
            state.playing -> {
                val legal = state.snapshot.humanLegal
                val meld = legal.filterIsInstance<Action.CreateMeld>().firstOrNull() ?: legal.filterIsInstance<Action.AddToMeld>().firstOrNull()
                val cards = (meld as? Action.CreateMeld)?.cards ?: (meld as? Action.AddToMeld)?.cards
                if (cards != null) {
                    vm.select(state.physical(cards))
                    if (meld is Action.AddToMeld) vm.onAddToMeld(meld.meldId) else vm.onCreateMeld()
                } else {
                    val discard = legal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card)))
                    vm.onDiscard()
                }
            }
        }
        runCurrent()
    }

    @Test
    fun `§3_5 o aviso da troca de 3 vermelho do proximo jogador so aparece depois que o descarte anterior pousou`() = runTest(dispatcher) {
        var announced = 0
        for (mode in GameMode.entries) for (botDelay in listOf(0L, 300L, 700L)) {
            for (seed in 1L..40L) {
                val vm = newGame(seed, mode = mode, botDelayMillis = botDelay, swapMillis = 1_800, animationMillis = 450, settleReveals = false)
                var t = 0L
                withClue("modo $mode, bot pensa $botDelay ms, semente $seed") {
                    while (t < 60_000 && vm.uiState.value.snapshot.stage == Stage.PLAYING) {
                        humanStep(vm)
                        val state = vm.uiState.value
                        if (state.banner != null) {
                            announced++
                            // nenhum voo em andamento (descarte, compra, baixa) enquanto o aviso está na tela
                            state.flights.filter { it.id !in state.dealFlightIds }.shouldBeEmpty()
                        }
                        advanceTimeBy(50)
                        runCurrent()
                        t += 50
                    }
                }
            }
        }
        withClue("avisos de troca vistos: $announced") { (announced > 0) shouldBe true }
    }

    @Test
    fun `§9 ao pegar o morto as cartas dele so aparecem na mao do humano quando o voo pousa`() = runTest(dispatcher) {
        var checked = false
        seeds@ for (seed in 1L..200L) {
            val vm = newGame(seed, target = 1_000_000, animationMillis = 450, settleReveals = false)
            var t = 0L
            while (t < 120_000 && vm.uiState.value.snapshot.stage == Stage.PLAYING) {
                val mortoBefore = vm.uiState.value.snapshot.view.mortoStatus
                humanStep(vm)
                val after = vm.uiState.value
                val tookMorto = after.snapshot.view.mortoStatus.indices.any { i ->
                    (after.snapshot.view.mortoStatus[i] as? com.gtranca.engine.model.MortoStatus.Taken)?.side == Side(0) &&
                        (mortoBefore[i] as? com.gtranca.engine.model.MortoStatus.Taken)?.side != Side(0)
                }
                if (tookMorto) {
                    val flights = after.flights.filter { it.from is AnimAnchor.Morto && it.to == AnimAnchor.OwnHand }
                    flights.shouldNotBeEmpty()
                    // as cartas novas (inclusive as do morto sem voo) ficam fora da mão até o voo, ou o último voo, pousar
                    val hiddenUntilLanding = after.flights.flatMap { listOfNotNull(it.card) + it.also }.toSet()
                    after.newCards.all { it in hiddenUntilLanding } shouldBe true
                    checked = true
                    break@seeds
                }
                advanceTimeBy(50)
                runCurrent()
                t += 50
            }
        }
        checked shouldBe true
    }

    @Test
    fun `§6_5 o detalhe da ultima jogada traz os 3 vermelhos baixados na vez de cada assento`() = runTest(dispatcher) {
        var seenWithReds = 0
        for (mode in GameMode.entries) {
            for (seed in 1L..40L) {
                val vm = newGame(seed, mode = mode, botDelayMillis = 0)
                var t = 0L
                withClue("modo $mode, semente $seed") {
                    while (t < 30_000 && vm.uiState.value.snapshot.stage == Stage.PLAYING) {
                        humanStep(vm)
                        val snap = vm.uiState.value.snapshot
                        snap.turnRedThrees.size shouldBe mode.seatCount
                        snap.turnRedThrees.forEachIndexed { seat, cards ->
                            // só 3 vermelhos que o motor registrou para esse assento
                            val logged = snap.view.redThreeLog.filter { it.seat.index == seat }.map { it.card }
                            cards.all { it in logged } shouldBe true
                            if (cards.isNotEmpty()) seenWithReds++
                        }
                        advanceTimeBy(50)
                        runCurrent()
                        t += 50
                    }
                }
            }
        }
        withClue("vezes com 3 vermelho no detalhe: $seenWithReds") { (seenWithReds > 0) shouldBe true }
    }

    @Test
    fun `§9_4 3 vermelho que veio no morto pego pelo humano - as cartas do morto aparecem como novas e so as reposicoes ficam ocultas`() = runTest(dispatcher) {
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
                    (after.snapshot.view.mortoStatus[i] as? com.gtranca.engine.model.MortoStatus.Taken)?.side == Side(0) &&
                        (mortoBefore[i] as? com.gtranca.engine.model.MortoStatus.Taken)?.side != Side(0)
                }
                val reveal = after.reveal
                if (tookMorto && reveal != null && !reveal.notice.atTurnStart) {
                    // §9.4 as cartas do morto aparecem como novas; só as reposições do monte (uma por 3 vermelho baixado, menos as
                    // que são 3 vermelhos em cadeia) ficam ocultas até a troca.
                    (reveal.hiddenHandCards.size <= reveal.notice.cards.size) shouldBe true
                    after.hand.none { it in reveal.hiddenHandCards } shouldBe true
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

    @Test
    fun `§9_2 morto direto do humano (baixou tudo) - as cartas do morto voam ate a mao`() = runTest(dispatcher) {
        var checked = false
        seeds@ for (seed in 1L..300L) {
            val vm = newGame(seed, target = 1_000_000, animationMillis = 450)
            repeat(80) {
                val state = vm.uiState.value
                if (state.snapshot.stage != Stage.PLAYING) return@repeat
                if (state.reveal != null) { vm.onRevealConfirmed(); advanceUntilIdle(); return@repeat }
                if (!state.isHumanTurn) { advanceUntilIdle(); return@repeat }
                if (state.awaitingDraw) { vm.onDraw(); advanceUntilIdle(); return@repeat }
                val legal = state.snapshot.humanLegal
                val meld = legal.filterIsInstance<Action.CreateMeld>().maxByOrNull { it.cards.size }
                    ?: legal.filterIsInstance<Action.AddToMeld>().firstOrNull()
                if (meld == null) {
                    val discard = legal.filterIsInstance<Action.Discard>().first()
                    vm.select(state.physical(listOf(discard.card))); vm.onDiscard(); advanceUntilIdle(); return@repeat
                }
                val cards = if (meld is Action.CreateMeld) meld.cards else (meld as Action.AddToMeld).cards
                val emptiesHand = cards.size == state.snapshot.view.hand.size
                val mortoBefore = state.snapshot.view.mortoStatus
                vm.select(state.physical(cards))
                if (meld is Action.AddToMeld) vm.onAddToMeld(meld.meldId) else vm.onCreateMeld()
                runCurrent()
                if (emptiesHand) {
                    val after = vm.uiState.value
                    val i = after.snapshot.view.mortoStatus.indices.firstOrNull { k ->
                        after.snapshot.view.mortoStatus[k] is com.gtranca.engine.model.MortoStatus.Taken && mortoBefore[k] !is com.gtranca.engine.model.MortoStatus.Taken
                    } ?: -1
                    if (i >= 0) {
                        val morto = after.flights.filter { it.from == AnimAnchor.Morto(i) }
                        morto.isNotEmpty() shouldBe true
                        morto.all { it.to == AnimAnchor.OwnHand } shouldBe true
                        checked = true
                        break@seeds
                    }
                }
                advanceUntilIdle()
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
                    // O botão "Pegar" (lixo) segue a mesma regra.
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

