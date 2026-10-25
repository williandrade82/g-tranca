package com.gtranca.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

/** *Seleção de jogadores* (docs/telas.md 3.3, §16.2): os lugares, os toques e as operações locais e em rede. */
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

    private fun duplas(seed: Long = 1) = PlayerSelectionViewModel(GameConfig(GameMode.DUPLAS), seed)

    private class FakeSeats(var accept: Boolean = true) : SeatRecruiter {
        val calls = mutableListOf<String>()
        override suspend fun open() { calls += "open" }
        override suspend fun call(seat: Int, presence: PresenceAdvert): Boolean { calls += "call $seat ${presence.key}"; return accept }
        override suspend fun placeRobot(seat: Int, persona: String): Boolean { calls += "robot $seat $persona"; return true }
        override suspend fun removeSeat(seat: Int): Boolean { calls += "remove $seat"; return true }
        override suspend fun move(from: Int, to: Int): Boolean { calls += "move $from $to"; return true }
        override suspend fun fillWithRobots(pool: List<String>, seed: Long) { calls += "fill $seed" }
    }

    @Test
    fun `§16_2 a mesa abre com o jogador no lugar 0 e os outros VAGO - 2 lugares no individual e 4 em duplas`() {
        val single = PlayerSelectionViewModel(GameConfig(GameMode.INDIVIDUAL))
        single.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant)
        duplas().layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Vacant, Slot.Vacant)
        duplas().canStart shouldBe false
    }

    @Test
    fun `§16_2 tocar num lugar VAGO e depois num robo coloca o robo, que nao pode ir a dois lugares`() = withMain {
        val vm = duplas()
        vm.onSeatTap(2)
        vm.ui.value.selectedSeat shouldBe 2
        vm.onRobotTap("zen")
        vm.layout.slots[2] shouldBe Slot.Robot("zen")
        vm.ui.value.selectedSeat.shouldBeNull()
        // o mesmo robô, em outro lugar: não vale
        vm.onSeatTap(1)
        vm.onRobotTap("zen")
        vm.layout.slots[1] shouldBe Slot.Vacant
        // tocar de novo no mesmo lugar desfaz a escolha
        vm.onSeatTap(1)
        vm.ui.value.selectedSeat.shouldBeNull()
    }

    @Test
    fun `§16_2 tocar num robo sem lugar escolhido so avisa`() = withMain {
        val vm = duplas()
        val notices = mutableListOf<SelectionNotice>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        vm.onRobotTap("zen")
        notices shouldContainExactly listOf(SelectionNotice.PICK_SEAT_FIRST)
        vm.layout.robots shouldBe emptyMap()
    }

    @Test
    fun `§16_2 tocar num lugar preenchido abre o menu e Remover devolve o lugar a VAGO`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("jovem")
        vm.onSeatTap(1)
        vm.ui.value.menuSeat shouldBe 1
        vm.onRemove(1)
        vm.layout.slots[1] shouldBe Slot.Vacant
        vm.ui.value.menuSeat.shouldBeNull()
        // o lugar do jogador não abre menu
        vm.onSeatTap(0)
        vm.ui.value.menuSeat.shouldBeNull()
    }

    @Test
    fun `§16_2 arrastar - ocupado troca, VAGO move, e o jogador nao se move`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("jovem")
        vm.onSeatTap(2); vm.onRobotTap("zen")
        vm.move(1, 3) // VAGO: move
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Robot("zen"), Slot.Robot("jovem"))
        vm.move(2, 3) // ocupado: troca
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Robot("jovem"), Slot.Robot("zen"))
        val before = vm.layout
        vm.move(0, 1)
        vm.move(2, 0)
        vm.layout shouldBe before
    }

    @Test
    fun `§16_2 alternativa por toque - Mover e depois o lugar de destino faz o mesmo que arrastar`() = withMain {
        val vm = duplas()
        vm.onSeatTap(1); vm.onRobotTap("atleta")
        vm.onSeatTap(1)
        vm.onMoveStart(1)
        vm.ui.value.movingFrom shouldBe 1
        vm.ui.value.menuSeat.shouldBeNull()
        vm.onSeatTap(3)
        vm.layout.slots shouldBe listOf(Slot.Host, Slot.Vacant, Slot.Vacant, Slot.Robot("atleta"))
        vm.ui.value.movingFrom.shouldBeNull()
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
    fun `§16_2 chamar um jogador - o lugar escolhido e o toque nele, e a recusa do recrutamento avisa`() = withMain {
        val vm = duplas()
        val seats = FakeSeats()
        vm.attach(seats)
        val notices = mutableListOf<SelectionNotice>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        val bia = PresenceAdvert("bia222", "Bia")
        // sem lugar escolhido
        vm.onPlayerTap(bia)
        notices shouldContainExactly listOf(SelectionNotice.PICK_SEAT_FIRST)
        vm.onSeatTap(2)
        vm.onPlayerTap(bia)
        advanceUntilIdle()
        seats.calls.last() shouldBe "call 2 bia222"
        seats.accept = false
        vm.onSeatTap(1)
        vm.onPlayerTap(PresenceAdvert("caio33", "Caio"))
        advanceUntilIdle()
        notices.last() shouldBe SelectionNotice.CANNOT_CALL
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
