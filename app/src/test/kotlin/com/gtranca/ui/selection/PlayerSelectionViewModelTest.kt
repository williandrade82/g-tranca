package com.gtranca.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gtranca.R
import com.gtranca.engine.model.GameMode
import com.gtranca.game.GameConfig
import com.gtranca.net.PresenceAdvert
import com.gtranca.net.Slot
import com.gtranca.net.TableLayout
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test

/** *Seleção de jogadores* (docs/telas.md 3.3, §14.4, §16.2): os lugares, a seleção em dois toques e as operações local e em rede. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSelectionViewModelTest {

    private fun <T> withMain(block: suspend kotlinx.coroutines.test.TestScope.() -> T) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private var now = 1_000L

    private fun duplas(seed: Long = 1) = PlayerSelectionViewModel(GameConfig(GameMode.DUPLAS), seed, clock = { now })

    private class FakeSeats(var accept: Boolean = true) : SeatRecruiter {
        val calls = mutableListOf<String>()
        override suspend fun open() { calls += "open" }
        override suspend fun call(seat: Int, presence: PresenceAdvert): Boolean { calls += "call $seat ${presence.key}"; return accept }
        override suspend fun placeRobot(seat: Int, persona: String): Boolean { calls += "robot $seat $persona"; return true }
        override suspend fun removeSeat(seat: Int): Boolean { calls += "remove $seat"; return true }
        override suspend fun move(from: Int, to: Int): Boolean { calls += "move $from $to"; return true }
        override suspend fun fillWithRobots(pool: List<String>, seed: Long) { calls += "fill $seed" }
    }

    private val bia = PresenceAdvert("bia222", "Bia")

    @Test
    fun `§16_2 a mesa abre com o jogador no lugar 0 e os outros VAGO - 2 lugares no individual e 4 em duplas`() {
        val single = PlayerSelectionViewModel(GameConfig(GameMode.INDIVIDUAL))
        single.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant)
        duplas().layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Vacant, Slot.Vacant)
        duplas().canStart shouldBe false
    }

    @Test
    fun `§16_2 lugar primeiro e depois o robo - o robo e colocado e nao vai a dois lugares`() = withMain {
        val vm = duplas()
        vm.onSeatTap(2)
        vm.ui.value.selected shouldBe Selection.Seat(2)
        vm.onRobotTap("zen")
        vm.layout.slots[2] shouldBe Slot.Robot("zen")
        vm.ui.value.selected.shouldBeNull()
        // o mesmo robô, que já está sentado, não pode ser escolhido de novo
        vm.onRobotTap("zen")
        vm.ui.value.selected.shouldBeNull()
        vm.onSeatTap(1)
        vm.onRobotTap("zen")
        vm.layout.slots[1] shouldBe Slot.Vacant
    }

    @Test
    fun `§16_2 robo primeiro e depois o lugar - nos dois sentidos, e tocar de novo no mesmo item cancela`() = withMain {
        val vm = duplas()
        vm.onRobotTap("jovem")
        vm.ui.value.selected shouldBe Selection.Robot("jovem")
        vm.onRobotTap("jovem")
        vm.ui.value.selected.shouldBeNull()
        vm.onRobotTap("jovem")
        vm.onSeatTap(3)
        vm.layout.slots[3] shouldBe Slot.Robot("jovem")
        vm.ui.value.selected.shouldBeNull()
        // trocar a escolha pendente por outro item da lista
        vm.onRobotTap("zen")
        vm.onRobotTap("atleta")
        vm.ui.value.selected shouldBe Selection.Robot("atleta")
        // tocar numa área vazia cancela
        vm.onClear()
        vm.ui.value.selected.shouldBeNull()
        // um lugar escolhido também se cancela tocando nele de novo
        vm.onSeatTap(1)
        vm.onSeatTap(1)
        vm.ui.value.selected.shouldBeNull()
    }

    @Test
    fun `§16_2 o convite so sai com os dois escolhidos - jogador e lugar, em qualquer ordem`() = withMain {
        val vm = duplas()
        val seats = FakeSeats()
        vm.attach(seats)
        seats.calls.clear()
        // jogador primeiro: nada sai ainda
        vm.onPlayerTap(bia)
        advanceUntilIdle()
        vm.ui.value.selected shouldBe Selection.Player(bia)
        seats.calls shouldBe emptyList()
        vm.onSeatTap(2)
        advanceUntilIdle()
        seats.calls shouldContainExactly listOf("call 2 bia222")
        // lugar primeiro e depois o jogador
        val caio = PresenceAdvert("caio33", "Caio")
        vm.onSeatTap(1)
        advanceUntilIdle()
        seats.calls.size shouldBe 1
        vm.onPlayerTap(caio)
        advanceUntilIdle()
        seats.calls.last() shouldBe "call 1 caio33"
    }

    @Test
    fun `§16_2 recusa do recrutamento ao chamar avisa`() = withMain {
        val vm = duplas()
        val seats = FakeSeats(accept = false)
        vm.attach(seats)
        val notices = mutableListOf<SelectionNotice>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        vm.onSeatTap(2)
        vm.onPlayerTap(bia)
        advanceUntilIdle()
        notices shouldContainExactly listOf(SelectionNotice.CANNOT_CALL)
    }

    @Test
    fun `§16_2 dois lugares trocam - ocupado com ocupado, ocupado com VAGO move, e o jogador nao entra`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("jovem")
        vm.onSeatTap(2); vm.onRobotTap("zen")
        vm.onSeatTap(1); vm.onSeatTap(3) // ocupado + VAGO: move
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Robot("zen"), Slot.Robot("jovem"))
        vm.onSeatTap(2); vm.onSeatTap(3) // ocupado + ocupado: troca
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Robot("jovem"), Slot.Robot("zen"))
        // dois VAGO: nada a trocar, vale o último escolhido
        vm.onSeatTap(1); vm.onSeatTap(1)
        vm.onSeatTap(1)
        vm.onSeatTap(2) // 1 VAGO + 2 ocupado: move para o 1
        vm.layout.slots[1] shouldBe Slot.Robot("jovem")
        // o lugar do jogador nunca se move nem recebe ninguém
        val before = vm.layout
        vm.onSeatTap(0)
        vm.move(0, 1)
        vm.move(1, 0)
        vm.layout shouldBe before
    }

    @Test
    fun `§16_2 arrastar e soltar faz o mesmo que escolher dois lugares`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("jovem")
        vm.move(1, 3)
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Vacant, Slot.Robot("jovem"))
    }

    @Test
    fun `§16_2 lugar ocupado e depois um item da lista retira quem estava no lugar, sem pôr o item no lugar`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("jovem")
        vm.onSeatTap(1)
        vm.onRobotTap("zen") // retira o Bolt; o Zen não entra
        vm.layout.slots[1] shouldBe Slot.Vacant
        vm.layout.usedPersonas shouldBe emptySet()
        vm.ui.value.selected.shouldBeNull()
        // o robô que já estava na mesa pode ser o tocado na lista: retira o que está no lugar escolhido
        vm.onSeatTap(2); vm.onRobotTap("atleta")
        vm.onSeatTap(2)
        vm.onRobotTap("atleta")
        vm.layout.slots[2] shouldBe Slot.Vacant
    }

    @Test
    fun `§16_2 retirar na rede - Chamado e Recrutado saem pelo recrutamento, tocando num jogador da lista`() = withMain {
        val vm = duplas()
        val seats = FakeSeats()
        vm.attach(seats)
        vm.onRemoteLayout(TableLayout(GameMode.DUPLAS, listOf(Slot.Host, Slot.Recruited("bia222", "Bia"), Slot.Called("caio33", "Caio"), Slot.Vacant)))
        vm.onSeatTap(1)
        vm.onPlayerTap(PresenceAdvert("duda44", "Duda"))
        advanceUntilIdle()
        seats.calls.last() shouldBe "remove 1" // Duda não é chamada
        vm.onSeatTap(2)
        vm.onRobotTap("zen")
        advanceUntilIdle()
        seats.calls.last() shouldBe "remove 2" // o Zen não entra
        // quem já está na mesa e aparece na lista também retira
        vm.onSeatTap(2)
        vm.onPlayerTap(PresenceAdvert("bia222", "Bia"))
        advanceUntilIdle()
        seats.calls.last() shouldBe "remove 2"
    }

    @Test
    fun `§16_15 o anel de contagem do Chamado comeca quando ele entra nos lugares e some com ele`() = withMain {
        val vm = duplas()
        vm.attach(FakeSeats())
        now = 5_000
        vm.onRemoteLayout(TableLayout(GameMode.DUPLAS, listOf(Slot.Host, Slot.Called("bia222", "Bia"), Slot.Vacant, Slot.Vacant)))
        vm.ui.value.calledAt shouldBe mapOf("bia222" to 5_000L)
        // mudanças seguintes não reiniciam o prazo
        now = 9_000
        vm.onRemoteLayout(TableLayout(GameMode.DUPLAS, listOf(Slot.Host, Slot.Called("bia222", "Bia"), Slot.Robot("zen"), Slot.Vacant)))
        vm.ui.value.calledAt shouldBe mapOf("bia222" to 5_000L)
        // aceitou: deixa de ser Chamado
        vm.onRemoteLayout(TableLayout(GameMode.DUPLAS, listOf(Slot.Host, Slot.Recruited("bia222", "Bia"), Slot.Robot("zen"), Slot.Vacant)))
        vm.ui.value.calledAt shouldBe emptyMap()
    }

    @Test
    fun `§14_5 Completar com robos preenche so os VAGO com robos ainda nao usados e libera Comecar Partida`() = withMain {
        val vm = duplas(seed = 7)
        vm.onSeatTap(2); vm.onRobotTap("sobrio")
        vm.onFill()
        vm.layout.isFull shouldBe true
        vm.layout.slots[2] shouldBe Slot.Robot("sobrio")
        vm.layout.robots.values.toSet().size shouldBe 3
        vm.canStart shouldBe true
        // a mesma semente sorteia igual
        val other = duplas(seed = 7)
        other.onSeatTap(2); other.onRobotTap("sobrio"); other.onFill()
        other.layout shouldBe vm.layout
    }

    @Test
    fun `§16_2 com o Wi-Fi ligado a mesa abre na rede repetindo os robos e as operacoes vao para o recrutamento`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("zen")
        val seats = FakeSeats()
        vm.attach(seats)
        seats.calls shouldContainExactly listOf("open", "robot 1 zen")
        // na rede, quem manda é o recrutamento: o toque só pede, o layout vem de onRemoteLayout
        vm.onSeatTap(2)
        vm.onRobotTap("jovem")
        advanceUntilIdle()
        seats.calls.last() shouldBe "robot 2 jovem"
        vm.layout.slots[2] shouldBe Slot.Vacant
        vm.onRemoteLayout(vm.layout.placeRobot(2, "jovem")!!)
        vm.layout.slots[2] shouldBe Slot.Robot("jovem")
        vm.move(1, 3)
        vm.onFill()
        advanceUntilIdle()
        seats.calls.takeLast(2) shouldContainExactly listOf("move 1 3", "fill ${vm.seed}")
    }

    @Test
    fun `§16_2 Wi-Fi desligado - chamados e recrutados saem dos lugares e os robos ficam`() = withMain {
        val vm = duplas()
        vm.attach(FakeSeats())
        vm.onRemoteLayout(
            TableLayout(
                GameMode.DUPLAS,
                listOf(Slot.Host, Slot.Robot("zen"), Slot.Called("bia222", "Bia"), Slot.Recruited("caio33", "Caio")),
            ),
        )
        vm.hasRemotes shouldBe true
        vm.canStart shouldBe false // há um Chamado pendente
        vm.detach()
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Robot("zen"), Slot.Vacant, Slot.Vacant)
        vm.hasRemotes shouldBe false
    }

    @Test
    fun `§16_2 a dica de uma linha acompanha a selecao`() {
        val layout = TableLayout(GameMode.DUPLAS, listOf(Slot.Host, Slot.Robot("zen"), Slot.Vacant, Slot.Vacant))
        selectionHintRes(null, layout) shouldBe R.string.selection_hint_idle
        selectionHintRes(Selection.Seat(2), layout) shouldBe R.string.selection_hint_who
        selectionHintRes(Selection.Seat(1), layout) shouldBe R.string.selection_hint_swap
        selectionHintRes(Selection.Robot("jovem"), layout) shouldBe R.string.selection_hint_seat_for
        selectionHintRes(Selection.Player(PresenceAdvert("bia222", "Bia")), layout) shouldBe R.string.selection_hint_seat_for
    }

    @Test
    fun `§16_2 o lugar onde o avatar e solto e achado pelos retangulos da tela`() {
        val bounds = mapOf(
            0 to Rect(0f, 200f, 100f, 300f),
            1 to Rect(0f, 0f, 100f, 100f),
            2 to Rect(150f, 0f, 250f, 100f),
        )
        seatAt(bounds, Offset(160f, 50f)) shouldBe 2
        seatAt(bounds, Offset(50f, 250f)) shouldBe 0
        seatAt(bounds, Offset(120f, 150f)).shouldBeNull()
    }
}
