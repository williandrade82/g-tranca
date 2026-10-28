package com.gtranca.ui.boosters

import com.gtranca.data.InventoryRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Gift
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** §17.3 seletor de boosters: começa com a marcação do Inventário, Seguir debita uma vez e entrega o que valeu. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoosterSelectorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class FakeInventory(start: Inventory, marked: List<String> = emptyList()) : InventoryRepository {
        override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT
        val stock = MutableStateFlow(start)
        val marked = MutableStateFlow(marked)
        var confirmCalls = 0
        override val inventory: Flow<Inventory> = stock
        override val selection: Flow<List<String>> = this.marked
        override suspend fun setSelection(ids: List<String>) { marked.value = ids }
        override suspend fun confirmLoadout(selection: List<String>): Loadout.Confirmed {
            confirmCalls++
            return Loadout.confirm(selection, stock.value, catalog).also { stock.value = it.inventory }
        }
        override suspend fun claimGift(today: Long, random: Random): Gift? = null
    }

    @Test
    fun `§17_3 o seletor comeca com a marcacao do inventario nos nichos`() = runTest(dispatcher) {
        val vm = BoosterSelectorViewModel(FakeInventory(Inventory.initial(), listOf("b3", "b1")))
        advanceUntilIdle()
        vm.state.value.slots shouldBe listOf("b3", "b1", null)
        vm.state.value.ready shouldBe true
    }

    @Test
    fun `§17_3 Seguir debita o custo de cada escolhido e entrega o que valeu`() = runTest(dispatcher) {
        val repo = FakeInventory(Inventory.initial(), listOf("b1", "b2"))
        val vm = BoosterSelectorViewModel(repo)
        advanceUntilIdle()
        var delivered: List<String>? = null
        vm.confirm { delivered = it }
        advanceUntilIdle()
        delivered shouldBe listOf("b1", "b2")
        repo.stock.value.quantities shouldBe mapOf("b1" to 4, "b2" to 4, "b3" to 5)
    }

    @Test
    fun `§17_3 Seguir e o fim do prazo juntos so debitam uma vez`() = runTest(dispatcher) {
        val repo = FakeInventory(Inventory.initial(), listOf("b1"))
        val vm = BoosterSelectorViewModel(repo)
        advanceUntilIdle()
        val calls = mutableListOf<List<String>>()
        vm.confirm { calls += it }
        vm.confirm { calls += it }
        advanceUntilIdle()
        calls.size shouldBe 1
        repo.confirmCalls shouldBe 1
        repo.stock.value.quantity("b1") shouldBe 4
    }

    @Test
    fun `§17_3 sem nenhum selecionado a partida segue sem boosters e nada e debitado`() = runTest(dispatcher) {
        val repo = FakeInventory(Inventory.initial())
        val vm = BoosterSelectorViewModel(repo)
        advanceUntilIdle()
        var delivered: List<String>? = null
        vm.confirm { delivered = it }
        advanceUntilIdle()
        delivered shouldBe emptyList()
        repo.stock.value shouldBe Inventory.initial()
    }

    @Test
    fun `§17_3 nicho vazio abre a lista, escolher preenche e tocar no cheio esvazia`() = runTest(dispatcher) {
        val vm = BoosterSelectorViewModel(FakeInventory(Inventory.initial()))
        advanceUntilIdle()
        vm.onSlot(1)
        vm.state.value.picking shouldBe 1
        vm.onPick("b3")
        vm.state.value.slots shouldBe listOf(null, "b3", null)
        vm.state.value.picking shouldBe null
        vm.onSlot(1)
        vm.state.value.slots shouldBe listOf(null, null, null)
    }

    @Test
    fun `§17_3 booster sem quantidade ou ja escolhido nao entra`() = runTest(dispatcher) {
        val vm = BoosterSelectorViewModel(FakeInventory(Inventory(mapOf("b1" to 0, "b2" to 3, "b3" to 3)), listOf("b2")))
        advanceUntilIdle()
        vm.state.value.canPick("b1") shouldBe false // sem quantidade: esmaecido
        vm.state.value.canPick("b2") shouldBe false // já está num nicho
        vm.onSlot(1)
        vm.onPick("b1")
        vm.state.value.slots shouldBe listOf("b2", null, null)
        vm.onSlot(1)
        vm.onPick("b2")
        vm.state.value.slots shouldBe listOf("b2", null, null)
    }

    @Test
    fun `§17_3 depois de confirmado os nichos nao mudam`() = runTest(dispatcher) {
        val vm = BoosterSelectorViewModel(FakeInventory(Inventory.initial(), listOf("b1")))
        advanceUntilIdle()
        vm.confirm { }
        vm.onSlot(0)
        vm.state.value.slots shouldBe listOf("b1", null, null)
    }
}
