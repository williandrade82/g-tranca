package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MeldKind
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Rank
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.engine.model.Suit
import com.gtranca.engine.model.TableMeld
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Determinização da [PlayerView] (base do bot Difícil, ISMCTS): sorteia um [RoundState] completo e
 * coerente com o que o assento vê, preenchendo ao acaso as cartas ocultas (§3.2 mortos e §3.3 monte
 * virados para baixo; mãos alheias) e mantendo o que é visível (§5.6 lixo aberto, mesas, 3 vermelhos).
 */
class DeterminizationTest {

    // ---------- estados reais ----------

    /** Política aleatória: prefere baixar/acrescentar a descartar, e às vezes pega o lixo. */
    private fun choose(legal: List<Action>, random: Random): Action {
        val melds = legal.filter { it is Action.CreateMeld || it is Action.AddToMeld }
        val takes = legal.filterIsInstance<Action.TakeDiscardPile>()
        return when {
            takes.isNotEmpty() && random.nextInt(3) == 0 -> takes.random(random)
            melds.isNotEmpty() && random.nextInt(4) != 0 -> melds.random(random)
            else -> legal.filterNot { it is Action.TakeDiscardPile }.ifEmpty { legal }.random(random)
        }
    }

    /** Joga a partida até o fim com a política aleatória; devolve o estado final. */
    private fun playToEnd(start: RoundState, random: Random, maxActions: Int = 5_000): RoundState {
        var state = start
        var count = 0
        while (state.phase != Phase.FINISHED && count < maxActions) {
            val seat = state.currentSeat
            val legal = RoundEngine.legalActions(state, seat)
            withClue("beco sem saída em $state") { legal.isEmpty() shouldBe false }
            state = RoundEngine.apply(state, seat, choose(legal, random))
            count++
            state.allCards() shouldContainExactlyInAnyOrder Deck.standard()
            state.redThreeHandViolation() shouldBe null
            // §3.5 / §6.5 o mundo sorteado mantém o registro coerente ao continuar jogando
            state.redThreeLogViolation() shouldBe null
        }
        return state
    }

    /** Estados de uma partida real (distribuição + jogadas aleatórias até o fim), um a cada [every]. */
    private fun trajectory(mode: GameMode, seed: Long, every: Int): List<RoundState> {
        val random = Random(seed)
        var state = dealRound(mode, random)
        val states = mutableListOf(state)
        var count = 0
        while (state.phase != Phase.FINISHED && count < 5_000) {
            val seat = state.currentSeat
            state = RoundEngine.apply(state, seat, choose(RoundEngine.legalActions(state, seat), random))
            count++
            if (count % every == 0 || state.phase == Phase.FINISHED) states += state
        }
        return states
    }

    private fun hiddenCards(state: RoundState, seat: Seat): List<Card> =
        state.mode.seats.filter { it != seat }.flatMap { state.handOf(it) } + state.stock + state.mortos.flatten()

    // ---------- propriedades sobre estados reais ----------

    @Test
    fun `determinizacao de estados reais e coerente com a vista e aceita pelo motor`() {
        var checked = 0
        var playedOut = 0
        for (mode in GameMode.entries) {
            for (seed in 0L until 8L) {
                for ((i, state) in trajectory(mode, seed, every = 7).withIndex()) {
                    for (seat in mode.seats) {
                        val view = state.viewFor(seat)
                        val r = Random(seed * 1_000 + i * 10 + seat.index)
                        val sampled = view.determinize(r)
                        withClue("$mode semente $seed estado $i assento ${seat.index}") {
                            // §1 conserva as 104 cartas, sem duplicatas
                            sampled.allCards() shouldContainExactlyInAnyOrder Deck.standard()
                            // §3.2 §3.3 §5.6 o que é visível ao assento é preservado
                            sampled.viewFor(seat) shouldBe view
                            // as cartas ocultas sorteadas são exatamente as ocultas do estado real
                            hiddenCards(sampled, seat) shouldContainExactlyInAnyOrder hiddenCards(state, seat)
                            // legalActions é a única fonte de validade: o que o assento pode fazer só depende do que vê
                            RoundEngine.legalActions(sampled, seat) shouldBe RoundEngine.legalActions(state, seat)
                            // §3.5 / §9.4 3 vermelho só na mão de quem ainda não trocou os seus
                            sampled.redThreeHandViolation() shouldBe null
                        }
                        checked++
                        // o motor joga a partir do estado sorteado até o fim (amostra, para manter o teste rápido)
                        if (seat == state.currentSeat && i % 3 == 0) {
                            val end = playToEnd(sampled, Random(seed + i))
                            withClue("$mode semente $seed estado $i: partida sorteada não terminou") {
                                end.phase shouldBe Phase.FINISHED
                            }
                            playedOut++
                        }
                    }
                }
            }
        }
        checked shouldBeGreaterThan 200
        playedOut shouldBeGreaterThan 20
    }

    // ---------- determinismo e variação ----------

    @Test
    fun `mesma semente gera o mesmo estado e sementes diferentes variam as cartas ocultas`() {
        val view = dealRound(GameMode.DUPLAS, Random(7)).viewFor(Seat(1))
        view.determinize(Random(42)) shouldBe view.determinize(Random(42))
        val samples = (0L until 10L).map { view.determinize(Random(it)) }.toSet()
        samples.size shouldBe 10
        // §3.2 §3.3 só as cartas ocultas variam: a vista é sempre a mesma
        samples.forEach { it.viewFor(Seat(1)) shouldBe view }
    }

    @Test
    fun `cartas ocultas se distribuem ao acaso entre maos alheias, monte e mortos`() {
        // individual, logo após a distribuição: mão alheia 11, mortos 22, monte ~60
        val state = dealRound(GameMode.INDIVIDUAL, Random(3))
        val view = state.viewFor(Seat(0))
        val hiddenNatural = hiddenCards(state, Seat(0)).filterNot { it.isRedThree }
        val card = hiddenNatural.first()
        val handSlots = view.handSizes[1]
        val mortoSlots = view.mortoSizes.sum()
        val total = handSlots + view.stockSize + mortoSlots
        val n = 3_000
        var inHand = 0
        var inStock = 0
        var inMorto = 0
        val stockTops = mutableSetOf<Card>()
        repeat(n) { k ->
            val s = view.determinize(Random(10_000L + k))
            when (card) {
                in s.handOf(Seat(1)) -> inHand++
                in s.stock -> inStock++
                else -> inMorto++
            }
            stockTops += s.stock.first()
        }
        // proporções esperadas = vagas / total de vagas ocultas (tolerância ampla e semente fixa: estável)
        fun expected(slots: Int) = n * slots / total
        inHand shouldBeInRange (expected(handSlots) * 7 / 10)..(expected(handSlots) * 13 / 10)
        inStock shouldBeInRange (expected(view.stockSize) * 8 / 10)..(expected(view.stockSize) * 12 / 10)
        inMorto shouldBeInRange (expected(mortoSlots) * 7 / 10)..(expected(mortoSlots) * 13 / 10)
        // o topo do monte não é sempre a mesma carta
        stockTops.size shouldBeGreaterThan 30
    }

    @Test
    fun `3 vermelho oculto cai no monte, nos mortos ou na mao de quem ainda nao comecou`() {
        // §3.5 / §6.5 os 3 vermelhos são trocados só na vez do dono: o assento 2 (que vê a partida logo após o
        // início da vez do primeiro jogador) sabe que só quem ainda não começou pode ter 3 vermelho na mão
        val state = dealRound(GameMode.DUPLAS, Random(11))
        val viewer = state.mode.nextSeat(state.mode.nextSeat(state.firstSeat)) // ainda não começou
        val view = state.viewFor(viewer)
        val begun = state.mode.seats.filter { view.hasBegunFirstTurn(it) }
        begun shouldContainExactlyInAnyOrder listOf(state.firstSeat)
        val hiddenRed = Deck.standard().filter { it.isRedThree } - view.redThrees.flatten().toSet() - view.hand.toSet()
        hiddenRed.size shouldBeGreaterThan 0
        var inStock = 0
        var inMorto = 0
        var inUnbegunHand = 0
        repeat(500) { k ->
            val s = view.determinize(Random(k.toLong()))
            // §3.5 quem já começou nunca tem 3 vermelho na mão
            s.handOf(state.firstSeat).none { it.isRedThree } shouldBe true
            s.redThreeHandViolation() shouldBe null
            hiddenRed.forEach { red ->
                when {
                    red in s.stock -> inStock++
                    s.mortos.any { red in it } -> inMorto++
                    else -> inUnbegunHand++
                }
            }
        }
        inStock shouldBeGreaterThan 0
        inMorto shouldBeGreaterThan 0
        inUnbegunHand shouldBeGreaterThan 0
        (inStock + inMorto + inUnbegunHand) shouldBe 500 * hiddenRed.size
    }

    @Test
    fun `depois que todos comecaram os 3 vermelhos ocultos so caem no monte ou nos mortos`() {
        // §3.5 com os dois assentos já tendo começado a vez, 3 vermelho na mão alheia é impossível
        val start = (0L until 200L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }.first { s ->
            val view = s.viewFor(s.firstSeat)
            (Deck.standard().filter { it.isRedThree } - view.redThrees.flatten().toSet() - view.hand.toSet()).isNotEmpty()
        }
        val afterFirst = start.act(start.currentSeat.index, Action.DrawFromStock).let { drawn ->
            drawn.act(start.currentSeat.index, Action.Discard(drawn.handOf(start.currentSeat).first { !it.isRedThree }))
        }
        afterFirst.turnsBegun shouldBe 2
        val viewer = start.firstSeat
        val view = afterFirst.viewFor(viewer)
        val other = afterFirst.mode.nextSeat(viewer)
        view.hasBegunFirstTurn(other) shouldBe true
        val hiddenRed = Deck.standard().filter { it.isRedThree } - view.redThrees.flatten().toSet() - view.hand.toSet()
        hiddenRed.size shouldBeGreaterThan 0
        repeat(300) { k ->
            val s = view.determinize(Random(k.toLong()))
            s.handOf(other).none { it.isRedThree } shouldBe true
            hiddenRed.forEach { red -> (red in s.stock || s.mortos.any { red in it }) shouldBe true }
        }
    }

    /**
     * Vista do assento 1 (o da vez) no estado [noStockNoMorto], com 1 dos 4 3 vermelhos escondido: saiu da mesa e a
     * mão do assento 0 ganhou uma vaga. [firstSeat] e [turnsBegun] dizem quem já começou; [unsettled] marca o morto indireto.
     */
    private fun viewWithHiddenRed(firstSeat: Seat, turnsBegun: Int, unsettled: List<Seat> = emptyList()): PlayerView {
        val state = noStockNoMorto().copy(firstSeat = firstSeat, turnsBegun = turnsBegun, unsettledMortoSeats = unsettled)
        val view = state.viewFor(Seat(1))
        return view.copy(
            redThrees = listOf(view.redThrees[0].drop(1), emptyList()),
            handSizes = listOf(view.handSizes[0] + 1, view.handSizes[1]),
        )
    }

    @Test
    fun `3 vermelho oculto na mao de assento que ainda nao comecou e aceito e vai para ela`() {
        // §3.5 o assento 0 ainda não teve a sua primeira vez (o 1 começou): o 3 vermelho que falta só pode estar na mão dele
        val view = viewWithHiddenRed(firstSeat = Seat(1), turnsBegun = 1)
        view.hasBegunFirstTurn(Seat(0)) shouldBe false
        val hiddenRed = noStockNoMorto().redThrees[0].first()
        repeat(20) { k ->
            val s = view.determinize(Random(k.toLong()))
            s.handOf(Seat(0)) shouldContain hiddenRed
            s.viewFor(Seat(1)) shouldBe view
            s.allCards() shouldContainExactlyInAnyOrder Deck.standard()
        }
    }

    @Test
    fun `3 vermelho oculto na mao de assento que ja comecou e uma vista incoerente`() {
        // §3.5 os dois já tiveram a vez: o 3 vermelho escondido não tem onde estar (sem monte nem morto)
        val view = viewWithHiddenRed(firstSeat = Seat(1), turnsBegun = 2)
        view.hasBegunFirstTurn(Seat(0)) shouldBe true
        shouldThrow<IllegalArgumentException> { view.determinize(Random(1)) }.message shouldContain "3 vermelho"
    }

    @Test
    fun `3 vermelho oculto na mao de quem pegou o morto indireto e aceito`() {
        // §9.4 o assento 0 já começou a vez, mas pegou o morto indireto e ainda não o resolveu: o 3 vermelho pode estar lá
        val view = viewWithHiddenRed(firstSeat = Seat(1), turnsBegun = 2, unsettled = listOf(Seat(0)))
        val hiddenRed = noStockNoMorto().redThrees[0].first()
        val s = view.determinize(Random(3))
        s.handOf(Seat(0)) shouldContain hiddenRed
        s.unsettledMortoSeats shouldBe listOf(Seat(0))
        s.viewFor(Seat(1)) shouldBe view
    }

    @Test
    fun `3 vermelho na propria mao de quem ja trocou os seus e uma vista incoerente`() {
        // §3.5 / §6.5 a própria mão só tem 3 vermelho se o assento ainda não começou (ou tem morto indireto pendente)
        val state = dealRound(GameMode.INDIVIDUAL, Random(5))
        val begunSeat = state.firstSeat
        val view = state.viewFor(begunSeat)
        val someRed = (Deck.standard().filter { it.isRedThree } - view.redThrees.flatten().toSet() - view.hand.toSet()).first()
        val withRed = view.copy(hand = view.hand.drop(1) + someRed)
        shouldThrow<IllegalArgumentException> { withRed.determinize(Random(1)) }.message shouldContain "própria mão"
    }

    @Test
    fun `a vista do assento que ainda nao comecou mostra e determiniza os 3 vermelhos da propria mao`() {
        // §3.5 o dono vê os 3 vermelhos que tem; a determinização os mantém na mão dele, na mesma ordem
        val state = (0L until 200L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }
            .first { s -> s.handOf(s.mode.nextSeat(s.firstSeat)).any { it.isRedThree } }
        val owner = state.mode.nextSeat(state.firstSeat)
        val view = state.viewFor(owner)
        view.hand.any { it.isRedThree } shouldBe true
        val s = view.determinize(Random(1))
        s.handOf(owner) shouldBe state.handOf(owner)
        s.viewFor(owner) shouldBe view
    }

    // ---------- cartas conhecidas ----------

    @Test
    fun `cartas conhecidas vao para a mao do assento indicado`() {
        // §5.6 o assento sabe, por exemplo, quais cartas o outro levou ao pegar o lixo
        val state = dealRound(GameMode.DUPLAS, Random(5))
        val view = state.viewFor(Seat(0))
        val partner = state.handOf(Seat(2)).take(4)
        val opponent = state.handOf(Seat(3)).take(11)
        val known = mapOf(Seat(2) to partner, Seat(3) to opponent)
        repeat(50) { k ->
            val s = view.determinize(Random(k.toLong()), known)
            s.handOf(Seat(2)) shouldContainAll partner
            s.handOf(Seat(3)) shouldContainExactlyInAnyOrder opponent
            s.viewFor(Seat(0)) shouldBe view
            s.allCards() shouldContainExactlyInAnyOrder Deck.standard()
        }
    }

    @Test
    fun `cartas conhecidas vazias equivalem a nao informar`() {
        val view = dealRound(GameMode.INDIVIDUAL, Random(9)).viewFor(Seat(1))
        view.determinize(Random(1), mapOf(Seat(0) to emptyList())) shouldBe view.determinize(Random(1))
    }

    @Test
    fun `carta conhecida visivel e rejeitada`() {
        val state = dealRound(GameMode.INDIVIDUAL, Random(5))
        val view = state.viewFor(Seat(0))
        val visible = view.hand.first()
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(1) to listOf(visible)))
        }.message shouldContain "visível"
        // §6.5 3 vermelho baixado na mesa também é visível
        val dealtWithRed = (0L until 50L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }
            .first { it.redThrees.flatten().isNotEmpty() }
        val red = dealtWithRed.redThrees.flatten().first()
        shouldThrow<IllegalArgumentException> {
            dealtWithRed.viewFor(Seat(0)).determinize(Random(1), mapOf(Seat(1) to listOf(red)))
        }.message shouldContain "visível"
    }

    @Test
    fun `mais cartas conhecidas do que o tamanho da mao e rejeitado`() {
        val state = dealRound(GameMode.INDIVIDUAL, Random(5))
        val view = state.viewFor(Seat(0))
        val tooMany = hiddenCards(state, Seat(0)).filterNot { it.isRedThree }.take(view.handSizes[1] + 1)
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(1) to tooMany))
        }.message shouldContain "tamanho da mão"
    }

    @Test
    fun `carta conhecida repetida e rejeitada`() {
        // §1 há só 2 cópias de cada carta, e cada cópia é única: a mesma carta não pode estar em dois lugares
        val state = dealRound(GameMode.DUPLAS, Random(5))
        val view = state.viewFor(Seat(0))
        val card = state.handOf(Seat(1)).first { !it.isRedThree }
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(1) to listOf(card, card)))
        }.message shouldContain "repetida"
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(1) to listOf(card), Seat(3) to listOf(card)))
        }.message shouldContain "repetida"
    }

    @Test
    fun `3 vermelho conhecido em mao so e aceito de assento que ainda nao comecou`() {
        // §3.5 3 vermelho só fica na mão até o início da vez do dono: o primeiro jogador já trocou os seus
        val state = (0L until 50L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }
            .first { (it.stock + it.mortos.flatten()).any(Card::isRedThree) }
        val red = (state.stock + state.mortos.flatten()).first { it.isRedThree }
        val begun = state.firstSeat
        val notBegun = state.mode.nextSeat(begun)
        shouldThrow<IllegalArgumentException> {
            state.viewFor(notBegun).determinize(Random(1), mapOf(begun to listOf(red)))
        }.message shouldContain "3 vermelho"
        // já o assento que ainda não começou pode ter o 3 vermelho conhecido na mão
        val s = state.viewFor(begun).determinize(Random(1), mapOf(notBegun to listOf(red)))
        s.handOf(notBegun) shouldContain red
        s.viewFor(begun) shouldBe state.viewFor(begun)
    }

    @Test
    fun `cartas conhecidas do proprio assento ou de assento fora do modo sao rejeitadas`() {
        val state = dealRound(GameMode.INDIVIDUAL, Random(5))
        val view = state.viewFor(Seat(0))
        val hidden = state.handOf(Seat(1)).first()
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(0) to listOf(hidden)))
        }.message shouldContain "próprio assento"
        shouldThrow<IllegalArgumentException> {
            view.determinize(Random(1), mapOf(Seat(2) to listOf(hidden)))
        }
    }

    // ---------- vistas incoerentes e casos-limite ----------

    /**
     * Estado completo (104 cartas) sem monte e sem mortos: os 4 3 vermelhos na mesa do lado 0, mão do
     * assento 0 com 10 cartas, K♠' no lixo e o resto na mão do assento 1, que é o da vez e, como tem reis,
     * pode pegar o lixo (§10/§11.2: senão a partida já teria terminado sem vencedor).
     */
    private fun noStockNoMorto(discardPile: List<Card> = listOf(Card.parse("KS'"))): RoundState {
        val deck = Deck.standard()
        val reds = deck.filter { it.isRedThree }
        val rest = deck - reds.toSet()
        return RoundState(
            mode = GameMode.INDIVIDUAL,
            hands = listOf(rest.take(10), rest.drop(10) - discardPile.toSet()),
            stock = emptyList(),
            discardPile = discardPile,
            mortos = listOf(emptyList(), emptyList()),
            redThrees = listOf(reds, emptyList()),
            tables = listOf(SideTable(), SideTable()),
            firstSeat = Seat(0),
            currentSeat = Seat(1),
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.BecameStock),
        )
    }

    @Test
    fun `sem monte nem mortos todas as cartas ocultas vao para as maos alheias`() {
        val state = noStockNoMorto()
        val view = state.viewFor(Seat(0))
        val s = view.determinize(Random(1))
        s.handOf(Seat(1)) shouldContainExactlyInAnyOrder state.handOf(Seat(1))
        s.viewFor(Seat(0)) shouldBe view
        // a ordem da mão alheia sorteada não tem significado (e muda as cópias que o gerador escolhe): compara
        // as ações do assento 1 com as mãos em ordem canônica
        fun sorted(r: RoundState) = r.copy(hands = r.hands.map { h -> h.sortedWith(compareBy({ it.suit }, { it.rank }, { it.deck })) })
        RoundEngine.legalActions(sorted(s), Seat(1)) shouldBe RoundEngine.legalActions(sorted(state), Seat(1))
    }

    @Test
    fun `3 vermelho oculto sem lugar fora das maos e uma vista incoerente`() {
        // §6.5 se um 3 vermelho não está na mesa, ele está no monte ou num morto. Sem monte nem morto, a vista
        // que esconde um 3 vermelho é impossível.
        val view = noStockNoMorto().viewFor(Seat(0))
        val incoherent = view.copy(
            redThrees = listOf(view.redThrees[0].drop(1), emptyList()),
            handSizes = listOf(view.handSizes[0], view.handSizes[1] + 1),
        )
        shouldThrow<IllegalArgumentException> { incoherent.determinize(Random(1)) }.message shouldContain "3 vermelho"
    }

    @Test
    fun `vista com contagem de cartas incoerente e rejeitada`() {
        val view = dealRound(GameMode.INDIVIDUAL, Random(2)).viewFor(Seat(0))
        shouldThrow<IllegalArgumentException> {
            view.copy(stockSize = view.stockSize + 1).determinize(Random(1))
        }.message shouldContain "104"
        shouldThrow<IllegalArgumentException> {
            view.copy(handSizes = listOf(view.handSizes[0] + 1, view.handSizes[1] - 1)).determinize(Random(1))
        }.message shouldContain "própria mão"
        shouldThrow<IllegalArgumentException> {
            view.copy(discardPile = listOf(view.hand.first())).determinize(Random(1))
        }.message shouldContain "repetida"
    }

    @Test
    fun `morto indisponivel com cartas e uma vista incoerente`() {
        // §9.1 §10.1 morto pego ou que virou monte não tem mais cartas
        val view = dealRound(GameMode.INDIVIDUAL, Random(2)).viewFor(Seat(0))
        val incoherent = view.copy(mortoStatus = listOf(MortoStatus.BecameStock, MortoStatus.Available))
        shouldThrow<IllegalArgumentException> { incoherent.determinize(Random(1)) }.message shouldContain "morto"
    }

    // ---------- vista incoerente com as regras (auditoria) ----------

    /** Estado recém-distribuído com algum 3 vermelho na mesa do lado 0 (§3.5/§6.5). */
    private fun dealtWithRedThreeOnSide0(): RoundState =
        (0L until 200L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }.first { it.redThrees[0].isNotEmpty() }

    @Test
    fun `lado diferente do lado do assento e uma vista incoerente`() {
        // §1.1 o lado do assento é dado pelo modo; com outro lado, viewFor(seat) não devolveria esta vista
        val view = dealRound(GameMode.DUPLAS, Random(5)).viewFor(Seat(0))
        shouldThrow<IllegalArgumentException> {
            view.copy(side = Side(1)).determinize(Random(1))
        }.message shouldContain "lado"
    }

    @Test
    fun `carta que nao e 3 vermelho na area de 3 vermelhos e uma vista incoerente`() {
        // §6.5 só 3 vermelhos vão para a área de 3 vermelhos; troca um 3 vermelho por um 3 preto oculto
        val state = dealtWithRedThreeOnSide0()
        val view = state.viewFor(Seat(0))
        val red = view.redThrees[0].first()
        val black = hiddenCards(state, Seat(0)).first { it.isBlackThree }
        val incoherent = view.copy(redThrees = listOf(view.redThrees[0] - red + black, view.redThrees[1]))
        shouldThrow<IllegalArgumentException> { incoherent.determinize(Random(1)) }.message shouldContain
            "não é 3 vermelho"
    }

    @Test
    fun `3 vermelho no lixo e uma vista incoerente`() {
        // §6.5 3 vermelho é baixado na hora e não pode ser descartado: nunca está no lixo
        val view = dealtWithRedThreeOnSide0().viewFor(Seat(0))
        val red = view.redThrees[0].first()
        val incoherent = view.copy(
            redThrees = listOf(view.redThrees[0] - red, view.redThrees[1]),
            discardPile = view.discardPile + red,
        )
        shouldThrow<IllegalArgumentException> { incoherent.determinize(Random(1)) }.message shouldContain
            "3 vermelho no lixo"
    }

    /** A vista com um conjunto (montado sem validação) na mesa do lado 0, feito com [fromHand] e [extra]. */
    private fun PlayerView.withRawMeld(kind: MeldKind, fromHand: List<Card>, extra: List<Card> = emptyList()): PlayerView {
        val meld = Meld(kind, fromHand + extra)
        return copy(
            hand = hand - fromHand.toSet(),
            handSizes = handSizes.mapIndexed { i, n -> if (i == seat.index) n - fromHand.size else n },
            tables = listOf(SideTable(listOf(TableMeld(MeldId(0), meld)), nextMeldId = 1), tables[1]),
        )
    }

    @Test
    fun `3 vermelho num conjunto na mesa e uma vista incoerente`() {
        // §6.2 §6.5 nenhum 3 compõe conjunto; 3 vermelho vai só para a área de 3 vermelhos
        val view = dealtWithRedThreeOnSide0().viewFor(Seat(0))
        val red = view.redThrees[0].first()
        val pair = view.hand.filterNot { it.isWild || it.rank.isThree }.take(2)
        val incoherent = view.copy(redThrees = listOf(view.redThrees[0] - red, view.redThrees[1]))
            .withRawMeld(MeldKind.Group(pair.first().rank), pair, listOf(red))
        shouldThrow<IllegalArgumentException> { incoherent.determinize(Random(1)) }.message shouldContain
            "3 vermelho na mesa"
    }

    @Test
    fun `conjunto invalido na mesa e uma vista incoerente`() {
        // §6.1 §6.2 a mesa só tem conjuntos válidos (mesma validação de Meld.create)
        val view = dealRound(GameMode.INDIVIDUAL, Random(5)).viewFor(Seat(0))
        val naturals = view.hand.filterNot { it.isWild || it.rank.isThree }
        // 2 cartas: abaixo do mínimo de 3 (§6.2)
        val two = naturals.take(2)
        shouldThrow<IllegalArgumentException> {
            view.withRawMeld(MeldKind.Group(two.first().rank), two).determinize(Random(1))
        }.message shouldContain "conjunto inválido"
        // 3 cartas que não são sequência nem grupo (§6.1)
        val mixed = naturals.windowed(3).first { Meld.create(it) is RuleResult.Failure }
        shouldThrow<IllegalArgumentException> {
            view.withRawMeld(MeldKind.Group(mixed.first().rank), mixed).determinize(Random(1))
        }.message shouldContain "conjunto inválido"
    }

    @Test
    fun `conjunto valido com tipo trocado e uma vista incoerente`() {
        // §6.1 o tipo do conjunto é o das cartas: 5-6-7♥ é sequência de copas, não grupo de 5
        val view = dealRound(GameMode.INDIVIDUAL, Random(5)).viewFor(Seat(0))
        val cards = listOf("5H", "6H", "7H").map(Card::parse)
        // usa uma vista em que essas 3 cartas estão na própria mão
        val base = swapIntoHand(view, cards)
        shouldThrow<IllegalArgumentException> {
            base.withRawMeld(MeldKind.Group(Rank.FIVE), cards).determinize(Random(1))
        }.message shouldContain "conjunto inválido"
        // a mesma mesa com o tipo certo é aceita
        base.withRawMeld(MeldKind.Sequence(Suit.HEARTS), cards).determinize(Random(1))
    }

    /** Põe [cards] (ocultas ou na mão) na própria mão, trocando-as por cartas comuns da mão. */
    private fun swapIntoHand(view: PlayerView, cards: List<Card>): PlayerView {
        val missing = cards - view.hand.toSet()
        require(missing.none { it in view.discardPile }) { "carta do teste está no lixo: escolha outra semente" }
        val out = view.hand.filterNot { it in cards }.take(missing.size)
        return view.copy(hand = view.hand - out.toSet() + missing)
    }

    // ---------- §10 / §11.2 vez alheia com monte e mortos esgotados ----------

    @Test
    fun `lixo vazio ou travado na vez alheia sem monte nem morto e uma vista incoerente`() {
        // §10/§11.2 o da vez, sem monte nem morto, tem de poder pegar o lixo; senão a partida já teria terminado
        val empty = noStockNoMorto(discardPile = emptyList()).viewFor(Seat(0))
        shouldThrow<IllegalArgumentException> { empty.determinize(Random(1)) }.message shouldContain "pegar o lixo"
        // §5.3 3 preto no topo trava o lixo
        val locked = noStockNoMorto(discardPile = listOf(Card.parse("3S'"))).viewFor(Seat(0))
        shouldThrow<IllegalArgumentException> { locked.determinize(Random(1)) }.message shouldContain "pegar o lixo"
        // a regra vale também na vez do próprio assento (cuja mão é conhecida)
        val own = noStockNoMorto(discardPile = emptyList()).viewFor(Seat(1))
        shouldThrow<IllegalArgumentException> { own.determinize(Random(1)) }.message shouldContain "pegar o lixo"
        noStockNoMorto().viewFor(Seat(1)).determinize(Random(1)).viewFor(Seat(1)) shouldBe noStockNoMorto().viewFor(Seat(1))
    }

    /**
     * Duplas, sem monte nem mortos, vez do assento 1 (que vai comprar). O assento 0 vê quase tudo: só 12 cartas
     * ocultas (mãos dos assentos 1, 2 e 3). O topo do lixo é K♠; o assento 1 (3 cartas) só pode pegá-lo com
     * os 2 reis ocultos, o que acontece em ~5% das distribuições sem condicionamento.
     */
    private fun duplasOpponentMustTake(withKings: Boolean = true): RoundState {
        val deck = Deck.standard()
        val reds = deck.filter { it.isRedThree }
        val kings = if (withKings) listOf("KH", "KD") else listOf("4S", "5S")
        val junk = listOf("3S", "3S'", "3C", "3C'", "4H", "5D", "6C", "7H", "8D", "9C")
        val hidden = (kings + junk).map(Card::parse)
        val discard = listOf("TH", "TH'", "KS").map(Card::parse)
        val own = deck - reds.toSet() - hidden.toSet() - discard.toSet()
        return RoundState(
            mode = GameMode.DUPLAS,
            hands = listOf(own, listOf(hidden[0], hidden[1], hidden[2]), hidden.subList(3, 8), hidden.subList(8, 12)),
            stock = emptyList(),
            discardPile = discard,
            mortos = listOf(emptyList(), emptyList()),
            redThrees = listOf(reds, emptyList()),
            tables = listOf(SideTable(), SideTable()),
            firstSeat = Seat(0),
            currentSeat = Seat(1),
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.BecameStock),
        )
    }

    private fun canTake(state: RoundState, seat: Seat): Boolean =
        RoundEngine.legalActions(state, seat).any { it is Action.TakeDiscardPile }

    @Test
    fun `vez alheia sem monte nem morto so sorteia maos em que o da vez pode pegar o lixo`() {
        // §10/§11.2 se o da vez não pudesse pegar o lixo, a partida já teria terminado sem vencedor
        val state = duplasOpponentMustTake()
        canTake(state, Seat(1)) shouldBe true
        val view = state.viewFor(Seat(0))
        val thirdCards = mutableSetOf<Card>()
        repeat(200) { k ->
            val s = view.determinize(Random(k.toLong()))
            withClue("semente $k: ${s.handOf(Seat(1))}") {
                canTake(s, Seat(1)) shouldBe true
                s.viewFor(Seat(0)) shouldBe view
                s.allCards() shouldContainExactlyInAnyOrder Deck.standard()
            }
            thirdCards += s.handOf(Seat(1)).filterNot { it.rank == Rank.KING }
        }
        // a 3ª carta do assento 1 continua sorteada entre as 10 restantes
        thirdCards.size shouldBeGreaterThan 5
        view.determinize(Random(9)) shouldBe view.determinize(Random(9))
    }

    @Test
    fun `vez alheia sem monte nem morto em que ninguem pode pegar o lixo e uma vista incoerente`() {
        // §10/§11.2 nenhuma distribuição das ocultas deixa o da vez pegar o lixo: a partida já teria terminado
        val view = duplasOpponentMustTake(withKings = false).viewFor(Seat(0))
        shouldThrow<IllegalArgumentException> { view.determinize(Random(1)) }.message shouldContain "pegar o lixo"
    }

    @Test
    fun `partida encerrada tambem e determinizada`() {
        val end = trajectory(GameMode.DUPLAS, 4, every = 1_000).last()
        end.phase shouldBe Phase.FINISHED
        val view = end.viewFor(Seat(3))
        val s = view.determinize(Random(1))
        s.result shouldBe end.result
        s.viewFor(Seat(3)) shouldBe view
        RoundEngine.legalActions(s, s.currentSeat) shouldBe emptyList()
    }
}
