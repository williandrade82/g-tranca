package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PlayerViewTest {

    private val individual = round(GameMode.INDIVIDUAL) {
        hand(0, "5H 6H 7H 2C 9D")
        hand(1, "KS KD QC JC")
        stock("8C 8D' 9S")
        discard("4S 3C' JH")
        morto(0, "4C 5C 6C")
        morto(1, "4D 5D")
        redThrees(1, "3H")
        meld(0, "TC JC' QC'")
        meld(1, "AH AD AS")
        current = 1
        phase = Phase.PLAYING
    }

    private val duplas = round(GameMode.DUPLAS) {
        hand(0, "5H 6H")
        hand(1, "KS KD QC")
        hand(2, "9H 9C 9S 2D")
        hand(3, "7D")
        stock("8C 8D' 9S' 4H")
        discard("4S JH' QD 3C")
        morto(1, "4C 5C 6C")
        mortoTaken(0, side = 1)
        redThrees(0, "3D 3H'")
        meld(1, "TC JC' QC'")
        current = 3
    }

    private fun json(view: PlayerView): String = Json.encodeToString(PlayerView.serializer(), view)

    /** Cartas que o assento não pode ver: mãos alheias, monte e mortos. */
    private fun hiddenCards(state: RoundState, seat: Seat): List<Card> =
        state.mode.seats.filter { it != seat }.flatMap { state.handOf(it) } + state.stock + state.mortos.flatten()

    private fun assertHidesEverythingHidden(state: RoundState) {
        state.mode.seats.forEach { seat ->
            val view = state.viewFor(seat)
            val text = json(view)
            val hidden = hiddenCards(state, seat)
            hidden.forEach { card -> text shouldNotContain "\"$card\"" }
            // a vista carrega só cartas visíveis: a própria mão, o lixo, a mesa e os 3 vermelhos
            val visible = view.hand + view.discardPile + view.redThrees.flatten() + view.tables.flatMap { it.allCards() }
            visible.none { it in hidden } shouldBe true
            view.hand.forEach { card -> text shouldContain "\"$card\"" }
        }
    }

    @Test
    fun `vista individual nao carrega maos alheias, monte nem mortos`() {
        // §3.2 mortos virados para baixo; §3.3 monte virado para baixo
        assertHidesEverythingHidden(individual)
    }

    @Test
    fun `vista em duplas nao carrega maos alheias nem a do parceiro, monte nem mortos`() {
        // §1.1 duplas; §3.2 mortos virados para baixo; §3.3 monte virado para baixo
        assertHidesEverythingHidden(duplas)
        // a mão do parceiro também é oculta
        val text = json(duplas.viewFor(Seat(0)))
        duplas.handOf(Seat(2)).forEach { card -> text shouldNotContain "\"$card\"" }
    }

    @Test
    fun `vista leva a propria mao e identifica assento e lado`() {
        // §1.1 lado do assento
        val view = individual.viewFor(Seat(1))
        view.seat shouldBe Seat(1)
        view.side shouldBe Side(1)
        view.hand shouldContainExactly individual.handOf(Seat(1))

        val partner = duplas.viewFor(Seat(2))
        partner.seat shouldBe Seat(2)
        partner.side shouldBe Side(0)
        partner.hand shouldContainExactly duplas.handOf(Seat(2))
    }

    @Test
    fun `tamanhos de maos, monte e mortos batem com o estado`() {
        // §3.2 todos sabem que os mortos existem; §3.3 monte
        listOf(individual, duplas).forEach { state ->
            state.mode.seats.forEach { seat ->
                val view = state.viewFor(seat)
                view.handSizes shouldContainExactly state.hands.map { it.size }
                view.stockSize shouldBe state.stock.size
                view.mortoSizes shouldContainExactly state.mortos.map { it.size }
                view.mortoStatus shouldContainExactly state.mortoStatus
            }
        }
        duplas.viewFor(Seat(0)).handSizes shouldContainExactly listOf(2, 3, 4, 1)
        duplas.viewFor(Seat(0)).mortoSizes shouldContainExactly listOf(0, 3)
        duplas.viewFor(Seat(0)).mortoStatus shouldContainExactly listOf(MortoStatus.Taken(Side(1)), MortoStatus.Available)
    }

    @Test
    fun `lixo vem completo e na ordem`() {
        // §5.6 lixo aberto
        individual.viewFor(Seat(0)).discardPile shouldContainExactly cards("4S 3C' JH")
        individual.viewFor(Seat(1)).discardPile shouldContainExactly cards("4S 3C' JH")
        duplas.mode.seats.forEach { seat ->
            val view = duplas.viewFor(seat)
            view.discardPile shouldContainExactly cards("4S JH' QD 3C")
            view.discardTop shouldBe c("3C")
            val text = json(view)
            duplas.discardPile.forEach { card -> text shouldContain "\"$card\"" }
        }
    }

    @Test
    fun `mesa e 3 vermelhos de todos os lados sao publicos`() {
        // §6.4 conjuntos na mesa; §6.5 3 vermelhos baixados na mesa
        listOf(individual, duplas).forEach { state ->
            state.mode.seats.forEach { seat ->
                val view = state.viewFor(seat)
                view.tables shouldContainExactly state.tables
                view.redThrees shouldContainExactly state.redThrees
            }
        }
    }

    @Test
    fun `vista copia modo, vez, primeiro jogador, fase e resultado`() {
        // §4.1 primeiro jogador; §4.3 fase da jogada; §11 resultado
        val view = individual.viewFor(Seat(0))
        view.mode shouldBe GameMode.INDIVIDUAL
        view.currentSeat shouldBe Seat(1)
        view.firstSeat shouldBe Seat(0)
        view.phase shouldBe Phase.PLAYING
        view.result shouldBe null

        val finished = round(GameMode.DUPLAS) { result = RoundResult.GoOut(Side(1), Seat(3)) }
        val finishedView = finished.viewFor(Seat(0))
        finishedView.mode shouldBe GameMode.DUPLAS
        finishedView.phase shouldBe Phase.FINISHED
        finishedView.result shouldBe RoundResult.GoOut(Side(1), Seat(3))
    }

    @Test
    fun `vista faz ida e volta em JSON`() {
        // vista serializável (envio ao bot, log)
        listOf(individual, duplas).forEach { state ->
            state.mode.seats.forEach { seat ->
                val view = state.viewFor(seat)
                Json.decodeFromString(PlayerView.serializer(), json(view)) shouldBe view
            }
        }
    }

    @Test
    fun `assento fora do modo e rejeitado`() {
        runCatching { individual.viewFor(Seat(2)) }.isFailure shouldBe true
    }

    @Test
    fun `vistas de partidas reais nunca expoem cartas ocultas`() {
        // §3 distribuição real e jogadas aleatórias válidas, nos dois modos e várias sementes
        listOf(GameMode.INDIVIDUAL, GameMode.DUPLAS).forEach { mode ->
            repeat(10) { seed ->
                val random = Random(seed)
                var state = dealRound(mode, random)
                repeat(40) {
                    assertHidesEverythingHidden(state)
                    if (state.phase == Phase.FINISHED) return@repeat
                    val actions = RoundEngine.legalActions(state, state.currentSeat)
                    state = RoundEngine.apply(state, state.currentSeat, actions[random.nextInt(actions.size)])
                }
            }
        }
    }
}
