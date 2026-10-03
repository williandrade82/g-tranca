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

    private fun stockSizeBeforeReplacements(mode: GameMode) = when (mode) {
        GameMode.INDIVIDUAL -> 60
        GameMode.DUPLAS -> 38
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
    fun `monte sem 3 vermelho na mao tem 60 no individual e 38 em duplas`(mode: GameMode) {
        // §3.3 restantes formam o monte: 60 individual / 38 duplas (antes das reposições de §3.5)
        val state = dealFromOrderedDeck(mode, orderedDeck(), Seat(0))
        state.stock shouldHaveSize stockSizeBeforeReplacements(mode)
        state.redThrees.flatten().shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(GameMode::class)
    fun `monte diminui uma carta por reposicao de 3 vermelho`(mode: GameMode) {
        // §3.3 + §3.5 monte = 60/38 − nº de reposições
        (0L until 50L).forEach { seed ->
            val state = dealRound(mode, Random(seed))
            val replacements = state.redThrees.sumOf { it.size }
            state.stock shouldHaveSize stockSizeBeforeReplacements(mode) - replacements
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
    fun `nenhuma mao termina com 3 vermelho apos a distribuicao`(mode: GameMode) {
        // §3.5 3 vermelhos recebidos são baixados automaticamente
        (0L until 200L).forEach { seed ->
            val state = dealRound(mode, Random(seed))
            state.hands.flatten().none { it.isRedThree } shouldBe true
            state.hands.forEach { it shouldHaveSize 11 }
        }
    }

    @Test
    fun `3 vermelho na mao vai para a mesa e e reposto do topo do monte`() {
        // §3.5 + §6.5 baixa o 3 vermelho e repõe com uma carta do monte
        val deck = orderedDeck(mapOf(0 to "3H", 44 to "KS"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H"))
        state.redThreesOf(Side(1)).shouldBeEmpty()
        state.handOf(Seat(0)) shouldContain c("KS")
        state.handOf(Seat(0)) shouldNotContain c("3H")
        state.handOf(Seat(0)) shouldHaveSize 11
        state.stock shouldHaveSize 59
        state.stock shouldContainExactly deck.subList(45, 104)
    }

    @Test
    fun `reposicao em cadeia quando a reposicao tambem e 3 vermelho`() {
        // §6.5 reposição em cadeia; §3.5 seguindo a ordem de jogada a partir do primeiro jogador
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
        // assento 0 é processado primeiro e consome 3D, 3H' (em cadeia) e KS
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H"), c("3D"), c("3H'"))
        state.handOf(Seat(0)) shouldContain c("KS")
        // depois o assento 1 repõe seu 3 vermelho com a carta seguinte
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3D'"))
        state.handOf(Seat(1)) shouldContain c("QD")
        state.hands.forEach { it shouldHaveSize 11 }
        state.stock shouldHaveSize 60 - 4
        state.stock shouldContainExactly deck.subList(48, 104)
    }

    @Test
    fun `ordem de jogada define quem repoe primeiro`() {
        // §3.5 seguindo a ordem de jogada: com o assento 1 iniciando, ele repõe antes do assento 0
        val deck = orderedDeck(
            mapOf(
                0 to "3H",  // assento 1 (primeiro jogador)
                1 to "3D",  // assento 0
                44 to "KS",
                45 to "QD",
            ),
        )
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(1))
        state.handOf(Seat(1)) shouldContain c("KS")
        state.handOf(Seat(0)) shouldContain c("QD")
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H"))
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3D"))
    }

    @Test
    fun `em duplas o 3 vermelho vai para a mesa do lado do jogador`() {
        // §3.5 + §1.1 + §6.4 3 vermelhos pertencem ao lado (assentos 0 e 2 = lado 0; 1 e 3 = lado 1)
        val deck = orderedDeck(
            mapOf(
                1 to "3H",  // assento 2 (com primeiro jogador = 1) → lado 0
                2 to "3D",  // assento 3 → lado 1
                4 to "3H'", // assento 1 (2ª carta) → lado 1
                66 to "KS", // topo do monte em duplas
                67 to "QD",
                68 to "JC",
            ),
        )
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(1))
        // ordem de jogada a partir do assento 1: 1, 2, 3, 0
        state.handOf(Seat(1)) shouldContain c("KS")
        state.handOf(Seat(2)) shouldContain c("QD")
        state.handOf(Seat(3)) shouldContain c("JC")
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3H"))
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H'"), c("3D"))
        state.stock shouldHaveSize 38 - 3
    }

    @Test
    fun `cada jogador termina suas trocas antes do proximo e so depois o primeiro joga`() {
        // §3.5 o primeiro jogador troca tudo (inclusive em cadeia), depois o próximo; ao fim,
        // o primeiro jogador inicia a jogada (§4.3) comprando
        val deck = orderedDeck(
            mapOf(
                0 to "3H",   // assento 3 (primeiro jogador) → lado 1
                1 to "3D",   // assento 0 → lado 0
                66 to "3H'", // reposição do assento 3, também 3 vermelho (cadeia)
                67 to "KS",  // fim da cadeia do assento 3
                68 to "QD",  // só então o assento 0 repõe
            ),
        )
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(3))
        state.redThreesOf(Side(1)) shouldContainExactly listOf(c("3H"), c("3H'"))
        state.handOf(Seat(3)) shouldContain c("KS")
        state.redThreesOf(Side(0)) shouldContainExactly listOf(c("3D"))
        state.handOf(Seat(0)) shouldContain c("QD")
        state.hands.flatten().none { it.isRedThree } shouldBe true
        state.currentSeat shouldBe Seat(3)
        state.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `quatro 3 vermelhos na mesma mao sao todos repostos`() {
        // §6.5 cada 3 vermelho baixado gera uma reposição
        val deck = orderedDeck(mapOf(0 to "3H", 2 to "3D", 4 to "3H'", 6 to "3D'"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.redThreesOf(Side(0)) shouldHaveSize 4
        state.handOf(Seat(0)) shouldHaveSize 11
        state.hands.flatten().none { it.isRedThree } shouldBe true
        state.stock shouldHaveSize 56
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
