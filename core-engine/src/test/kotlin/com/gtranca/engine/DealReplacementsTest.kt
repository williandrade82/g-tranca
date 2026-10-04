package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Deck
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * §3.5 / §6.5 reposições de 3 vermelho na distribuição: as cartas que ficaram na mão de quem trocou. São ocultas
 * para os outros assentos; para o próprio jogador, são cartas da sua mão. Informação só para a interface: não muda
 * o jogo.
 */
class DealReplacementsTest {

    /** Baralho ordenado: posições fixas com as cartas pedidas; o resto sem 3 vermelho (os não usados no fundo). */
    private fun orderedDeck(fixed: Map<Int, String> = emptyMap()): List<Card> {
        val placed = fixed.mapValues { c(it.value) }
        val remaining = Deck.standard() - placed.values.toSet()
        val fillers = ArrayDeque(remaining.filterNot { it.isRedThree } + remaining.filter { it.isRedThree })
        return List(Deck.SIZE) { i -> placed[i] ?: fillers.removeFirst() }
    }

    @Test
    fun `assento sem 3 vermelho na distribuicao nao tem reposicao`() {
        // §3.5 sem troca, nenhuma carta de reposição
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(mapOf(0 to "3H", 44 to "KS")), Seat(0))
        state.dealReplacementsOf(Seat(1)).shouldBeEmpty()
        state.viewFor(Seat(1)).ownDealReplacements.shouldBeEmpty()
        state.dealReplacements shouldBe listOf(listOf(c("KS")), emptyList())
    }

    @Test
    fun `com um 3 vermelho a reposicao e a carta do topo do monte`() {
        // §3.5 + §6.5 o 3 vermelho é reposto com a carta do topo do monte, que fica na mão de quem trocou
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(mapOf(0 to "3H", 44 to "KS")), Seat(0))
        state.dealReplacementsOf(Seat(0)) shouldContainExactly listOf(c("KS"))
        state.viewFor(Seat(0)).ownDealReplacements shouldContainExactly listOf(c("KS"))
    }

    @Test
    fun `reposicao em cadeia guarda so a carta final`() {
        // §6.5 reposição em cadeia: os 3 vermelhos repostos foram baixados (estão no registro), só a carta final
        // fica na mão; §3.5 o assento 0 (primeiro) termina a cadeia antes de o assento 1 trocar
        val deck = orderedDeck(mapOf(0 to "3H", 1 to "3D'", 44 to "3D", 45 to "3H'", 46 to "KS", 47 to "QD"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.dealReplacementsOf(Seat(0)) shouldContainExactly listOf(c("KS"))
        state.dealReplacementsOf(Seat(1)) shouldContainExactly listOf(c("QD"))
    }

    @Test
    fun `varios 3 vermelhos na mesma mao guardam as reposicoes na ordem em que entraram`() {
        // §3.5 / §6.5 cada 3 vermelho da mão gera uma reposição, na ordem das trocas
        val deck = orderedDeck(mapOf(0 to "3H", 2 to "3D", 44 to "KS", 45 to "QD"))
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, deck, Seat(0))
        state.dealReplacementsOf(Seat(0)) shouldContainExactly listOf(c("KS"), c("QD"))
    }

    @Test
    fun `em duplas cada assento ve so as suas reposicoes`() {
        // §3.5 a reposição é oculta para os outros (inclusive o parceiro); ordem de jogada a partir do assento 1
        val deck = orderedDeck(mapOf(1 to "3H", 2 to "3D", 4 to "3H'", 66 to "KS", 67 to "QD", 68 to "JC"))
        val state = dealFromOrderedDeck(GameMode.DUPLAS, deck, Seat(1))
        val expected = mapOf(Seat(0) to emptyList(), Seat(1) to listOf(c("KS")), Seat(2) to listOf(c("QD")), Seat(3) to listOf(c("JC")))
        for (seat in GameMode.DUPLAS.seats) {
            val view = state.viewFor(seat)
            view.ownDealReplacements shouldContainExactly expected.getValue(seat)
            val json = Json.encodeToString(PlayerView.serializer(), view)
            for (other in GameMode.DUPLAS.seats - seat) {
                for (card in expected.getValue(other)) json shouldNotContain "\"$card\""
            }
        }
    }

    @Test
    fun `reposicoes da vista sao copia defensiva`() {
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(mapOf(0 to "3H", 44 to "KS")), Seat(0))
        val view = state.viewFor(Seat(0))
        runCatching { (view.ownDealReplacements as MutableList<Card>).clear() }
        state.dealReplacementsOf(Seat(0)) shouldContainExactly listOf(c("KS"))
    }

    @Test
    fun `reposicoes valem ate a primeira acao do assento`() {
        // §3.5 / §6.5 depois da primeira ação uma carta pode sair da mão e voltar (ex.: pelo lixo, §5.2), então o
        // motor zera as reposições do assento; as dos outros assentos continuam até a primeira ação de cada um
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(mapOf(0 to "3H", 1 to "3D", 44 to "KS", 45 to "QD")), Seat(0))
        state.dealReplacementsOf(Seat(0)) shouldContainExactly listOf(c("KS"))
        state.dealReplacementsOf(Seat(1)) shouldContainExactly listOf(c("QD"))
        val afterDraw = state.act(0, Action.DrawFromStock)
        afterDraw.dealReplacementsOf(Seat(0)).shouldBeEmpty()
        afterDraw.viewFor(Seat(0)).ownDealReplacements.shouldBeEmpty()
        afterDraw.dealReplacementsOf(Seat(1)) shouldContainExactly listOf(c("QD"))
        val json = Json.encodeToString(PlayerView.serializer(), afterDraw.viewFor(Seat(0)))
        json shouldNotContain "\"QD\""
    }

    @Test
    fun `reposicoes da vista estao sempre na propria mao em partidas simuladas`(): Unit = runBlocking {
        // §3.5 a vista nunca carrega reposição fora da mão nem de outro assento
        checkAll(20, Arb.long(), Arb.enum<GameMode>()) { seed, mode ->
            val random = Random(seed)
            var state = dealRound(mode, random)
            var steps = 0
            while (state.phase != Phase.FINISHED && steps < 400) {
                for (seat in mode.seats) {
                    val view = state.viewFor(seat)
                    view.hand.containsAll(view.ownDealReplacements) shouldBe true
                    view.ownDealReplacements shouldBe state.dealReplacementsOf(seat).filter { it in view.hand }
                }
                val actions = RoundEngine.legalActions(state, state.currentSeat)
                state = RoundEngine.apply(state, state.currentSeat, actions.random(random))
                steps++
            }
        }
    }

    @Test
    fun `reposicoes nao alteram o jogo`(): Unit = runBlocking {
        // campo só informativo: as ações válidas e o efeito delas não dependem dele
        // a partida inteira, jogada em paralelo com e sem o campo, segue igual (exceto o próprio campo)
        checkAll(20, Arb.long(), Arb.enum<GameMode>()) { seed, mode ->
            val random = Random(seed)
            var state = dealRound(mode, random)
            var blank = state.copy(dealReplacements = List(mode.seatCount) { emptyList() })
            var steps = 0
            while (state.phase != Phase.FINISHED && steps < 400) {
                val seat = state.currentSeat
                val actions = RoundEngine.legalActions(state, seat)
                actions shouldBe RoundEngine.legalActions(blank, seat)
                val action = actions.random(random)
                state = RoundEngine.apply(state, seat, action)
                blank = RoundEngine.apply(blank, seat, action)
                blank shouldBe state.copy(dealReplacements = blank.dealReplacements)
                steps++
            }
        }
    }

    // ---------- serialização ----------

    private fun without(json: String, key: String): String =
        JsonObject(Json.parseToJsonElement(json).jsonObject - key).toString()

    @Test
    fun `estado e vista com reposicoes fazem ida e volta em JSON`() {
        val state = dealFromOrderedDeck(GameMode.DUPLAS, orderedDeck(mapOf(0 to "3H", 66 to "KS")), Seat(0))
        val json = Json.encodeToString(RoundState.serializer(), state)
        json shouldContain "dealReplacements"
        Json.decodeFromString(RoundState.serializer(), json) shouldBe state
        val view = state.viewFor(Seat(0))
        Json.decodeFromString(PlayerView.serializer(), Json.encodeToString(PlayerView.serializer(), view)) shouldBe view
    }

    @Test
    fun `JSON antigo sem as reposicoes e lido com reposicoes vazias`() {
        // compatibilidade: partidas salvas antes do campo existir
        for (mode in GameMode.entries) {
            val state = dealFromOrderedDeck(mode, orderedDeck(mapOf(0 to "3H", 66 to "KS")), Seat(0))
            val old = without(Json.encodeToString(RoundState.serializer(), state), "dealReplacements")
            old shouldNotContain "dealReplacements"
            val decoded = Json.decodeFromString(RoundState.serializer(), old)
            decoded.dealReplacements shouldBe List(mode.seatCount) { emptyList() }
            decoded shouldBe state.copy(dealReplacements = List(mode.seatCount) { emptyList() })

            val view = state.viewFor(Seat(0))
            val oldView = without(Json.encodeToString(PlayerView.serializer(), view), "ownDealReplacements")
            Json.decodeFromString(PlayerView.serializer(), oldView) shouldBe view.copy(ownDealReplacements = emptyList())
        }
    }

    @Test
    fun `estado com reposicoes de tamanho errado e recusado`() {
        val state = dealRound(GameMode.DUPLAS, Random(1))
        shouldThrow<IllegalArgumentException> { state.copy(dealReplacements = listOf(emptyList())) }
    }

    // ---------- determinização ----------

    @Test
    fun `determinizacao mantem as reposicoes do proprio assento e deixa as alheias vazias`() {
        // §3.5 as reposições alheias são ocultas: o mundo sorteado não as conhece
        for (mode in GameMode.entries) {
            val state = (0L until 500L).map { dealRound(mode, Random(it)) }
                .first { s -> mode.seats.count { s.dealReplacementsOf(it).isNotEmpty() } >= 2 }
            for (seat in mode.seats) {
                val view = state.viewFor(seat)
                val sampled = view.determinize(Random(seat.index.toLong()))
                sampled.dealReplacementsOf(seat) shouldBe state.dealReplacementsOf(seat)
                (mode.seats - seat).forEach { sampled.dealReplacementsOf(it).shouldBeEmpty() }
                sampled.viewFor(seat) shouldBe view
            }
        }
    }

    @Test
    fun `reposicoes fora da propria mao ou repetidas tornam a vista incoerente`() {
        // §3.5 as reposições da vista são cartas da própria mão; §6.5 3 vermelho nunca fica na mão
        val state = dealFromOrderedDeck(GameMode.INDIVIDUAL, orderedDeck(mapOf(0 to "3H", 44 to "KS")), Seat(0))
        val view = state.viewFor(Seat(0))
        val notInHand = Deck.standard().first { it !in view.hand && !it.isRedThree }
        shouldThrow<IllegalArgumentException> {
            view.copy(ownDealReplacements = listOf(c("3D"))).determinize(Random(1))
        }.message shouldContain "reposi"
        shouldThrow<IllegalArgumentException> {
            view.copy(ownDealReplacements = listOf(c("KS"), notInHand)).determinize(Random(1))
        }.message shouldContain "reposi"
        shouldThrow<IllegalArgumentException> {
            view.copy(ownDealReplacements = listOf(c("KS"), c("KS"))).determinize(Random(1))
        }.message shouldContain "reposi"
    }
}
