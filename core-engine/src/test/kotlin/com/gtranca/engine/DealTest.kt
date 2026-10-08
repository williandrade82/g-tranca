package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

class DealTest {

    private fun c(text: String) = Card.parse(text)

    /**
     * Monta um baralho de 104 cartas já ordenado (índice 0 = primeira carta distribuída).
     * As posições informadas recebem as cartas pedidas; as demais são preenchidas com cartas
     * que não são 3 vermelho, e os 3 vermelhos não usados ficam no fim (fundo do monte).
     */
    private fun orderedDeck(fixed: Map<Int, String> = emptyMap()): List<Card> {
        val placed = fixed.mapValues { c(it.value) }
        require(placed.values.toSet().size == placed.size) { "carta repetida no cenário" }
        val remaining = Deck.standard() - placed.values.toSet()
        val fillers = ArrayDeque(remaining.filterNot { it.isRedThree } + remaining.filter { it.isRedThree })
        return List(Deck.SIZE) { i -> placed[i] ?: fillers.removeFirst() }
    }

    private fun baseStockSize(mode: GameMode) = when (mode) {
        GameMode.INDIVIDUAL -> 60
        GameMode.DUPLAS -> 38
    }

    /** Compra uma carta e descarta a primeira que não é 3 vermelho: passa a vez ao assento seguinte. */
    private fun RoundState.drawAndDiscard(): RoundState {
        val seat = currentSeat.index
        val drawn = act(seat, Action.DrawFromStock)
        return drawn.act(seat, Action.Discard(drawn.hand(seat).first { !it.isRedThree }))
    }

    // ---------- §3.1 / §3.2 / §3.3 / §3.4 ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `cada jogador recebe 11 cartas`(mode: GameMode) {
        // §3.1 cada jogador recebe 11 cartas
        val state = dealRound(mode, Random(1))
        state.hands shouldHaveSize mode.seatCount
        state.hands.forEach { it shouldHaveSize 11 }
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `dois mortos de 11 cartas`(mode: GameMode) {
        // §3.2 2 mortos, cada um com 11 cartas
        val state = dealRound(mode, Random(1))
        state.mortos shouldHaveSize 2
        state.mortos.forEach { it shouldHaveSize 11 }
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `distribuicao nao troca nada e o monte tem 60 no individual e 38 em duplas`(mode: GameMode) {
        // §3.3 + §3.5 nada sai do monte na distribuição: 60 / 38 cartas, mesmo com 3 vermelhos nas mãos
        val deck = orderedDeck(mapOf(0 to "3H", 1 to "3D", 2 to "3H'", 3 to "3D'"))
        val state = distributeOrderedDeck(mode, deck, Seat(0))
        state.stock shouldHaveSize baseStockSize(mode)
        state.redThrees.flatten().shouldBeEmpty()
        state.redThreeLog.shouldBeEmpty()
        state.turnsBegun shouldBe 0
        state.hands.forEach { it shouldHaveSize 11 }
        state.handOf(Seat(0)) shouldContain c("3H")
        state.handOf(Seat(1)) shouldContain c("3D")
        state.currentSeat shouldBe Seat(0)
        state.phase shouldBe Phase.AWAITING_DRAW
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `monte diminui uma carta por reposicao do primeiro jogador`(mode: GameMode) {
        // §3.3 + §3.5 só o primeiro jogador troca no início da partida: monte = 60/38 − nº de reposições dele
        (0L until 50L).forEach { seed ->
            val state = dealRound(mode, Random(seed))
            val replacements = state.redThrees.sumOf { it.size }
            state.stock shouldHaveSize baseStockSize(mode) - replacements
        }
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `lixo comeca vazio`(mode: GameMode) {
        // §3.4 o lixo começa vazio
        dealRound(mode, Random(3)).discardPile.shouldBeEmpty()
    }

    @Test
    fun `distribuicao e uma carta por vez comecando pelo primeiro jogador`() {
        // §3.1 uma por vez, em ordem (a partir do primeiro jogador, §4.1)
        val deck = orderedDeck()
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, firstSeat = Seat(2))
        // ordem: assento 2, 3, 0, 1, 2, 3, ...
        state.handOf(Seat(2)) shouldContainExactly (0 until 44 step 4).map { deck[it] }
        state.handOf(Seat(3)) shouldContainExactly (1 until 44 step 4).map { deck[it] }
        state.handOf(Seat(0)) shouldContainExactly (2 until 44 step 4).map { deck[it] }
        state.handOf(Seat(1)) shouldContainExactly (3 until 44 step 4).map { deck[it] }
    }

    @Test
    fun `mortos e monte vem das cartas seguintes as maos`() {
        // §3.2 / §3.3 mortos formados após as mãos, uma carta por vez para cada morto, alternadamente;
        // restante é o monte (topo = índice 0)
        val deck = orderedDeck()
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, firstSeat = Seat(1))
        state.handOf(Seat(1)) shouldContainExactly (0 until 22 step 2).map { deck[it] }
        state.handOf(Seat(0)) shouldContainExactly (1 until 22 step 2).map { deck[it] }
        state.mortos[0] shouldContainExactly (22 until 44 step 2).map { deck[it] }
        state.mortos[1] shouldContainExactly (23 until 44 step 2).map { deck[it] }
        state.stock shouldContainExactly deck.subList(44, 104)
    }

    @Test
    fun `em duplas os mortos tambem alternam carta a carta apos as maos`() {
        // §3.2 mortos distribuídos alternadamente depois das 44 cartas das mãos
        val deck = orderedDeck()
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, firstSeat = Seat(0))
        state.mortos[0] shouldContainExactly (44 until 66 step 2).map { deck[it] }
        state.mortos[1] shouldContainExactly (45 until 66 step 2).map { deck[it] }
        state.stock shouldContainExactly deck.subList(66, 104)
    }

    @Test
    fun `assento inicial e assento da vez sao o primeiro jogador`() {
        // §4.1 o primeiro jogador inicia a partida
        val state = dealFromOrderedDeck(GameMode.DUPLAS, orderedDeck(), firstSeat = Seat(3))
        state.firstSeat shouldBe Seat(3)
        state.currentSeat shouldBe Seat(3)
    }

    @Test
    fun `distribuicao exige baralho completo e assento valido`() {
        shouldThrow<IllegalArgumentException> {
            dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck().drop(1), Seat(0))
        }
        shouldThrow<IllegalArgumentException> {
            dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(), Seat(2))
        }
        shouldThrow<IllegalArgumentException> {
            val deck = orderedDeck()
            dealFromOrderedDeck(GameMode.INDIVIDUAL, deck.dropLast(1) + deck.first(), Seat(0))
        }
    }

    // ---------- §3.5 / §6.5 ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `so o primeiro jogador troca na partida e as outras maos ficam como distribuidas`(mode: GameMode) {
        // §3.5 nada é trocado na distribuição: no início da partida só a mão do primeiro jogador (que vai comprar) é
        // trocada; a dos demais é exatamente a distribuída, com os 3 vermelhos
        (0L until 200L).forEach { seed ->
            val first = Seat((seed % mode.seatCount).toInt())
            val state = dealRound(mode, Random(seed), firstSeat = first)
            val pure = distributeOrderedDeck(mode, Deck.shuffled(Random(seed)), first)
            state.hands.forEach { it shouldHaveSize 11 }
            state.handOf(first).none { it.isRedThree } shouldBe true
            for (seat in mode.seats - first) state.handOf(seat) shouldBe pure.handOf(seat)
            state.turnsBegun shouldBe 1
            state.redThreeLog.all { it.seat == first && it.atTurnStart && !it.atDeal } shouldBe true
            state.redThreeLogViolation() shouldBe null
        }
    }

    @Test
    fun `3 vermelho do primeiro jogador e baixado e reposto do topo do monte antes da primeira compra`() {
        // §3.5 + §6.5 no início da vez do primeiro jogador, antes de comprar
        val deck = orderedDeck(mapOf(0 to "3H", 44 to "KS"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H"))
        state.redThreesOf(Side(1)).shouldBeEmpty()
        state.handOf(Seat(0)) shouldContain c("KS")
        state.handOf(Seat(0)) shouldNotContain c("3H")
        state.handOf(Seat(0)) shouldHaveSize 11
        state.stock shouldHaveSize 59
        state.stock shouldContainExactly deck.subList(45, 104)
        state.phase shouldBe Phase.AWAITING_DRAW // ainda vai comprar
        state.currentSeat shouldBe Seat(0)
        state.turnsBegun shouldBe 1
    }

    @Test
    fun `reposicao em cadeia no inicio da vez do primeiro jogador`() {
        // §6.5 reposição em cadeia; §3.5 o 3 vermelho do assento 1 continua na mão dele
        val deck = orderedDeck(
            mapOf(
                0 to "3H",  // 1ª carta do assento 0 (primeiro jogador)
                1 to "3D'", // 1ª carta do assento 1
                44 to "3D", // topo do monte
                45 to "3H'",
                46 to "KS",
                47 to "QD",
            ),
        )
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H"), c("3D"), c("3H'"))
        state.handOf(Seat(0)) shouldContain c("KS")
        state.handOf(Seat(0)) shouldHaveSize 11
        state.redThreesOf(Side(1)).shouldBeEmpty()
        state.handOf(Seat(1)) shouldContain c("3D'")
        state.handOf(Seat(1)) shouldHaveSize 11
        state.stock shouldHaveSize 60 - 3
        state.stock shouldContainExactly deck.subList(47, 104)
    }

    @Test
    fun `com o assento 1 iniciando so ele troca`() {
        // §3.5 a ordem de jogada define quem começa: o assento 0 mantém o 3 vermelho
        val deck = orderedDeck(mapOf(0 to "3H", 1 to "3D", 44 to "KS", 45 to "QD"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(1))
        state.handOf(Seat(1)) shouldContain c("KS")
        state.handOf(Seat(0)) shouldContain c("3D")
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H"))
        state.redThreesOf(Side(0)).shouldBeEmpty()
        state.stock shouldContainExactly deck.subList(45, 104)
    }

    @Test
    fun `em duplas o 3 vermelho vai para a mesa do lado de quem comeca e os demais seguram os seus`() {
        // §3.5 + §1.1 + §6.4 3 vermelhos pertencem ao lado (assentos 0 e 2 = lado 0; 1 e 3 = lado 1)
        val deck = orderedDeck(
            mapOf(
                1 to "3H",  // assento 2 (com primeiro jogador = 1) → lado 0
                2 to "3D",  // assento 3 → lado 1
                4 to "3H'", // assento 1 (2ª carta) → lado 1
                66 to "KS", // topo do monte em duplas
                67 to "QD",
            ),
        )
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(1))
        state.handOf(Seat(1)) shouldContain c("KS")
        state.handOf(Seat(2)) shouldContain c("3H")
        state.handOf(Seat(3)) shouldContain c("3D")
        state.redThreesOf(Side(0)).shouldBeEmpty()
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H'"))
        state.stock shouldHaveSize 38 - 1
    }

    @Test
    fun `o segundo jogador so troca no inicio da sua vez e a mao dele fica intacta ate la`() {
        // §3.5 o primeiro jogador troca tudo (inclusive em cadeia) antes de comprar; o seguinte só na sua vez
        val deck = orderedDeck(
            mapOf(
                0 to "3H",   // assento 3 (primeiro jogador) → lado 1
                1 to "3D",   // assento 0 → lado 0
                66 to "3H'", // reposição do assento 3, também 3 vermelho (cadeia)
                67 to "KS",  // fim da cadeia do assento 3
                68 to "QD",  // topo do monte depois do início da vez do assento 3: ele compra
            ),
        )
        val dealt = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(3))
        dealt.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H"), c("3H'"))
        dealt.handOf(Seat(3)) shouldContain c("KS")
        dealt.redThreesOf(Side(0)).shouldBeEmpty()
        dealt.handOf(Seat(0)) shouldContain c("3D") // intacta antes da vez dele
        dealt.currentSeat shouldBe Seat(3)
        dealt.phase shouldBe Phase.AWAITING_DRAW

        // o assento 3 compra (QD) e descarta; no início da vez do assento 0 o 3D é baixado e reposto
        val bought = dealt.act(3, Action.DrawFromStock)
        bought.handOf(Seat(3)) shouldContain c("QD")
        val afterThree = bought.act(3, discardCard("KS"))
        afterThree.currentSeat shouldBe Seat(0)
        afterThree.redThreesOf(Side(0)) shouldContainExactly listOf(c("3D"))
        afterThree.handOf(Seat(0)) shouldNotContain c("3D")
        afterThree.handOf(Seat(0)) shouldContain deck[69]
        afterThree.handOf(Seat(0)) shouldHaveSize 11
        afterThree.phase shouldBe Phase.AWAITING_DRAW
        afterThree.turnsBegun shouldBe 2
    }

    @Test
    fun `quatro 3 vermelhos na mesma mao sao todos repostos no inicio da vez`() {
        // §6.5 cada 3 vermelho baixado gera uma reposição
        val deck = orderedDeck(mapOf(0 to "3H", 2 to "3D", 4 to "3H'", 6 to "3D'"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreesOf(Side(0)) shouldHaveSize 4
        state.handOf(Seat(0)) shouldHaveSize 11
        state.handOf(Seat(0)).none { it.isRedThree } shouldBe true
        state.stock shouldHaveSize 56
    }

    // ---------- §3.5 / §6.5 registro público das trocas ----------

    @Test
    fun `registro do inicio da vez segue a ordem real das trocas inclusive em cadeia`() {
        // §3.5 trocas públicas, na ordem em que aconteceram: a cadeia (§6.5) do primeiro jogador, com atTurnStart
        val deck = orderedDeck(mapOf(0 to "3H", 1 to "3D'", 44 to "3D", 45 to "3H'", 46 to "KS", 47 to "QD"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreeLog shouldContainExactly listOf(
            laid(0, "3H", atTurnStart = true),
            laid(0, "3D", atTurnStart = true),
            laid(0, "3H'", atTurnStart = true),
        )
        state.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `registro comeca pelo primeiro jogador`() {
        // §3.5 com o assento 1 iniciando, só a troca dele está no registro
        val deck = orderedDeck(mapOf(0 to "3H", 1 to "3D", 44 to "KS", 45 to "QD"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(1))
        state.redThreeLog shouldContainExactly listOf(laid(1, "3H", atTurnStart = true))
    }

    @Test
    fun `em duplas o registro diz qual parceiro baixou cada 3 vermelho e quando`() {
        // §3.5 todos são avisados de QUEM baixou: assentos 0 e 2 são do mesmo lado (§1.1), mas o registro os distingue
        val deck = orderedDeck(
            mapOf(
                1 to "3D",  // assento 1 → lado 1
                2 to "3H",  // assento 2 → lado 0
                4 to "3H'", // assento 0 (2ª carta) → lado 0
            ),
        )
        val dealt = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(0))
        dealt.redThreeLog shouldContainExactly listOf(laid(0, "3H'", atTurnStart = true))
        // a vez passa: assento 1 e depois assento 2 baixam os seus no início de cada vez
        val s = dealt.drawAndDiscard().drawAndDiscard()
        s.currentSeat shouldBe Seat(2)
        s.redThreeLog shouldContainExactly listOf(
            laid(0, "3H'", atTurnStart = true),
            laid(1, "3D", atTurnStart = true),
            laid(2, "3H", atTurnStart = true),
        )
        s.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H'"), c("3H"))
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `distribuicao sem 3 vermelho na mao tem registro vazio`() {
        // §3.5 sem troca, nada a avisar
        dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(), Seat(0)).redThreeLog.shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `registro bate com os 3 vermelhos de cada lado`(mode: GameMode) {
        // §3.5 / §6.5 um item por 3 vermelho baixado, nenhum da distribuição (atDeal sempre falso)
        (0L until 200L).forEach { seed ->
            val state = dealRound(mode, Random(seed))
            state.redThreeLogViolation() shouldBe null
            state.redThreeLog.none { it.atDeal } shouldBe true
        }
    }

    // ---------- §4.1 ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `primeiro jogador e sorteado e varia com a semente`(mode: GameMode) {
        // §4.1 jogador inicial escolhido aleatoriamente
        val firsts = (0L until 100L).map { dealRound(mode, Random(it)).firstSeat }.toSet()
        firsts shouldBe mode.seats.toSet()
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `mesma semente sorteia o mesmo primeiro jogador`(mode: GameMode) {
        // §4.1 aleatório, mas reprodutível pela semente
        (0L until 20L).forEach { seed ->
            dealRound(mode, Random(seed)).firstSeat shouldBe dealRound(mode, Random(seed)).firstSeat
        }
    }

    // ---------- Reprodutibilidade ----------

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `mesma semente gera o mesmo estado e sementes diferentes geram estados diferentes`(mode: GameMode) {
        // §1 embaralhamento aleatório com RNG de semente injetada
        val a: RoundState = dealRound(mode, Random(42))
        a shouldBe dealRound(mode, Random(42))
        a shouldNotBe dealRound(mode, Random(43))
    }
}
